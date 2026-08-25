package com.openclaw.assistant.node

import android.Manifest
import android.util.Log
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.openclaw.assistant.CameraHudKind
import com.openclaw.assistant.CameraHudState
import com.openclaw.assistant.LocationMode
import com.openclaw.assistant.PermissionRequester
import com.openclaw.assistant.SecurePrefs
import com.openclaw.assistant.VoiceWakeMode
import com.openclaw.assistant.chat.ChatController
import com.openclaw.assistant.chat.ChatMessage
import com.openclaw.assistant.chat.ChatPendingToolCall
import com.openclaw.assistant.chat.ChatSessionEntry
import com.openclaw.assistant.chat.OutgoingAttachment
import com.openclaw.assistant.chat.isCanonicalMainSessionKey
import com.openclaw.assistant.chat.normalizeMainKey
import com.openclaw.assistant.bridge.MobileBridgeConfig
import com.openclaw.assistant.gateway.AgentInfo
import com.openclaw.assistant.gateway.AgentListResult
import com.openclaw.assistant.gateway.DeviceAuthStore
import com.openclaw.assistant.gateway.DeviceIdentityStore
import com.openclaw.assistant.gateway.GatewayDiscovery
import com.openclaw.assistant.gateway.GatewayEndpoint
import com.openclaw.assistant.gateway.GatewaySession
import com.openclaw.assistant.gateway.probeGatewayTlsFingerprint
import com.openclaw.assistant.protocol.OpenClawCanvasA2UIAction
import com.openclaw.assistant.R
import com.openclaw.assistant.service.OpenClawNotificationListenerService


import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.util.concurrent.atomic.AtomicLong

class NodeRuntime(context: Context) {
  private val appContext = context.applicationContext
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  val prefs = SecurePrefs(appContext)
  private val deviceAuthStore = DeviceAuthStore(prefs)
  val canvas = CanvasController()
  val camera = CameraCaptureManager(appContext)
  val location = LocationCaptureManager(appContext)
  val screenRecorder = ScreenRecordManager(appContext)
  val sms = SmsManager(appContext)
  private val json = Json { ignoreUnknownKeys = true }
  private val mobileBridgeConfig = MobileBridgeConfig.getInstance(appContext)

  private val externalAudioCaptureActive = MutableStateFlow(false)
  private val chatMicActive = MutableStateFlow(false)

  private val _voiceWakeIsListening = MutableStateFlow(false)
  val voiceWakeIsListening: StateFlow<Boolean> = _voiceWakeIsListening.asStateFlow()

  private val _voiceWakeStatusText = MutableStateFlow("Off")
  val voiceWakeStatusText: StateFlow<String> = _voiceWakeStatusText.asStateFlow()

  private val discovery = GatewayDiscovery(appContext, scope = scope)
  val gateways: StateFlow<List<GatewayEndpoint>> = discovery.gateways
  val discoveryStatusText: StateFlow<String> = discovery.statusText

  private val identityStore = DeviceIdentityStore(appContext)
  private var connectedEndpoint: GatewayEndpoint? = null

  private val cameraHandler: CameraHandler = CameraHandler(
    appContext = appContext,
    camera = camera,
    prefs = prefs,
    connectedEndpoint = { connectedEndpoint },
    externalAudioCaptureActive = externalAudioCaptureActive,
    showCameraHud = ::showCameraHud,
    triggerCameraFlash = ::triggerCameraFlash,
    invokeErrorFromThrowable = { invokeErrorFromThrowable(it) },
  )

  private val debugHandler: DebugHandler = DebugHandler(
    appContext = appContext,
    identityStore = identityStore,
  )

  private val appUpdateHandler: AppUpdateHandler = AppUpdateHandler(
    appContext = appContext,
    connectedEndpoint = { connectedEndpoint },
  )

  private val deviceHandler: DeviceHandler = DeviceHandler(
    appContext = appContext,
    prefs = prefs,
  )

  private val locationHandler: LocationHandler = LocationHandler(
    appContext = appContext,
    location = location,
    json = json,
    isForeground = { _isForeground.value },
    locationMode = { locationMode.value },
    locationPreciseEnabled = { locationPreciseEnabled.value },
  )

  private val screenHandler: ScreenHandler = ScreenHandler(
    screenRecorder = screenRecorder,
    setScreenRecordActive = { _screenRecordActive.value = it },
    invokeErrorFromThrowable = { invokeErrorFromThrowable(it) },
  )

  private val smsHandlerImpl: SmsHandler = SmsHandler(
    sms = sms,
  )

  private val notificationManager: NotificationManager = NotificationManager().also {
    OpenClawNotificationListenerService.manager = it
  }

  private val notificationsHandler: NotificationsHandler = NotificationsHandler(
    context = appContext,
    notificationManager = notificationManager,
  )

  private val systemHandler: SystemHandler = SystemHandler(
    appContext = appContext,
  )

  private val photosHandler: PhotosHandler = PhotosHandler(
    appContext = appContext,
  )

  private val contactsHandler: ContactsHandler = ContactsHandler(
    appContext = appContext,
  )

  private val calendarHandler: CalendarHandler = CalendarHandler(
    appContext = appContext,
  )

  private val motionHandler: MotionHandler = MotionHandler(
    appContext = appContext,
  )

  private val a2uiHandler: A2UIHandler = A2UIHandler(
    canvas = canvas,
    json = json,
    getNodeCanvasHostUrl = { nodeSession.currentCanvasHostUrl() },
    getOperatorCanvasHostUrl = { operatorSession.currentCanvasHostUrl() },
  )

  private val wifiHandler = WifiHandler(
    context = appContext,
    json = json,
    invokeErrorFromThrowable = { invokeErrorFromThrowable(it) },
  )

  private val clipboardHandler = ClipboardHandler(
    context = appContext,
    json = json,
    invokeErrorFromThrowable = { invokeErrorFromThrowable(it) },
  )

  private val appHandler = AppHandler(
    context = appContext,
    json = json,
    invokeErrorFromThrowable = { invokeErrorFromThrowable(it) },
  )

