package com.openclaw.assistant.ui.diag

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.openclaw.assistant.BuildConfig
import com.openclaw.assistant.R
import com.openclaw.assistant.backend.AgentClientFactory
import com.openclaw.assistant.backend.BackendRepository
import com.openclaw.assistant.bridge.MobileBridgeConfig
import com.openclaw.assistant.bridge.accessibility.AgentVoiceAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Self-check / diagnostic screen. One scrollable
 * view that probes every load-bearing surface so a user (or Hermes itself
 * via the bridge) can answer "is everything wired correctly?" in a glance.
 *
 * Each check is a pure status read except for backend probes, which spawn
 * actual `testConnection()` calls and surface latency + winning endpoint.
 */
class SelfCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SelfCheckScreen() } }
    }
}

private data class CheckRow(val label: String, val ok: Boolean, val detail: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SelfCheckScreen() {
    val context = LocalContext.current
    val repo = remember { BackendRepository.getInstance(context) }
    val cfg = remember { MobileBridgeConfig.getInstance(context) }

    var rows by remember { mutableStateOf(listOf<CheckRow>()) }
    val scope = rememberCoroutineScope()

    val backendsConfiguredLabel = stringResource(R.string.av_selfcheck_backends_configured)
    val backendsNone = stringResource(R.string.av_selfcheck_backends_none)
    val primaryBackendLabel = stringResource(R.string.av_home_primary_backend)
    val primaryReachableLabel = stringResource(R.string.av_selfcheck_primary_reachable)
    val notSet = stringResource(R.string.av_selfcheck_not_set)
    val bridgeEnabledLabel = stringResource(R.string.av_selfcheck_mobile_bridge_enabled)
    val bridgeTokenLabel = stringResource(R.string.av_selfcheck_bridge_token_present)
    val notificationAccessLabel = stringResource(R.string.av_selfcheck_notification_access)
    val accessibilityBridgeLabel = stringResource(R.string.av_selfcheck_accessibility_bridge)
    val buildDistributionLabel = stringResource(R.string.av_selfcheck_build_distribution)
    val off = stringResource(R.string.av_selfcheck_off)
    val storedEncrypted = stringResource(R.string.av_selfcheck_stored_encrypted)
    val generateBridgeSettings = stringResource(R.string.av_selfcheck_generate_bridge_settings)
    val granted = stringResource(R.string.av_selfcheck_granted)
    val openNotificationSettings = stringResource(R.string.av_selfcheck_open_notification_settings)
    val serviceRunning = stringResource(R.string.av_selfcheck_service_running)
    val openAccessibilitySettings = stringResource(R.string.av_selfcheck_open_accessibility_settings)
    val sideloadDistribution = stringResource(R.string.av_selfcheck_sideload_distribution)
    val playDistribution = stringResource(R.string.av_selfcheck_play_distribution)
    val voiceEngineLabel = stringResource(R.string.voice_engine_section)
    val voiceEngineDeviceLabel = stringResource(R.string.voice_engine_device)
    val voiceEngineRelayLabel = stringResource(R.string.voice_engine_openclaw_talk)

    suspend fun runChecks() {
        val list = mutableListOf<CheckRow>()
        // Backend health
        val backends = repo.backends.value
        list += CheckRow(
            backendsConfiguredLabel,
            backends.isNotEmpty(),
            if (backends.isEmpty()) backendsNone else backends.joinToString(", ") { it.displayName },
        )
        val primary = backends.firstOrNull { it.isPrimary && it.enabled }
        list += CheckRow(
            primaryBackendLabel,
            primary != null,
            primary?.displayName ?: notSet,
        )
        primary?.let { p ->
            val r = withContext(Dispatchers.IO) { AgentClientFactory.create(p).testConnection() }
            list += CheckRow(
                primaryReachableLabel,
                r.ok,
                if (r.ok) "${r.message}${r.latencyMs?.let { " · ${it}ms" } ?: ""}" else r.message,
            )
        }

        // Conversation engine: report the RESOLVED engine, not the stored preference — they differ
        // whenever OpenClaw Talk is selected but the routing or the gateway makes it unusable.
        val settings = com.openclaw.assistant.data.SettingsRepository.getInstance(context)
        val runtime = (context.applicationContext as com.openclaw.assistant.OpenClawApplication).nodeRuntime
        val voiceTarget =
            if (settings.wakewordConnectionType == com.openclaw.assistant.data.SettingsRepository.CONNECTION_TYPE_GATEWAY) {
                com.openclaw.assistant.data.SettingsRepository.VOICE_TARGET_OPENCLAW
            } else {
                com.openclaw.assistant.data.SettingsRepository.VOICE_TARGET_HERMES
            }
        val engine = com.openclaw.assistant.backend.VoiceEngineSelector.resolve(
            requestedEngine = settings.voiceEngine,
            voiceTarget = voiceTarget,
            backends = backends,
            gatewayHealthy = runtime.chatHealthOk.value,
        )
        val relayRequested =
            settings.voiceEngine == com.openclaw.assistant.data.SettingsRepository.VOICE_ENGINE_OPENCLAW_TALK
        list += CheckRow(
            voiceEngineLabel,
            ok = !relayRequested || engine.isRelay,
            detail = when {
                !relayRequested -> voiceEngineDeviceLabel
                engine.isRelay -> voiceEngineRelayLabel
                else -> "$voiceEngineDeviceLabel — ${engine.unavailable}"
            },
        )

        // Bridge
        list += CheckRow(bridgeEnabledLabel, cfg.enabled.value, if (cfg.enabled.value) context.getString(R.string.av_selfcheck_on_port, cfg.port.value) else off)
        list += CheckRow(bridgeTokenLabel, cfg.tokenOrNull() != null, if (cfg.tokenOrNull() != null) storedEncrypted else generateBridgeSettings)

        // Permissions / opt-ins
        val notifAccess = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
        list += CheckRow(notificationAccessLabel, notifAccess, if (notifAccess) granted else openNotificationSettings)
        val a11y = AgentVoiceAccessibilityService.isRunning()
        list += CheckRow(accessibilityBridgeLabel, a11y, if (a11y) serviceRunning else openAccessibilitySettings)

        // Distribution gates
        list += CheckRow(
            buildDistributionLabel,
            true,
            if (BuildConfig.IS_SIDELOAD) sideloadDistribution else playDistribution,
        )

        rows = list
    }

    LaunchedEffect(Unit) { runChecks() }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.av_selfcheck_title)) }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(
                stringResource(R.string.av_selfcheck_intro),
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(12.dp))
            rows.forEach { row ->
                Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(
                            if (row.ok) "✓" else "✗",
                            color = if (row.ok) Color(0xFF34D399) else Color(0xFFEF4444),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(0.dp))
                        Column(modifier = Modifier.padding(start = 12.dp)) {
                            Text(row.label, style = MaterialTheme.typography.titleSmall)
                            Text(row.detail, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { runChecks() } }) { Text(stringResource(R.string.av_selfcheck_rerun)) }
            }
        }
    }
}
