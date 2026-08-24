package com.openclaw.assistant.backend

import com.openclaw.assistant.data.SettingsRepository

/**
 * Whether a voice turn belongs to OpenClaw or to Hermes.
 *
 * Distinct from [VoiceBackendSelector], which picks the concrete backend once
 * the product is known. This only answers "which of the two branches".
 */
object VoiceTargetResolver {

    /**
     * @param explicitTarget the target carried by the launching intent, when the
     *   entry point knows it (a wake word bound to one product, for example).
     * @param backends every configured backend, enabled and disabled alike.
     * @param legacyConnectionType the pre-backends setting, consulted only by
     *   installs that have no backends at all.
     */
    fun resolve(
        explicitTarget: String?,
        backends: List<AgentBackendConfig>,
        legacyConnectionType: String,
    ): String {
        explicitTarget
            ?.takeIf { it == SettingsRepository.VOICE_TARGET_OPENCLAW || it == SettingsRepository.VOICE_TARGET_HERMES }
            ?.let { return it }

        // Entry points that carry no explicit target — the in-app voice button,
        // a headset button, the system assistant — used to fall back on the
        // legacy connection type, which defaults to Gateway. That sent every
        // Hermes-only install to a gateway it has never configured.
        val enabled = backends.filter { it.enabled }
        if (enabled.isNotEmpty()) {
            val primaryType = (enabled.firstOrNull { it.isPrimary } ?: enabled.first()).type
            return if (primaryType == BackendType.OPENCLAW_GATEWAY) {
                SettingsRepository.VOICE_TARGET_OPENCLAW
            } else {
                SettingsRepository.VOICE_TARGET_HERMES
            }
        }

        return if (legacyConnectionType == SettingsRepository.CONNECTION_TYPE_GATEWAY) {
            SettingsRepository.VOICE_TARGET_OPENCLAW
        } else {
            SettingsRepository.VOICE_TARGET_HERMES
        }
    }
}
