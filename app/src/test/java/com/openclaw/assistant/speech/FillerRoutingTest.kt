package com.openclaw.assistant.speech

import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.speech.FillerRouting.Route
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the provider-selection and fallback rules for filler/wait phrases
 * (see FillerRouting and its use in TTSManager.getFillerProvider).
 */
class FillerRoutingTest {

    private fun route(
        fillerType: String,
        fillerEngine: String = "",
        mainEngine: String = "",
        dedicatedReady: Boolean = true,
        usableTypes: Set<String> = setOf(
            TTSProviderType.LOCAL,
            TTSProviderType.ELEVENLABS,
            TTSProviderType.OPENAI
        )
    ): Route = FillerRouting.route(
        fillerType = fillerType,
        fillerEngine = fillerEngine,
        mainEngine = mainEngine,
        isDedicatedLocalReady = { dedicatedReady },
        isProviderUsable = { it in usableTypes }
    )

    // ----- "same as main" -----

    @Test
    fun `same as main routes to the answer provider`() {
        assertEquals(Route.Main, route(SettingsRepository.FILLER_TTS_TYPE_SAME))
    }

    @Test
    fun `same as main never touches other providers`() {
        var dedicatedQueried = false
        var usableQueried = false
        FillerRouting.route(
            fillerType = SettingsRepository.FILLER_TTS_TYPE_SAME,
            fillerEngine = "com.other.engine",
            mainEngine = "",
            isDedicatedLocalReady = { dedicatedQueried = true; true },
            isProviderUsable = { usableQueried = true; true }
        )
        // Querying has side effects (binding a TextToSpeech engine), so "same" must not probe
        assertFalse(dedicatedQueried)
        assertFalse(usableQueried)
    }

    // ----- explicit network provider -----

    @Test
    fun `configured network provider is used directly`() {
        assertEquals(
            Route.Shared(TTSProviderType.ELEVENLABS),
            route(TTSProviderType.ELEVENLABS)
        )
    }

    @Test
    fun `unconfigured network provider falls back to the answer provider`() {
        assertEquals(
            Route.Main,
            route(TTSProviderType.ELEVENLABS, usableTypes = setOf(TTSProviderType.LOCAL))
        )
    }

    @Test
    fun `provider missing from the registry falls back to the answer provider`() {
        // VOICEVOX is absent in the standard flavor: no shared provider exists for the type
        assertEquals(
            Route.Main,
            route(TTSProviderType.VOICEVOX)
        )
    }

    // ----- local TTS and its engine variants -----

    @Test
    fun `local with no engine override uses the shared local provider`() {
        assertEquals(Route.Shared(TTSProviderType.LOCAL), route(TTSProviderType.LOCAL))
    }

    @Test
    fun `local with the same engine as answers uses the shared local provider`() {
        var dedicatedQueried = false
        val result = FillerRouting.route(
            fillerType = TTSProviderType.LOCAL,
            fillerEngine = "com.google.tts",
            mainEngine = "com.google.tts",
            isDedicatedLocalReady = { dedicatedQueried = true; true },
            isProviderUsable = { true }
        )
        assertEquals(Route.Shared(TTSProviderType.LOCAL), result)
        // The shared instance already speaks with this engine — no second instance
        assertFalse(dedicatedQueried)
    }

    @Test
    fun `local with a different engine uses a dedicated instance when ready`() {
        assertEquals(
            Route.DedicatedLocal("com.other.engine"),
            route(
                TTSProviderType.LOCAL,
                fillerEngine = "com.other.engine",
                mainEngine = "com.google.tts"
            )
        )
    }

    @Test
    fun `local with an unready dedicated engine falls back to the shared local provider`() {
        assertEquals(
            Route.Shared(TTSProviderType.LOCAL),
            route(
                TTSProviderType.LOCAL,
                fillerEngine = "com.other.engine",
                mainEngine = "com.google.tts",
                dedicatedReady = false
            )
        )
    }

    @Test
    fun `local fully unavailable falls back to the answer provider`() {
        assertEquals(
            Route.Main,
            route(
                TTSProviderType.LOCAL,
                fillerEngine = "com.other.engine",
                mainEngine = "com.google.tts",
                dedicatedReady = false,
                usableTypes = emptySet()
            )
        )
    }

    // ----- overrides scope -----

    @Test
    fun `overrides apply when the chosen provider speaks`() {
        assertTrue(
            FillerRouting.overridesApply(
                spokenByType = TTSProviderType.OPENAI,
                fillerType = TTSProviderType.OPENAI
            )
        )
    }

    @Test
    fun `overrides do not apply after a fallback to another provider`() {
        // e.g. ElevenLabs chosen for fillers but unusable, answers voiced by local TTS:
        // the local provider must keep its own configured voice and speed
        assertFalse(
            FillerRouting.overridesApply(
                spokenByType = TTSProviderType.LOCAL,
                fillerType = TTSProviderType.ELEVENLABS
            )
        )
    }

    @Test
    fun `overrides do not apply with same as main`() {
        assertFalse(
            FillerRouting.overridesApply(
                spokenByType = TTSProviderType.LOCAL,
                fillerType = SettingsRepository.FILLER_TTS_TYPE_SAME
            )
        )
    }
}