  private val voiceWakeHandler = VoiceWakeHandler(
    json = json,
    voiceWakeMode = { prefs.voiceWakeMode.value },
    setVoiceWakeMode = { mode -> 
      prefs.setVoiceWakeMode(mode)
    },
    voiceWakeStatusText = { voiceWakeStatusText.value },
    invokeErrorFromThrowable = { invokeErrorFromThrowable(it) },
  )

  private val mobileBridgeHandler = MobileBridgeHandler(
    context = appContext,
    config = mobileBridgeConfig,
    json = json,
  )

  private val connectionManager: ConnectionManager = ConnectionManager(
    prefs = prefs,
    appContext = appContext,
    cameraEnabled = { cameraEnabled.value },
    locationMode = { locationMode.value },
    voiceWakeMode = { voiceWakeMode.value },
    smsAvailable = { sms.canSendSms() },
    hasRecordAudioPermission = { hasRecordAudioPermission() },
    manualTls = { manualTls.value },
    deviceId = { deviceId },
  )

  private val invokeDispatcher: InvokeDispatcher = InvokeDispatcher(
    canvas = canvas,
    cameraHandler = cameraHandler,
    locationHandler = locationHandler,
    screenHandler = screenHandler,
    smsHandler = smsHandlerImpl,
    notificationsHandler = notificationsHandler,
    systemHandler = systemHandler,
    photosHandler = photosHandler,
    contactsHandler = contactsHandler,
    calendarHandler = calendarHandler,
    motionHandler = motionHandler,
    wifiHandler = wifiHandler,
    clipboardHandler = clipboardHandler,
    appHandler = appHandler,
    voiceWakeHandler = voiceWakeHandler,
    a2uiHandler = a2uiHandler,
    debugHandler = debugHandler,
    appUpdateHandler = appUpdateHandler,
    deviceHandler = deviceHandler,
    mobileBridgeHandler = mobileBridgeHandler,
    isForeground = { _isForeground.value },
    cameraEnabled = { cameraEnabled.value },
    locationEnabled = { locationMode.value != LocationMode.Off },
  )

  private lateinit var gatewayEventHandler: GatewayEventHandler

  data class GatewayTrustPrompt(
    val endpoint: GatewayEndpoint,
    val fingerprintSha256: String,
  )

  private val _isConnected = MutableStateFlow(false)
  val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

  private val _isOperatorOffline = MutableStateFlow(false)
  val isOperatorOffline: StateFlow<Boolean> = _isOperatorOffline.asStateFlow()

  /**
   * Bumped every time the operator socket connects or drops.
   *
   * Gateway-relay Talk sessions are owned by a single connId, so a reconnect silently invalidates
   * an open relay session. Consumers compare the generation they started with against the current
   * value to notice that, since the connId itself is never surfaced by [GatewaySession].
   */
  private val _operatorConnectionGeneration = MutableStateFlow(0)
  val operatorConnectionGeneration: StateFlow<Int> = _operatorConnectionGeneration.asStateFlow()

  private val _statusText = MutableStateFlow("Offline")
  val statusText: StateFlow<String> = _statusText.asStateFlow()

  private val _pendingGatewayTrust = MutableStateFlow<GatewayTrustPrompt?>(null)
  val pendingGatewayTrust: StateFlow<GatewayTrustPrompt?> = _pendingGatewayTrust.asStateFlow()

  private val _mainSessionKey = MutableStateFlow("main")
  val mainSessionKey: StateFlow<String> = _mainSessionKey.asStateFlow()

  private val _serverVersion = MutableStateFlow<String?>(null)
  val serverVersion: StateFlow<String?> = _serverVersion.asStateFlow()

  private val cameraHudSeq = AtomicLong(0)
  private val _cameraHud = MutableStateFlow<CameraHudState?>(null)
  val cameraHud: StateFlow<CameraHudState?> = _cameraHud.asStateFlow()

  private val _cameraFlashToken = MutableStateFlow(0L)
  val cameraFlashToken: StateFlow<Long> = _cameraFlashToken.asStateFlow()

  private val _screenRecordActive = MutableStateFlow(false)
  val screenRecordActive: StateFlow<Boolean> = _screenRecordActive.asStateFlow()

  private val _serverName = MutableStateFlow<String?>(null)
  val serverName: StateFlow<String?> = _serverName.asStateFlow()

  private val _remoteAddress = MutableStateFlow<String?>(null)
  val remoteAddress: StateFlow<String?> = _remoteAddress.asStateFlow()



  private val _isPairingRequired = MutableStateFlow(false)
  val isPairingRequired: StateFlow<Boolean> = _isPairingRequired.asStateFlow()

  private val _missingScopeError = MutableStateFlow<String?>(null)
  val missingScopeError: StateFlow<String?> = _missingScopeError.asStateFlow()

  private val _agentList = MutableStateFlow<AgentListResult?>(null)
  val agentList: StateFlow<AgentListResult?> = _agentList.asStateFlow()

  val deviceId: String?
    get() = identityStore.loadOrCreate().deviceId

  private fun hasStoredOperatorToken(): Boolean {
    val identityId = deviceId ?: return false
    return !deviceAuthStore.loadToken(identityId, "operator").isNullOrBlank()
  }

  data class PairingApprovalResult(
    val approved: Boolean,
    val requestId: String? = null,
    val message: String? = null,
  )

  private val _isForeground = MutableStateFlow(true)
  val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

  private var lastAutoA2uiUrl: String? = null
  private var operatorConnected = false
  private var nodeConnected = false
  private var operatorStatusText: String = "Offline"
  private var nodeStatusText: String = "Offline"

