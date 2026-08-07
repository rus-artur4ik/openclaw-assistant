package com.openclaw.assistant.talk

import org.json.JSONObject

/**
 * Wire model for OpenClaw's gateway-relay Talk sessions.
 *
 * The gateway owns the whole voice loop except the agent consult: it runs STT on the uplink audio,
 * emits transcripts, asks the client to run `openclaw_agent_consult` as a tool call, and synthesizes
 * whatever the client hands back. Everything here is pure parsing so it can be unit tested without a
 * gateway; the RPC calls live in [TalkRelayClient] and the audio in [TalkRelayController].
 */
object TalkRelayProtocol {
  const val EVENT_NAME = "talk.event"

  const val METHOD_CREATE = "talk.session.create"
  const val METHOD_APPEND_AUDIO = "talk.session.appendAudio"
  const val METHOD_CANCEL_OUTPUT = "talk.session.cancelOutput"
  const val METHOD_SUBMIT_TOOL_RESULT = "talk.session.submitToolResult"
  const val METHOD_CLOSE = "talk.session.close"
  const val METHOD_CLIENT_TOOL_CALL = "talk.client.toolCall"
  const val METHOD_CONFIG = "talk.config"

  const val AGENT_CONSULT_TOOL = "openclaw_agent_consult"
  const val AGENT_CONTROL_TOOL = "openclaw_agent_control"

  /** Server default when neither `mode` nor `transport` is given; sent explicitly for clarity. */
  const val MODE_REALTIME = "realtime"
  const val TRANSPORT_GATEWAY_RELAY = "gateway-relay"
  const val BRAIN_AGENT_CONSULT = "agent-consult"

  /** Result of [METHOD_CREATE] for a gateway-relay session. */
  data class Session(
    val relaySessionId: String,
    val provider: String?,
    val mode: String?,
    val transport: String?,
    val inputEncoding: String,
    val inputSampleRateHz: Int,
    val outputEncoding: String,
    val outputSampleRateHz: Int,
    /** Unix seconds. The gateway hard-closes the relay 30 minutes after create. */
    val expiresAtSeconds: Long?,
    /**
     * Only the provider-native realtime path (GPT Realtime) returns this. The STT-TTS cascade this
     * deployment runs omits it. Both speak the same relay protocol, so this is diagnostics only —
     * `mode` always echoes the request and `provider` is the STT provider id in both cases.
     */
    val voiceSessionId: String? = null,
  ) {
    val usesPcm16: Boolean
      get() = inputEncoding == "pcm16" && outputEncoding == "pcm16"

    /** Best-effort label for logs and diagnostics. */
    val engineLabel: String
      get() = if (voiceSessionId != null) "provider-realtime" else "stt-tts cascade"
  }

  /** A decoded `talk.event` payload. Unknown types map to [Event.Unknown] and are ignored. */
  sealed interface Event {
    val relaySessionId: String

    data class Ready(override val relaySessionId: String) : Event

    data class Transcript(
      override val relaySessionId: String,
      val role: String,
      val text: String,
      val final: Boolean,
    ) : Event

    data class Audio(override val relaySessionId: String, val audioBase64: String) : Event

    /** Output was superseded or barged in on; drop everything buffered for playback. */
    data class Clear(override val relaySessionId: String) : Event

    data class AudioDone(override val relaySessionId: String) : Event

    data class ToolCall(
      override val relaySessionId: String,
      val callId: String,
      val name: String,
      val argsJson: String,
      val forced: Boolean,
    ) : Event

    data class ToolResult(
      override val relaySessionId: String,
      val callId: String,
      val final: Boolean,
    ) : Event

    data class Failure(override val relaySessionId: String, val message: String) : Event

    data class Closed(override val relaySessionId: String, val reason: String?) : Event

    data class Unknown(override val relaySessionId: String, val type: String) : Event
  }

  fun parseSession(payloadJson: String): Session? {
    val root = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return null
    val relaySessionId =
      root.optString("relaySessionId").takeIf { it.isNotBlank() }
        ?: root.optString("sessionId").takeIf { it.isNotBlank() }
        ?: return null
    val audio = root.optJSONObject("audio")
    return Session(
      relaySessionId = relaySessionId,
      provider = root.optString("provider").takeIf { it.isNotBlank() },
      mode = root.optString("mode").takeIf { it.isNotBlank() },
      transport = root.optString("transport").takeIf { it.isNotBlank() },
      inputEncoding = audio?.optString("inputEncoding").orEmpty().ifBlank { "pcm16" },
      inputSampleRateHz = audio?.optInt("inputSampleRateHz", 0)?.takeIf { it > 0 } ?: 24_000,
      outputEncoding = audio?.optString("outputEncoding").orEmpty().ifBlank { "pcm16" },
      outputSampleRateHz = audio?.optInt("outputSampleRateHz", 0)?.takeIf { it > 0 } ?: 24_000,
      expiresAtSeconds = root.optLong("expiresAt", 0L).takeIf { it > 0L },
      voiceSessionId = root.optString("voiceSessionId").takeIf { it.isNotBlank() },
    )
  }

