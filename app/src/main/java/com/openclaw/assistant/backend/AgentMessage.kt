package com.openclaw.assistant.backend

import kotlinx.serialization.Serializable

enum class AgentRole { SYSTEM, USER, ASSISTANT, TOOL }

/** An inline image sent alongside a message. Hermes accepts `data:` image URLs. */
@Serializable
data class AgentAttachment(
    val mimeType: String,
    val base64: String,
) {
    val dataUrl: String get() = "data:$mimeType;base64,$base64"
}

@Serializable
data class AgentMessage(
    val role: String,
    val content: String,
    val attachments: List<AgentAttachment> = emptyList(),
) {
    companion object {
        fun system(text: String) = AgentMessage("system", text)
        fun user(text: String, attachments: List<AgentAttachment> = emptyList()) =
            AgentMessage("user", text, attachments)
        fun assistant(text: String) = AgentMessage("assistant", text)
    }
}

/**
 * How to answer a Hermes `approval.request` — the server blocks the agent's
 * tool call until a decision arrives, so a client that never answers hangs the
 * run until the server-side timeout (5 minutes by default) elapses.
 */
enum class ApprovalPolicy {
    /** Surface the request to the user and wait. Only safe where there is a UI. */
    ASK,

    /** Deny immediately. The right default for headless paths such as wake-word voice. */
    DENY,
}

data class AgentSendOptions(
    /**
     * Conversation identifier. On Hermes this is the server-side session id, so
     * passing a stable value is what gives a conversation memory across turns.
     */
    val sessionId: String? = null,
    val stream: Boolean = true,
    val modelOverride: String? = null,
    /**
     * Provider slug. Hermes ignores a bare `model` on its OpenAI-compatible
     * endpoints unless the operator opted in, but always honours `model` when
     * it arrives with a `provider`.
     */
    val providerOverride: String? = null,
    /** Extra provider knobs, e.g. `reasoning_effort`, `service_tier`. */
    val modelOptions: Map<String, String> = emptyMap(),
    /** Extra system instructions layered on top of the agent's own prompt. */
    val instructions: String? = null,
    /** Long-term memory scope, sent as the server's session-key header. */
    val memoryScopeKey: String? = null,
    val approvalPolicy: ApprovalPolicy = ApprovalPolicy.DENY,
    val stopOnDispose: Boolean = true,
    val extra: Map<String, String> = emptyMap(),
)

sealed class AgentEvent {
    data class Started(val runId: String? = null) : AgentEvent()
    data class TokenDelta(val text: String) : AgentEvent()
    data class MessageDelta(val role: String, val text: String) : AgentEvent()
    data class ToolProgress(val tool: String, val stage: String, val detail: String? = null) : AgentEvent()

    /** Model reasoning the server chose to expose. Not part of the answer text. */
    data class Reasoning(val text: String) : AgentEvent()

    /**
     * The agent wants permission to run a gated tool and is blocked until the
     * client calls [AgentClient.respondToApproval] with one of [choices].
     */
    data class ApprovalRequest(
        val runId: String,
        val requestId: String? = null,
        val tool: String? = null,
        val command: String? = null,
        val description: String? = null,
        val choices: List<String> = listOf("once", "deny"),
    ) : AgentEvent()

    data class Completed(val finalText: String, val runId: String? = null) : AgentEvent()
    data class Error(val message: String, val cause: Throwable? = null) : AgentEvent()
}