  private val operatorSession =
    GatewaySession(
      scope = scope,
      identityStore = identityStore,
      deviceAuthStore = deviceAuthStore,
      onConnected = { name, remote, version, mainSessionKey ->
        operatorConnected = true
        operatorStatusText = "Connected"
        _operatorConnectionGeneration.value += 1
        _serverName.value = name
        _remoteAddress.value = remote
        _serverVersion.value = version
        applyMainSessionKey(mainSessionKey)
        chat.onConnected()
        updateStatus()
        scope.launch { refreshBrandingFromGateway() }
        scope.launch { gatewayEventHandler.refreshWakeWordsFromGateway() }
        scope.launch {
          fetchAgentList()
          updateHomeCanvasState()
        }
      },
      onDisconnected = { message ->
        val wasConnected = operatorConnected
        operatorConnected = false
        operatorStatusText = message
        if (wasConnected) _operatorConnectionGeneration.value += 1
        _serverName.value = null
        _remoteAddress.value = null
        _serverVersion.value = null
        if (!isCanonicalMainSessionKey(_mainSessionKey.value)) {
          _mainSessionKey.value = "main"
        }
        val mainKey = resolveMainSessionKey()
        chat.applyMainSessionKey(mainKey)
        chat.onDisconnected(message)
        updateStatus()
        updateHomeCanvasState()
      },
      onEvent = { event, payloadJson ->
        handleGatewayEvent(event, payloadJson)
      },
    )

  private val nodeSession =
    GatewaySession(
      scope = scope,
      identityStore = identityStore,
      deviceAuthStore = deviceAuthStore,
      onConnected = { _, _, version, _ ->
        nodeConnected = true
        nodeStatusText = "Connected"
        _serverVersion.value = version
        updateStatus()
        scope.launch { refreshNodeCanvasCapabilityAndNavigate() }
        scope.launch { connectOperatorAfterNodeBootstrap() }
      },
      onDisconnected = { message ->
        nodeConnected = false
        nodeStatusText = message
        _serverVersion.value = null
        updateStatus()
        showLocalCanvasOnDisconnect()
      },
      onEvent = { _, _ -> },
      onInvoke = { req ->
        val result = invokeDispatcher.handleInvoke(req.command, req.paramsJson)
        if (!result.ok) {
          val errorMsg = result.error?.message ?: "Unknown error"
          reportCapabilityError(errorMsg)
        }
        result
      },
      onTlsFingerprint = { stableId, fingerprint ->
        prefs.saveGatewayTlsFingerprint(stableId, fingerprint)
      },
      onBootstrapTokenInvalid = {
        // The bootstrapToken was consumed by the server (single-use). Clear it from persistent
        // storage so future retries don't loop on the expired token. The user must scan a fresh
        // setup code — on second scan the device is already approved, so the server issues a
        // deviceToken directly without requiring another pairing approval.
        Log.i("NodeRuntime", "bootstrapToken consumed/expired — clearing from prefs")
        prefs.saveGatewayBootstrapToken("")
      },
    )

  /** Adapts the operator socket to the narrow surface chat needs. */
  private val chatSession =
    object : com.openclaw.assistant.chat.ChatGatewaySession {
      override suspend fun request(method: String, paramsJson: String?, timeoutMs: Long): String =
        operatorSession.request(method, paramsJson, timeoutMs)

      override suspend fun sendNodeEvent(event: String, payloadJson: String?): Boolean =
        operatorSession.sendNodeEvent(event, payloadJson)
    }

  private val chat: ChatController =
    ChatController(
      scope = scope,
      session = chatSession,
      json = json,
      supportsChatSubscribe = false, // node.event is node-role only; operator connections receive events automatically
    )

  private fun applyMainSessionKey(candidate: String?) {
    val trimmed = normalizeMainKey(candidate) ?: return
    if (isCanonicalMainSessionKey(_mainSessionKey.value)) return
    if (_mainSessionKey.value == trimmed) return
    _mainSessionKey.value = trimmed
    chat.applyMainSessionKey(trimmed)
  }

  private fun updateStatus() {
    _isConnected.value = operatorConnected || nodeConnected
    _isOperatorOffline.value = !operatorConnected && nodeConnected

    val pairingKeyword = "pairing required"
    val paringKeyword = "paring required" // Handle common typo in gateway or its libraries
    val operatorNeedsPairing = !operatorConnected && (
      operatorStatusText.contains(pairingKeyword, ignoreCase = true) ||
      operatorStatusText.contains(paringKeyword, ignoreCase = true)
    )
    val nodeNeedsPairing = !nodeConnected && (
      nodeStatusText.contains(pairingKeyword, ignoreCase = true) ||
      nodeStatusText.contains(paringKeyword, ignoreCase = true)
    )

    if (operatorNeedsPairing || nodeNeedsPairing) {
      // Keep the pairing card visible once the gateway reports it. Some status polls return a
      // generic disconnected message while the request is still pending, so only a real connection
      // should clear the prompt.
      _isPairingRequired.value = true
    } else if (operatorConnected && nodeConnected) {
      _isPairingRequired.value = false
    }

    _statusText.value =
      when {
        operatorConnected && nodeConnected -> "Connected"
        operatorConnected && !nodeConnected -> "Operator Online (Node Offline)"
        !operatorConnected && nodeConnected -> "Node Online (Operator Offline)"
        // Prefer nodeStatusText when informative: bootstrapToken is issued for the node role, so
        // nodeSession carries the meaningful auth error. operatorSession's "password missing" is
        // expected noise when bootstrapToken-only setup is used.
        nodeStatusText.isNotBlank() && nodeStatusText != "Offline" -> nodeStatusText
        operatorStatusText.isNotBlank() && operatorStatusText != "Offline" -> operatorStatusText
        else -> nodeStatusText
      }
  }

  private fun resolveMainSessionKey(): String {
    val trimmed = _mainSessionKey.value.trim()
    return if (trimmed.isEmpty()) "main" else trimmed
  }

  private suspend fun refreshNodeCanvasCapabilityAndNavigate() {
    nodeSession.refreshNodeCanvasCapability()
    maybeNavigateToA2uiOnConnect()
  }

  private fun maybeNavigateToA2uiOnConnect() {
    val a2uiUrl = a2uiHandler.resolveA2uiHostUrl() ?: return
    val current = canvas.currentUrl()?.trim().orEmpty()
    if (current.isEmpty() || current == lastAutoA2uiUrl) {
      lastAutoA2uiUrl = a2uiUrl
      canvas.navigate(a2uiUrl)
    }
  }

  private fun showLocalCanvasOnDisconnect() {
    lastAutoA2uiUrl = null
    canvas.navigate("")
  }

