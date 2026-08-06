package com.openclaw.assistant.speech

import kotlinx.coroutines.flow.Flow

/**
 * Common interface for all TTS providers (Local, ElevenLabs, OpenAI, VOICEVOX)
 */
interface TTSProvider {
    /**
     * Speak the given text.
     * @param text The text to speak
     * @return true if successful, false otherwise
     */
    suspend fun speak(text: String): Boolean
    
    /**
     * Stop current speech.
     */
    fun stop()
    
    /**
     * Release resources.
     */
    fun shutdown()
    
    /**
     * Check if this provider is available/ready.
     */
    fun isAvailable(): Boolean
    
    /**
     * Get provider type identifier.
     */
    fun getType(): String
    
    /**
     * Get provider display name.
     */
    fun getDisplayName(): String
    
    /**
     * Check if provider is properly configured (API keys set, etc.)
     */
    fun isConfigured(): Boolean
    
    /**
     * Get configuration error message if not configured.
     */
    fun getConfigurationError(): String?
    
    /**
     * Speak with progress updates.
     */
    fun speakWithProgress(text: String): Flow<TTSState> {
        throw NotImplementedError("Progress tracking not implemented for this provider")
    }

    /**
     * Speak with progress updates using one-off settings instead of the configured ones.
     * Providers ignore the fields they cannot vary per utterance.
     */
    fun speakWithProgress(text: String, overrides: TTSOverrides?): Flow<TTSState> =
        speakWithProgress(text)
}

/**
 * Per-utterance overrides. Null fields keep the provider's configured value.
 *
 * The local TTS engine is not here: [android.speech.tts.TextToSpeech] binds an engine at
 * construction, so a different engine means a separate provider instance.
 */
data class TTSOverrides(
    /** Provider-specific voice: ElevenLabs voice id, OpenAI voice name. */
    val voice: String? = null,
    /** Provider-specific synthesis model, e.g. eleven_flash_v2_5 / gpt-4o-mini-tts. */
    val model: String? = null,
    /** Speech rate multiplier, clamped by each provider to what its API accepts. */
    val speed: Float? = null,
) {
    fun voiceOrNull(): String? = voice?.takeIf { it.isNotBlank() }
    fun modelOrNull(): String? = model?.takeIf { it.isNotBlank() }
    fun speedOrNull(): Float? = speed?.takeIf { it > 0f }
}

/**
 * TTS State for progress tracking
 */
sealed class TTSState {
    object Preparing : TTSState()
    object Speaking : TTSState()
    object Done : TTSState()
    data class Error(val message: String) : TTSState()
}

/**
 * Provider type constants
 */
object TTSProviderType {
    const val LOCAL = "local"
    const val ELEVENLABS = "elevenlabs"
    const val OPENAI = "openai"
    const val VOICEVOX = "voicevox"
}
