package com.openclaw.assistant.backend

/** Which product a locally-stored conversation belongs to. */
enum class SessionProduct { OPENCLAW, HERMES }

/**
 * Decides the product badge and the reopen target for a conversation.
 *
 * The session list mixes gateway threads, which live on the host, with local
 * ones that may belong to either product. Getting this wrong sends the next
 * message in a thread to the other backend, so the rules are kept here where
 * they can be tested against both branches.
 */
object SessionProductResolver {

    fun productOf(type: BackendType?): SessionProduct = when (type) {
        BackendType.HERMES_API_SERVER -> SessionProduct.HERMES
        else -> SessionProduct.OPENCLAW
    }

    /**
     * Product of the backend a conversation was created against, falling back to
     * the current Primary when that backend is gone.
     */
    fun productForBackend(
        backends: List<AgentBackendConfig>,
        backendId: String?,
    ): SessionProduct {
        val backend = backendId?.let { id -> backends.firstOrNull { it.id == id } }
            ?: backends.firstOrNull { it.enabled && it.isPrimary }
        return productOf(backend?.type)
    }

    /**
     * The backend a conversation was created against, or null if that backend no
     * longer exists or has been disabled.
     */
    fun storedBackendId(
        backends: List<AgentBackendConfig>,
        storedId: String?,
    ): String? = storedId?.takeIf { id -> backends.any { it.id == id && it.enabled } }

    /**
     * The product badge for a local conversation.
     *
     * @param storedProduct the tag written when the conversation was created.
     * @param storedBackendId the raw stored backend id, present even when that
     *   backend has since been deleted — its presence is what distinguishes
     *   "created after per-session backends existed" from an older thread.
     */
    fun badgeFor(
        backends: List<AgentBackendConfig>,
        storedProduct: SessionProduct?,
        storedBackendId: String?,
        hasStoredBackendKey: Boolean,
    ): SessionProduct {
        storedProduct?.let { return it }
        if (hasStoredBackendKey) {
            return productForBackend(backends, storedBackendId(backends, storedBackendId))
        }
        // An untagged thread predates per-session backends. Only call it Hermes
        // when there is no OpenClaw backend it could have belonged to.
        val hasHermes = backends.any { it.enabled && it.type == BackendType.HERMES_API_SERVER }
        val hasOpenClaw = backends.any { it.enabled && it.type == BackendType.OPENCLAW_HTTP }
        return if (hasHermes && !hasOpenClaw) SessionProduct.HERMES else SessionProduct.OPENCLAW
    }

    /**
     * Chat target to select when a conversation is reopened.
     *
     * A gateway conversation resolves to the gateway backend's own id rather
     * than null: null means "follow the Primary backend", which on an install
     * whose Primary is Hermes would open a gateway thread pointed at Hermes.
     */
    fun chatTargetFor(
        backends: List<AgentBackendConfig>,
        isGateway: Boolean,
        storedBackendId: String?,
    ): String? = if (isGateway) {
        backends.firstOrNull { it.enabled && it.type == BackendType.OPENCLAW_GATEWAY }?.id
    } else {
        storedBackendId(backends, storedBackendId)
    }
}