  val instanceId: StateFlow<String> = prefs.instanceId
  val displayName: StateFlow<String> = prefs.displayName
  val cameraEnabled: StateFlow<Boolean> = prefs.cameraEnabled
  val locationMode: StateFlow<LocationMode> = prefs.locationMode
  val locationPreciseEnabled: StateFlow<Boolean> = prefs.locationPreciseEnabled
  val preventSleep: StateFlow<Boolean> = prefs.preventSleep
  val wakeWords: StateFlow<List<String>> = prefs.wakeWords
  val voiceWakeMode: StateFlow<VoiceWakeMode> = prefs.voiceWakeMode

  val smsEnabled: StateFlow<Boolean> = prefs.smsEnabled
  val manualEnabled: StateFlow<Boolean> = prefs.manualEnabled
  val manualHost: StateFlow<String> = prefs.manualHost
  val manualPort: StateFlow<Int> = prefs.manualPort
  val manualTls: StateFlow<Boolean> = prefs.manualTls
  val gatewayToken: StateFlow<String> = prefs.gatewayToken
  fun setGatewayToken(value: String) = prefs.setGatewayToken(value)
  fun getGatewayPassword(): String? = prefs.loadGatewayPassword()
  fun setGatewayPassword(value: String) = prefs.saveGatewayPassword(value)
  fun getGatewayBootstrapToken(): String? = prefs.loadGatewayBootstrapToken()
  fun setGatewayBootstrapToken(value: String) = prefs.saveGatewayBootstrapToken(value)
  val lastDiscoveredStableId: StateFlow<String> = prefs.lastDiscoveredStableId
  val canvasDebugStatusEnabled: StateFlow<Boolean> = prefs.canvasDebugStatusEnabled

  private var didAutoConnect = false

  val chatSessionKey: StateFlow<String> = chat.sessionKey
  val chatSessionId: StateFlow<String?> = chat.sessionId
  val chatMessages: StateFlow<List<ChatMessage>> = chat.messages
  val chatError: StateFlow<String?> = chat.errorText
  val chatHealthOk: StateFlow<Boolean> = chat.healthOk
  val chatThinkingLevel: StateFlow<String> = chat.thinkingLevel
  val chatStreamingAssistantText: StateFlow<String?> = chat.streamingAssistantText
  val chatPendingToolCalls: StateFlow<List<ChatPendingToolCall>> = chat.pendingToolCalls
  val chatSessions: StateFlow<List<ChatSessionEntry>> = chat.sessions
  val pendingRunCount: StateFlow<Int> = chat.pendingRunCount

  init {
    gatewayEventHandler = GatewayEventHandler(
      scope = scope,
      prefs = prefs,
      json = json,
      operatorSession = operatorSession,
      isConnected = { _isConnected.value },
    )

    scope.launch {
      combine(
        voiceWakeMode,
        isForeground,
        externalAudioCaptureActive,
        wakeWords,
        chatMicActive,
      ) { mode: VoiceWakeMode, foreground: Boolean, externalAudio: Boolean, words: List<String>, chatMic: Boolean -> 
        Quint(mode, foreground, externalAudio, words, chatMic) 
      }
        .distinctUntilChanged()
        .collect { (mode, foreground, externalAudio, words, chatMic) ->
          val shouldListen =
            when (mode) {
              VoiceWakeMode.Off -> false
              VoiceWakeMode.Foreground -> foreground
              VoiceWakeMode.Always -> true
            } && !externalAudio && !chatMic

          _voiceWakeStatusText.value = if (!shouldListen) {
            if (mode == VoiceWakeMode.Off) "Off" else "Paused"
          } else if (!hasRecordAudioPermission()) {
            "Microphone permission required"
          } else {
            "Active"
          }
        }
    }



    scope.launch(Dispatchers.Default) {
      // Auto-connect for Manual Mode on startup
      if (manualEnabled.value) {
        delay(500) // Brief delay to allow system to settle
        if (!_isConnected.value) {
           connectManual()
        }
      }
    }

    scope.launch(Dispatchers.Default) {
      gateways.collect { list ->
        seedLastDiscoveredGateway(list)
        autoConnectIfNeeded()
      }
    }

    scope.launch {
      combine(
        canvasDebugStatusEnabled,
        statusText,
        serverName,
        remoteAddress,
      ) { debugEnabled, status, server, remote ->
        Quad(debugEnabled, status, server, remote)
      }.distinctUntilChanged()
        .collect { (debugEnabled, status, server, remote) ->
          canvas.setDebugStatusEnabled(debugEnabled)
          if (!debugEnabled) return@collect
          canvas.setDebugStatus(status, server ?: remote)
        }
    }
  }

  fun setForeground(value: Boolean) {
    _isForeground.value = value
    if (value) {
      reconnectPreferredGatewayOnForeground()
    }
  }

  private fun seedLastDiscoveredGateway(list: List<GatewayEndpoint>) {
    if (list.isEmpty()) return
    if (lastDiscoveredStableId.value.trim().isNotEmpty()) return
    prefs.setLastDiscoveredStableId(list.first().stableId)
  }

  private fun resolvePreferredGatewayEndpoint(): GatewayEndpoint? {
    if (manualEnabled.value) {
      val host = manualHost.value.trim()
        .removePrefix("https://")
        .removePrefix("http://")
        .removeSuffix("/")
      val port = manualPort.value
      if (host.isEmpty() || port !in 1..65535) return null
      return GatewayEndpoint.manual(host = host, port = port)
    }

    val targetStableId = lastDiscoveredStableId.value.trim()
    if (targetStableId.isEmpty()) return null
    val endpoint = gateways.value.firstOrNull { it.stableId == targetStableId } ?: return null
    val storedFingerprint = prefs.loadGatewayTlsFingerprint(endpoint.stableId)?.trim().orEmpty()
    if (storedFingerprint.isEmpty()) return null
    return endpoint
  }

  private fun autoConnectIfNeeded() {
    if (didAutoConnect) return
    if (_isConnected.value) return
    // Manual mode startup connection is handled by the dedicated startup block; skip here
    // to avoid a double-connect race when discovery fires before the 500 ms delay elapses.
    if (manualEnabled.value) return
    val endpoint = resolvePreferredGatewayEndpoint() ?: return
    didAutoConnect = true
    connect(endpoint)
  }

