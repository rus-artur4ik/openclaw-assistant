package com.openclaw.assistant.talk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import com.openclaw.assistant.node.NodeRuntime
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Runs one OpenClaw Talk conversation over the gateway relay.
 *
 * Division of labour on the wire (verified against the gateway and the Control UI's own relay
 * client): the server does STT, barge-in detection and TTS, but it does **not** run the agent. It
 * asks us to, via an `openclaw_agent_consult` tool call, and only speaks once we hand the answer
 * back. The full turn is:
 *
 * ```
 *   appendAudio ─► transcript(user, final)
 *               ─► toolCall(openclaw_agent_consult, forced)
 *   ◄─ submitToolResult(status=working, willContinue)     // server speaks a filler
 *   ◄─ talk.client.toolCall                               // starts the agent run
 *      … wait for chat{runId, state:final} …
 *   ◄─ submitToolResult(result)                           // server synthesizes and streams audio
 *               ─► audio × N ─► audioDone
 * ```
 *
 * The controller owns the microphone, the speaker and the wake-word handover for the whole session,
 * and reports progress through [state] so the caller's UI does not need to know any of this.
 */
class TalkRelayController(
  private val context: Context,
  private val runtime: NodeRuntime,
  private val callbacks: Callbacks,
) {
  companion object {
    private const val TAG = "TalkRelayController"

    /** Vosk's own watchdog re-arms wake-word capture after 5 minutes; re-pause well before that. */
    private const val HOTWORD_KEEP_PAUSED_INTERVAL_MS = 120_000L

    /** How long to wait for the agent run behind a consult before giving up on the turn. */
    private const val CONSULT_TIMEOUT_MS = 120_000L

    /** Guard against a relay that accepts audio but never signals readiness. */
    private const val READY_TIMEOUT_MS = 15_000L

    /** ~2 seconds of 40 ms chunks in flight before the uplink starts shedding the oldest audio. */
    private const val UPLINK_QUEUE_CHUNKS = 50

    /** Only the create round-trip is buffered, so this never needs to be large. */
    private const val MAX_BUFFERED_EVENTS = 64

    private const val CONSULT_WORKING_MESSAGE =
      "Tell the person briefly that you are checking, then wait for the final OpenClaw result " +
        "before answering with the actual result."
  }

  /** Coarse conversation phase; the caller maps it onto its own UI state. */
  enum class Phase {
    CONNECTING,
    LISTENING,
    THINKING,
    SPEAKING,
    CLOSED,
  }

  interface Callbacks {
    fun onPhase(phase: Phase)

    /** Partial and final user speech, plus the assistant's spoken text. */
    fun onTranscript(role: String, text: String, final: Boolean)

    fun onAudioLevel(level: Float)

    /**
     * Terminal failure. [recoverable] means the caller may retry on the local engine — the relay
     * never started or died before producing anything useful.
     */
    fun onFailure(message: String, recoverable: Boolean)

    fun onClosed()
  }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val client = TalkRelayClient(runtime)
  private val consultJobs = ConcurrentHashMap<String, Job>()
  private val completedToolCalls = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
  private val pendingRuns = ConcurrentHashMap<String, CompletableDeferred<TalkRelayProtocol.ChatRunOutcome>>()

  private val _state = MutableStateFlow(Phase.CONNECTING)
  val state: StateFlow<Phase> = _state.asStateFlow()

  private var eventSubscription: AutoCloseable? = null
  private var session: TalkRelayProtocol.Session? = null
  private var player: PcmStreamPlayer? = null
  private var streamer: MicPcmStreamer? = null
  private var micJob: Job? = null
  private var uplinkJob: Job? = null
  private var previousAudioMode: Int? = null

  @Volatile private var closed = false
  @Volatile private var ready = false
  @Volatile private var startGeneration = 0

  /** Set after a client-initiated cancel; see [cancelSpeech]. Cleared when a new utterance starts. */
  @Volatile private var suppressAudio = false

  /** Events seen between subscribing and learning our relay id; replayed by [drainBufferedEvents]. */
  private val bufferedEvents = ArrayList<TalkRelayProtocol.Event>()

  @Volatile private var boundSessionKey: String = ""

  @Volatile private var failureReported = false

  /** True once the relay produced anything the user could hear or read — fallback is no longer clean. */
  @Volatile private var producedOutput = false

  val isRunning: Boolean
    get() = !closed && session != null

  /**
   * Diagnostic: whether full-duplex echo cancellation is really in effect.
   *
   * Both halves are required — the AEC effect object attaches happily even when the platform never
   * entered communication mode, in which case it has no playback reference to subtract.
   */
  val echoCancellationEnabled: Boolean
    get() = streamer?.echoCancellationEnabled == true && communicationAudioMode

  @Volatile private var communicationAudioMode = false

  /**
   * Opens a relay session and starts streaming. Throws when the session cannot be created, which the
   * caller treats as "fall back to the on-device engine".
   *
   * @param sessionKey chat session the voice conversation belongs to. The consult run is a normal
   *   `chat.send` into this session, so the exchange lands in the same history the app shows.
   */
  suspend fun start(sessionKey: String) {
    check(!closed) { "TalkRelayController is single-use" }
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
      != PackageManager.PERMISSION_GRANTED
    ) {
      throw MicrophonePermissionException()
    }
    // `talk.client.toolCall` rejects an empty session key, so a turn could never complete without
    // one. Pin it here and reuse it: the gateway binds the key to the relay on first use and
    // rejects any later mismatch, so re-reading a live StateFlow mid-conversation would break it.
    require(sessionKey.isNotBlank()) { "Talk relay requires a chat session key" }
    boundSessionKey = sessionKey
    startGeneration = runtime.operatorConnectionGeneration.value

    // Subscribe before creating: `ready` can arrive as soon as the STT socket connects, which may
    // beat the create response back to us.
    eventSubscription = runtime.addGatewayEventListener(::onGatewayEvent)

    val created =
      try {
        client.createSession(sessionKey)
      } catch (err: Throwable) {
        eventSubscription?.let { runCatching { it.close() } }
        eventSubscription = null
        throw err
      }
    if (!created.usesPcm16) {
      runCatching { client.closeSession(created.relaySessionId) }
      throw IllegalStateException(
        "Talk relay negotiated ${created.inputEncoding}/${created.outputEncoding}; only pcm16 is supported"
      )
    }
    session = created
    Log.i(
      TAG,
      "relay session ${created.relaySessionId} engine=${created.engineLabel} provider=${created.provider} " +
        "in=${created.inputSampleRateHz}Hz out=${created.outputSampleRateHz}Hz",
    )
    drainBufferedEvents(created.relaySessionId)

    // The caller resumes on the main thread after createSession; AudioManager.setMode and
    // AudioTrack construction both touch the audio HAL, so keep them off it.
    withContext(Dispatchers.IO) {
      enterCommunicationAudioMode()
      startPlayback(created)
    }
    startMicrophone(created)
    startWakeWordHold()
    watchConnection()
    watchReadiness()
    setPhase(Phase.LISTENING)
  }

  /**
   * Manual barge-in: drop buffered audio and tell the gateway to stop speaking. The microphone stays
   * open, so the conversation continues — unlike the on-device engine, there is nothing to restart.
   *
   * `talk.session.cancelOutput` only emits `clear`; on this deployment it does not actually cancel
   * the running synthesis job, so chunks for the cancelled utterance keep arriving. Suppress them
   * locally until the next utterance announces itself.
   */
  fun cancelSpeech() {
    val active = session ?: return
    suppressAudio = true
    player?.flush()
    setPhase(Phase.LISTENING)
    scope.launch { runCatching { client.cancelOutput(active.relaySessionId, "barge-in") } }
  }

  /** Stops everything and closes the relay session. Safe to call more than once. */
  fun stop() {
    if (closed) return
    closed = true
    val active = session
    session = null

    micJob?.cancel()
    micJob = null
    uplinkJob?.cancel()
    uplinkJob = null
    streamer?.stop()
    streamer = null
    player?.release()
    player = null
    eventSubscription?.let { runCatching { it.close() } }
    eventSubscription = null
    for (job in consultJobs.values) job.cancel()
    consultJobs.clear()
    for (deferred in pendingRuns.values) deferred.cancel()
    pendingRuns.clear()
    restoreAudioMode()

    if (active != null) {
      // Detached from `scope` so the close request still goes out after cancellation.
      runtime.launchDetached { client.closeSession(active.relaySessionId) }
    }
    scope.cancel()
    setPhase(Phase.CLOSED)
    // onFailure is the terminal callback in the failure path; firing onClosed first as well would
    // make the host flash an idle state before the error.
    if (!failureReported) callbacks.onClosed()
  }

  class MicrophonePermissionException : Exception("RECORD_AUDIO permission not granted")

  // ---------------------------------------------------------------- audio

  /**
   * Built eagerly rather than on the first `audio` event: [PcmStreamPlayer.enqueue] drops chunks
   * until the track exists, and the gateway starts streaming as soon as it has an answer.
   */
  private fun startPlayback(session: TalkRelayProtocol.Session) {
    val created =
      PcmStreamPlayer(
        sampleRateHz = session.outputSampleRateHz,
        scope = scope,
        // Fires only once the gateway has sent the whole utterance AND AudioTrack has rendered it,
        // so the UI leaves SPEAKING when the user stops hearing the answer, not when the last chunk
        // was copied into the buffer.
        onUtteranceFinished = { if (!closed && _state.value == Phase.SPEAKING) setPhase(Phase.LISTENING) },
      )
    created.start()
    player = created
  }

  private fun startMicrophone(session: TalkRelayProtocol.Session) {
    val created = MicPcmStreamer(targetSampleRateHz = session.inputSampleRateHz)
    streamer = created
    val startedAtMs = System.currentTimeMillis()

    // Bounded with DROP_OLDEST: if the uplink stalls, dropping the oldest audio is the right
    // failure mode for a live conversation — an unbounded queue would replay stale speech minutes
    // later and pin memory.
    val uplink =
      kotlinx.coroutines.channels.Channel<Pair<ByteArray, Long>>(
        capacity = UPLINK_QUEUE_CHUNKS,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
      )

    uplinkJob =
      scope.launch {
        for ((pcm, timestampMs) in uplink) {
          if (closed) break
          val audioBase64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
          val delivered = client.appendAudio(session.relaySessionId, audioBase64, timestampMs)
          if (!delivered && !closed) {
            created.stop()
            fail("Gateway connection lost", recoverable = !producedOutput)
            break
          }
        }
      }

    micJob =
      scope.launch {
        try {
          created.run(
            onChunk = { pcm ->
              if (!closed) uplink.trySend(pcm to (System.currentTimeMillis() - startedAtMs))
            },
            onLevel = { level -> if (!closed) callbacks.onAudioLevel(level) },
          )
        } catch (err: MicPcmStreamer.UnavailableException) {
          fail(err.message ?: "Microphone unavailable", recoverable = !producedOutput)
        } catch (err: kotlinx.coroutines.CancellationException) {
          throw err
        } catch (err: Throwable) {
          Log.w(TAG, "microphone pump failed", err)
          fail(err.message ?: "Microphone failed", recoverable = !producedOutput)
        } finally {
          uplink.close()
        }
      }
  }

  /**
   * Full-duplex playback while the mic is open only works with the platform in communication mode;
   * otherwise the AEC has no reference signal and the server hears the assistant barge in on itself.
   */
  private fun enterCommunicationAudioMode() {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    previousAudioMode = audioManager.mode
    runCatching { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION }
    // AudioService drops the write silently when MODIFY_AUDIO_SETTINGS is missing or a call owns the
    // mode — no exception, so read it back. Without communication mode the echo canceller has no
    // playback reference and the server VAD interrupts the assistant's own speech.
    communicationAudioMode = audioManager.mode == AudioManager.MODE_IN_COMMUNICATION
    if (!communicationAudioMode) {
      Log.w(TAG, "Audio mode stayed ${audioManager.mode}; echo cancellation will be unreliable")
    }
  }

  private fun restoreAudioMode() {
    communicationAudioMode = false
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
    previousAudioMode?.let { mode -> runCatching { audioManager.mode = mode } }
    previousAudioMode = null
  }

  // ------------------------------------------------------------ wake word

  /**
   * Vosk owns a second AudioRecord and would fight us for the microphone, and a wake word spoken
   * mid-conversation would spawn a second assistant session. Barge-in is the server's job here, so
   * hold the wake-word service down for the whole conversation.
   */
  private fun startWakeWordHold() {
    scope.launch {
      while (!closed) {
        val intent = android.content.Intent("com.openclaw.assistant.ACTION_PAUSE_HOTWORD")
        intent.setPackage(context.packageName)
        context.sendBroadcast(intent)
        delay(HOTWORD_KEEP_PAUSED_INTERVAL_MS)
      }
    }
  }

  // --------------------------------------------------------------- events

  private fun onGatewayEvent(event: String, payloadJson: String?) {
    if (closed) return
    when (event) {
      TalkRelayProtocol.EVENT_NAME -> handleRelayEvent(payloadJson)
      "chat" -> handleChatEvent(payloadJson)
    }
  }

  private fun handleRelayEvent(payloadJson: String?) {
    val parsed = TalkRelayProtocol.parseEvent(payloadJson) ?: return
    val active = session
    if (active == null) {
      // Still waiting for the create response; hold onto anything that might be ours.
      synchronized(bufferedEvents) {
        if (bufferedEvents.size < MAX_BUFFERED_EVENTS) bufferedEvents.add(parsed)
      }
      return
    }
    if (parsed.relaySessionId != active.relaySessionId) return
    dispatchRelayEvent(active, parsed)
  }

  private fun drainBufferedEvents(relaySessionId: String) {
    val buffered = synchronized(bufferedEvents) { bufferedEvents.toList().also { bufferedEvents.clear() } }
    val active = session ?: return
    for (event in buffered) {
      if (event.relaySessionId == relaySessionId) dispatchRelayEvent(active, event)
    }
  }

  private fun dispatchRelayEvent(
    active: TalkRelayProtocol.Session,
    parsed: TalkRelayProtocol.Event,
  ) {
    when (parsed) {
      is TalkRelayProtocol.Event.Ready -> {
        ready = true
        setPhase(Phase.LISTENING)
      }
      is TalkRelayProtocol.Event.Transcript -> {
        if (parsed.text.isNotBlank()) {
          producedOutput = true
          callbacks.onTranscript(parsed.role, parsed.text, parsed.final)
        }
        when {
          parsed.role == "user" && parsed.final -> setPhase(Phase.THINKING)
          // The cascade emits the assistant transcript immediately before synthesizing, so this is
          // the start of a new utterance and any suppression from a manual cancel is over.
          parsed.role == "assistant" && parsed.final -> suppressAudio = false
        }
      }
      is TalkRelayProtocol.Event.Audio -> {
        if (suppressAudio) return
        producedOutput = true
        setPhase(Phase.SPEAKING)
        val pcm = runCatching { Base64.decode(parsed.audioBase64, Base64.DEFAULT) }.getOrNull()
        if (pcm != null) player?.enqueue(pcm)
      }
      is TalkRelayProtocol.Event.Clear -> {
        // Server-side barge-in genuinely stops the synthesis job, so no local suppression is needed.
        player?.flush()
        setPhase(Phase.LISTENING)
      }
      is TalkRelayProtocol.Event.AudioDone -> {
        suppressAudio = false
        // Only now is the utterance fully sent; the player waits for AudioTrack to actually render
        // what it holds before reporting the turn finished.
        player?.markUtteranceComplete()
      }
      is TalkRelayProtocol.Event.ToolCall -> handleToolCall(active, parsed)
      is TalkRelayProtocol.Event.ToolResult -> {
        if (parsed.final) {
          completedToolCalls.add(parsed.callId)
          consultJobs.remove(parsed.callId)?.cancel()
        }
      }
      is TalkRelayProtocol.Event.Failure -> fail(parsed.message, recoverable = !producedOutput)
      is TalkRelayProtocol.Event.Closed -> {
        if (parsed.reason == "error") {
          fail("Talk relay closed unexpectedly", recoverable = !producedOutput)
        } else {
          stop()
        }
      }
      is TalkRelayProtocol.Event.Unknown -> Unit
    }
  }

  private fun handleChatEvent(payloadJson: String?) {
    if (pendingRuns.isEmpty()) return
    for ((runId, deferred) in pendingRuns) {
      val outcome = TalkRelayProtocol.parseChatFinalText(payloadJson, runId) ?: continue
      deferred.complete(outcome)
      pendingRuns.remove(runId)
    }
  }

  // ----------------------------------------------------------- consult loop

  private fun handleToolCall(
    active: TalkRelayProtocol.Session,
    call: TalkRelayProtocol.Event.ToolCall,
  ) {
    if (completedToolCalls.contains(call.callId) || consultJobs.containsKey(call.callId)) return

    if (call.name != TalkRelayProtocol.AGENT_CONSULT_TOOL) {
      // Agent-control (steer/cancel) is a Control-UI affordance with no equivalent here; decline it
      // explicitly so the relay stops waiting instead of stalling the turn.
      scope.launch {
        runCatching {
          client.submitToolResult(
            active.relaySessionId,
            call.callId,
            JSONObject().put("error", "Tool \"${call.name}\" is not available in the Android client"),
          )
        }
      }
      return
    }

    setPhase(Phase.THINKING)
    val job =
      scope.launch {
        try {
          if (call.forced) {
            // Acknowledging with willContinue keeps the call open and makes the gateway speak a
            // filler phrase, so the user is not left in silence while the agent thinks.
            runCatching {
              client.submitToolResult(
                active.relaySessionId,
                call.callId,
                JSONObject()
                  .put("status", "working")
                  .put("tool", TalkRelayProtocol.AGENT_CONSULT_TOOL)
                  .put("message", CONSULT_WORKING_MESSAGE),
                willContinue = true,
              )
            }
          }
          val runId =
            client.startAgentConsult(boundSessionKey, active.relaySessionId, call.callId, call.argsJson)
          val deferred = CompletableDeferred<TalkRelayProtocol.ChatRunOutcome>()
          pendingRuns[runId] = deferred
          val outcome =
            try {
              withTimeoutOrNull(CONSULT_TIMEOUT_MS) { deferred.await() }
            } finally {
              pendingRuns.remove(runId)
            }
          val result =
            when (outcome) {
              is TalkRelayProtocol.ChatRunOutcome.Final -> {
                // The gateway speaks the submitted text verbatim, so reduce the reply to its
                // speakable part first — otherwise the assistant reads its reasoning, the markdown
                // and then the [[tts:...]] directive on top. The full reply stays in chat history.
                val spoken =
                  com.openclaw.assistant.speech.TTSUtils
                    .stripMarkdownForSpeech(TalkRelayProtocol.extractSpokenText(outcome.text))
                    .trim()
                JSONObject().put("result", spoken.ifBlank { "OpenClaw finished with no text." })
              }
              is TalkRelayProtocol.ChatRunOutcome.Failed -> JSONObject().put("error", outcome.message)
              TalkRelayProtocol.ChatRunOutcome.Aborted -> JSONObject().put("error", "Cancelled")
              null -> {
                client.abortRun(boundSessionKey, runId)
                JSONObject().put("error", "OpenClaw took too long to answer")
              }
            }
          client.submitToolResult(active.relaySessionId, call.callId, result)
        } catch (err: kotlinx.coroutines.CancellationException) {
          throw err
        } catch (err: Throwable) {
          Log.w(TAG, "agent consult failed", err)
          runCatching {
            client.submitToolResult(
              active.relaySessionId,
              call.callId,
              JSONObject().put("error", err.message ?: "OpenClaw consult failed"),
            )
          }
        } finally {
          consultJobs.remove(call.callId)
        }
      }
    consultJobs[call.callId] = job
  }

  // -------------------------------------------------------------- watchdogs

  /** A relay session belongs to one gateway connection; a reconnect silently orphans it. */
  private fun watchConnection() {
    scope.launch {
      runtime.operatorConnectionGeneration.collect { generation ->
        if (generation != startGeneration && !closed) {
          fail("Gateway connection was reset", recoverable = !producedOutput)
        }
      }
    }
  }

  private fun watchReadiness() {
    scope.launch {
      delay(READY_TIMEOUT_MS)
      if (!ready && !closed) fail("Talk relay did not become ready", recoverable = !producedOutput)
    }
  }

  private fun setPhase(phase: Phase) {
    if (_state.value == phase) return
    _state.value = phase
    callbacks.onPhase(phase)
  }

  private fun fail(message: String, recoverable: Boolean) {
    if (closed) return
    Log.w(TAG, "relay failed: $message (recoverable=$recoverable)")
    failureReported = true
    stop()
    callbacks.onFailure(message, recoverable)
  }
}
