package com.openclaw.assistant.backend

import com.openclaw.assistant.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which product a voice turn belongs to.
 *
 * Voice has more entry points than Chat — two wake words, the in-app button, a
 * headset button, the system assistant — and only the wake words carry an
 * explicit target. The rest are resolved here, so a Hermes-only install is not
 * silently pointed at a gateway it never configured.
 */
class VoiceTargetResolverTest {

    private fun backend(id: String, type: BackendType, primary: Boolean = false, enabled: Boolean = true) =
        AgentBackendConfig(
            id = id,
            displayName = id,
            type = type,
            enabled = enabled,
            isPrimary = primary,
            baseUrl = "http://$id.test",
        )

    private val gateway = backend("gw", BackendType.OPENCLAW_GATEWAY)
    private val hermes = backend("hm", BackendType.HERMES_API_SERVER)
    private val http = backend("api", BackendType.OPENCLAW_HTTP)

    private fun resolve(
        explicit: String? = null,
        backends: List<AgentBackendConfig> = emptyList(),
        legacy: String = SettingsRepository.CONNECTION_TYPE_GATEWAY,
    ) = VoiceTargetResolver.resolve(explicit, backends, legacy)

    @Test fun `the openclaw wake word wins over a hermes primary`() {
        assertEquals(
            SettingsRepository.VOICE_TARGET_OPENCLAW,
            resolve(explicit = SettingsRepository.VOICE_TARGET_OPENCLAW, backends = listOf(hermes.copy(isPrimary = true))),
        )
    }

    @Test fun `the hermes wake word wins over a gateway primary`() {
        assertEquals(
            SettingsRepository.VOICE_TARGET_HERMES,
            resolve(explicit = SettingsRepository.VOICE_TARGET_HERMES, backends = listOf(gateway.copy(isPrimary = true))),
        )
    }

    @Test fun `an unrecognised explicit target is ignored`() {
        assertEquals(
            SettingsRepository.VOICE_TARGET_HERMES,
            resolve(explicit = "something-else", backends = listOf(hermes.copy(isPrimary = true))),
        )
    }

    @Test fun `a targetless entry point follows a gateway primary`() {
        assertEquals(
            SettingsRepository.VOICE_TARGET_OPENCLAW,
            resolve(backends = listOf(gateway.copy(isPrimary = true), hermes)),
        )
    }

    @Test fun `a targetless entry point follows a hermes primary`() {
        assertEquals(
            SettingsRepository.VOICE_TARGET_HERMES,
            resolve(backends = listOf(gateway, hermes.copy(isPrimary = true))),
        )
    }

    @Test fun `a hermes-only install never falls through to the gateway`() {
        // The regression this pins: the in-app voice button used to consult the
        // legacy connection type, which defaults to Gateway.
        assertEquals(
            SettingsRepository.VOICE_TARGET_HERMES,
            resolve(backends = listOf(hermes), legacy = SettingsRepository.CONNECTION_TYPE_GATEWAY),
        )
    }

    @Test fun `openclaw http counts as the openclaw product for voice`() {
        // VoiceBackendSelector then picks the HTTP backend; here we only decide
        // the product, and an HTTP-only install is not Hermes.
        assertEquals(
            SettingsRepository.VOICE_TARGET_HERMES,
            resolve(backends = listOf(http.copy(isPrimary = true))),
        )
    }

    @Test fun `a disabled primary does not decide the branch`() {
        assertEquals(
            SettingsRepository.VOICE_TARGET_HERMES,
            resolve(backends = listOf(gateway.copy(isPrimary = true, enabled = false), hermes)),
        )
    }

    @Test fun `with no backends the legacy gateway setting decides`() {
        assertEquals(
            SettingsRepository.VOICE_TARGET_OPENCLAW,
            resolve(legacy = SettingsRepository.CONNECTION_TYPE_GATEWAY),
        )
    }

    @Test fun `with no backends the legacy http setting means hermes`() {
        assertEquals(
            SettingsRepository.VOICE_TARGET_HERMES,
            resolve(legacy = SettingsRepository.CONNECTION_TYPE_HTTP),
        )
    }

    @Test fun `with no primary marked the first enabled backend decides`() {
        assertEquals(
            SettingsRepository.VOICE_TARGET_OPENCLAW,
            resolve(backends = listOf(gateway, hermes)),
        )
        assertEquals(
            SettingsRepository.VOICE_TARGET_HERMES,
            resolve(backends = listOf(hermes, gateway)),
        )
    }
}
