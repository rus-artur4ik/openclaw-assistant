package com.openclaw.assistant.ui.setup

import com.openclaw.assistant.backend.HermesCapabilities
import com.openclaw.assistant.backend.HermesLanScanner
import com.openclaw.assistant.backend.HermesSetupProbe
import com.openclaw.assistant.backend.HermesSuggestedModel

enum class HermesSetupStep { CONNECT, REVIEW, FINISH }

/**
 * Everything the guided Hermes flow shows, in one value.
 *
 * The screen is a pure function of this, so every state a user can land in —
 * mid-scan, key rejected, old server that answers no capability probe — can be
 * rendered in a test without a Hermes server anywhere nearby.
 */
data class HermesSetupUiState(
    val step: HermesSetupStep = HermesSetupStep.CONNECT,
    val address: String = "",
    val apiKey: String = "",
    /** A probe or a save is in flight; the primary action is disabled. */
    val busy: Boolean = false,
    val probe: HermesSetupProbe? = null,
    val scanning: Boolean = false,
    val scanScanned: Int = 0,
    val scanTotal: Int = 0,
    val scanFinished: Boolean = false,
    val scanResults: List<HermesLanScanner.Found> = emptyList(),
    /** Set when the device has no subnet to scan. */
    val scanUnavailable: Boolean = false,
    val capabilities: HermesCapabilities? = null,
    val suggested: HermesSuggestedModel? = null,
    val model: String = "",
    val provider: String = "",
    val displayName: String = "",
    val memoryScopeKey: String = "",
    val makePrimary: Boolean = true,
    val savedName: String? = null,
) {
    /** True once a server has been reached and authenticated. */
    val connected: Boolean get() = probe is HermesSetupProbe.Ready

    /** The address the probe settled on, which may differ from what was typed. */
    val resolvedBaseUrl: String? get() = (probe as? HermesSetupProbe.Ready)?.baseUrl

    val canConnect: Boolean get() = address.isNotBlank() && !busy && !scanning

    val canContinueFromReview: Boolean get() = connected && !busy

    val canSave: Boolean get() = connected && displayName.isNotBlank() && !busy
}
