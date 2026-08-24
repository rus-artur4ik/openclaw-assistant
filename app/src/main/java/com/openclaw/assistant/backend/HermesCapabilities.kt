package com.openclaw.assistant.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * What a particular Hermes API server can actually do, read from
 * `GET /v1/capabilities` rather than assumed.
 *
 * Hermes advertises a stable feature map so clients do not have to guess which
 * endpoints a given deployment serves. Everything here degrades safely: an
 * older server that does not serve `/v1/capabilities` yields [LEGACY], which
 * claims only the OpenAI-compatible surface that has always existed.
 */
data class HermesCapabilities(
    /** Model id advertised by this server — the "use the server default" sentinel. */
    val advertisedModel: String? = null,
    val authRequired: Boolean = true,
    val chatCompletions: Boolean = true,
    val chatCompletionsStreaming: Boolean = true,
    val runSubmission: Boolean = false,
    val runEventsSse: Boolean = false,
    val runStop: Boolean = false,
    val runSteer: Boolean = false,
    val runApprovalResponse: Boolean = false,
    val approvalEvents: Boolean = false,
    val toolProgressEvents: Boolean = false,
    val sessionResources: Boolean = false,
    val sessionChat: Boolean = false,
    val sessionChatStreaming: Boolean = false,
    val sessionModelLock: Boolean = false,
    val modelOptions: Boolean = false,
    val skillsApi: Boolean = false,
    /** Header name the server wants for conversation continuity, e.g. `X-Hermes-Session-Id`. */
    val sessionContinuityHeader: String? = null,
    /** Header name the server wants for long-term memory scoping. */
    val sessionKeyHeader: String? = null,
    /** False when `/v1/capabilities` did not answer — every flag above is then a guess. */
    val detected: Boolean = false,
) {
    /**
     * The richest conversation transport this server supports.
     *
     * Session chat is preferred because Hermes then owns the transcript: history
     * survives app restarts and is not re-uploaded on every turn. Runs come next
     * because they are the only surface that can answer tool approvals. Plain
     * chat completions is the universal floor.
     */
    fun preferredTransport(allowSessions: Boolean = true): HermesTransport = when {
        allowSessions && sessionChatStreaming -> HermesTransport.SESSION_CHAT
        runSubmission && runEventsSse -> HermesTransport.RUNS
        else -> HermesTransport.CHAT_COMPLETIONS
    }

    companion object {
        /**
         * Assumed surface for a server that does not answer `/v1/capabilities`.
         * Only the OpenAI-compatible endpoints, which every Hermes build serves.
         */
        val LEGACY = HermesCapabilities(detected = false)
    }
}

enum class HermesTransport { SESSION_CHAT, RUNS, CHAT_COMPLETIONS }

/**
 * Fetches and caches [HermesCapabilities] per backend id.
 *
 * The probe is cheap and its answer is stable for the lifetime of a server
 * process, so it is cached in memory and refreshed only when a backend's
 * configuration changes or a caller explicitly asks.
 */
class HermesCapabilitiesProbe(
    private val httpClient: OkHttpClient = defaultClient(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    suspend fun fetch(baseUrl: String, token: String?): HermesCapabilities = withContext(Dispatchers.IO) {
        try {
            val builder = Request.Builder().url(HermesUrl.capabilitiesUrl(baseUrl)).get()
            token?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
            httpClient.newCall(builder.build()).execute().use { response ->
                if (!response.isSuccessful) return@withContext HermesCapabilities.LEGACY
                val body = response.body?.string()?.takeIf { it.isNotBlank() }
                    ?: return@withContext HermesCapabilities.LEGACY
                parse(json.parseToJsonElement(body).jsonObject)
            }
        } catch (_: Throwable) {
            HermesCapabilities.LEGACY
        }
    }

    internal fun parse(root: JsonObject): HermesCapabilities {
        val features = root["features"] as? JsonObject ?: JsonObject(emptyMap())
        fun flag(name: String, default: Boolean = false): Boolean =
            features[name]?.jsonPrimitive?.booleanOrNull ?: default
        fun text(name: String): String? =
            features[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

        return HermesCapabilities(
            advertisedModel = root["model"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            authRequired = (root["auth"] as? JsonObject)?.get("required")?.jsonPrimitive?.booleanOrNull ?: true,
            chatCompletions = flag("chat_completions", default = true),
            chatCompletionsStreaming = flag("chat_completions_streaming", default = true),
            runSubmission = flag("run_submission"),
            runEventsSse = flag("run_events_sse"),
            runStop = flag("run_stop"),
            runSteer = flag("run_steer"),
            runApprovalResponse = flag("run_approval_response"),
            approvalEvents = flag("approval_events"),
            toolProgressEvents = flag("tool_progress_events"),
            sessionResources = flag("session_resources"),
            sessionChat = flag("session_chat"),
            sessionChatStreaming = flag("session_chat_streaming"),
            sessionModelLock = flag("session_model_lock"),
            modelOptions = flag("model_options"),
            skillsApi = flag("skills_api"),
            sessionContinuityHeader = text("session_continuity_header"),
            sessionKeyHeader = text("session_key_header"),
            detected = true,
        )
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Process-wide cache of the capability probe, keyed by backend id.
 *
 * A successful probe is stable for the life of a server process and is kept.
 * A *failed* probe yields [HermesCapabilities.LEGACY], which is a guess rather
 * than a measurement — caching that forever would let one timeout during a
 * network handoff silently downgrade the app to the plainest transport, with no
 * tool approvals, no tool progress and no Stop button, until it was restarted.
 * Failures are therefore retried shortly.
 */
object HermesCapabilityCache {
    private const val FAILED_PROBE_RETRY_MS = 30_000L

    private data class Entry(val capabilities: HermesCapabilities, val fetchedAtMs: Long)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun peek(backendId: String): HermesCapabilities? = valid(cache[backendId])

    suspend fun get(
        backendId: String,
        baseUrl: String,
        token: String?,
        probe: HermesCapabilitiesProbe = HermesCapabilitiesProbe(),
    ): HermesCapabilities {
        valid(cache[backendId])?.let { return it }
        val fetched = probe.fetch(baseUrl, token)
        cache[backendId] = Entry(fetched, System.currentTimeMillis())
        return fetched
    }

    private fun valid(entry: Entry?): HermesCapabilities? {
        if (entry == null) return null
        if (entry.capabilities.detected) return entry.capabilities
        val expired = System.currentTimeMillis() - entry.fetchedAtMs > FAILED_PROBE_RETRY_MS
        return if (expired) null else entry.capabilities
    }

    fun invalidate(backendId: String) { cache.remove(backendId) }

    fun clear() = cache.clear()
}
