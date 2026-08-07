package com.openclaw.assistant.talk

import org.json.JSONObject

/**
 * The openclaw-side Talk settings this mode obeys, as returned by `talk.config`.
 *
 * Shown read-only in the app so it is obvious which knobs move to the server once OpenClaw Talk is
 * selected. Parsed defensively: the gateway omits sections that are not configured, and redacts
 * secrets — this never asks for `includeSecrets`, so no API key ever reaches the device.
 */
data class TalkConfigSummary(
  /** Active TTS provider on the server, e.g. "elevenlabs". */
  val provider: String? = null,
  val voiceId: String? = null,
  val modelId: String? = null,
  val languageCode: String? = null,
  /** Realtime session shape the gateway will hand back: mode/transport/brain. */
  val realtimeMode: String? = null,
  val realtimeTransport: String? = null,
  /** Agent that answers voice consults when the client does not pin a session. */
  val agentId: String? = null,
  val consultThinkingLevel: String? = null,
  val consultFastMode: Boolean? = null,
  /** Server-side barge-in: interrupts playback when the caller starts speaking. */
  val interruptOnSpeech: Boolean? = null,
  val silenceTimeoutMs: Int? = null,
) {
  val isEmpty: Boolean
    get() =
      provider == null &&
        voiceId == null &&
        modelId == null &&
        realtimeMode == null &&
        realtimeTransport == null &&
        agentId == null &&
        interruptOnSpeech == null

  companion object {
    fun parse(responseJson: String): TalkConfigSummary? {
      val root = runCatching { JSONObject(responseJson) }.getOrNull() ?: return null
      val talk = root.optJSONObject("config")?.optJSONObject("talk") ?: return null
      val realtime = talk.optJSONObject("realtime")
      val resolved = talk.optJSONObject("resolved")
      val provider =
        talk.optString("provider").takeIf { it.isNotBlank() }
          ?: resolved?.optString("provider")?.takeIf { it.isNotBlank() }
      val providerConfig =
        resolved?.optJSONObject("config")
          ?: provider?.let { talk.optJSONObject("providers")?.optJSONObject(it) }

      val summary =
        TalkConfigSummary(
          provider = provider,
          voiceId =
            providerConfig?.optString("voiceId")?.takeIf { it.isNotBlank() }
              ?: providerConfig?.optString("speakerVoiceId")?.takeIf { it.isNotBlank() },
          modelId = providerConfig?.optString("modelId")?.takeIf { it.isNotBlank() },
          languageCode = providerConfig?.optString("languageCode")?.takeIf { it.isNotBlank() },
          realtimeMode = realtime?.optString("mode")?.takeIf { it.isNotBlank() },
          realtimeTransport = realtime?.optString("transport")?.takeIf { it.isNotBlank() },
          agentId = talk.optString("agentId").takeIf { it.isNotBlank() },
          consultThinkingLevel = talk.optString("consultThinkingLevel").takeIf { it.isNotBlank() },
          consultFastMode = if (talk.has("consultFastMode")) talk.optBoolean("consultFastMode") else null,
          interruptOnSpeech = if (talk.has("interruptOnSpeech")) talk.optBoolean("interruptOnSpeech") else null,
          silenceTimeoutMs = talk.optInt("silenceTimeoutMs", 0).takeIf { it > 0 },
        )
      return if (summary.isEmpty) null else summary
    }
  }
}