  fun parseEvent(payloadJson: String?): Event? {
    if (payloadJson.isNullOrBlank()) return null
    val root = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return null
    val relaySessionId = root.optString("relaySessionId").takeIf { it.isNotBlank() } ?: return null
    return when (val type = root.optString("type")) {
      "ready" -> Event.Ready(relaySessionId)
      "transcript" ->
        Event.Transcript(
          relaySessionId = relaySessionId,
          role = root.optString("role").takeIf { it.isNotBlank() } ?: "assistant",
          text = root.optString("text"),
          final = root.optBoolean("final", false),
        )
      "audio" -> {
        val audioBase64 = root.optString("audioBase64")
        if (audioBase64.isBlank()) null else Event.Audio(relaySessionId, audioBase64)
      }
      "clear" -> Event.Clear(relaySessionId)
      "audioDone" -> Event.AudioDone(relaySessionId)
      "toolCall" -> {
        val callId = root.optString("callId").takeIf { it.isNotBlank() } ?: return null
        val name = root.optString("name").takeIf { it.isNotBlank() } ?: return null
        Event.ToolCall(
          relaySessionId = relaySessionId,
          callId = callId,
          name = name,
          argsJson = root.opt("args")?.takeIf { it != JSONObject.NULL }?.toString() ?: "{}",
          forced = root.optBoolean("forced", false),
        )
      }
      "toolResult" -> {
        val callId = root.optString("callId").takeIf { it.isNotBlank() } ?: return null
        Event.ToolResult(relaySessionId, callId, final = isFinalToolResult(root))
      }
      "error" ->
        Event.Failure(
          relaySessionId,
          root.optString("message").takeIf { it.isNotBlank() } ?: "Talk relay failed",
        )
      "close" -> Event.Closed(relaySessionId, root.optString("reason").takeIf { it.isNotBlank() })
      else -> Event.Unknown(relaySessionId, type)
    }
  }

  /**
   * `toolResult` also carries progress updates: the tool is still running when the nested talkEvent
   * is `tool.progress`, or a `tool.result` explicitly marked non-final.
   */
  private fun isFinalToolResult(root: JSONObject): Boolean {
    val talkEvent = root.optJSONObject("talkEvent") ?: return true
    return when (talkEvent.optString("type")) {
      "tool.progress" -> false
      "tool.result" -> talkEvent.optBoolean("final", true)
      else -> true
    }
  }

  /** Extract the assistant text of a terminal `chat` event for [runId], or null if not ours/not done. */
  fun parseChatFinalText(payloadJson: String?, runId: String): ChatRunOutcome? {
    if (payloadJson.isNullOrBlank()) return null
    val root = runCatching { JSONObject(payloadJson) }.getOrNull() ?: return null
    if (root.optString("runId") != runId) return null
    return when (root.optString("state")) {
      "final" -> ChatRunOutcome.Final(extractMessageText(root.optJSONObject("message")))
      "aborted" -> ChatRunOutcome.Aborted
      "error" ->
        ChatRunOutcome.Failed(
          root.optString("errorMessage").takeIf { it.isNotBlank() } ?: "OpenClaw run failed",
        )
      else -> null
    }
  }

  sealed interface ChatRunOutcome {
    data class Final(val text: String) : ChatRunOutcome

    data object Aborted : ChatRunOutcome

    data class Failed(val message: String) : ChatRunOutcome
  }

  private fun extractMessageText(message: JSONObject?): String {
    if (message == null) return ""
    message.optString("text").takeIf { it.isNotBlank() }?.let { return it }
    val content = message.optJSONArray("content") ?: return ""
    val parts = mutableListOf<String>()
    for (i in 0 until content.length()) {
      val part = content.optJSONObject(i) ?: continue
      if (part.optString("type") != "text") continue
      part.optString("text").takeIf { it.isNotBlank() }?.let { parts.add(it) }
    }
    return parts.joinToString("\n").trim()
  }
}