  private fun reconnectPreferredGatewayOnForeground() {
    if (_isConnected.value) return
    if (_pendingGatewayTrust.value != null) return
    if (connectedEndpoint != null) {
      refreshGatewayConnection()
      return
    }
    resolvePreferredGatewayEndpoint()?.let(::connect)
  }

  fun setDisplayName(value: String) {
    prefs.setDisplayName(value)
  }

  fun setCameraEnabled(value: Boolean) {
    prefs.setCameraEnabled(value)
    if (value) refreshGatewayConnection() // Re-announce capabilities so gateway allows camera commands
  }

  fun setLocationMode(mode: LocationMode) {
    prefs.setLocationMode(mode)
  }

  fun setLocationPreciseEnabled(value: Boolean) {
    prefs.setLocationPreciseEnabled(value)
  }

  fun setPreventSleep(value: Boolean) {
    prefs.setPreventSleep(value)
  }

  fun setManualEnabled(value: Boolean) {
    prefs.setManualEnabled(value)
  }

  fun setManualHost(value: String) {
    prefs.setManualHost(value)
  }

  fun setManualPort(value: Int) {
    prefs.setManualPort(value)
  }

  fun setManualTls(value: Boolean) {
    prefs.setManualTls(value)
  }

  fun setCanvasDebugStatusEnabled(value: Boolean) {
    prefs.setCanvasDebugStatusEnabled(value)
  }

  fun setWakeWords(words: List<String>) {
    prefs.setWakeWords(words)
    gatewayEventHandler.scheduleWakeWordsSyncIfNeeded()
  }

  fun resetWakeWordsDefaults() {
    setWakeWords(SecurePrefs.defaultWakeWords)
  }

  fun pauseVoiceWake() {
    chatMicActive.value = true
  }

  fun resumeVoiceWake() {
    chatMicActive.value = false
  }

  fun setVoiceWakeMode(mode: VoiceWakeMode) {
    prefs.setVoiceWakeMode(mode)
  }



  fun setSmsEnabled(value: Boolean) {
    prefs.setSmsEnabled(value)
    if (value) refreshGatewayConnection() // Re-announce capabilities so gateway allows SMS commands
  }

  fun setScreenRecordActive(value: Boolean) {
    _screenRecordActive.value = value
  }

  fun stopScreenRecording() {
    screenRecorder.stopRecording()
  }

  suspend fun refreshWakeWordsFromGateway() {
    gatewayEventHandler.refreshWakeWordsFromGateway()
  }

  private val _lastCapabilityError = MutableStateFlow<String?>(null)
  val lastCapabilityError: StateFlow<String?> = _lastCapabilityError.asStateFlow()

  fun clearCapabilityError() {
    _lastCapabilityError.value = null
  }

  internal fun reportCapabilityError(msg: String) {
    _lastCapabilityError.value = msg
  }

  fun refreshGatewayConnection() {
    val endpoint = connectedEndpoint ?: return
    val token = prefs.loadGatewayToken()
    val password = prefs.loadGatewayPassword()
    val bootstrapToken = prefs.loadGatewayBootstrapToken()
    val tls = connectionManager.resolveTlsParams(endpoint)
    // bootstrapToken is single-use and issued for the node role only — never pass it to operatorSession.
    // If this device already has an operator token, keep the operator connection alive so it can
    // approve node repair pairings without asking the user to run a CLI command.
    if (bootstrapToken.isNullOrBlank() || hasStoredOperatorToken()) {
      operatorSession.connect(endpoint, token, password, null, connectionManager.buildOperatorConnectOptions(), tls)
    }
    nodeSession.connect(endpoint, token, password, bootstrapToken, connectionManager.buildNodeConnectOptions(), tls)
    if (bootstrapToken.isNullOrBlank() || hasStoredOperatorToken()) {
      operatorSession.reconnect()
    }
    nodeSession.reconnect()
  }

  suspend fun approvePendingPairingForDevice(targetDeviceId: String): PairingApprovalResult {
    val normalizedDeviceId = targetDeviceId.trim()
    if (normalizedDeviceId.isBlank()) {
      return PairingApprovalResult(approved = false, message = "deviceId is empty")
    }
    return try {
      val password = prefs.loadGatewayPassword()
      Log.d("NodeRuntime", "Pairing approval v20260520c device=$normalizedDeviceId hasPassword=${!password.isNullOrBlank()}")
      Log.d("NodeRuntime", "Approving pending device pairing for $normalizedDeviceId")
      if (!password.isNullOrBlank()) {
        val requestId = approvePendingPairingWithPasswordOperatorForDevice(normalizedDeviceId)
        Log.d("NodeRuntime", "Approved pending device pairing request $requestId")
        refreshGatewayConnection()
        return PairingApprovalResult(approved = true, requestId = requestId)
      }
      val listJson = operatorSession.request("device.pair.list", "{}", timeoutMs = 10_000)
      val listRoot = json.parseToJsonElement(listJson).asObjectOrNull()
      val pending = listRoot?.get("pending") as? JsonArray
      val requestId =
        pending
          ?.mapNotNull { it.asObjectOrNull() }
          ?.firstOrNull { it["deviceId"].asStringOrNull()?.trim() == normalizedDeviceId }
          ?.get("requestId")
          .asStringOrNull()
          ?.trim()
      if (requestId.isNullOrBlank()) {
        Log.w("NodeRuntime", "No pending pairing request found for $normalizedDeviceId")
        return PairingApprovalResult(approved = false, message = "No pending request for this device.")
      }
      val params = buildJsonObject { put("requestId", JsonPrimitive(requestId)) }
      Log.d("NodeRuntime", "Pairing approval using existing operator session; no password available for operator.pairing session")
      operatorSession.request("device.pair.approve", params.toString(), timeoutMs = 10_000)
      Log.d("NodeRuntime", "Approved pending device pairing request $requestId")
      refreshGatewayConnection()
      PairingApprovalResult(approved = true, requestId = requestId)
    } catch (err: Throwable) {
      Log.w("NodeRuntime", "Device pairing approval failed: ${err.message ?: err::class.java.simpleName}")
      PairingApprovalResult(
        approved = false,
        message = err.message ?: err::class.java.simpleName,
      )
    }
  }

