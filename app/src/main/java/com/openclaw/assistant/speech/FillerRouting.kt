package com.openclaw.assistant.speech

import com.openclaw.assistant.data.SettingsRepository

/**
 * Decides which provider voices filler/wait phrases and whether the filler-specific
 * voice/model/speed settings apply to it. Kept free of Android types so the fallback
 * rules stay unit-testable; TTSManager owns the provider instances and side effects.
 */
internal object FillerRouting {

    /** Where a filler phrase should be spoken. */
    sealed interface Route {
        /** The provider that voices answers. */
        object Main : Route

        /** The shared provider registered for [type]. */
        data class Shared(val type: String) : Route

        /** A dedicated local instance bound to [engine] (differs from the answer engine). */
        data class DedicatedLocal(val engine: String) : Route
    }

    /**
     * Resolve the filler route. Runtime state comes in as functions:
     * [isDedicatedLocalReady] — a local instance bound to that engine is initialized;
     * [isProviderUsable] — the shared provider for a type exists, is available and configured.
     */
    fun route(
        fillerType: String,
        fillerEngine: String,
        mainEngine: String,
        isDedicatedLocalReady: (engine: String) -> Boolean,
        isProviderUsable: (type: String) -> Boolean,
    ): Route {
        if (fillerType == SettingsRepository.FILLER_TTS_TYPE_SAME) return Route.Main
        // A dedicated instance is only worth binding when the engine actually differs;
        // the shared local provider already speaks with the configured engine.
        if (fillerType == TTSProviderType.LOCAL &&
            fillerEngine.isNotBlank() && fillerEngine != mainEngine &&
            isDedicatedLocalReady(fillerEngine)
        ) {
            return Route.DedicatedLocal(fillerEngine)
        }
        if (isProviderUsable(fillerType)) return Route.Shared(fillerType)
        return Route.Main
    }

    /**
     * Filler voice/model/speed apply only to the provider they were chosen for: after a
     * fallback (or with "same as main") the provider keeps its own configured settings.
     */
    fun overridesApply(spokenByType: String, fillerType: String): Boolean =
        spokenByType == fillerType
}
