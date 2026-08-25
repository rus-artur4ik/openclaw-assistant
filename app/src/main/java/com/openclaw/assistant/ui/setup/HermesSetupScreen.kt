package com.openclaw.assistant.ui.setup

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.openclaw.assistant.R
import com.openclaw.assistant.backend.HermesCapabilities
import com.openclaw.assistant.backend.HermesLanScanner
import com.openclaw.assistant.backend.HermesSetupProbe
import com.openclaw.assistant.backend.HermesTransport

/** Callbacks the guided flow needs; grouped so the screen signature stays readable. */
data class HermesSetupActions(
    val onAddressChange: (String) -> Unit = {},
    val onApiKeyChange: (String) -> Unit = {},
    val onConnect: () -> Unit = {},
    val onScanLan: () -> Unit = {},
    val onStopScan: () -> Unit = {},
    val onScanQr: () -> Unit = {},
    val onPickFound: (HermesLanScanner.Found) -> Unit = {},
    val onModelChange: (String) -> Unit = {},
    val onProviderChange: (String) -> Unit = {},
    val onDisplayNameChange: (String) -> Unit = {},
    val onMemoryScopeChange: (String) -> Unit = {},
    val onPrimaryChange: (Boolean) -> Unit = {},
    val onContinue: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onManualEntry: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HermesSetupScreen(state: HermesSetupUiState, actions: HermesSetupActions) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(R.string.hermes_setup_title)) })
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(
                    when (state.step) {
                        HermesSetupStep.CONNECT -> R.string.hermes_setup_step_connect
                        HermesSetupStep.REVIEW -> R.string.hermes_setup_step_review
                        HermesSetupStep.FINISH -> R.string.hermes_setup_step_finish
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            when (state.step) {
                HermesSetupStep.CONNECT -> ConnectStep(state, actions)
                HermesSetupStep.REVIEW -> ReviewStep(state, actions)
                HermesSetupStep.FINISH -> FinishStep(state, actions)
            }
        }
    }
}