  private suspend fun approvePendingPairingWithPasswordOperatorForDevice(deviceId: String): String {
    val pairingSession = createPairingOperatorSession()
    try {
      connectPairingOperatorSession(pairingSession)
      withTimeout(10_000) { pairingSession.connected.await() }
      val listJson = requestPairingOperator(pairingSession.session, "device.pair.list", "{}")
      val listRoot = json.parseToJsonElement(listJson).asObjectOrNull()
      val pending = listRoot?.get("pending") as? JsonArray
      val requestId =
        pending
          ?.mapNotNull { it.asObjectOrNull() }
          ?.firstOrNull { it["deviceId"].asStringOrNull()?.trim() == deviceId }
          ?.get("requestId")
          .asStringOrNull()
          ?.trim()
      if (requestId.isNullOrBlank()) {
        throw IllegalStateException("No pending request for this device.")
      }
      val params = buildJsonObject { put("requestId", JsonPrimitive(requestId)) }
      requestPairingOperator(pairingSession.session, "device.pair.approve", params.toString())
      return requestId
    } finally {
      pairingSession.session.disconnect()
    }
  }

  private suspend fun approvePendingPairingWithPasswordOperator(requestId: String) {
    val pairingSession = createPairingOperatorSession()
    try {
      connectPairingOperatorSession(pairingSession)
      withTimeout(10_000) { pairingSession.connected.await() }
      val params = buildJsonObject { put("requestId", JsonPrimitive(requestId)) }
      requestPairingOperator(pairingSession.session, "device.pair.approve", params.toString())
    } finally {
      pairingSession.session.disconnect()
    }
  }

  private data class PairingOperatorHandle(
    val session: GatewaySession,
    val connected: CompletableDeferred<Unit>,
  )

  private fun createPairingOperatorSession(): PairingOperatorHandle {
    val connected = CompletableDeferred<Unit>()
    val session = GatewaySession(
      scope = scope,
      identityStore = identityStore,
      deviceAuthStore = deviceAuthStore,
      onConnected = { _, _, _, _ ->
        if (!connected.isCompleted) connected.complete(Unit)
      },
      onDisconnected = { message ->
        if (
          !connected.isCompleted &&
          !message.equals("Connecting...", ignoreCase = true) &&
          !message.equals("Connecting…", ignoreCase = true)
        ) {
          connected.completeExceptionally(IllegalStateException(message))
        }
      },
      onEvent = { _, _ -> },
    )
    return PairingOperatorHandle(session = session, connected = connected)
  }

  private fun connectPairingOperatorSession(pairingSession: PairingOperatorHandle) {
    val endpoint = connectedEndpoint ?: throw IllegalStateException("gateway not connected")
    val password = prefs.loadGatewayPassword()
      ?: throw IllegalStateException("operator.pairing scope required and gateway password is not configured")
    Log.d("NodeRuntime", "Connecting temporary operator.pairing password session")
    val tls = connectionManager.resolveTlsParams(endpoint)
    pairingSession.session.connect(
      endpoint = endpoint,
      token = null,
      password = password,
      bootstrapToken = null,
      options = connectionManager.buildPairingOperatorConnectOptions(),
      tls = tls,
    )
  }

  private suspend fun requestPairingOperator(
    pairingSession: GatewaySession,
    method: String,
    paramsJson: String,
  ): String {
    var lastError: Throwable? = null
    repeat(20) {
      try {
        return pairingSession.request(method, paramsJson, timeoutMs = 10_000)
      } catch (err: Throwable) {
        lastError = err
        if (!err.message.orEmpty().contains("not connected", ignoreCase = true)) throw err
        delay(250)
      }
    }
    throw IllegalStateException(lastError?.message ?: "pairing operator not connected")
  }

  fun connect(endpoint: GatewayEndpoint) {
    val tls = connectionManager.resolveTlsParams(endpoint)
    if (tls?.required == true && tls.expectedFingerprint.isNullOrBlank()) {
      // First-time TLS: capture fingerprint, ask user to verify out-of-band, then store and connect.
      scope.launch {
        _statusText.value = appContext.getString(R.string.state_verify_fingerprint)
        android.util.Log.d("NodeRuntime", "Starting TLS probe for ${endpoint.host}:${endpoint.port}")
        val fp = probeGatewayTlsFingerprint(endpoint.host, endpoint.port) ?: run {
          android.util.Log.e("NodeRuntime", "TLS probe failed")
          _statusText.value = appContext.getString(R.string.state_failed_fingerprint)
          return@launch
        }
        android.util.Log.d("NodeRuntime", "TLS probe success, setting pending trust")
        _pendingGatewayTrust.value = GatewayTrustPrompt(endpoint = endpoint, fingerprintSha256 = fp)
      }
      return
    }

    connectedEndpoint = endpoint
    operatorStatusText = "Connecting…"
    nodeStatusText = "Connecting…"
    updateStatus()
    val token = prefs.loadGatewayToken()
    val password = prefs.loadGatewayPassword()
    val bootstrapToken = prefs.loadGatewayBootstrapToken()
    // bootstrapToken is single-use and issued for the node role only — never pass it to operatorSession.
    if (bootstrapToken.isNullOrBlank() || hasStoredOperatorToken()) {
      operatorSession.connect(endpoint, token, password, null, connectionManager.buildOperatorConnectOptions(), tls)
    }
    nodeSession.connect(endpoint, token, password, bootstrapToken, connectionManager.buildNodeConnectOptions(), tls)
  }

  private suspend fun connectOperatorAfterNodeBootstrap() {
    val endpoint = connectedEndpoint ?: return
    if (operatorConnected) return
    if (prefs.loadGatewayBootstrapToken().isNullOrBlank()) return
    delay(250)
    val token = prefs.loadGatewayToken()
    val password = prefs.loadGatewayPassword()
    val tls = connectionManager.resolveTlsParams(endpoint)
    operatorSession.connect(endpoint, token, password, null, connectionManager.buildOperatorConnectOptions(), tls)
  }

