package com.openclaw.assistant.backend

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Which Hermes endpoint family carries a conversation.
 *
 * [AUTO] is the right answer for almost everyone: the client asks the server
 * what it supports via `/v1/capabilities` and picks the richest transport.
 */
@Serializable
enum class HermesTransportPreference {
    /** Feature-detect and use the best transport the server offers. */
    AUTO,

    /** `/api/sessions/{id}/chat/stream` — Hermes keeps the transcript. */
    SESSION_CHAT,

    /** `/v1/runs` — the only transport that can answer tool approvals. */
    RUNS,

    /** `/v1/chat/completions` — stateless, maximum compatibility. */
    CHAT_COMPLETIONS,
}

@Serializable
data class AgentBackendConfig(
    val id: String = UUID.randomUUID().toString(),
    val displayName: String,
    val type: BackendType,
    val enabled: Boolean = true,
    val isPrimary: Boolean = false,
    val baseUrl: String? = null,
    val apiKeyOrToken: String? = null,
    val host: String? = null,
    val port: Int? = null,
    val useTls: Boolean = false,
    val modelName: String? = null,
    /**
     * Provider slug that pairs with [modelName]. Hermes ignores a bare model on
     * its OpenAI-compatible endpoints unless the operator opted in, but always
     * honours a model that arrives with a provider.
     */
    val providerName: String? = null,
    /**
     * Legacy flag kept so existing installs deserialize. [transport] supersedes
     * it; [effectiveTransport] applies the migration.
     */
    val useRunsApi: Boolean = true,
    val transport: HermesTransportPreference? = null,
    val useStreaming: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /**
     * Additional endpoints to race alongside [baseUrl] at connect time.
     * Additional routes such as LAN, VPN, and public URLs. The client tries all
     * candidates in parallel on connect and uses the first reachable route.
     */
    val secondaryUrls: List<String> = emptyList(),
    /**
     * Hermes Dashboard endpoint used by the Terminal tab. Hermes exposes the
     * PTY bridge at `/api/pty` on `hermes dashboard --tui`, not on the normal
     * API server port.
     */
    val terminalUrl: String? = null,
    val terminalSessionToken: String? = null,
    /** Optional cross-backend agent/profile label selected by the user. */
    val agentContextName: String? = null,
    /** Optional model/personality/profile hint shown in shared Agent Context UI. */
    val agentContextDetail: String? = null,
    /** Optional preferred endpoint role, such as lan, vpn, or public. */
    val preferredEndpointRole: String? = null,
    /**
     * Stable long-term-memory scope sent as the server's session-key header, so
     * Hermes can accumulate memory about this user across conversations.
     */
    val memoryScopeKey: String? = null,
) {
    /**
     * Transport to use, migrating installs that only ever set [useRunsApi].
     * Those users chose between two options that both predate session chat, so
     * they are moved to [HermesTransportPreference.AUTO] only when they left the
     * Runs default in place.
     */
    val effectiveTransport: HermesTransportPreference
        get() = transport
            ?: if (useRunsApi) HermesTransportPreference.AUTO else HermesTransportPreference.CHAT_COMPLETIONS
}