@Composable
private fun ConnectStep(state: HermesSetupUiState, actions: HermesSetupActions) {
    OutlinedTextField(
        value = state.address,
        onValueChange = actions.onAddressChange,
        label = { Text(stringResource(R.string.hermes_setup_address_label)) },
        supportingText = { Text(stringResource(R.string.hermes_setup_address_help)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    var keyVisible by rememberSaveable { mutableStateOf(false) }
    OutlinedTextField(
        value = state.apiKey,
        onValueChange = actions.onApiKeyChange,
        label = { Text(stringResource(R.string.hermes_setup_key_label)) },
        supportingText = { Text(stringResource(R.string.hermes_setup_key_help)) },
        singleLine = true,
        // Masked like every other secret field in the app, with a reveal toggle
        // so a mistyped key can still be checked.
        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { keyVisible = !keyVisible }) {
                Icon(
                    imageVector = if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    contentDescription = stringResource(
                        if (keyVisible) R.string.hermes_setup_key_hide else R.string.hermes_setup_key_show,
                    ),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = actions.onConnect, enabled = state.canConnect) {
            Text(stringResource(R.string.hermes_setup_connect))
        }
        OutlinedButton(onClick = actions.onScanQr) {
            Icon(Icons.Default.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.hermes_setup_scan_qr))
        }
    }

    if (state.scanning) {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.hermes_setup_scanning, state.scanScanned, state.scanTotal),
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = actions.onStopScan) { Text(stringResource(R.string.hermes_setup_scan_stop)) }
    } else {
        OutlinedButton(
            onClick = actions.onScanLan,
            enabled = !state.busy && !state.scanUnavailable,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Search, contentDescription = null)
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.hermes_setup_scan_lan))
        }
    }

    if (state.scanUnavailable) {
        Text(
            stringResource(R.string.hermes_setup_scan_unavailable),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (state.scanResults.isNotEmpty()) {
        Text(stringResource(R.string.hermes_setup_found_title), style = MaterialTheme.typography.titleSmall)
        state.scanResults.forEach { found ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { actions.onPickFound(found) },
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(found.host, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        found.advertisedModel
                            ?: if (found.requiresKey) stringResource(R.string.hermes_setup_found_needs_key) else found.baseUrl,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    } else if (state.scanFinished && !state.scanning) {
        Text(
            stringResource(R.string.hermes_setup_scan_none),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (state.busy) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(modifier = Modifier.height(18.dp))
            Text(stringResource(R.string.hermes_setup_probing))
        }
    }

    state.probe?.let { ProbeStatus(it) }

    TextButton(onClick = actions.onManualEntry) {
        Text(stringResource(R.string.hermes_setup_manual))
    }

    if (state.connected) {
        Button(onClick = actions.onContinue, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.hermes_setup_continue))
        }
    }
}

/** Turns a probe outcome into the one sentence that tells the user what to do next. */
@Composable
private fun ProbeStatus(probe: HermesSetupProbe) {
    val ok = probe is HermesSetupProbe.Ready
    val text = when (probe) {
        is HermesSetupProbe.Ready -> stringResource(R.string.hermes_setup_status_ready, probe.baseUrl)
        is HermesSetupProbe.NeedsKey -> stringResource(R.string.hermes_setup_status_needs_key, probe.baseUrl)
        is HermesSetupProbe.KeyRejected -> stringResource(R.string.hermes_setup_status_bad_key, probe.baseUrl)
        is HermesSetupProbe.NotHermes ->
            stringResource(R.string.hermes_setup_status_not_hermes, probe.baseUrl, probe.detail)
        is HermesSetupProbe.Unreachable ->
            stringResource(R.string.hermes_setup_status_unreachable, probe.tried.joinToString(", "))
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(
            imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ReviewStep(state: HermesSetupUiState, actions: HermesSetupActions) {
    val caps = state.capabilities
    Text(stringResource(R.string.hermes_setup_caps_title), style = MaterialTheme.typography.titleSmall)

    if (caps == null || !caps.detected) {
        Text(
            stringResource(R.string.hermes_setup_caps_undetected),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (caps.sessionChatStreaming) CapabilityLine(stringResource(R.string.hermes_setup_caps_sessions))
            if (caps.chatCompletionsStreaming || caps.runEventsSse) {
                CapabilityLine(stringResource(R.string.hermes_setup_caps_streaming))
            }
            if (caps.runStop) CapabilityLine(stringResource(R.string.hermes_setup_caps_stop))
            CapabilityLine(
                text = if (caps.approvalEvents || caps.runApprovalResponse) {
                    stringResource(R.string.hermes_setup_caps_approvals)
                } else {
                    stringResource(R.string.hermes_setup_caps_no_approvals)
                },
                positive = caps.approvalEvents || caps.runApprovalResponse,
            )
            Text(
                stringResource(R.string.hermes_setup_caps_transport, transportLabel(caps.preferredTransport())),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    OutlinedTextField(
        value = state.model,
        onValueChange = actions.onModelChange,
        label = { Text(stringResource(R.string.hermes_setup_model_label)) },
        supportingText = { Text(stringResource(R.string.hermes_setup_model_help)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.provider,
        onValueChange = actions.onProviderChange,
        label = { Text(stringResource(R.string.hermes_setup_provider_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    state.suggested?.options?.take(6)?.takeIf { it.isNotEmpty() }?.let { options ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            options.forEach { option ->
                AssistChip(
                    onClick = {
                        actions.onModelChange(option.id)
                        option.provider?.takeIf { it.isNotBlank() }?.let(actions.onProviderChange)
                    },
                    label = { Text(option.id, style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = actions.onBack) { Text(stringResource(R.string.hermes_setup_back)) }
        Button(onClick = actions.onContinue, enabled = state.canContinueFromReview) {
            Text(stringResource(R.string.hermes_setup_continue))
        }
    }
}

@Composable
private fun CapabilityLine(text: String, positive: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Icon(
            imageVector = if (positive) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
            contentDescription = null,
            tint = if (positive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.height(16.dp),
        )
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun transportLabel(transport: HermesTransport): String = stringResource(
    when (transport) {
        HermesTransport.SESSION_CHAT -> R.string.backend_hermes_transport_session
        HermesTransport.RUNS -> R.string.backend_hermes_transport_runs
        HermesTransport.CHAT_COMPLETIONS -> R.string.backend_hermes_transport_chat
    },
)

@Composable
private fun FinishStep(state: HermesSetupUiState, actions: HermesSetupActions) {
    OutlinedTextField(
        value = state.displayName,
        onValueChange = actions.onDisplayNameChange,
        label = { Text(stringResource(R.string.hermes_setup_name_label)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.memoryScopeKey,
        onValueChange = actions.onMemoryScopeChange,
        label = { Text(stringResource(R.string.hermes_setup_memory_label)) },
        supportingText = { Text(stringResource(R.string.backend_hermes_memory_scope_help)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = state.makePrimary, onCheckedChange = actions.onPrimaryChange)
        Text(stringResource(R.string.hermes_setup_primary))
    }
    state.savedName?.let {
        Text(stringResource(R.string.hermes_setup_saved, it), style = MaterialTheme.typography.bodyMedium)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = actions.onBack) { Text(stringResource(R.string.hermes_setup_back)) }
        Button(onClick = actions.onSave, enabled = state.canSave) {
            Text(stringResource(R.string.hermes_setup_save))
        }
    }
}
