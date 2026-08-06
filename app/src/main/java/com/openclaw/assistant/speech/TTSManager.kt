package com.openclaw.assistant.speech

import android.content.Context
import android.util.Log
import com.openclaw.assistant.BuildConfig
import com.openclaw.assistant.R
import com.openclaw.assistant.data.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

private const val TAG = "TTSManager"

/**
 * Text-to-Speech Manager with support for multiple providers
 * (Local TTS, ElevenLabs, OpenAI, VOICEVOX)
 */
class TTSManager(private val context: Context) {
    
    private val settings = SettingsRepository.getInstance(context)
    
    // Provider instances
    private val providers = mutableMapOf<String, TTSProvider>()
    
    init {
        // Initialize all providers
        providers[TTSProviderType.LOCAL] = AndroidTTSProvider(context)
        providers[TTSProviderType.ELEVENLABS] = ElevenLabsProvider(context)
        providers[TTSProviderType.OPENAI] = OpenAIProvider(context)
        if (BuildConfig.FLAVOR == "full") {
            providers[TTSProviderType.VOICEVOX] = VoiceVoxProvider(context)
        }
    }
    
    /**
     * Get the currently configured provider
     */
    private fun getCurrentProvider(): TTSProvider? {
        val type = settings.ttsType
        return providers[type]
    }
    
    /**
     * Check if current provider is configured and available
     */
    fun isReady(): Boolean {
        val provider = getCurrentProvider()
        if (provider == null) {
            Log.e(TAG, "isReady: no provider for type '${settings.ttsType}'")
            return false
        }
        val available = provider.isAvailable()
        val configured = provider.isConfigured()
        if (!available || !configured) {
            Log.e(TAG, "isReady: ${provider.getDisplayName()} available=$available configured=$configured error=${provider.getConfigurationError()}")
        }
        return available && configured
    }
    
    /**
     * Get error message if not ready
     */
    fun getErrorMessage(): String? {
        val provider = getCurrentProvider()
        return when {
            provider == null -> context.getString(R.string.tts_error_unknown_type, settings.ttsType)
            !provider.isConfigured() -> provider.getConfigurationError()
            !provider.isAvailable() -> context.getString(R.string.tts_error_provider_unavailable, provider.getDisplayName())
            else -> null
        }
    }
    
    /**
     * Speak the given text using the configured provider
     */
    suspend fun speak(text: String): Boolean {
        val provider = getCurrentProvider()
        if (provider == null) {
            Log.e(TAG, "No provider found for type: ${settings.ttsType}")
            return false
        }
        
        if (!provider.isConfigured()) {
            Log.e(TAG, "Provider not configured: ${provider.getConfigurationError()}")
            return false
        }
        
        if (!provider.isAvailable()) {
            Log.e(TAG, "Provider not available: ${provider.getDisplayName()}")
            return false
        }
        
        // Preprocess text (strip markdown, etc.)
        val processedText = TTSUtils.stripMarkdownForSpeech(text)
        
        return provider.speak(processedText)
    }
    
    /**
     * Speak with progress updates
     */
    fun speakWithProgress(text: String): Flow<TTSState> {
        val provider = getCurrentProvider()
        if (provider == null) {
            return callbackFlow {
                trySend(TTSState.Error("No provider found"))
                close()
            }
        }
        
        if (!provider.isConfigured()) {
            return callbackFlow {
                trySend(TTSState.Error(provider.getConfigurationError() ?: "Not configured"))
                close()
            }
        }
        
        val processedText = TTSUtils.stripMarkdownForSpeech(text)
        return provider.speakWithProgress(processedText)
    }

    // A filler-specific local engine needs its own instance: TextToSpeech binds its engine
    // at construction, so it cannot be switched per utterance like a voice or model.
    private var fillerLocalProvider: AndroidTTSProvider? = null
    private var fillerLocalEngine: String? = null

    private fun localProviderForEngine(engine: String): AndroidTTSProvider {
        fillerLocalProvider?.let { if (fillerLocalEngine == engine) return it }
        fillerLocalProvider?.shutdown()
        return AndroidTTSProvider(context, engine).also {
            fillerLocalProvider = it
            fillerLocalEngine = engine
        }
    }

    /**
     * Resolve which provider voices filler/wait phrases. Falls back to the answer provider
     * when the configured filler provider is unusable (missing key, engine not initialized).
     */
    private fun getFillerProvider(): TTSProvider? {
        val fillerType = settings.fillerTtsType
        val route = FillerRouting.route(
            fillerType = fillerType,
            fillerEngine = settings.fillerTtsEngine,
            mainEngine = settings.ttsEngine,
            isDedicatedLocalReady = { engine ->
                // Binding here is deliberate: an engine that is still connecting now
                // becomes ready for the next filler.
                val ready = localProviderForEngine(engine).isAvailable()
                if (!ready) Log.w(TAG, "filler engine '$engine' not initialized yet, using the shared local provider")
                ready
            },
            isProviderUsable = { type ->
                providers[type]?.let { it.isAvailable() && it.isConfigured() } == true
            }
        )
        return when (route) {
            is FillerRouting.Route.DedicatedLocal -> localProviderForEngine(route.engine)
            is FillerRouting.Route.Shared -> providers[route.type]
            is FillerRouting.Route.Main -> {
                if (fillerType != SettingsRepository.FILLER_TTS_TYPE_SAME) {
                    Log.w(TAG, "filler provider '$fillerType' unusable, falling back to '${settings.ttsType}'")
                }
                getCurrentProvider()
            }
        }
    }

