package com.openclaw.assistant.backend

import com.openclaw.assistant.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceEngineSelectorTest {

  private fun backend(
    id: String,
    type: BackendType,
    enabled: Boolean = true,
    isPrimary: Boolean = false,
  ) = AgentBackendConfig(id = id, displayName = id, type = type, enabled = enabled, isPrimary = isPrimary)

  private val gateway = backend("gw", BackendType.OPENCLAW_GATEWAY, isPrimary = true)

  @Test
  fun `device engine is returned untouched`() {
    val resolution =
      VoiceEngineSelector.resolve(
        requestedEngine = SettingsRepository.VOICE_ENGINE_DEVICE,
        voiceTarget = SettingsRepository.VOICE_TARGET_OPENCLAW,
        backends = listOf(gateway),
        gatewayHealthy = true,
      )

    assertFalse(resolution.isRelay)
    // Not a fallback, so there is nothing to explain to the user.
    assertNull(resolution.unavailable)
  }

  @Test
  fun `relay runs when an openclaw target and a healthy gateway line up`() {
    val resolution =
      VoiceEngineSelector.resolve(
        requestedEngine = SettingsRepository.VOICE_ENGINE_OPENCLAW_TALK,
        voiceTarget = SettingsRepository.VOICE_TARGET_OPENCLAW,
        backends = listOf(gateway),
        gatewayHealthy = true,
      )

    assertTrue(resolution.isRelay)
    assertNull(resolution.unavailable)
  }

  @Test
  fun `a hermes voice turn falls back — hermes has no talk relay`() {
    val resolution =
      VoiceEngineSelector.resolve(
        requestedEngine = SettingsRepository.VOICE_ENGINE_OPENCLAW_TALK,
        voiceTarget = SettingsRepository.VOICE_TARGET_HERMES,
        backends = listOf(gateway),
        gatewayHealthy = true,
      )

    assertFalse(resolution.isRelay)
    assertEquals(VoiceEngineSelector.Unavailable.NOT_OPENCLAW_TARGET, resolution.unavailable)
  }

  @Test
  fun `an http-only openclaw setup falls back`() {
    val resolution =
      VoiceEngineSelector.resolve(
        requestedEngine = SettingsRepository.VOICE_ENGINE_OPENCLAW_TALK,
        voiceTarget = SettingsRepository.VOICE_TARGET_OPENCLAW,
        backends = listOf(backend("http", BackendType.OPENCLAW_HTTP, isPrimary = true)),
        gatewayHealthy = true,
      )

    assertEquals(VoiceEngineSelector.Unavailable.NO_GATEWAY_BACKEND, resolution.unavailable)
  }

  @Test
  fun `a disabled gateway backend does not count`() {
    val resolution =
      VoiceEngineSelector.resolve(
        requestedEngine = SettingsRepository.VOICE_ENGINE_OPENCLAW_TALK,
        voiceTarget = SettingsRepository.VOICE_TARGET_OPENCLAW,
        backends = listOf(backend("gw", BackendType.OPENCLAW_GATEWAY, enabled = false)),
        gatewayHealthy = true,
      )

    assertEquals(VoiceEngineSelector.Unavailable.NO_GATEWAY_BACKEND, resolution.unavailable)
  }

  @Test
  fun `an unreachable gateway falls back with a distinct reason`() {
    val resolution =
      VoiceEngineSelector.resolve(
        requestedEngine = SettingsRepository.VOICE_ENGINE_OPENCLAW_TALK,
        voiceTarget = SettingsRepository.VOICE_TARGET_OPENCLAW,
        backends = listOf(gateway),
        gatewayHealthy = false,
      )

    assertFalse(resolution.isRelay)
    assertEquals(VoiceEngineSelector.Unavailable.GATEWAY_OFFLINE, resolution.unavailable)
  }

  @Test
  fun `an unknown stored value is treated as the device engine`() {
    val resolution =
      VoiceEngineSelector.resolve(
        requestedEngine = "something-from-a-future-version",
        voiceTarget = SettingsRepository.VOICE_TARGET_OPENCLAW,
        backends = listOf(gateway),
        gatewayHealthy = true,
      )

    assertEquals(SettingsRepository.VOICE_ENGINE_DEVICE, resolution.engine)
  }
}
