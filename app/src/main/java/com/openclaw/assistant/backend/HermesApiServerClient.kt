package com.openclaw.assistant.backend

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for a Hermes Agent API server (NousResearch/hermes-agent).
 *
 * Hermes exposes three conversation transports and this client speaks all
 * three, picking one from `GET /v1/capabilities` unless the user pinned a
 * preference:
 *
 *  - **Session chat** (`/api/sessions/{id}/chat/stream`) — Hermes owns the
 *    transcript, so history survives app restarts and is not re-uploaded every
 *    turn. Preferred when available.
 *  - **Runs** (`/v1/runs` + `/v1/runs/{id}/events`) — history is resent as
 *    `conversation_history`, but this is the only transport that surfaces tool
 *    approval requests.
 *  - **Chat completions** (`/v1/chat/completions`) — stateless OpenAI-compatible
 *    fallback that every Hermes build serves.
 *
 * All three stream Server-Sent Events. Session chat and runs additionally carry
 * tool-progress and lifecycle events, which are mapped onto [AgentEvent].
 */
class HermesApiServerClient(
    override val config: AgentBackendConfig,
    private val httpClient: OkHttpClient = defaultHttpClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val capabilityProbe: HermesCapabilitiesProbe = HermesCapabilitiesProbe(),
) : AgentClient {

    @Volatile private var currentCall: Call? = null
    @Volatile private var currentRunId: String? = null

    private val token: String?
        get() = config.apiKeyOrToken?.takeIf { it.isNotBlank() }

    /**
     * Model to request, or null to let the server use its own default.
     *
     * Hermes-native endpoints forward any model id straight to the provider, so
     * a placeholder like `"default"` becomes a provider-side 400. Only a real
     * user-chosen id is sent, and the server's own advertised alias is dropped
     * because it already means "use the default".
     */
    private fun requestedModel(options: AgentSendOptions, caps: HermesCapabilities): String? {
        val name = options.modelOverride?.takeIf { it.isNotBlank() }
            ?: config.modelName?.takeIf { it.isNotBlank() }
            ?: return null
        if (name.equals("default", ignoreCase = true)) return null
        if (caps.advertisedModel != null && name == caps.advertisedModel) return null
        return name
    }

    private fun requestedProvider(options: AgentSendOptions): String? =
        options.providerOverride?.takeIf { it.isNotBlank() }
            ?: config.providerName?.takeIf { it.isNotBlank() }

    private fun candidateEndpoints(): List<String> = buildList {
        config.baseUrl?.takeIf { it.isNotBlank() }?.let { add(it) }
        addAll(config.secondaryUrls.filter { it.isNotBlank() })
    }

    /**
     * Picks the endpoint to talk to, racing LAN/VPN/public candidates when more
     * than one is configured. The winner is cached, but expires, so a phone that
     * leaves the home network stops dialling a now-unreachable LAN address.
     */
    private suspend fun resolveBaseUrl(): String {
        HermesEndpointSelection.forBackend(config.id)?.let { return it }
        val candidates = candidateEndpoints()
        if (candidates.size > 1) {
            val outcome = HermesEndpointRacer().race(candidates, token)
            if (outcome != null && outcome.ok) {
                HermesEndpointSelection.remember(config.id, outcome.url)
                return outcome.url
            }
        }
        return config.baseUrl?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Hermes backend has no base URL")
    }

    private suspend fun capabilities(baseUrl: String): HermesCapabilities =
        HermesCapabilityCache.get(config.id, baseUrl, token, capabilityProbe)

    // ------------------------------------------------------------------
    // Connection test
    // ------------------------------------------------------------------

    override suspend fun testConnection(): ConnectionTestResult {
        val candidates = candidateEndpoints()
        if (candidates.isEmpty()) return ConnectionTestResult(false, "No base URL configured")
        if (candidates.size > 1) {
            val outcome = HermesEndpointRacer().race(candidates, token)
            if (outcome != null && outcome.ok) {
                HermesEndpointSelection.remember(config.id, outcome.url)
                HermesCapabilityCache.invalidate(config.id)
                val tag = if (outcome.url == config.baseUrl) "OK" else "OK via ${outcome.url}"
                return ConnectionTestResult(true, tag, outcome.latencyMs)
            }
            if (outcome != null) {
                return ConnectionTestResult(false, outcome.errorMessage ?: "HTTP ${outcome.httpStatus}")
            }
            return ConnectionTestResult(false, "No endpoints reachable")
        }

        val base = candidates.first()
        return try {
            val started = System.currentTimeMillis()
            probeChain(base)?.let { via ->
                HermesCapabilityCache.invalidate(config.id)
                ConnectionTestResult(true, via, System.currentTimeMillis() - started)
            } ?: ConnectionTestResult(false, lastProbeFailure ?: "Not reachable")
        } catch (e: Exception) {
            ConnectionTestResult(false, e.message ?: e.javaClass.simpleName)
        }
    }

    @Volatile private var lastProbeFailure: String? = null

    /** Tries `/v1/models`, then `/v1/health`, then root `/health`. Returns a label for the one that answered. */
    private fun probeChain(base: String): String? {
        val attempts = listOf(
            "OK" to HermesUrl.modelsUrl(base),
            "OK (via /v1/health)" to HermesUrl.healthUrl(base),
            "OK (via /health)" to HermesUrl.rootHealthUrl(base),
        )
        var failure: String? = null
        for ((label, url) in attempts) {
            try {
                val outcome = httpClient.newCall(authed(Request.Builder().url(url).get()).build())
                    .execute()
                    .use { resp ->
                        when {
                            resp.isSuccessful -> ProbeOutcome.Reached
                            // The server answered, but rejected us. Walking the rest
                            // of the chain would only replace a precise error with a
                            // vaguer one, so stop here.
                            resp.code == 401 || resp.code == 403 ->
                                ProbeOutcome.Rejected(describeHttpFailure(resp))
                            else -> ProbeOutcome.Missing(describeHttpFailure(resp))
                        }
                    }
                when (outcome) {
                    ProbeOutcome.Reached -> return label
                    is ProbeOutcome.Rejected -> {
                        lastProbeFailure = outcome.message
                        return null
                    }
                    is ProbeOutcome.Missing -> failure = failure ?: outcome.message
                }
            } catch (e: Exception) {
                failure = failure ?: (e.message ?: e.javaClass.simpleName)
            }
        }
        lastProbeFailure = failure
        return null
    }

    private sealed interface ProbeOutcome {
        data object Reached : ProbeOutcome
        data class Rejected(val message: String) : ProbeOutcome
        data class Missing(val message: String) : ProbeOutcome
    }

    // ------------------------------------------------------------------
    // Sending
    // ------------------------------------------------------------------

    override fun sendMessage(
        messages: List<AgentMessage>,
        options: AgentSendOptions,
    ): Flow<AgentEvent> = flow {
        val baseUrl = resolveBaseUrl()
        val caps = capabilities(baseUrl)
        when (chooseTransport(caps, messages, options)) {
            HermesTransport.SESSION_CHAT -> sendViaSessionChat(baseUrl, caps, messages, options)
            HermesTransport.RUNS -> sendViaRunsApi(baseUrl, caps, messages, options)
            HermesTransport.CHAT_COMPLETIONS -> sendViaChatCompletions(baseUrl, caps, messages, options)
        }
    }.flowOn(Dispatchers.IO)

    internal fun chooseTransport(
        caps: HermesCapabilities,
        messages: List<AgentMessage>,
        options: AgentSendOptions,
    ): HermesTransport {
        val hasImages = messages.any { it.attachments.isNotEmpty() }
        val preferred = when (config.effectiveTransport) {
            HermesTransportPreference.AUTO -> caps.preferredTransport()
            HermesTransportPreference.SESSION_CHAT -> HermesTransport.SESSION_CHAT
            HermesTransportPreference.RUNS -> HermesTransport.RUNS
            HermesTransportPreference.CHAT_COMPLETIONS -> HermesTransport.CHAT_COMPLETIONS
        }
        // Session chat needs a session id to address; without one the transport
        // has nothing to attach the conversation to.
        if (preferred == HermesTransport.SESSION_CHAT && options.sessionId.isNullOrBlank()) {
            return if (caps.runSubmission) HermesTransport.RUNS else HermesTransport.CHAT_COMPLETIONS
        }
        // The runs endpoint takes its user message as a plain string, so inline
        // images have nowhere to go. Route those turns somewhere multimodal.
        if (preferred == HermesTransport.RUNS && hasImages) {
            return if (caps.sessionChatStreaming && !options.sessionId.isNullOrBlank()) {
                HermesTransport.SESSION_CHAT
            } else {
                HermesTransport.CHAT_COMPLETIONS
            }
        }
        return preferred
    }

    // ---- Transport: session chat ---------------------------------------

    private suspend fun FlowCollector<AgentEvent>.sendViaSessionChat(
        baseUrl: String,
        caps: HermesCapabilities,
        messages: List<AgentMessage>,
        options: AgentSendOptions,
    ) {
        val sessionId = options.sessionId!!
        val ensured = ensureSession(baseUrl, sessionId)
        if (ensured != null) {
            emit(AgentEvent.Error(ensured))
            return
        }
        emit(AgentEvent.Started())

        val body = buildSessionChatBody(messages, options, caps).toRequestBody(JSON_MEDIA)
        val request = authed(
            Request.Builder().url(HermesUrl.sessionChatStreamUrl(baseUrl, sessionId)).post(body),
            options,
        ).header("Accept", "text/event-stream").build()

        streamSse(request, options) { ev, collected ->
            mapSessionEvent(ev, collected)
        }
    }

    /** Creates the server-side session if it does not exist. Returns an error message on failure. */
    private suspend fun ensureSession(baseUrl: String, sessionId: String): String? {
        val body = buildJsonObject {
            put("id", sessionId)
            put("source", "api_server")
        }.toString().toRequestBody(JSON_MEDIA)
        return try {
            httpClient.newCall(
                authed(Request.Builder().url(HermesUrl.sessionsUrl(baseUrl)).post(body)).build(),
            ).execute().use { resp ->
                // 409 means it already exists, which is exactly what we want.
                if (resp.isSuccessful || resp.code == 409) null else describeHttpFailure(resp)
            }
        } catch (e: IOException) {
            e.message ?: "Could not reach Hermes to open a session"
        }
    }

    internal fun buildSessionChatBody(
        messages: List<AgentMessage>,
        options: AgentSendOptions,
        caps: HermesCapabilities,
    ): String {
        val last = messages.lastOrNull { it.role.equals("user", ignoreCase = true) } ?: messages.last()
        return buildJsonObject {
            put("message", messageContent(last))
            systemInstructions(messages, options)?.let { put("system_message", it) }
            putModelSelection(options, caps)
        }.toString()
    }

    // ---- Transport: runs -------------------------------------------------

    private suspend fun FlowCollector<AgentEvent>.sendViaRunsApi(
        baseUrl: String,
        caps: HermesCapabilities,
        messages: List<AgentMessage>,
        options: AgentSendOptions,
    ) {
        val createBody = buildRunsRequestBody(messages, options, caps).toRequestBody(JSON_MEDIA)
        val createReq = authed(Request.Builder().url(HermesUrl.runsUrl(baseUrl)).post(createBody), options).build()

        val runId = try {
            httpClient.newCall(createReq).execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit(AgentEvent.Error("Could not start the run: ${describeHttpFailure(resp)}"))
                    return
                }
                val obj = runCatching { json.parseToJsonElement(resp.body?.string().orEmpty()).jsonObject }.getOrNull()
                obj?.get("run_id")?.jsonPrimitive?.contentOrNull
                    ?: obj?.get("id")?.jsonPrimitive?.contentOrNull
            }
        } catch (e: IOException) {
            emit(AgentEvent.Error(e.message ?: "Could not reach Hermes", e))
            return
        }
        if (runId.isNullOrBlank()) {
            emit(AgentEvent.Error("Hermes started a run but did not return a run id"))
            return
        }

        currentRunId = runId
        emit(AgentEvent.Started(runId))

        val eventsReq = authed(Request.Builder().url(HermesUrl.runEventsUrl(baseUrl, runId)).get(), options)
            .header("Accept", "text/event-stream").build()
        var finished = false
        try {
            streamSse(eventsReq, options, runId = runId) { ev, collected ->
                mapRunEvent(ev, collected, runId)
            }
            finished = true
        } finally {
            currentRunId = null
            // Unlike the session stream, /v1/runs does NOT interrupt the agent
            // when the SSE consumer disconnects — it only drops the event queue.
            // Leaving the screen would otherwise let the agent keep running host
            // tools and burning tokens to completion.
            if (!finished && options.stopOnDispose) {
                withContext(NonCancellable) { runCatching { stopRun(runId) } }
            }
        }
    }

    internal fun buildRunsRequestBody(
        messages: List<AgentMessage>,
        options: AgentSendOptions,
        caps: HermesCapabilities,
    ): String {
        val lastUserIdx = messages.indexOfLast { it.role.equals("user", ignoreCase = true) }
        val inputIdx = if (lastUserIdx >= 0) lastUserIdx else messages.lastIndex
        val history = messages.filterIndexed { i, m ->
            i != inputIdx && !m.role.equals("system", ignoreCase = true)
        }
        return buildJsonObject {
            put("input", messages.getOrNull(inputIdx)?.content.orEmpty())
            options.sessionId?.takeIf { it.isNotBlank() }?.let { put("session_id", it) }
            systemInstructions(messages, options)?.let { put("instructions", it) }
            if (history.isNotEmpty()) {
                putJsonArray("conversation_history") {
                    history.forEach { m ->
                        add(buildJsonObject {
                            put("role", m.role)
                            put("content", m.content)
                        })
                    }
                }
            }
            putModelSelection(options, caps)
        }.toString()
    }

    // ---- Transport: chat completions -------------------------------------

    private suspend fun FlowCollector<AgentEvent>.sendViaChatCompletions(
        baseUrl: String,
        caps: HermesCapabilities,
        messages: List<AgentMessage>,
        options: AgentSendOptions,
    ) {
        emit(AgentEvent.Started())
        val stream = options.stream && config.useStreaming && caps.chatCompletionsStreaming
        val body = buildChatRequestBody(messages, stream, options, caps).toRequestBody(JSON_MEDIA)
        val builder = authed(
            Request.Builder().url(HermesUrl.chatCompletionsUrl(baseUrl)).post(body),
            options,
        )
        if (stream) builder.header("Accept", "text/event-stream")
        val request = builder.build()

        if (!stream) {
            try {
                httpClient.newCall(request).also { currentCall = it }.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        emit(AgentEvent.Error(describeHttpFailure(resp)))
                        return
                    }
                    emit(AgentEvent.Completed(extractNonStreamingContent(resp.body?.string().orEmpty())))
                }
            } catch (e: IOException) {
                emit(AgentEvent.Error(e.message ?: "I/O error", e))
            } finally {
                currentCall = null
            }
            return
        }
        streamSse(request, options) { ev, collected -> mapChatCompletionEvent(ev, collected) }
    }

    internal fun buildChatRequestBody(
        messages: List<AgentMessage>,
        stream: Boolean,
        options: AgentSendOptions = AgentSendOptions(),
        caps: HermesCapabilities = HermesCapabilities.LEGACY,
    ): String = buildJsonObject {
        put("stream", stream)
        putJsonArray("messages") {
            messages.forEach { m ->
                add(buildJsonObject {
                    put("role", m.role)
                    put("content", messageContent(m))
                })
            }
        }
        putModelSelection(options, caps)
    }.toString()

    // ---- Shared request building ----------------------------------------

    /** Plain string when there is nothing but text, OpenAI content parts when there are images. */
    private fun messageContent(message: AgentMessage): JsonElement =
        if (message.attachments.isEmpty()) {
            JsonPrimitive(message.content)
        } else {
            buildJsonArray {
                if (message.content.isNotBlank()) {
                    add(buildJsonObject {
                        put("type", "text")
                        put("text", message.content)
                    })
                }
                message.attachments.forEach { attachment ->
                    add(buildJsonObject {
                        put("type", "image_url")
                        putJsonObject("image_url") { put("url", attachment.dataUrl) }
                    })
                }
            }
        }

    /** Hermes layers `instructions` on top of its own prompt, so a system turn maps straight onto it. */
    private fun systemInstructions(messages: List<AgentMessage>, options: AgentSendOptions): String? {
        val fromMessages = messages.filter { it.role.equals("system", ignoreCase = true) }
            .joinToString("\n\n") { it.content }
            .takeIf { it.isNotBlank() }
        val explicit = options.instructions?.takeIf { it.isNotBlank() }
        return listOfNotNull(explicit, fromMessages).joinToString("\n\n").takeIf { it.isNotBlank() }
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putModelSelection(
        options: AgentSendOptions,
        caps: HermesCapabilities,
    ) {
        requestedModel(options, caps)?.let { put("model", it) }
        requestedProvider(options)?.let { put("provider", it) }
        val modelOptions = options.modelOptions.filterValues { it.isNotBlank() }
        if (modelOptions.isNotEmpty()) {
            putJsonObject("model_options") { modelOptions.forEach { (k, v) -> put(k, v) } }
        }
    }

    private fun authed(
        builder: Request.Builder,
        options: AgentSendOptions? = null,
    ): Request.Builder {
        token?.let { builder.header("Authorization", "Bearer $it") }
        options?.sessionId?.takeIf { it.isNotBlank() }?.let { builder.header(HEADER_SESSION_ID, it) }
        (options?.memoryScopeKey ?: config.memoryScopeKey)
            ?.takeIf { it.isNotBlank() }
            ?.take(MAX_SESSION_KEY_LEN)
            ?.let { builder.header(HEADER_SESSION_KEY, it) }
        return builder
    }

    // ------------------------------------------------------------------
    // SSE plumbing
    // ------------------------------------------------------------------

    /**
     * Reads an SSE response to completion, mapping each frame with [map].
     *
     * The OkHttp call is cancelled when the collecting coroutine is cancelled,
     * so abandoning a stream releases the socket immediately instead of leaving
     * a thread parked in a blocking read. A [AgentEvent.Completed] from [map]
     * ends the stream; running out of frames without one completes with whatever
     * text was collected.
     */
    private suspend fun FlowCollector<AgentEvent>.streamSse(
        request: Request,
        options: AgentSendOptions,
        runId: String? = null,
        map: (SseEvent, StringBuilder) -> AgentEvent?,
    ) {
        val call = httpClient.newCall(request)
        currentCall = call
        val cancelOnAbort = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) {
                    emit(AgentEvent.Error(describeHttpFailure(resp)))
                    return
                }
                val source = resp.body?.source()
                if (source == null) {
                    emit(AgentEvent.Error("Hermes returned an empty response body"))
                    return
                }
                val parser = SseParser()
                val collected = StringBuilder()
                var terminated = false
                while (!source.exhausted()) {
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    val frame = parser.feed(line) ?: continue
                    val mapped = map(frame, collected) ?: continue
                    if (mapped is AgentEvent.ApprovalRequest) {
                        emit(mapped)
                        handleApproval(mapped, options)
                        continue
                    }
                    emit(mapped)
                    if (mapped is AgentEvent.Completed || mapped is AgentEvent.Error) {
                        terminated = true
                        break
                    }
                }
                if (!terminated) emit(AgentEvent.Completed(collected.toString(), runId))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            if (call.isCanceled()) throw CancellationException("Hermes stream cancelled")
            noteTransportFailure(e)
            emit(AgentEvent.Error(readableIoFailure(e), e))
        } finally {
            cancelOnAbort?.dispose()
            currentCall = null
        }
    }

    /**
     * Hermes blocks the agent until an approval is answered. Where there is no
     * UI to ask, deny immediately rather than leaving the run wedged until the
     * server's five-minute timeout.
     */
    private suspend fun FlowCollector<AgentEvent>.handleApproval(
        request: AgentEvent.ApprovalRequest,
        options: AgentSendOptions,
    ) {
        if (options.approvalPolicy != ApprovalPolicy.DENY) return
        respondToApproval(request.runId, "deny")
        emit(
            AgentEvent.ToolProgress(
                tool = request.tool ?: "approval",
                stage = "denied",
                detail = "Declined automatically — approve tool use from the chat screen.",
            ),
        )
    }

    // ------------------------------------------------------------------
    // Event mapping
    // ------------------------------------------------------------------

    /** `/api/sessions/{id}/chat/stream` frames carry a named `event:` line. */
    internal fun mapSessionEvent(ev: SseEvent, collected: StringBuilder): AgentEvent? {
        val obj = parseObject(ev.data)
        return when (ev.event) {
            "assistant.delta" -> {
                val delta = obj?.string("delta").orEmpty()
                if (delta.isEmpty()) null else {
                    collected.append(delta)
                    AgentEvent.TokenDelta(delta)
                }
            }
            "tool.progress" -> {
                val name = obj?.string("tool_name")
                // The server folds reasoning into tool.progress under a
                // reserved tool name rather than a distinct event.
                if (name == "_thinking") {
                    obj.string("delta")?.takeIf { it.isNotBlank() }?.let { AgentEvent.Reasoning(it) }
                } else {
                    AgentEvent.ToolProgress(name ?: "tool", "progress", obj?.string("delta"))
                }
            }
            "tool.started", "tool.completed", "tool.failed" -> AgentEvent.ToolProgress(
                tool = obj?.string("tool_name") ?: "tool",
                stage = ev.event.substringAfter('.'),
                detail = obj?.string("preview"),
            )
            "assistant.completed" -> {
                val content = obj?.string("content")
                if (!content.isNullOrEmpty()) {
                    collected.setLength(0)
                    collected.append(content)
                }
                if (obj?.bool("interrupted") == true) {
                    AgentEvent.Error("Hermes interrupted this answer before it finished")
                } else {
                    null
                }
            }
            "run.completed" -> AgentEvent.Completed(collected.toString(), obj?.string("run_id"))
            "error" -> AgentEvent.Error(obj?.string("message") ?: "Hermes reported an error")
            "done" -> AgentEvent.Completed(collected.toString(), obj?.string("run_id"))
            else -> null
        }
    }

    /** `/v1/runs/{id}/events` frames are unnamed; the event name lives in the JSON. */
    internal fun mapRunEvent(ev: SseEvent, collected: StringBuilder, runIdHint: String?): AgentEvent? {
        if (ev.data == "[DONE]") return AgentEvent.Completed(collected.toString(), runIdHint)
        val obj = parseObject(ev.data) ?: return null
        return when (obj.string("event")) {
            "message.delta" -> {
                val delta = obj.string("delta").orEmpty()
                if (delta.isEmpty()) null else {
                    collected.append(delta)
                    AgentEvent.TokenDelta(delta)
                }
            }
            "tool.started" -> AgentEvent.ToolProgress(
                tool = obj.string("tool") ?: "tool",
                stage = "started",
                detail = obj.string("preview"),
            )
            "tool.completed" -> AgentEvent.ToolProgress(
                tool = obj.string("tool") ?: "tool",
                stage = if (obj.bool("error") == true) "failed" else "completed",
                // `error` here is a boolean flag, not a message; the useful
                // detail is how long the tool took.
                detail = obj["duration"]?.jsonPrimitive?.contentOrNull?.let { "${it}s" },
            )
            "reasoning.available" -> obj.string("text")
                ?.takeIf { it.isNotBlank() }
                ?.let { AgentEvent.Reasoning(it) }
            "approval.request" -> AgentEvent.ApprovalRequest(
                runId = obj.string("run_id") ?: runIdHint.orEmpty(),
                requestId = obj.string("request_id"),
                tool = obj.string("tool") ?: obj.string("tool_name"),
                command = obj.string("command"),
                description = obj.string("description"),
                choices = (obj["choices"] as? JsonArray)
                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                    ?.takeIf { it.isNotEmpty() }
                    ?: listOf("once", "deny"),
            )
            "approval.responded" -> AgentEvent.ToolProgress(
                tool = "approval",
                stage = "resolved",
                detail = obj.string("choice"),
            )
            "subagent.start" -> AgentEvent.ToolProgress(
                tool = "subagent",
                stage = "started",
                detail = obj.string("goal") ?: obj.string("preview"),
            )
            "subagent.complete" -> AgentEvent.ToolProgress(
                tool = "subagent",
                stage = "completed",
                detail = obj.string("summary") ?: obj.string("status"),
            )
            "run.steered" -> AgentEvent.ToolProgress("run", "steered", obj.string("text"))
            "run.completed" -> AgentEvent.Completed(
                obj.string("output") ?: obj.string("message") ?: collected.toString(),
                runIdHint ?: obj.string("run_id"),
            )
            "run.failed" -> AgentEvent.Error(
                obj.string("error") ?: obj.string("message") ?: "The Hermes run failed",
            )
            "run.cancelled" -> AgentEvent.Error("The Hermes run was cancelled")
            else -> null
        }
    }

    /** `/v1/chat/completions` frames are OpenAI chunks, plus a named Hermes tool event. */
    internal fun mapChatCompletionEvent(ev: SseEvent, collected: StringBuilder): AgentEvent? {
        if (ev.data == "[DONE]") return AgentEvent.Completed(collected.toString())
        if (ev.event == "hermes.tool.progress") {
            val obj = parseObject(ev.data) ?: return null
            return AgentEvent.ToolProgress(
                tool = obj.string("tool") ?: "tool",
                stage = obj.string("stage") ?: "progress",
                detail = obj.string("detail") ?: obj.string("preview"),
            )
        }
        val obj = parseObject(ev.data) ?: return null

        // A chunk can carry a transport-level error object alongside the choice.
        obj["error"]?.let { err ->
            val message = (err as? JsonObject)?.string("message") ?: err.jsonPrimitive.contentOrNull
            if (!message.isNullOrBlank()) return AgentEvent.Error(message)
        }

        val choice = (obj["choices"] as? JsonArray)?.firstOrNull()?.jsonObject
        val token = (choice?.get("delta") as? JsonObject)?.string("content")
            ?: choice?.string("text")
        if (!token.isNullOrEmpty()) {
            collected.append(token)
            return AgentEvent.TokenDelta(token)
        }

        val finish = choice?.string("finish_reason") ?: return null
        // Hermes reports failure and truncation through finish_reason plus a
        // `hermes` block. Treating those as a clean stop would present a broken
        // answer as a complete one.
        val hermes = obj["hermes"] as? JsonObject
        return when {
            finish == "stop" -> AgentEvent.Completed(collected.toString())
            finish == "length" -> AgentEvent.Error(
                "Hermes stopped early: the reply hit the model's output limit.",
            )
            else -> AgentEvent.Error(
                hermes?.string("error")
                    ?: hermes?.string("error_code")
                    ?: "Hermes stopped early (finish_reason=$finish)",
            )
        }
    }

    // ------------------------------------------------------------------
    // Run control
    // ------------------------------------------------------------------

    override suspend fun stopCurrentRun(): Boolean {
        val call = currentCall
        val runId = currentRunId
        call?.cancel()
        currentCall = null
        val stopped = runId?.let { stopRun(it) } ?: false
        currentRunId = null
        return stopped || call != null
    }

    override suspend fun stopRun(runId: String): Boolean =
        postRunControl(HermesUrl::runStopUrl, runId, "{}")

    override suspend fun respondToApproval(runId: String, choice: String): Boolean =
        postRunControl(HermesUrl::runApprovalUrl, runId, buildJsonObject { put("choice", choice) }.toString())

    override suspend fun steerRun(runId: String, text: String): Boolean =
        postRunControl(HermesUrl::runSteerUrl, runId, buildJsonObject { put("text", text) }.toString())

    /**
     * Run-control calls are made straight from UI callbacks (the Stop button, the
     * approval dialog), so they must move to IO themselves — a blocking socket
     * call on the main thread throws [android.os.NetworkOnMainThreadException],
     * and swallowing that would turn Stop and Approve into silent no-ops.
     */
    private suspend fun postRunControl(
        url: (String, String) -> String,
        runId: String,
        body: String,
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val base = resolveBaseUrl()
            val request = authed(
                Request.Builder().url(url(base, runId)).post(body.toRequestBody(JSON_MEDIA)),
            ).build()
            httpClient.newCall(request).execute().use { it.isSuccessful }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Hermes run control failed for $runId: ${e.message}")
            false
        }
    }

    // ------------------------------------------------------------------
    // Parsing helpers
    // ------------------------------------------------------------------

    private fun parseObject(raw: String): JsonObject? =
        runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()

    /** Reads a primitive as text; JSON `null` and absent keys both come back as null. */
    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun extractNonStreamingContent(text: String): String = runCatching {
        val choice = (json.parseToJsonElement(text).jsonObject["choices"] as? JsonArray)
            ?.firstOrNull()?.jsonObject
        (choice?.get("message") as? JsonObject)?.string("content")
            ?: choice?.string("text")
            ?: ""
    }.getOrDefault("")

    /** Turns a non-2xx response into something worth showing a user. */
    private fun describeHttpFailure(response: Response): String {
        val bodyText = runCatching { response.peekBody(MAX_ERROR_BODY).string() }.getOrNull().orEmpty()
        val detail = extractErrorMessage(bodyText)
        return when (response.code) {
            401, 403 -> "Hermes rejected the API key (HTTP ${response.code})" +
                if (detail.isNotBlank()) ": $detail" else ""
            404 -> "Hermes has no endpoint at ${response.request.url.encodedPath} (HTTP 404)" +
                if (detail.isNotBlank()) ": $detail" else ""
            429 -> {
                val retry = response.header("Retry-After")
                "Hermes is at its concurrent-run limit" + (retry?.let { "; retry in ${it}s" } ?: "; try again shortly")
            }
            503 -> "Hermes is restarting or unavailable (HTTP 503)"
            else -> listOf("HTTP ${response.code}", detail.ifBlank { response.message })
                .filter { it.isNotBlank() }
                .joinToString(": ")
        }
    }

    private fun extractErrorMessage(text: String): String {
        if (text.isBlank()) return ""
        return runCatching {
            val obj = json.parseToJsonElement(text).jsonObject
            (obj["error"] as? JsonObject)?.string("message")
                ?: (obj["error"] as? JsonPrimitive)?.contentOrNull
                ?: obj.string("message")
                ?: obj.string("detail")
                ?: text
        }.getOrDefault(text).trim().take(MAX_ERROR_DETAIL)
    }

    /**
     * A route-level failure means the cached race winner is no longer reachable
     * — usually the phone left the network the LAN URL belongs to. Dropping it
     * makes the next call re-race instead of retrying a dead address.
     */
    private fun noteTransportFailure(e: IOException) {
        val routeIsGone = e is java.net.UnknownHostException ||
            e is java.net.ConnectException ||
            e is java.net.NoRouteToHostException
        if (routeIsGone && config.secondaryUrls.isNotEmpty()) {
            HermesEndpointSelection.invalidate(config.id)
        }
    }

    private fun readableIoFailure(e: IOException): String = when (e) {
        is java.net.SocketTimeoutException ->
            "Hermes stopped sending data. The run may still be going on the server."
        is java.net.UnknownHostException -> "Cannot resolve the Hermes host"
        is java.net.ConnectException -> "Cannot reach the Hermes server"
        else -> e.message ?: "Network error talking to Hermes"
    }

    companion object {
        private const val TAG = "HermesApiServerClient"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private const val MAX_ERROR_BODY = 8_192L
        private const val MAX_ERROR_DETAIL = 500
        private const val MAX_SESSION_KEY_LEN = 256
        internal const val HEADER_SESSION_ID = "X-Hermes-Session-Id"
        internal const val HEADER_SESSION_KEY = "X-Hermes-Session-Key"

        /**
         * Read timeout is finite on purpose. Hermes writes an SSE keepalive
         * comment every 30 seconds on every streaming endpoint, so a stream that
         * goes quiet for longer is genuinely dead — an infinite timeout just
         * turns a dropped connection into a spinner that never stops.
         */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