  fun acceptGatewayTrustPrompt() {
    val prompt = _pendingGatewayTrust.value ?: return
    _pendingGatewayTrust.value = null
    prefs.saveGatewayTlsFingerprint(prompt.endpoint.stableId, prompt.fingerprintSha256)
    connect(prompt.endpoint)
  }

  fun declineGatewayTrustPrompt() {
    _pendingGatewayTrust.value = null
    _statusText.value = "Offline"
  }

  private fun hasRecordAudioPermission(): Boolean {
    return (
      ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
      )
  }

  fun connectManual() {
    operatorStatusText = ""
    nodeStatusText = ""
    val host = manualHost.value.trim()
      .removePrefix("http://")
      .removePrefix("https://")
      .removeSuffix("/")

    val port = manualPort.value
    if (host.isEmpty() || port <= 0 || port > 65535) {
      _statusText.value = "Failed: invalid manual host/port"
      return
    }
    connect(GatewayEndpoint.manual(host = host, port = port))
  }

  fun attachPermissionRequester(requester: PermissionRequester) {
    notificationsHandler.attachPermissionRequester(requester)
    systemHandler.attachPermissionRequester(requester)
    contactsHandler.attachPermissionRequester(requester)
    calendarHandler.attachPermissionRequester(requester)
    photosHandler.attachPermissionRequester(requester)
    motionHandler.attachPermissionRequester(requester)
  }

  fun disconnect() {
    connectedEndpoint = null
    _pendingGatewayTrust.value = null
    operatorSession.disconnect()
    nodeSession.disconnect()
    motionHandler.close()
  }

  fun handleCanvasA2UIActionFromWebView(payloadJson: String) {
    scope.launch {
      val trimmed = payloadJson.trim()
      if (trimmed.isEmpty()) return@launch

      val root =
        try {
          json.parseToJsonElement(trimmed).asObjectOrNull() ?: return@launch
        } catch (_: Throwable) {
          return@launch
        }

      val userActionObj = (root["userAction"] as? JsonObject) ?: root
      val actionId = (userActionObj["id"] as? JsonPrimitive)?.content?.trim().orEmpty().ifEmpty {
        java.util.UUID.randomUUID().toString()
      }
      val name = OpenClawCanvasA2UIAction.extractActionName(userActionObj) ?: return@launch

      val surfaceId =
        (userActionObj["surfaceId"] as? JsonPrimitive)?.content?.trim().orEmpty().ifEmpty { "main" }
      val sourceComponentId =
        (userActionObj["sourceComponentId"] as? JsonPrimitive)?.content?.trim().orEmpty().ifEmpty { "-" }
      val contextJson = (userActionObj["context"] as? JsonObject)?.toString()

      val sessionKey = resolveMainSessionKey()
      val message =
        OpenClawCanvasA2UIAction.formatAgentMessage(
          actionName = name,
          sessionKey = sessionKey,
          surfaceId = surfaceId,
          sourceComponentId = sourceComponentId,
          host = displayName.value,
          instanceId = instanceId.value.lowercase(),
          contextJson = contextJson,
        )

      val connected = nodeConnected
      var error: String? = null
      if (connected) {
        val sent = nodeSession.sendNodeEvent(
          event = "agent.request",
          payloadJson =
            buildJsonObject {
              put("message", JsonPrimitive(message))
              put("sessionKey", JsonPrimitive(sessionKey))
              put("thinking", JsonPrimitive("low"))
              put("deliver", JsonPrimitive(false))
              put("key", JsonPrimitive(actionId))
            }.toString(),
        )
        if (!sent) error = "send failed"
      } else {
        error = "gateway not connected"
      }

      try {
        canvas.eval(
          OpenClawCanvasA2UIAction.jsDispatchA2UIActionStatus(
            actionId = actionId,
            ok = connected && error == null,
            error = error,
          ),
        )
      } catch (_: Throwable) {
        // ignore
      }
    }
  }

  fun loadChat(sessionKey: String) {
    val key = sessionKey.trim().ifEmpty { resolveMainSessionKey() }
    chat.load(key)
  }

  fun refreshChat() {
    chat.refresh()
  }

  fun refreshChatSessions(limit: Int? = null) {
    chat.refreshSessions(limit = limit)
  }

  /** Create or rename a session on the gateway via sessions.patch. */
  suspend fun patchChatSession(key: String, label: String): Boolean {
    return try {
      val params = buildJsonObject {
        put("key", JsonPrimitive(key))
        put("label", JsonPrimitive(label))
      }
      operatorSession.request("sessions.patch", params.toString())
      true
    } catch (_: Throwable) { false }
  }

  /** Delete a session on the gateway via sessions.delete. */
  suspend fun deleteChatSession(key: String): Boolean {
    return try {
      val params = buildJsonObject { put("key", JsonPrimitive(key)) }
      val result = operatorSession.request("sessions.delete", params.toString())
      android.util.Log.d("NodeRuntime", "deleteChatSession result for key $key: $result")
      true
    } catch (e: Throwable) { 
      android.util.Log.e("NodeRuntime", "deleteChatSession error for key $key", e)
      false 
    }
  }

  fun setChatThinkingLevel(level: String) {
    chat.setThinkingLevel(level)
  }

  fun switchChatSession(sessionKey: String) {
    chat.switchSession(sessionKey)
  }

  fun abortChat() {
    chat.abort()
  }

  fun sendChat(message: String, thinking: String, attachments: List<OutgoingAttachment>, modelName: String? = null) {
    chat.sendMessage(message = message, thinkingLevel = thinking, attachments = attachments, modelName = modelName)
  }

  private fun handleGatewayEvent(event: String, payloadJson: String?) {
    // Extra subscribers run first and never consume the event: the Talk relay needs both
    // `talk.event` (its own stream) and `chat` (agent-consult run results), and `chat` must still
    // reach ChatController. Keep these handlers non-blocking — they run on the frame pump, which
    // is serialized so relay audio stays in order.
    for (listener in gatewayEventListeners) {
      try {
        listener(event, payloadJson)
      } catch (err: Throwable) {
        android.util.Log.w("NodeRuntime", "gateway event listener failed: ${err.message}")
      }
    }

    if (event == "voicewake.changed") {
      gatewayEventHandler.handleVoiceWakeChangedEvent(payloadJson)
      return
    }

    chat.handleGatewayEvent(event, payloadJson)
  }

