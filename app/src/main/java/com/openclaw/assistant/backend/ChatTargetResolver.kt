package com.openclaw.assistant.backend

/**
 * Which backend a Chat message goes to, and what that implies.
 *
 * Chat has two very different code paths — the OpenClaw Gateway runtime, which
 * owns the transcript and streams through [com.openclaw.assistant.node.NodeRuntime],
 * and every other backend, which goes through [PrimaryBackendDispatcher]. The
 * decision used to be re-derived inline at three call sites with subtly
 * different rules, so a stale selection could route the turn down one path and
 * then look the target up again and find nothing.
 */
object ChatTargetResolver {

    /**
     * The backend a message would be sent to.
     *
     * An explicit selection wins, but only while it still names an enabled
     * backend: a backend that was deleted or disabled with Chat open falls back
     * to Primary rather than resolving to nothing.
     */
    fun resolveTarget(
        backends: List<AgentBackendConfig>,
        selectedId: String?,
    ): AgentBackendConfig? =
        selectedId?.let { id -> backends.firstOrNull { it.id == id && it.enabled } }
            ?: backends.firstOrNull { it.isPrimary && it.enabled }

    /**
     * True when the turn belongs to the gateway runtime rather than to
     * [PrimaryBackendDispatcher].
     *
     * With no backends configured at all the legacy setting decides, because
     * that is an install that never went through backend onboarding.
     */
    fun isGatewayTarget(
        useNodeChat: Boolean,
        backends: List<AgentBackendConfig>,
        selectedId: String?,
    ): Boolean {
        if (!useNodeChat) return false
        if (backends.isEmpty()) return true
        return resolveTarget(backends, selectedId)?.type == BackendType.OPENCLAW_GATEWAY
    }

    /**
     * Model override to send with an OpenClaw request, or null to let the host
     * decide. Hermes takes its model through its own request builder, which also
     * has to pair it with a provider, so it is deliberately not answered here.
     */
    fun openClawModelOverride(
        backends: List<AgentBackendConfig>,
        selectedId: String?,
    ): String? = resolveTarget(backends, selectedId)
        ?.takeIf { it.type == BackendType.OPENCLAW_GATEWAY || it.type == BackendType.OPENCLAW_HTTP }
        ?.modelName
        ?.takeIf { it.isNotBlank() }

    /**
     * Trims a transcript to the last [cap] non-empty turns, oldest first.
     *
     * Backends without a server-side transcript are re-sent the conversation on
     * every turn, so this is what stops a long thread from growing the request
     * without bound.
     */
    fun trimHistory(
        messages: List<Pair<Boolean, String>>,
        cap: Int,
    ): List<AgentMessage> = messages
        .filter { (_, text) -> text.isNotBlank() }
        .takeLast(cap)
        .map { (isUser, text) -> if (isUser) AgentMessage.user(text) else AgentMessage.assistant(text) }
}