    /**
     * Filler settings only apply to the provider they were chosen for: after a fallback (or
     * with "same as main") the provider speaks with its own configured voice, model and speed.
     */
    private fun fillerOverridesFor(provider: TTSProvider): TTSOverrides? {
        if (!FillerRouting.overridesApply(provider.getType(), settings.fillerTtsType)) return null
        return TTSOverrides(
            voice = settings.fillerVoiceId,
            model = settings.fillerModel,
            speed = settings.fillerSpeed
        )
    }

    /**
     * Speak a short filler/wait phrase through the filler provider and voice, which may
     * differ from the answer voice (see [getFillerProvider]).
     */
    fun speakFillerWithProgress(text: String): Flow<TTSState> {
        val provider = getFillerProvider()
            ?: return callbackFlow {
                trySend(TTSState.Error("No provider found"))
                close()
            }
        return provider.speakWithProgress(
            TTSUtils.stripMarkdownForSpeech(text),
            fillerOverridesFor(provider)
        )
    }

    /**
     * Stop current speech
     */
    fun stop() {
        getCurrentProvider()?.stop()
    }
    
    /**
     * Stop all providers
     */
    fun stopAll() {
        providers.values.forEach { it.stop() }
        fillerLocalProvider?.stop()
    }
    
    /**
     * Release all resources
     */
    fun shutdown() {
        providers.values.forEach { it.shutdown() }
        providers.clear()
        fillerLocalProvider?.shutdown()
        fillerLocalProvider = null
        fillerLocalEngine = null
    }
    
    /**
     * Reinitialize after settings change
     */
    fun reinitialize() {
        shutdown()
        providers[TTSProviderType.LOCAL] = AndroidTTSProvider(context)
        providers[TTSProviderType.ELEVENLABS] = ElevenLabsProvider(context)
        providers[TTSProviderType.OPENAI] = OpenAIProvider(context)
        if (BuildConfig.FLAVOR == "full") {
            providers[TTSProviderType.VOICEVOX] = VoiceVoxProvider(context)
        }
    }
    
    /**
     * Initialize the current provider (needed for VOICEVOX)
     * Call this before using TTS
     */
    fun initializeCurrentProvider(): Boolean {
        val ready = initializeIfNeeded(getCurrentProvider())
        // Fillers may run on a different provider, which needs the same explicit setup
        val fillerType = settings.fillerTtsType
        if (fillerType != SettingsRepository.FILLER_TTS_TYPE_SAME) {
            initializeIfNeeded(providers[fillerType])
            // Binding the engine now means the first filler does not fall back while
            // this instance is still connecting to the engine service
            if (fillerType == TTSProviderType.LOCAL) {
                val engine = settings.fillerTtsEngine
                if (engine.isNotBlank() && engine != settings.ttsEngine) localProviderForEngine(engine)
            }
        }
        return ready
    }

    private fun initializeIfNeeded(provider: TTSProvider?): Boolean {
        return if (BuildConfig.FLAVOR == "full" && provider is VoiceVoxProvider) {
            provider.initialize()
        } else {
            true // Other providers don't need explicit initialization
        }
    }
    
    /**
     * Get all available providers
     */
    fun getAvailableProviders(): List<TTSProviderInfo> {
        return listOf(
            TTSProviderInfo(
                type = TTSProviderType.LOCAL,
                displayName = context.getString(R.string.tts_provider_local_name),
                description = context.getString(R.string.tts_provider_local_description),
                isAvailable = true,
                isConfigured = true
            ),
            TTSProviderInfo(
                type = TTSProviderType.ELEVENLABS,
                displayName = "ElevenLabs",
                description = context.getString(R.string.tts_provider_elevenlabs_description),
                isAvailable = true,
                isConfigured = settings.elevenLabsApiKey.isNotBlank()
            ),
            TTSProviderInfo(
                type = TTSProviderType.OPENAI,
                displayName = "OpenAI",
                description = "OpenAI TTS API",
                isAvailable = true,
                isConfigured = settings.openAiApiKey.isNotBlank()
            ),
            TTSProviderInfo(
                type = TTSProviderType.VOICEVOX,
                displayName = "VOICEVOX",
                description = context.getString(R.string.tts_provider_voicevox_description),
                isAvailable = providers[TTSProviderType.VOICEVOX]?.isAvailable() == true,
                isConfigured = settings.voiceVoxTermsAccepted &&
                              providers[TTSProviderType.VOICEVOX]?.isAvailable() == true
            )
        )
    }
    
    /**
     * Get provider instance by type
     */
    fun getProvider(type: String): TTSProvider? = providers[type]
    
    data class TTSProviderInfo(
        val type: String,
        val displayName: String,
        val description: String,
        val isAvailable: Boolean,
        val isConfigured: Boolean
    )
}
