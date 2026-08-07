package com.openclaw.assistant.backend

import com.openclaw.assistant.data.SettingsRepository

/**
 * Decides which voice engine actually runs a conversation.
 *
 * The stored preference is only a wish: OpenClaw Talk needs a healthy openclaw-gateway backend and a
 * voice turn that is routed to OpenClaw at all. Wake-word routing is per phrase ("hey claw" vs
 * "hey hermes"), so this is resolved per invocation rather than once in Settings.
 */
object VoiceEngineSelector {
  /** Reason the requested engine is unavailable; drives both the Settings hint and the fallback notice. */
  enum class Unavailable {
    /** The voice turn targets Hermes, which has no Talk relay. */
    NOT_OPENCLAW_TARGET,

    /** No enabled openclaw-gateway backend is configured. */
    NO_GATEWAY_BACKEND,

    /** A gateway backend exists but is not currently reachable. */
    GATEWAY_OFFLINE,
  }

  data class Resolution(
    val engine: String,
    val unavailable: Unavailable? = null,
  ) {
    val isRelay: Boolean
      get() = engine == SettingsRepository.VOICE_ENGINE_OPENCLAW_TALK
  }

  fun resolve(
    requestedEngine: String,
    voiceTarget: String,
    backends: List<AgentBackendConfig>,
    gatewayHealthy: Boolean,
  ): Resolution {
    if (requestedEngine != SettingsRepository.VOICE_ENGINE_OPENCLAW_TALK) {
      return Resolution(SettingsRepository.VOICE_ENGINE_DEVICE)
    }
    if (voiceTarget != SettingsRepository.VOICE_TARGET_OPENCLAW) {
      return Resolution(SettingsRepository.VOICE_ENGINE_DEVICE, Unavailable.NOT_OPENCLAW_TARGET)
    }
    val hasGateway = backends.any { it.enabled && it.type == BackendType.OPENCLAW_GATEWAY }
    if (!hasGateway) {
      return Resolution(SettingsRepository.VOICE_ENGINE_DEVICE, Unavailable.NO_GATEWAY_BACKEND)
    }
    if (!gatewayHealthy) {
      return Resolution(SettingsRepository.VOICE_ENGINE_DEVICE, Unavailable.GATEWAY_OFFLINE)
    }
    return Resolution(SettingsRepository.VOICE_ENGINE_OPENCLAW_TALK)
  }
}
