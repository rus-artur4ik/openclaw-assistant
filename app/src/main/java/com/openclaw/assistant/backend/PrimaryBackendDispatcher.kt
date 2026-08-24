package com.openclaw.assistant.backend

import android.content.Context
import com.openclaw.assistant.OpenClawApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Single entry point used by voice (wake word, Voice Overlay, Assistant
 * activation, continuous conversation) and Chat to send one user message to
 * whichever backend is currently selected.
 *
 * Streaming is surfaced through [onEvent] for callers that can render it; the
 * final text is always returned so the text-only voice pipeline can stay simple.
 */
object PrimaryBackendDispatcher {
    /** How long a single turn may take before the caller is told it failed. */
    private const val TURN_TIMEOUT_MS = 180_000L

    data class Reply(
        val text: String,
        val sourceDisplayName: String,
        /** Set when the backend exposed a cancellable run for this turn. */
        val runId: String? = null,
    )

    /**
     * Sends to the selected enabled backend. If [backendId] is null, the
     * persisted Primary is used. Returns null only when there is no configured
     * target, allowing legacy installs without migrated backends to fall back to
     * the original settings-driven pipeline.
     *
     * @param history prior turns of this conversation, oldest first, excluding
     *   [userText]. Backends that keep server-side transcripts ignore it.
     * @param onEvent receives every [AgentEvent] as it arrives, for callers that
     *   render tokens or tool activity live.
     */
    suspend fun send(
        context: Context,
        userText: String,
        backendId: String? = null,
        sessionId: String? = null,
        agentId: String? = null,
        history: List<AgentMessage> = emptyList(),
        attachments: List<AgentAttachment> = emptyList(),
        approvalPolicy: ApprovalPolicy = ApprovalPolicy.DENY,
        onEvent: ((AgentEvent) -> Unit)? = null,
    ): Reply? {
        val manager = BackendManager.getInstance(context)
        val backends = manager.backends.first()
        val target = if (backendId != null) {
            backends.firstOrNull { it.id == backendId && it.enabled }
        } else {
            backends.firstOrNull { it.enabled && it.isPrimary }
        } ?: return null
        return when (target.type) {
            BackendType.HERMES_API_SERVER,
            BackendType.OPENCLAW_HTTP -> sendViaAgentClient(
                context = context,
                target = target,
                userText = userText,
                sessionId = sessionId,
                agentId = agentId,
                history = history,
                attachments = attachments,
                approvalPolicy = approvalPolicy,
                onEvent = onEvent,
            )
            BackendType.OPENCLAW_GATEWAY -> sendViaGateway(context, target, userText)
        }
    }

    suspend fun sendPrimary(
        context: Context,
        userText: String,
        sessionId: String? = null,
        agentId: String? = null,
        history: List<AgentMessage> = emptyList(),
        onEvent: ((AgentEvent) -> Unit)? = null,
    ): Reply? = send(
        context = context,
        userText = userText,
        backendId = null,
        sessionId = sessionId,
        agentId = agentId,
        history = history,
        onEvent = onEvent,
    )

    /**
     * Backwards-compatible alias retained for older call sites during the
     * migration. It now sends to any Primary backend, not only Hermes.
     */
    suspend fun sendIfHermesPrimary(
        context: Context,
        userText: String,
    ): Reply? = sendPrimary(context, userText)

    private suspend fun sendViaAgentClient(
        context: Context,
        target: AgentBackendConfig,
        userText: String,
        sessionId: String?,
        agentId: String?,
        history: List<AgentMessage>,
        attachments: List<AgentAttachment>,
        approvalPolicy: ApprovalPolicy,
        onEvent: ((AgentEvent) -> Unit)?,
    ): Reply {
        val client = AgentClientFactory.create(target)
        val collected = StringBuilder()
        var completedText: String? = null
        var runId: String? = null
        var failure: String? = null
        val run = AgentDiagnostics.beginMessage(context, target, userText.length)

        val messages = history + AgentMessage.user(userText, attachments)
        val options = AgentSendOptions(
            sessionId = sessionId,
            stream = target.useStreaming,
            approvalPolicy = approvalPolicy,
            memoryScopeKey = target.memoryScopeKey,
            extra = mapOf("agentId" to agentId.orEmpty()),
        )

        // The deadline is a watchdog rather than a plain withTimeout, because an
        // approval prompt is answered by a human: their thinking time must not
        // count against the turn.
        val deadline = java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis() + TURN_TIMEOUT_MS)
        var timedOut = false

