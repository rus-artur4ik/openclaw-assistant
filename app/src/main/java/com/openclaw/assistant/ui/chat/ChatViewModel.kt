package com.openclaw.assistant.ui.chat

import android.app.Application
import android.content.Context
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.assistant.OpenClawApplication
import com.openclaw.assistant.R
import com.openclaw.assistant.api.OpenClawClient
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.chat.ChatMarkdownPreprocessor
import com.openclaw.assistant.gateway.AgentInfo
import com.openclaw.assistant.speech.SpeechRecognizerManager
import com.openclaw.assistant.speech.SpeechResult
import com.openclaw.assistant.speech.TTSManager
import com.openclaw.assistant.speech.TTSState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID

private const val TAG = "ChatViewModel"
private const val INITIAL_FILLER_DELAY_MS = 750L
private const val FIRST_WAIT_PHRASE_DELAY_MS = 5000L
private const val REPEAT_WAIT_PHRASE_DELAY_MS = 9000L
private const val INTERRUPT_LISTEN_DELAY_MS = 350L

data class PendingFileAttachment(
    val id: String,
    val fileName: String,
    val mimeType: String,
    val base64: String,
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
    val attachments: List<com.openclaw.assistant.chat.ChatMessageContent> = emptyList()
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isListening: Boolean = false,
    val isThinking: Boolean = false,
    val isSpeaking: Boolean = false,
    val isPreparingSpeech: Boolean = false,
    val error: String? = null,
    val partialText: String = "", // For real-time speech transcription
    val availableAgents: List<AgentInfo> = emptyList(),
    val selectedAgentId: String? = null, // null = use default from settings
    val defaultAgentId: String = "main", // From settings, for display when agent list unavailable
    val isPairingRequired: Boolean = false,
    val deviceId: String? = null,
    val pendingToolCalls: List<String> = emptyList(),
    val isNodeChatMode: Boolean = false,
    val pendingGatewayTrust: com.openclaw.assistant.node.NodeRuntime.GatewayTrustPrompt? = null,
    val displayName: String = "",
    val attachments: List<PendingFileAttachment> = emptyList(),
    /** Partial assistant text while a non-gateway backend streams its answer. */
    val streamingAssistantText: String? = null,
    /** Set while a backend run is cancellable, so the UI can offer Stop. */
    val activeRunId: String? = null,
    /** A tool the agent wants permission to run; blocks until answered. */
    val pendingApproval: com.openclaw.assistant.backend.AgentEvent.ApprovalRequest? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val settings = SettingsRepository.getInstance(application)
    private val chatRepository = com.openclaw.assistant.data.repository.ChatRepository.getInstance(application)
    private val apiClient = OpenClawClient()
    private val nodeRuntime = (application as OpenClawApplication).nodeRuntime
    private val speechManager = SpeechRecognizerManager(application)
    private val toneGenerator = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 100)
    private val useNodeChat: Boolean
        get() = settings.useNodeChat

    private var thinkingSoundJob: Job? = null
    private var initialFillerPhraseJob: Job? = null
    private var auxiliarySpeechJob: Job? = null
    @Volatile private var ignoreNextTtsStop = false
    private val interruptReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: android.content.Intent?) {
            if (intent?.action != "com.openclaw.assistant.ACTION_INTERRUPT_TTS") return
            if (!_uiState.value.isSpeaking && !_uiState.value.isPreparingSpeech) return
            Log.d(TAG, "Barge-in interrupt received in ChatViewModel")
            interruptAndListen()
        }
    }

    // WakeLock to keep CPU alive during voice interaction with screen off
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    
    // Session Management
    private val _allSessions = MutableStateFlow<List<com.openclaw.assistant.data.local.entity.SessionEntity>>(emptyList())
    val allSessions: StateFlow<List<com.openclaw.assistant.data.local.entity.SessionEntity>> = _allSessions.asStateFlow()
    
    // Current Session
    private val _currentSessionId = MutableStateFlow<String?>(null)
    val currentSessionId: StateFlow<String?> = _currentSessionId.asStateFlow()

    // Initial title passed via Intent (used before allSessions is loaded)
    private val _initialSessionTitle = MutableStateFlow<String?>(null)
    val initialSessionTitle: StateFlow<String?> = _initialSessionTitle.asStateFlow()

    // Whether selectSessionOnStart() was called (session set via Intent before init completes)
    private var sessionSelectedViaIntent = false

    // Set when user sends a message in nodeChat mode; cleared after TTS is triggered.
    // Avoids race condition between pendingRunCount→0 and chatMessages emitting.
    private var pendingNodeChatTts = false
    
    // Sync current session with Settings if needed, or just let UI drive it?
    // Let's load the last one if available, or create new.
    
    // Messages Flow - mapped from current Session ID
    private val _messagesFlow = _currentSessionId.flatMapLatest { sessionId ->
         if (sessionId != null) {
             chatRepository.getMessages(sessionId).map { entities ->
                 entities.map { entity ->
                     ChatMessage(
                         id = entity.id,
                         text = entity.content,
                         isUser = entity.isUser,
                         timestamp = entity.timestamp
                     )
                 }
             }
         } else {
             flowOf(emptyList())
         }
    }
    
    // We combine local/remote message streams into uiState
    init {
        val app = getApplication<Application>()
        androidx.core.content.ContextCompat.registerReceiver(
            app,
            interruptReceiver,
            android.content.IntentFilter("com.openclaw.assistant.ACTION_INTERRUPT_TTS"),
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        _uiState.update { it.copy(isNodeChatMode = shouldUseNodeChatForCurrentTarget()) }
        viewModelScope.launch {
            combine(
                com.openclaw.assistant.backend.BackendRepository.getInstance(app).backends,
                com.openclaw.assistant.ui.backend.ChatBackendTarget.selectedId,
            ) { _, _ -> shouldUseNodeChatForCurrentTarget() }
                .distinctUntilChanged()
                .collect { isNodeTarget ->
                    _uiState.update { it.copy(isNodeChatMode = isNodeTarget) }
                }
        }
        if (useNodeChat) {
            // Remote sessions/messages via NodeRuntime
            viewModelScope.launch {
                nodeRuntime.chatSessions.collect { sessions ->
                    val mapped = sessions.map { session ->
                        com.openclaw.assistant.data.local.entity.SessionEntity(
                            id = session.key,
                            title = session.displayName ?: session.key,
                            createdAt = session.updatedAtMs ?: System.currentTimeMillis()
                        )
                    }
                    _allSessions.value = mapped
                }
            }
            viewModelScope.launch {
                nodeRuntime.chatSessionKey.collect { key ->
                    if (!shouldUseNodeChatForCurrentTarget()) return@collect
                    _currentSessionId.value = key
                    // Extract agentId from session key format: "agent:<agentId>:<sessionName>"
                    val agentId = if (key.startsWith("agent:")) {
                        key.removePrefix("agent:").substringBefore(":")
                    } else null
                    _uiState.update { it.copy(selectedAgentId = agentId) }
                }
            }
            viewModelScope.launch {
                var previousCount = 0
                nodeRuntime.chatMessages.collect { messages ->
                    // The gateway replaces the whole list on every emission. When
                    // chat is pointed at another backend its transcript lives in
                    // the local database, and letting this run would wipe it.
                    if (!shouldUseNodeChatForCurrentTarget()) return@collect
                    val uiMessages = messages.map { it.toUiChatMessage() }
                    _uiState.update { state ->
                        state.copy(messages = uiMessages)
                    }
                    // Trigger TTS when a new assistant message arrives after user sent a message
                    if (uiMessages.size > previousCount && pendingNodeChatTts) {
                        val lastMessage = uiMessages.lastOrNull()
                        if (lastMessage != null && !lastMessage.isUser) {
                            pendingNodeChatTts = false
                            stopThinkingSound()
                            _uiState.update { it.copy(isThinking = false) }
                            afterResponseReceived(lastMessage.text)
                        }
                    }
                    previousCount = uiMessages.size
                }
            }
            // Use pendingRunCount as the authoritative source for isThinking,
            // but only while we are still waiting for a response (pendingNodeChatTts=true).
            // Once the response message arrives (pendingNodeChatTts=false), do not
            // re-set isThinking=true even if runId cleanup is delayed.
            // NOTE: pendingNodeChatTts is intentionally NOT cleared here to avoid a race where
            // count drops to 0 before the async chat.history fetch completes, which would
            // prevent TTS from firing in chatMessages.collect.
            viewModelScope.launch {
                nodeRuntime.pendingRunCount.collect { count ->
                    if (!shouldUseNodeChatForCurrentTarget()) return@collect
                    if (count == 0) {
                        // Run finished: clear thinking state only.
                        // pendingNodeChatTts is managed by chatMessages.collect.
                        stopThinkingSound()
                        _uiState.update { it.copy(isThinking = false) }
                    } else if (pendingNodeChatTts) {
                        // Still waiting for response: set thinking
                        _uiState.update { it.copy(isThinking = true) }
                    }
                    // if count > 0 but pendingNodeChatTts=false: response already received,
                    // do NOT flip isThinking back to true
                }
            }
            viewModelScope.launch {
                nodeRuntime.chatError.collect { error ->
                    // A gateway error says nothing about a turn running on
                    // another backend, and clearing isThinking would abandon it.
                    if (!shouldUseNodeChatForCurrentTarget()) return@collect
                    if (!error.isNullOrBlank()) {
                        stopThinkingSound()
                    }
                    _uiState.update { it.copy(error = error, isThinking = false) }
                }
            }
            viewModelScope.launch {
                nodeRuntime.chatPendingToolCalls.collect { calls ->
                    _uiState.update { state ->
                        state.copy(
                            pendingToolCalls = calls.map { call ->
                                val args = call.args?.toString()?.take(80)?.let { " $it" } ?: ""
                                "${call.name}$args"
                            }
                        )
                    }
                }
            }
            viewModelScope.launch {
                nodeRuntime.pendingGatewayTrust.collect { prompt ->
                    _uiState.update { it.copy(pendingGatewayTrust = prompt) }
                }
            }
            viewModelScope.launch {
                nodeRuntime.displayName.collect { name ->
                    _uiState.update { it.copy(displayName = name) }
                }
            }
            // Backends other than the gateway keep their transcript in the local
            // database; mirror it into the UI whenever one of them is selected.
            viewModelScope.launch {
                _messagesFlow.collect { messages ->
                    if (shouldUseNodeChatForCurrentTarget()) return@collect
                    _uiState.update { it.copy(messages = messages) }
                }
            }
            viewModelScope.launch {
                com.openclaw.assistant.ui.backend.ChatBackendTarget.selectedId.collect {
                    if (shouldUseNodeChatForCurrentTarget()) {
                        // The gateway flows are StateFlows that will not re-emit
                        // just because the target changed, so restore from their
                        // current values or the screen keeps the other backend's
                        // transcript.
                        _currentSessionId.value = nodeRuntime.chatSessionKey.value
                        _uiState.update { state ->
                            state.copy(messages = nodeRuntime.chatMessages.value.map { it.toUiChatMessage() })
                        }
                    } else {
                        ensureLocalSession()
                    }
                }
            }
            viewModelScope.launch {
                // If selectSessionOnStart was already called (from Intent), skip loadChat
                // to avoid a second bootstrap() that would clear in-flight pendingRuns.
                if (!sessionSelectedViaIntent) {
                    val key = nodeRuntime.chatSessionKey.value
                    nodeRuntime.loadChat(key)
                }
                nodeRuntime.refreshChatSessions()
            }
        } else {
            // Local DB sessions/messages (existing behavior)
            viewModelScope.launch {
                chatRepository.allSessions.collect { sessions ->
                    _allSessions.value = sessions
                }
            }
            viewModelScope.launch {
                _messagesFlow.collect { messages ->
                    _uiState.update { it.copy(messages = messages) }
                }
            }
            // Initial session setup (skip if already set via Intent)
            viewModelScope.launch {
                if (_currentSessionId.value != null) return@launch
                val latest = chatRepository.getLatestSession()
                if (latest != null) {
                    _currentSessionId.value = latest.id
                    settings.sessionId = latest.id
                } else {
                    createNewSession()
                }
            }
        }

        // Shared observation
        viewModelScope.launch {
            if (useNodeChat) {
                // Skip the initial disconnected state to avoid showing error on startup
                nodeRuntime.isConnected.drop(1).collect { connected ->
                    if (!shouldUseNodeChatForCurrentTarget()) return@collect
                    if (!connected) {
                        _uiState.update { it.copy(error = "Node gateway offline") }
                    } else {
                        _uiState.update { it.copy(error = null) }
                    }
                }
            }
        }

        // Observe agent list from NodeRuntime
        viewModelScope.launch {
            nodeRuntime.agentList.collect { agentListResult ->
                val apiDefaultId = agentListResult?.defaultId
                _uiState.update { state ->
                    // If user hasn't overridden the default agent, resolve it from the API's defaultId
                    val resolvedDefaultId = if (state.defaultAgentId == "main" && !apiDefaultId.isNullOrBlank()) {
                        apiDefaultId
                    } else {
                        state.defaultAgentId
                    }
                    state.copy(
                        availableAgents = agentListResult?.agents ?: emptyList(),
                        defaultAgentId = resolvedDefaultId
                    )
                }
            }
        }

        // Initialize default agent from settings (HTTP mode only; in Gateway mode,
        // the agent is resolved from the session key in chatSessionKey.collect above)
        val savedAgentId = settings.defaultAgentId
        if (savedAgentId.isNotBlank() && savedAgentId != "main") {
            if (useNodeChat) {
                _uiState.update { it.copy(defaultAgentId = savedAgentId) }
            } else {
                _uiState.update { it.copy(defaultAgentId = savedAgentId, selectedAgentId = savedAgentId) }
            }
        }

    }

    fun createNewSession() {
        if (useNodeChat) {
            val agentId = _uiState.value.selectedAgentId
            val ts = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(java.util.Date())
            val key = if (!agentId.isNullOrBlank()) "agent:$agentId:chat-$ts" else "chat-$ts"
            Log.d("AgentDbg", "createNewSession: selectedAgentId=$agentId key=$key")
            nodeRuntime.switchChatSession(key)
            nodeRuntime.loadChat(key)
            nodeRuntime.refreshChatSessions()
            return
        }
        viewModelScope.launch {
            val simpleDateFormat = java.text.SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())
            val app = getApplication<Application>()
            val newId = chatRepository.createSession(String.format(app.getString(com.openclaw.assistant.R.string.chat_session_title_format), simpleDateFormat.format(java.util.Date())))
            _currentSessionId.value = newId
            lastLocalSessionId = newId
            settings.sessionId = newId // Sync for API use
        }
    }

    fun selectSession(sessionId: String) {
        if (useNodeChat) {
            nodeRuntime.switchChatSession(sessionId)
            nodeRuntime.loadChat(sessionId)
        } else {
            _currentSessionId.value = sessionId
            settings.sessionId = sessionId
        }
    }

    // Called from ChatActivity.onCreate when a specific session ID is provided via Intent.
    // Must be called before the init coroutine runs (i.e., synchronously after ViewModel creation).
    fun selectSessionOnStart(sessionId: String, initialTitle: String? = null) {
        if (useNodeChat) {
            sessionSelectedViaIntent = true
            _currentSessionId.value = sessionId
            if (!initialTitle.isNullOrBlank()) {
                _initialSessionTitle.value = initialTitle
            }
            nodeRuntime.switchChatSession(sessionId)
            nodeRuntime.loadChat(sessionId)
            // After bootstrap (chat.history), re-apply the session label.
            // The gateway creates new sessions with the device name as default label,
            // so we patch after the session actually exists on the gateway.
            if (!initialTitle.isNullOrBlank()) {
                val label = initialTitle
                viewModelScope.launch {
                    withTimeoutOrNull(10_000L) {
                        nodeRuntime.chatSessions.first { sessions ->
                            sessions.any { it.key == sessionId }
                        }
                    }
                    nodeRuntime.patchChatSession(sessionId, label)
                    nodeRuntime.refreshChatSessions()
                }
            }
        } else {
            _currentSessionId.value = sessionId
            settings.sessionId = sessionId
        }
    }

    fun deleteSession(sessionId: String) {
        if (useNodeChat) {
            _uiState.update {
                it.copy(error = "Gateway session deletion is not supported yet. Please keep using session switch.")
            }
            return
        }
        // Immediate UI update if deleting current session
        val isCurrent = _currentSessionId.value == sessionId
        if (isCurrent) {
            _currentSessionId.value = null
        }

        viewModelScope.launch {
            chatRepository.deleteSession(sessionId)
            if (isCurrent) {
                // Determine if we should switch to another or create new
                val nextSession = chatRepository.getLatestSession()
                if (nextSession != null) {
                    _currentSessionId.value = nextSession.id
                    settings.sessionId = nextSession.id
                } else {
                    createNewSession()
                }
            }
        }
    }

    // TTSManager will be initialized from Activity
    private var ttsManager: TTSManager? = null

    /**
     * Initialize TTSManager from Activity
     */
    fun initializeTTS() {
        Log.d(TAG, "initializeTTS called (ttsType=${settings.ttsType})")
        try {
            ttsManager = TTSManager(getApplication())
            val initialized = ttsManager?.initializeCurrentProvider() ?: false
            Log.d(TAG, "TTS initialized: $initialized, ready=${ttsManager?.isReady()}, error=${ttsManager?.getErrorMessage()}")
        } catch (e: Exception) {
            Log.e(TAG, "initializeTTS failed", e)
            ttsManager = null
        }
    }

    /**
     * Called from ChatActivity.onResume() to refresh chat history in NodeChat mode.
     * Only refreshes when not currently thinking (i.e., no in-flight request).
     */
    fun refreshChatIfNeeded() {
        if (!useNodeChat) return
        if (_uiState.value.isThinking) return
        nodeRuntime.refreshChat()
    }

    fun acceptGatewayTrust() {
        nodeRuntime.acceptGatewayTrustPrompt()
    }

    fun declineGatewayTrust() {
        nodeRuntime.declineGatewayTrustPrompt()
    }



    fun setAgent(agentId: String?) {
        Log.d("AgentDbg", "setAgent: agentId=$agentId useNodeChat=$useNodeChat")
        _uiState.update { it.copy(selectedAgentId = agentId) }
        if (agentId.isNullOrBlank()) return
        if (useNodeChat) {
            // Gateway mode: agent is fixed per session key, do not switch sessions.
            // Agent selection is only available at session creation time.
            return
        }
        // HTTP mode: agentId is sent via x-openclaw-agent-id header in sendViaHttp
    }

    fun addAttachments(newAttachments: List<PendingFileAttachment>) {
        _uiState.update { it.copy(attachments = it.attachments + newAttachments) }
    }

    fun removeAttachment(id: String) {
        _uiState.update { it.copy(attachments = it.attachments.filterNot { att -> att.id == id }) }
    }

    private fun getEffectiveAgentId(): String? {
        val selected = _uiState.value.selectedAgentId
        if (selected != null) return selected
        val default = settings.defaultAgentId
        return if (default.isNotBlank() && default != "main") default else null
    }

    private fun shouldUseNodeChatForCurrentTarget(): Boolean =
        com.openclaw.assistant.backend.ChatTargetResolver.isGatewayTarget(
            useNodeChat = useNodeChat,
            backends = com.openclaw.assistant.backend.BackendRepository
                .getInstance(getApplication<Application>()).backends.value,
            selectedId = com.openclaw.assistant.ui.backend.ChatBackendTarget.selectedId.value,
        )

    /**
     * The backend this Chat turn is addressed to, resolved the same way for the
     * routing decision, the model override and the dispatcher call.
     */
    private fun resolvedChatTarget(): com.openclaw.assistant.backend.AgentBackendConfig? =
        com.openclaw.assistant.backend.ChatTargetResolver.resolveTarget(
            backends = com.openclaw.assistant.backend.BackendRepository
                .getInstance(getApplication<Application>()).backends.value,
            selectedId = com.openclaw.assistant.ui.backend.ChatBackendTarget.selectedId.value,
        )

    fun sendMessage(text: String) {
        if (text.isBlank() && _uiState.value.attachments.isEmpty()) return

        if (shouldUseNodeChatForCurrentTarget()) {
            // Check gateway health before sending; if not ready, show a clear error
            // instead of letting the message silently fail inside ChatController.
            if (!nodeRuntime.chatHealthOk.value) {
                Log.w(TAG, "sendMessage: chatHealthOk is false. useNodeChat=true")
                val app = getApplication<Application>()
                val errorMsg = app.getString(com.openclaw.assistant.R.string.error_gateway_not_connected)
                _uiState.update { it.copy(error = errorMsg) }
                return
            }
            val attachmentsToProcess = _uiState.value.attachments
            _uiState.update { it.copy(error = null, attachments = emptyList(), isThinking = true) }
            pendingNodeChatTts = true
            if (lastInputWasVoice) {
                toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 150)
            }
            startThinkingSound()
            scheduleInitialFillerPhrase()
            startWaitPhraseTimer()

            viewModelScope.launch {
                try {
                    val outgoing = attachmentsToProcess.map { att ->
                        val attachType = if (att.mimeType.startsWith("image/")) "image" else "image" // Gateway only supports image attachments
                        com.openclaw.assistant.chat.OutgoingAttachment(
                            type = attachType,
                            mimeType = att.mimeType,
                            fileName = att.fileName,
                            base64 = att.base64
                        )
                    }
                    nodeRuntime.sendChat(
                        message = text,
                        thinking = "low",
                        attachments = outgoing,
                        modelName = resolveSelectedOpenClawModel(),
                    )
                } catch (e: Exception) {
                    pendingNodeChatTts = false
                    cancelInitialFillerPhrase()
                    cancelWaitPhraseTimer()
                    stopThinkingSound()
                    _uiState.update { it.copy(isThinking = false, error = e.message) }
                }
            }
            return
        }

        // Ensure we have a session
        val sessionId = _currentSessionId.value ?: settings.sessionId.ifBlank { "agentvoice-chat" }

        val httpAttachments = _uiState.value.attachments
        _uiState.update { it.copy(isThinking = true, attachments = emptyList()) }
        if (lastInputWasVoice) {
            toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_ACK, 150)
        }
        startThinkingSound()
        scheduleInitialFillerPhrase()
        startWaitPhraseTimer()

        viewModelScope.launch {
            try {
                if (useNodeChat) {
                    sendViaSelectedBackendInline(sessionId, text, httpAttachments)
                    return@launch
                }
                // Snapshot the transcript before this turn joins it, so the
                // backend sees each message exactly once.
                val history = conversationHistory()
                // Save User Message
                chatRepository.addMessage(sessionId, text, isUser = true)
                sendViaHttp(sessionId, text, httpAttachments, history)
            } catch (e: Exception) {
                cancelInitialFillerPhrase()
                cancelWaitPhraseTimer()
                stopThinkingSound()
                _uiState.update { it.copy(isThinking = false, error = e.message) }
            }
        }
    }

    private suspend fun sendViaSelectedBackendInline(
        sessionId: String,
        text: String,
        attachments: List<PendingFileAttachment>,
    ) {
        // History is taken before the new turn is persisted, so the backend gets
        // the conversation so far plus this message exactly once.
        val history = conversationHistory()

        chatRepository.addMessage(sessionId, text, isUser = true)
        _uiState.update { state ->
            state.copy(
                messages = state.messages + ChatMessage(text = text, isUser = true),
                error = null,
                streamingAssistantText = "",
            )
        }

        val effectiveAgentId = getEffectiveAgentId()
        val backendText = try {
            trySendViaSelectedBackend(
                sessionId = sessionId,
                text = text,
                agentId = effectiveAgentId,
                history = history,
                attachments = attachments.map {
                    com.openclaw.assistant.backend.AgentAttachment(it.mimeType, it.base64)
                },
            ) ?: throw IllegalStateException("No backend selected")
        } catch (e: Throwable) {
            // A user-initiated stop is not a failure: keep whatever streamed in
            // rather than discarding it behind an error banner.
            val partial = _uiState.value.streamingAssistantText?.takeIf { it.isNotBlank() }
            if (stopRequested && partial != null) {
                stopRequested = false
                chatRepository.addMessage(sessionId, partial, isUser = false)
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + ChatMessage(text = partial, isUser = false),
                        isThinking = false,
                        streamingAssistantText = null,
                        activeRunId = null,
                        pendingApproval = null,
                    )
                }
                stopThinkingSound()
                return
            }
            stopRequested = false
            clearTurnState()
            throw e
        }

        chatRepository.addMessage(sessionId, backendText, isUser = false)
        _uiState.update { state ->
            // The local-database collector re-emits the persisted pair; append here
            // too so the answer appears immediately in gateway-mode installs where
            // that collector is idle.
            val alreadyShown = state.messages.lastOrNull()?.let { !it.isUser && it.text == backendText } == true
            state.copy(
                messages = if (alreadyShown) state.messages else state.messages + ChatMessage(text = backendText, isUser = false),
                isThinking = false,
                streamingAssistantText = null,
                activeRunId = null,
                pendingApproval = null,
            )
        }
        stopThinkingSound()
        afterResponseReceived(backendText)
    }

    /**
     * Clears the state that only makes sense while a turn is in flight.
     *
     * Leaving [ChatUiState.streamingAssistantText] set renders the answer a
     * second time next to its persisted bubble, and a stale
     * [ChatUiState.activeRunId] keeps offering Stop for a run that has finished.
     */
    private fun clearTurnState(error: String? = null) {
        _uiState.update {
            it.copy(
                isThinking = false,
                streamingAssistantText = null,
                activeRunId = null,
                pendingApproval = null,
                error = error ?: it.error,
            )
        }
    }

    /** The current transcript as backend messages, oldest first. */
    private fun conversationHistory(): List<com.openclaw.assistant.backend.AgentMessage> =
        com.openclaw.assistant.backend.ChatTargetResolver.trimHistory(
            messages = _uiState.value.messages.map { it.isUser to it.text },
            cap = MAX_HISTORY_MESSAGES,
        )

    /**
     * Ensures a local-database session is selected for backends that are not the
     * gateway.
     *
     * Switching away from the gateway leaves [_currentSessionId] holding a
     * gateway session key, which is not a row in the local database — writing
     * a transcript against it would create a phantom session.
     */
    private suspend fun ensureLocalSession() {
        lastLocalSessionId?.let {
            _currentSessionId.value = it
            settings.sessionId = it
            return
        }
        val latest = chatRepository.getLatestSession()
        val id = latest?.id ?: chatRepository.createSession()
        lastLocalSessionId = id
        _currentSessionId.value = id
        settings.sessionId = id
    }

    /** Last session id known to exist in the local database, as opposed to on the gateway. */
    private var lastLocalSessionId: String? = null

    /** True between a Stop tap and the resulting stream teardown. */
    private var stopRequested = false

    /** Cancels the in-flight backend run, if the backend supports it. */
    fun stopGeneration() {
        val runId = _uiState.value.activeRunId ?: return
        stopRequested = true
        viewModelScope.launch {
            val ctx = getApplication<Application>().applicationContext
            val manager = com.openclaw.assistant.backend.BackendManager.getInstance(ctx)
            val client = resolvedChatTarget()?.let { manager.clientForId(it.id) } ?: manager.primaryClient()
            runCatching { client?.stopRun(runId) }
            // The stream teardown clears the rest and persists the partial answer.
            _uiState.update { it.copy(activeRunId = null) }
        }
    }

    /** Answers a pending tool-approval request with one of the choices it offered. */
    fun respondToApproval(choice: String) {
        val pending = _uiState.value.pendingApproval ?: return
        viewModelScope.launch {
            val ctx = getApplication<Application>().applicationContext
            val manager = com.openclaw.assistant.backend.BackendManager.getInstance(ctx)
            val client = resolvedChatTarget()?.let { manager.clientForId(it.id) } ?: manager.primaryClient()
            runCatching { client?.respondToApproval(pending.runId, choice) }
            _uiState.update { it.copy(pendingApproval = null) }
        }
    }

    private fun sendViaHttp(
        sessionId: String,
        text: String,
        attachments: List<PendingFileAttachment> = emptyList(),
        history: List<com.openclaw.assistant.backend.AgentMessage> = emptyList(),
    ) {
        val httpUrl = settings.getChatCompletionsUrl()
        val authToken = settings.authToken.takeIf { it.isNotBlank() }
        val effectiveAgentId = getEffectiveAgentId()

        chatRepository.applicationScope.launch {
            try {
                // Route through the selected backend when Chat has an explicit
                // override, otherwise through the Primary backend. If no
                // multi-backend config exists yet, fall back to the legacy
                // OpenClaw HTTP settings below.
                val backendText = trySendViaSelectedBackend(
                    sessionId = sessionId,
                    text = text,
                    agentId = effectiveAgentId,
                    history = history,
                    attachments = attachments.map {
                        com.openclaw.assistant.backend.AgentAttachment(it.mimeType, it.base64)
                    },
                )
                if (backendText != null) {
                    chatRepository.addMessage(sessionId, backendText, isUser = false)
                    viewModelScope.launch {
                        stopThinkingSound()
                        clearTurnState()
                        afterResponseReceived(backendText)
                    }
                    return@launch
                }

                val result = apiClient.sendMessage(
                    httpUrl = httpUrl,
                    message = text,
                    sessionId = sessionId,
                    authToken = authToken,
                    agentId = effectiveAgentId,
                    modelName = resolveSelectedOpenClawModel(),
                    attachments = attachments.map { Pair(it.mimeType, it.base64) }
                )

                result.fold(
                    onSuccess = { response ->
                        val responseText = response.getResponseText() ?: "No response"
                        chatRepository.addMessage(sessionId, responseText, isUser = false)

                        viewModelScope.launch {
                            stopThinkingSound()
                            clearTurnState()
                            afterResponseReceived(responseText)
                        }
                    },
                    onFailure = { error ->
                        viewModelScope.launch {
                            cancelInitialFillerPhrase()
                            stopThinkingSound()
                            clearTurnState(error = error.message)
                        }
                    }
                )
            } catch (e: Exception) {
                viewModelScope.launch {
                    cancelInitialFillerPhrase()
                    stopThinkingSound()
                    clearTurnState(error = e.message)
                }
            }
        }
    }

    private fun afterResponseReceived(responseText: String) {
        cancelInitialFillerPhrase()
        cancelWaitPhraseTimer()
        stopAuxiliarySpeech()
        if (settings.ttsEnabled) {
            speak(responseText)
        } else if (lastInputWasVoice && settings.continuousMode) {
            viewModelScope.launch {
                delay(500)
                startListening()
            }
        }
    }

    private var lastInputWasVoice = false
    private var listeningJob: kotlinx.coroutines.Job? = null

    fun attachSpeechContext(context: Context) {
        speechManager.attachForegroundContext(context)
    }

    fun detachSpeechContext(context: Context? = null) {
        speechManager.clearForegroundContext(context)
    }

    fun startListening() {
        startListeningInternal(initialDelayMs = 500L, forceRestart = false)
    }

    private fun startListeningInternal(initialDelayMs: Long, forceRestart: Boolean) {
        Log.e(TAG, "startListening() called, isListening=${_uiState.value.isListening}")
        if (_uiState.value.isListening && !forceRestart) return

        // Pause Hotword Service to prevent microphone conflict
        sendPauseBroadcast()

        // Keep CPU alive during voice interaction (screen off)
        acquireWakeLock()

        lastInputWasVoice = true // Mark as voice input
        listeningJob?.cancel()
        _uiState.update { it.copy(isListening = false, partialText = "", error = null) }

        // Stop TTS if speaking
        ttsManager?.stop()
        speechManager.destroy()

        listeningJob = viewModelScope.launch {
            val startTime = System.currentTimeMillis()
            var hasActuallySpoken = false
            
            // Wait for TTS resource release before starting mic
            delay(initialDelayMs)

            var effectiveLanguage: String? = settings.speechLanguage.ifEmpty { null }

            try {
                while (isActive && !hasActuallySpoken) {
                    Log.e(TAG, "Starting speechManager.startListening()")
                    _uiState.update { it.copy(isListening = false, partialText = "") }

                    speechManager.startListening(effectiveLanguage, settings.speechSilenceTimeout).collect { result ->
                        Log.e(TAG, "SpeechResult: $result")
                        when (result) {
                            is SpeechResult.Ready -> {
                                _uiState.update { it.copy(isListening = true, partialText = "", error = null) }
                                toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 150)
                            }
                            is SpeechResult.Listening -> {
                                _uiState.update { it.copy(isListening = true, error = null) }
                            }
                            is SpeechResult.Processing -> {
                                _uiState.update { it.copy(isListening = false) }
                                // No sound here - thinking ACK sound will play when AI starts processing
                            }
                            is SpeechResult.PartialResult -> {
                                _uiState.update { it.copy(isListening = true, partialText = result.text) }
                            }
                            is SpeechResult.Result -> {
                                hasActuallySpoken = true
                                _uiState.update { it.copy(isListening = false, partialText = "") }
                                sendMessage(result.text)
                            }
                            is SpeechResult.Error -> {
                                val elapsed = System.currentTimeMillis() - startTime
                                val isTimeout = result.code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                              result.code == SpeechRecognizer.ERROR_NO_MATCH
                                val isRetryableStartError =
                                    result.code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                                    result.code == SpeechRecognizer.ERROR_CLIENT
                                val isLanguageUnsupported =
                                    result.code == 12 /* ERROR_LANGUAGE_NOT_SUPPORTED */ ||
                                    result.code == 13 /* ERROR_LANGUAGE_UNAVAILABLE */

                                if (isLanguageUnsupported && effectiveLanguage != null) {
                                    Log.w(TAG, "Speech language '$effectiveLanguage' unsupported, falling back to system default")
                                    effectiveLanguage = null
                                    speechManager.destroy()
                                    delay(300)
                                } else if (isRetryableStartError && elapsed < 10_000L) {
                                    Log.d(TAG, "Speech recognizer not ready yet ($result), retrying...")
                                    _uiState.update { it.copy(isListening = false, partialText = "") }
                                    speechManager.destroy()
                                    delay(500)
                                } else if (isTimeout && elapsed < settings.speechSilenceTimeout) {
                                    Log.d(TAG, "Speech timeout within ${settings.speechSilenceTimeout}ms window ($elapsed ms), retrying loop...")
                                    // Just fall through to next while iteration
                                    _uiState.update { it.copy(isListening = false) }
                                } else if (isTimeout) {
                                    // Timeout - stop listening silently (no error message)
                                    toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_NACK, 100)
                                    _uiState.update { it.copy(isListening = false, error = null) }
                                    lastInputWasVoice = false
                                    hasActuallySpoken = true // Break the while loop
                                } else {
                                    // Permanent error
                                    toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_NACK, 100)
                                    _uiState.update { it.copy(isListening = false, error = result.message) }
                                    lastInputWasVoice = false
                                    hasActuallySpoken = true // Break the while loop
                                }
                            }
                            else -> {}
                        }
                    }
                    
                    if (!hasActuallySpoken) {
                        delay(300) // Small gap between retries
                    }
                }
            } finally {
                // If the loop finishes (e.g. error or spoken), and we are NOT continuing to speak/think immediately,
                // we might want to resume hotword...
                // HOWEVER: if we successfully spoke, we are now "Thinking" or "Speaking", so we shouldn't resume yet.
                // We only resume if we are truly done (e.g. stopped listening without input).
                
                // But actually, sendMessage() triggers Thinking -> Speaking -> (maybe) startListening again.
                // So we should only resume hotword if we are definitely NOT going to loop back.
                
                if (!lastInputWasVoice) {
                    releaseWakeLock()
                    sendResumeBroadcast()
                }
            }
        }
    }

    fun stopListening() {
        lastInputWasVoice = false // User manually stopped
        listeningJob?.cancel()
        _uiState.update { it.copy(isListening = false) }
        releaseWakeLock()
        sendResumeBroadcast()
    }

    private var speakingJob: kotlinx.coroutines.Job? = null
    private val speechRequests = SpeechRequestTracker()

    private fun nextSpeechRequestId(): Long = speechRequests.nextRequest()

    private fun isCurrentSpeechRequest(requestId: Long): Boolean = speechRequests.isCurrent(requestId)

    private fun ensureCurrentSpeechRequest(requestId: Long) {
        if (!isCurrentSpeechRequest(requestId)) {
            throw CancellationException("Speech request superseded")
        }
    }

    // Wait for the previous speech job to unwind before starting replacement playback.
    private suspend fun cancelSupersededSpeech(previousJob: Job?) {
        _uiState.update { it.copy(isSpeaking = false, isPreparingSpeech = false) }
        cancelAndAwaitSpeechReplacement(previousJob, stopPlayback = { ttsManager?.stop() })
    }

    private fun speak(text: String) {
        val cleanText = com.openclaw.assistant.speech.TTSUtils.stripMarkdownForSpeech(text)
        val previousJob = speakingJob
        val requestId = nextSpeechRequestId()
        speakingJob = viewModelScope.launch {
            ignoreNextTtsStop = false
            try {
                cancelSupersededSpeech(previousJob)
                ensureCurrentSpeechRequest(requestId)
                _uiState.update { it.copy(isPreparingSpeech = true, isSpeaking = false) }

                val manager = ttsManager
                val success = if (manager != null && manager.isReady()) {
                    speakWithTTSManager(manager, cleanText, requestId)
                } else {
                    if (manager == null) {
                        Log.e(TAG, "TTS: ttsManager is null")
                    } else {
                        Log.e(TAG, "TTS: not ready – type=${settings.ttsType} error=${manager.getErrorMessage()}")
                    }
                    false
                }

                ensureCurrentSpeechRequest(requestId)
                _uiState.update { it.copy(isSpeaking = false, isPreparingSpeech = false) }

                if (ignoreNextTtsStop) {
                    return@launch
                }

                // If it was a voice conversation and continuous mode is on, continue listening
                if (success && lastInputWasVoice && settings.continuousMode) {
                    // Explicit cleanup and wait for TTS to fully release audio focus
                    speechManager.destroy()
                    kotlinx.coroutines.delay(1000)

                    // Restart listening
                    ensureCurrentSpeechRequest(requestId)
                    startListening()
                } else {
                    // Conversation ended
                    releaseWakeLock()
                    sendResumeBroadcast()
                }
            } catch (e: CancellationException) {
                if (ignoreNextTtsStop) {
                    Log.d(TAG, "Ignoring controlled TTS cancellation (requestId=$requestId)")
                    return@launch
                }
                Log.d(TAG, "TTS speak cancelled (requestId=$requestId)")
            } catch (e: Exception) {
                if (ignoreNextTtsStop) {
                    return@launch
                }
                Log.e(TAG, "TTS speak error", e)
                if (!isCurrentSpeechRequest(requestId)) {
                    return@launch
                }
                _uiState.update { it.copy(isSpeaking = false, isPreparingSpeech = false) }
                ttsManager?.stop()
                releaseWakeLock()
                sendResumeBroadcast()
            } finally {
                if (isCurrentSpeechRequest(requestId)) {
                    speakingJob = null
                }
            }
        }
    }

    private suspend fun speakWithTTSManager(manager: TTSManager, text: String, requestId: Long): Boolean {
        // Query the engine's actual max input length
        val engineMaxLen = com.openclaw.assistant.speech.TTSUtils.getMaxInputLength(null)
        // Further limit to 1000 for stability and consistent timeout behavior
        val maxLen = minOf(engineMaxLen, 1000)
        val chunks = com.openclaw.assistant.speech.TTSUtils.splitTextForTTS(text, maxLen)
        Log.d(TAG, "TTS splitting text (${text.length} chars) into ${chunks.size} chunks (targetMaxLen=$maxLen, engineMaxLen=$engineMaxLen)")

        for ((index, chunk) in chunks.withIndex()) {
            ensureCurrentSpeechRequest(requestId)
            val success = speakSingleChunkWithManager(manager, chunk, requestId)
            if (!success) {
                Log.e(TAG, "TTS chunk $index failed, aborting remaining chunks")
                return false
            }
        }
        return true
    }

    private suspend fun speakSingleChunkWithManager(manager: TTSManager, text: String, requestId: Long): Boolean {
        var completed = false
        var error = false
        
        try {
            ensureCurrentSpeechRequest(requestId)
            manager.speakWithProgress(text).collect { state ->
                ensureCurrentSpeechRequest(requestId)
                when (state) {
                    is TTSState.Preparing -> {
                        Log.d(TAG, "TTS Preparing")
                    }
                    is TTSState.Speaking -> {
                        Log.d(TAG, "TTS Speaking")
                        _uiState.update { it.copy(isPreparingSpeech = false, isSpeaking = true) }
                        if (settings.ttsBargeInEnabled) {
                            sendResumeBroadcast()
                        }
                    }
                    is TTSState.Done -> {
                        Log.d(TAG, "TTS Done")
                        completed = true
                        if (settings.ttsBargeInEnabled && !settings.continuousMode) {
                            sendPauseBroadcast()
                        }
                    }
                    is TTSState.Error -> {
                        if (ignoreNextTtsStop) {
                            Log.d(TAG, "Ignoring TTS stop during controlled interruption")
                            return@collect
                        }
                        Log.e(TAG, "TTS Error: ${state.message}")
                        error = true
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "TTS flow error", e)
            error = true
        }
        
        return completed && !error
    }

    fun stopSpeaking() {
        nextSpeechRequestId()
        lastInputWasVoice = false // Stop loop if manually stopped
        cancelInitialFillerPhrase()
        cancelWaitPhraseTimer()
        stopThinkingSound()
        stopAuxiliarySpeech()
        ignoreNextTtsStop = true
        ttsManager?.stop()
        speakingJob?.cancel()
        speakingJob = null
        _uiState.update { it.copy(isSpeaking = false, isPreparingSpeech = false) }
        releaseWakeLock()
        sendResumeBroadcast()
    }

    /**
     * Returns true if a voice conversation is currently active
     * (listening, thinking after voice input, or speaking a voice response).
     * Used by ChatActivity to avoid stopping the session when the screen turns off.
     */
    fun isVoiceSessionActive(): Boolean {
        val state = _uiState.value
        return lastInputWasVoice && (state.isListening || state.isThinking || state.isSpeaking)
    }

    fun interruptAndListen() {
        cancelInitialFillerPhrase()
        cancelWaitPhraseTimer()
        stopThinkingSound()
        stopAuxiliarySpeech()
        listeningJob?.cancel()
        nextSpeechRequestId()
        ignoreNextTtsStop = true
        ttsManager?.stop()
        speakingJob?.cancel()
        speakingJob = null
        speechManager.destroy()
        _uiState.update {
            it.copy(
                isListening = false,
                isThinking = false,
                isSpeaking = false,
                isPreparingSpeech = false,
                partialText = "",
                error = null
            )
        }
        sendPauseBroadcast()
        startListeningInternal(initialDelayMs = INTERRUPT_LISTEN_DELAY_MS, forceRestart = true)
    }

    // REMOVED private fun addMessage because we now flow from DB

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val app = getApplication<Application>()
        val powerManager = app.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
        wakeLock = powerManager.newWakeLock(
            android.os.PowerManager.PARTIAL_WAKE_LOCK,
            "OpenClawAssistant::ChatWakeLock"
        ).apply {
            acquire(10 * 60 * 1000L) // 10 min max to prevent leak
        }
        Log.d(TAG, "WakeLock acquired")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "WakeLock released")
            }
        }
        wakeLock = null
    }

    private fun startThinkingSound() {
        thinkingSoundJob?.cancel()
        if (!settings.thinkingSoundEnabled || !lastInputWasVoice) return
        thinkingSoundJob = viewModelScope.launch {
            delay(2000)
            while (isActive) {
                toneGenerator.startTone(android.media.ToneGenerator.TONE_SUP_RINGTONE, 100)
                delay(3000)
            }
        }
    }

    private fun stopThinkingSound() {
        thinkingSoundJob?.cancel()
        thinkingSoundJob = null
    }

    private var waitPhraseJob: Job? = null

    private fun scheduleInitialFillerPhrase() {
        cancelInitialFillerPhrase()
        if (!settings.fillerPhrasesEnabled || !settings.ttsEnabled) return
        initialFillerPhraseJob = viewModelScope.launch {
            delay(INITIAL_FILLER_DELAY_MS)
            if (_uiState.value.isThinking && isActive) {
                playFillerPhrase()
            }
        }
    }

    private fun cancelInitialFillerPhrase() {
        initialFillerPhraseJob?.cancel()
        initialFillerPhraseJob = null
    }

    private fun stopAuxiliarySpeech() {
        val hadActiveAuxSpeech = auxiliarySpeechJob?.isActive == true
        auxiliarySpeechJob?.cancel()
        auxiliarySpeechJob = null
        if (hadActiveAuxSpeech) {
            // Fillers may play on the local engine while the answer voice is a network
            // provider — stop() only reaches the current provider, so stop them all.
            ttsManager?.stopAll()
        }
    }

    private fun startWaitPhraseTimer() {
        waitPhraseJob?.cancel()
        if (!settings.fillerPhrasesEnabled || !settings.ttsEnabled) return
        waitPhraseJob = viewModelScope.launch {
            delay(FIRST_WAIT_PHRASE_DELAY_MS)
            while (_uiState.value.isThinking && isActive) {
                Log.d(TAG, "Wait phrase timer triggered. Playing wait phrase.")
                playWaitPhrase()
                delay(REPEAT_WAIT_PHRASE_DELAY_MS)
            }
        }
    }

    private fun cancelWaitPhraseTimer() {
        waitPhraseJob?.cancel()
        waitPhraseJob = null
    }

    private fun playFillerPhrase() {
        val app = getApplication<Application>()
        val fillerPhrases = listOf(
            app.getString(R.string.filler_checking_1),
            app.getString(R.string.filler_checking_2),
            app.getString(R.string.filler_checking_3)
        )
        val phrase = fillerPhrases.random()

        stopAuxiliarySpeech()
        var playbackJob: Job? = null
        playbackJob = viewModelScope.launch {
            try {
                ttsManager?.speakFillerWithProgress(phrase)?.collect {} // Fire-and-forget: playback completion is not awaited
            } catch (_: kotlinx.coroutines.CancellationException) {
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play filler phrase", e)
            } finally {
                if (auxiliarySpeechJob === playbackJob) {
                    auxiliarySpeechJob = null
                }
            }
        }
        auxiliarySpeechJob = playbackJob
    }

    private fun playWaitPhrase() {
        val app = getApplication<Application>()
        val waitPhrases = listOf(
            app.getString(R.string.wait_phrase_let_me_think),
            app.getString(R.string.wait_phrase_one_moment),
            app.getString(R.string.wait_phrase_checking)
        )
        val phrase = waitPhrases.random()

        stopAuxiliarySpeech()
        var playbackJob: Job? = null
        playbackJob = viewModelScope.launch {
            try {
                ttsManager?.speakFillerWithProgress(phrase)?.collect {}
            } catch (_: kotlinx.coroutines.CancellationException) {
            } catch (e: Exception) {
                Log.w(TAG, "Failed to play wait phrase", e)
            } finally {
                if (auxiliarySpeechJob === playbackJob) {
                    auxiliarySpeechJob = null
                }
            }
        }
        auxiliarySpeechJob = playbackJob
    }

    override fun onCleared() {
        super.onCleared()
        cancelInitialFillerPhrase()
        cancelWaitPhraseTimer()
        stopThinkingSound()
        stopAuxiliarySpeech()
        getApplication<Application>().unregisterReceiver(interruptReceiver)
        speechManager.destroy()
        toneGenerator.release()
        releaseWakeLock()
        sendResumeBroadcast()
        // TTSManager lifecycle is managed by Activity
    }

    private fun sendPauseBroadcast() {
        val intent = android.content.Intent("com.openclaw.assistant.ACTION_PAUSE_HOTWORD")
        intent.setPackage(getApplication<Application>().packageName)
        getApplication<Application>().sendBroadcast(intent)
    }
    
    private fun sendResumeBroadcast() {
        val intent = android.content.Intent("com.openclaw.assistant.ACTION_RESUME_HOTWORD")
        intent.setPackage(getApplication<Application>().packageName)
        getApplication<Application>().sendBroadcast(intent)
    }

    private fun com.openclaw.assistant.chat.ChatMessage.toUiChatMessage(): ChatMessage {
        val mergedText = content.joinToString("\n") { it.text ?: "" }.trim().ifBlank { "(thinking)" }
        val preprocessed = ChatMarkdownPreprocessor.preprocess(mergedText)
        val isUserMessage = role.equals("user", ignoreCase = true)
        val attachmentContents = content.filter { it.type != "text" && it.base64 != null }
        return ChatMessage(
            id = id,
            text = preprocessed,
            isUser = isUserMessage,
            timestamp = timestampMs ?: System.currentTimeMillis(),
            attachments = attachmentContents
        )
    }

    /**
     * Sends `text` through the Chat-selected backend, or the Primary backend
     * when the selector is set to Primary. Returns null only when there are no
     * configured multi-backends yet, in which case callers use the legacy
     * OpenClaw HTTP settings.
     */
    private suspend fun trySendViaSelectedBackend(
        sessionId: String,
        text: String,
        agentId: String?,
        history: List<com.openclaw.assistant.backend.AgentMessage> = emptyList(),
        attachments: List<com.openclaw.assistant.backend.AgentAttachment> = emptyList(),
    ): String? {
        val ctx = getApplication<Application>().applicationContext
        // Resolved rather than raw, so a selection left pointing at a deleted or
        // disabled backend falls back to Primary here exactly as it does in the
        // gateway-or-not decision, instead of failing with "no backend selected".
        val overrideId = resolvedChatTarget()?.id
        val streamed = StringBuilder()
        return com.openclaw.assistant.backend.PrimaryBackendDispatcher.send(
            context = ctx,
            userText = text,
            backendId = overrideId,
            sessionId = sessionId,
            agentId = agentId,
            history = history,
            attachments = attachments,
            // Chat can show the prompt, so let the user decide rather than
            // declining tool use on their behalf.
            approvalPolicy = com.openclaw.assistant.backend.ApprovalPolicy.ASK,
            onEvent = { event ->
                when (event) {
                    is com.openclaw.assistant.backend.AgentEvent.Started ->
                        _uiState.update { it.copy(activeRunId = event.runId) }
                    is com.openclaw.assistant.backend.AgentEvent.TokenDelta -> {
                        streamed.append(event.text)
                        _uiState.update { it.copy(streamingAssistantText = streamed.toString()) }
                    }
                    is com.openclaw.assistant.backend.AgentEvent.MessageDelta -> {
                        streamed.append(event.text)
                        _uiState.update { it.copy(streamingAssistantText = streamed.toString()) }
                    }
                    is com.openclaw.assistant.backend.AgentEvent.ApprovalRequest ->
                        _uiState.update { it.copy(pendingApproval = event) }
                    else -> Unit
                }
            },
        )?.text
    }

    private suspend fun resolveSelectedOpenClawModel(): String? {
        val ctx = getApplication<Application>().applicationContext
        val backends = com.openclaw.assistant.backend.BackendRepository.getInstance(ctx).backends.first()
        return com.openclaw.assistant.backend.ChatTargetResolver.openClawModelOverride(
            backends = backends,
            selectedId = com.openclaw.assistant.ui.backend.ChatBackendTarget.selectedId.value,
        )
    }

    private companion object {
        /** Turns of local transcript replayed to backends that do not keep one. */
        const val MAX_HISTORY_MESSAGES = 40
    }

}
