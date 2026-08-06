package com.openclaw.assistant.speech

object ElevenLabsModels {

    data class Model(
        val id: String,
        val displayName: String,
        val description: String,
        val supportsLanguageCode: Boolean,
        val supportsVoiceTuning: Boolean
    )

    val ALL = listOf(
        Model(
            id = "eleven_multilingual_v2",
            displayName = "Multilingual v2",
            description = "Most stable quality, 29 languages",
            supportsLanguageCode = false,
            supportsVoiceTuning = true
        ),
        Model(
            id = "eleven_v3",
            displayName = "Eleven v3",
            description = "Most expressive, 70+ languages, no speed control",
            supportsLanguageCode = true,
            supportsVoiceTuning = false
        ),
        Model(
            id = "eleven_flash_v2_5",
            displayName = "Flash v2.5",
            description = "Lowest latency (~75 ms), 32 languages",
            supportsLanguageCode = true,
            supportsVoiceTuning = true
        ),
        Model(
            id = "eleven_flash_v2",
            displayName = "Flash v2",
            description = "Lowest latency (~75 ms), English only",
            supportsLanguageCode = false,
            supportsVoiceTuning = true
        ),
        Model(
            id = "eleven_turbo_v2_5",
            displayName = "Turbo v2.5",
            description = "Fast (~300 ms), 32 languages",
            supportsLanguageCode = true,
            supportsVoiceTuning = true
        ),
        Model(
            id = "eleven_turbo_v2",
            displayName = "Turbo v2",
            description = "Fast (~300 ms), English only",
            supportsLanguageCode = false,
            supportsVoiceTuning = true
        )
    )

    fun find(id: String): Model? = ALL.firstOrNull { it.id == id }
}
