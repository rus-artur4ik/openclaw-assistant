package com.openclaw.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.assistant.data.local.entity.SessionEntity
import com.openclaw.assistant.data.repository.ChatRepository
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.backend.BackendRepository
import com.openclaw.assistant.backend.BackendType
import com.openclaw.assistant.backend.SessionProduct
import com.openclaw.assistant.backend.SessionProductResolver
import com.openclaw.assistant.gateway.AgentInfo
import com.openclaw.assistant.gateway.AgentListResult
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class SessionUiModel(
    val id: String,
    val title: String,
    val createdAt: Long,
    val isGateway: Boolean,
    val product: ChatProduct,
)

enum class ChatProduct { OPENCLAW, HERMES }

class SessionListViewModel(application: Application) : AndroidViewModel(application) {

    private val chatRepository = ChatRepository.getInstance(application)
    private val settingsRepository = SettingsRepository.getInstance(application)
    private val backendRepository = BackendRepository.getInstance(application)
    private val prefs = application.getSharedPreferences("chat_session_products", android.content.Context.MODE_PRIVATE)
    private val nodeRuntime = (application as OpenClawApplication).nodeRuntime

    val isGatewayConfigured: Boolean
        get() = nodeRuntime.manualEnabled.value && nodeRuntime.manualHost.value.isNotBlank()
                
    val isHttpConfigured: Boolean
        get() = settingsRepository.isConfigured()

    val agentList: StateFlow<AgentListResult?> = nodeRuntime.agentList

    val allSessions: StateFlow<List<SessionUiModel>> = combine(
        nodeRuntime.chatSessions,
        chatRepository.allSessionsWithLatestTime,
        backendRepository.backends,
    ) { nodeEntries, localSessions, backends ->
        val gatewayModels = nodeEntries.map { entry ->
            SessionUiModel(
                id = entry.key,
                title = entry.displayName ?: "New Session",
                createdAt = entry.updatedAtMs ?: System.currentTimeMillis(),
                isGateway = true,
                product = ChatProduct.OPENCLAW,
            )
        }
        val httpModels = localSessions.map { session ->
            SessionUiModel(
                id = session.id,
                title = session.title,
                createdAt = session.latestMessageTime ?: session.createdAt,
                isGateway = false,
                product = badgeFor(session.id, backends),
            )
        }
        
        (gatewayModels + httpModels).sortedByDescending { it.createdAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refreshSessions()
    }

    fun refreshSessions() {
        if (settingsRepository.useNodeChat) {
            nodeRuntime.refreshChatSessions(limit = 100)
        }
    }

    fun createSession(name: String, isGateway: Boolean, agentId: String? = null, targetBackendId: String? = null, onCreated: (String, Boolean) -> Unit) {
        if (isGateway) {
            val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
            val id = if (!agentId.isNullOrBlank()) "agent:$agentId:chat-$ts" else "chat-$ts"
            viewModelScope.launch {
                nodeRuntime.patchChatSession(id, name.trim())
                onCreated(id, true)
            }
        } else {
            viewModelScope.launch {
                val id = chatRepository.createSession(name.trim())
                saveProduct(id, productForBackend(targetBackendId))
                saveBackendId(id, targetBackendId)
                onCreated(id, false)
            }
        }
    }

    fun setUseNodeChat(useNodeChat: Boolean) {
        settingsRepository.useNodeChat = useNodeChat
    }

    fun renameSession(sessionId: String, newName: String, isGateway: Boolean) {
        if (isGateway) {
            viewModelScope.launch {
                nodeRuntime.patchChatSession(sessionId, newName.trim())
                nodeRuntime.refreshChatSessions()
            }
        } else {
            viewModelScope.launch {
                chatRepository.renameSession(sessionId, newName.trim())
            }
        }
    }

    fun deleteSession(sessionId: String, isGateway: Boolean) {
        if (isGateway) {
            viewModelScope.launch {
                nodeRuntime.deleteChatSession(sessionId)
                nodeRuntime.refreshChatSessions()
            }
        } else {
            viewModelScope.launch {
                chatRepository.deleteSession(sessionId)
                prefs.edit().remove(sessionId).remove(backendKey(sessionId)).apply()
            }
        }
    }

    private fun productForBackend(backendId: String?): ChatProduct =
        SessionProductResolver.productForBackend(backendRepository.backends.value, backendId).toChatProduct()

    /**
     * Product badge for a local conversation. The legacy OpenClaw HTTP settings
     * count as an OpenClaw backend here, because a thread created before
     * backends existed most likely belongs to them.
     */
    private fun badgeFor(sessionId: String, backends: List<com.openclaw.assistant.backend.AgentBackendConfig>): ChatProduct {
        val synthetic = if (isHttpConfigured && backends.none { it.enabled && it.type == BackendType.OPENCLAW_HTTP }) {
            backends + com.openclaw.assistant.backend.AgentBackendConfig(
                id = "legacy-http-settings",
                displayName = "legacy",
                type = BackendType.OPENCLAW_HTTP,
            )
        } else {
            backends
        }
        return SessionProductResolver.badgeFor(
            backends = synthetic,
            storedProduct = loadProduct(sessionId)?.toSessionProduct(),
            storedBackendId = prefs.getString(backendKey(sessionId), null),
            hasStoredBackendKey = prefs.contains(backendKey(sessionId)),
        ).toChatProduct()
    }

    private fun saveProduct(sessionId: String, product: ChatProduct) {
        prefs.edit().putString(sessionId, product.name).apply()
    }

    /**
     * Remembers which backend a conversation belongs to.
     *
     * The product tag alone is not enough: with two Hermes backends configured
     * it cannot say which one, and reopening a thread would send the next
     * message wherever the selector happened to be pointing.
     */
    private fun saveBackendId(sessionId: String, backendId: String?) {
        prefs.edit().apply {
            if (backendId == null) remove(backendKey(sessionId)) else putString(backendKey(sessionId), backendId)
        }.apply()
    }

    /** The backend a conversation was created against, if it still exists. */
    fun backendIdFor(sessionId: String): String? = SessionProductResolver.storedBackendId(
        backends = backendRepository.backends.value,
        storedId = prefs.getString(backendKey(sessionId), null),
    )

    private fun backendKey(sessionId: String) = "$sessionId::backend"

    /**
     * The chat target to select when a conversation is reopened.
     *
     * A gateway conversation resolves to the gateway backend's own id rather
     * than null: null means "follow the Primary backend", which on an install
     * whose Primary is Hermes would open a gateway thread pointed at Hermes.
     */
    fun chatTargetFor(sessionId: String, isGateway: Boolean): String? = SessionProductResolver.chatTargetFor(
        backends = backendRepository.backends.value,
        isGateway = isGateway,
        storedBackendId = prefs.getString(backendKey(sessionId), null),
    )

    private fun loadProduct(sessionId: String): ChatProduct? =
        prefs.getString(sessionId, null)?.let { runCatching { ChatProduct.valueOf(it) }.getOrNull() }
}

private fun SessionProduct.toChatProduct(): ChatProduct =
    if (this == SessionProduct.HERMES) ChatProduct.HERMES else ChatProduct.OPENCLAW

private fun ChatProduct.toSessionProduct(): SessionProduct =
    if (this == ChatProduct.HERMES) SessionProduct.HERMES else SessionProduct.OPENCLAW