        try {
            coroutineScope {
                val collector = launch {
                    client.sendMessage(messages, options).collect { event ->
                        onEvent?.invoke(event)
                        deadline.set(System.currentTimeMillis() + TURN_TIMEOUT_MS)
                        when (event) {
                            is AgentEvent.Started -> runId = event.runId ?: runId
                            is AgentEvent.TokenDelta -> {
                                collected.append(event.text)
                                run.onToken(event.text.length)
                            }
                            is AgentEvent.MessageDelta -> {
                                collected.append(event.text)
                                run.onToken(event.text.length)
                            }
                            is AgentEvent.Completed -> {
                                completedText = event.finalText
                                runId = event.runId ?: runId
                                run.complete(event.finalText.length.coerceAtLeast(collected.length))
                            }
                            is AgentEvent.ToolProgress -> com.openclaw.assistant.ui.backend.ToolProgressFeed.push(event)
                            is AgentEvent.ApprovalRequest ->
                                // Waiting on a person. Hold the deadline open; the
                                // server keeps the stream alive with keepalives, and
                                // it enforces its own approval timeout.
                                deadline.set(Long.MAX_VALUE)
                            is AgentEvent.Reasoning -> Unit
                            is AgentEvent.Error -> failure = event.message
                        }
                    }
                }
                val watchdog = launch {
                    while (collector.isActive) {
                        val remaining = deadline.get() - System.currentTimeMillis()
                        if (remaining <= 0) {
                            timedOut = true
                            collector.cancel()
                            return@launch
                        }
                        delay(remaining.coerceAtMost(TURN_TIMEOUT_MS))
                    }
                }
                collector.join()
                watchdog.cancel()
            }
        } catch (e: CancellationException) {
            if (!timedOut) throw e
        } catch (e: Throwable) {
            run.error(e.message ?: e.javaClass.simpleName)
            throw e
        }

        if (timedOut) {
            run.error("Timed out")
            runId?.let { id -> withContext(NonCancellable) { runCatching { client.stopRun(id) } } }
            throw IllegalStateException(
                "${target.displayName} did not finish within ${TURN_TIMEOUT_MS / 1000}s.",
            )
        }

        failure?.let { message ->
            run.error(message)
            throw RuntimeException("${target.displayName}: $message")
        }

        // A Completed carrying text wins; otherwise fall back to what streamed in.
        val text = completedText?.takeIf { it.isNotBlank() } ?: collected.toString()
        if (text.isBlank()) {
            throw IllegalStateException(
                "${target.displayName} returned an empty response. Check the backend model/provider configuration.",
            )
        }
        return Reply(text = text, sourceDisplayName = target.displayName, runId = runId)
    }

    /**
     * The slice of the gateway runtime one turn needs.
     *
     * A seam, not an abstraction for its own sake: [com.openclaw.assistant.node.NodeRuntime]
     * needs the Android KeyStore and system services, so without this the whole
     * gateway leg — the reply poll, the error hand-off and the timeout — could
     * only ever be exercised by hand on a device.
     */
    internal interface GatewayChatPort {
        fun isHealthy(): Boolean

        /** Error text the runtime is currently reporting, if any. */
        fun currentError(): String?

        /** First text part of each assistant reply in the transcript, oldest first. */
        fun assistantReplies(): List<String>

        suspend fun send(message: String, modelName: String?)
    }

    private class NodeRuntimeGatewayPort(context: Context) : GatewayChatPort {
        private val runtime = (context.applicationContext as OpenClawApplication).nodeRuntime
        override fun isHealthy() = runtime.chatHealthOk.value
        override fun currentError() = runtime.chatError.value
        override fun assistantReplies(): List<String> = runtime.chatMessages.value
            .filter { it.role == "assistant" }
            .map { it.content.firstOrNull { part -> part.type == "text" }?.text.orEmpty() }
        override suspend fun send(message: String, modelName: String?) = runtime.sendChat(
            message = message,
            thinking = "low",
            attachments = emptyList(),
            modelName = modelName,
        )
    }

    /** Replaced in tests; see [GatewayChatPort]. */
    @Volatile
    internal var gatewayPortFactory: (Context) -> GatewayChatPort = { NodeRuntimeGatewayPort(it) }

    /** How long the gateway has to produce a reply before the turn is failed. */
    internal const val GATEWAY_REPLY_TIMEOUT_MS = 60_000L

    private suspend fun sendViaGateway(
        context: Context,
        target: AgentBackendConfig,
        userText: String,
    ): Reply {
        val gateway = gatewayPortFactory(context)
        if (!gateway.isHealthy()) {
            throw IllegalStateException("OpenClaw Gateway is not connected")
        }

        val run = AgentDiagnostics.beginMessage(context, target, userText.length)
        val repliesBefore = gateway.assistantReplies().size
        gateway.send(userText, target.modelName?.takeIf { it.isNotBlank() })

        val responseText: String? = try {
            withTimeout<String>(GATEWAY_REPLY_TIMEOUT_MS) {
                var found: String? = null
                while (found == null) {
                    gateway.currentError()?.takeIf { it.isNotBlank() }?.let { error ->
                        throw IllegalStateException(error)
                    }
                    val replies = gateway.assistantReplies()
                    val newest = replies.takeIf { it.size > repliesBefore }?.lastOrNull()
                    if (!newest.isNullOrBlank()) {
                        found = newest
                    } else {
                        delay(250L)
                    }
                }
                found
            }
        } catch (_: TimeoutCancellationException) {
            null
        }

        if (responseText.isNullOrBlank()) {
            run.error("No reply before timeout")
            throw IllegalStateException(
                "OpenClaw Gateway accepted the message, but the agent did not return a reply. Check the host OpenClaw agent/model authentication."
            )
        }
        run.onToken(responseText.length)
        run.complete(responseText.length)
        return Reply(text = responseText, sourceDisplayName = target.displayName)
    }
}