  private val gatewayEventListeners = java.util.concurrent.CopyOnWriteArrayList<(String, String?) -> Unit>()

  /** Subscribe to raw gateway events on the operator socket. Returns a handle that unsubscribes. */
  fun addGatewayEventListener(listener: (event: String, payloadJson: String?) -> Unit): AutoCloseable {
    gatewayEventListeners.add(listener)
    return AutoCloseable { gatewayEventListeners.remove(listener) }
  }

  private suspend fun fetchAgentList() {
    if (!_isConnected.value) return
    try {
      val res = operatorSession.request("agents.list", "{}")
      val root = res.let { Json.parseToJsonElement(it) } as? JsonObject ?: return
      val defaultId = (root["defaultId"] as? JsonPrimitive)?.content ?: "main"
      val agentsArray = root["agents"] as? JsonArray ?: run {
        _agentList.value = AgentListResult(defaultId, emptyList())
        return
      }
      val agents = agentsArray.mapNotNull { item ->
        val obj = item as? JsonObject ?: return@mapNotNull null
        val id = (obj["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
        val name = (obj["name"] as? JsonPrimitive)?.content ?: id
        AgentInfo(id = id, name = name)
      }
      _agentList.value = AgentListResult(defaultId = defaultId, agents = agents)
      _missingScopeError.value = null
    } catch (e: Throwable) {
      val msg = e.message ?: ""
      if (msg.contains("missing scope", ignoreCase = true)) {
        _missingScopeError.value = msg
      }
    }
  }

  /** Refresh the available agent list from the gateway (fire-and-forget). */
  fun refreshAgentList() {
    scope.launch {
      fetchAgentList()
      updateHomeCanvasState()
    }
  }

  suspend fun requestGateway(method: String, paramsJson: String = "{}", timeoutMs: Long = 15_000): String {
    return operatorSession.request(method, paramsJson, timeoutMs)
  }

  /** Fire-and-forget operator RPC. See [GatewaySession.sendNoWait]; used for the Talk audio uplink. */
  suspend fun sendGatewayNoWait(method: String, paramsJson: String): Boolean {
    return operatorSession.sendNoWait(method, paramsJson)
  }

  /**
   * Run [block] on the app-lifetime scope, so teardown work (closing a Talk relay session, sending
   * telemetry) still completes after the caller's own scope is cancelled.
   */
  fun launchDetached(block: suspend () -> Unit) {
    scope.launch {
      try {
        block()
      } catch (err: Throwable) {
        android.util.Log.w("NodeRuntime", "detached task failed: ${err.message}")
      }
    }
  }

  /** Fire-and-forget on the app-lifetime scope, so it survives the voice dialog closing mid-send. */
  fun reportVoiceTimings(reportJson: String) {
    scope.launch {
      try {
        val result = operatorSession.request("voicetraces.report", reportJson)
        android.util.Log.i("NodeRuntime", "voicetraces.report sent: $reportJson -> $result")
      } catch (e: Throwable) {
        android.util.Log.w("NodeRuntime", "voicetraces.report failed: ${e.message}")
      }
    }
  }

  /** Push current gateway + agent state into the home canvas so it survives reconnects and restarts. */
  private fun updateHomeCanvasState() {
    val connected = _isConnected.value
    val name = _serverName.value
    val address = _remoteAddress.value
    val agentResult = _agentList.value
    val activeKey = _mainSessionKey.value

    val agentsJson = agentResult?.agents?.joinToString(",") { agent ->
      """{"id":${org.json.JSONObject.quote(agent.id)},"name":${org.json.JSONObject.quote(agent.name)}}"""
    } ?: ""
    val defaultId = agentResult?.defaultId ?: activeKey
    val activeAgentName = agentResult?.agents?.firstOrNull { it.id == activeKey }?.name ?: activeKey
    val nameJson = name?.let { org.json.JSONObject.quote(it) } ?: "null"
    val addressJson = address?.let { org.json.JSONObject.quote(it) } ?: "null"
    val payload = """{"connected":$connected,"serverName":$nameJson,"remoteAddress":$addressJson,"defaultAgentId":${org.json.JSONObject.quote(defaultId)},"activeAgentId":${org.json.JSONObject.quote(activeKey)},"activeAgentName":${org.json.JSONObject.quote(activeAgentName)},"agents":[$agentsJson]}"""
    canvas.updateHomeCanvasState(if (connected || agentResult != null) payload else null)
  }

  /** Refresh the home canvas overview if the gateway is currently connected. */
  fun refreshHomeCanvasOverviewIfConnected() {
    if (!_isConnected.value) return
    scope.launch {
      fetchAgentList()
      updateHomeCanvasState()
    }
  }

  private suspend fun refreshBrandingFromGateway() {
    if (!_isConnected.value) return
    try {
      val res = operatorSession.request("config.get", "{}")
      val root = json.parseToJsonElement(res).asObjectOrNull()
      val config = root?.get("config").asObjectOrNull()
      val sessionCfg = config?.get("session").asObjectOrNull()
      val mainKey = normalizeMainKey(sessionCfg?.get("mainKey").asStringOrNull())
      applyMainSessionKey(mainKey)
    } catch (_: Throwable) {
      // ignore
    }
  }

  private fun triggerCameraFlash() {
    // Token is used as a pulse trigger; value doesn't matter as long as it changes.
    _cameraFlashToken.value = SystemClock.elapsedRealtimeNanos()
  }

  private fun showCameraHud(message: String, kind: CameraHudKind, autoHideMs: Long? = null) {
    val token = cameraHudSeq.incrementAndGet()
    _cameraHud.value = CameraHudState(token = token, kind = kind, message = message)

    if (autoHideMs != null && autoHideMs > 0) {
      scope.launch {
        delay(autoHideMs)
        if (_cameraHud.value?.token == token) _cameraHud.value = null
      }
    }
  }

}
