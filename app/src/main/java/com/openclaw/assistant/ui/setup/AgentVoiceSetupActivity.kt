package com.openclaw.assistant.ui.setup

import android.content.Intent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.openclaw.assistant.R
import com.openclaw.assistant.backend.AgentBackendConfig
import com.openclaw.assistant.backend.BackendRepository

/**
 * Simplified first-run wizard.
 *
 *   Step 1 — Welcome
 *   Step 2 — Tick the backends you want to set up (Hermes / OpenClaw)
 *   Step 3 — Per-backend setup, only for ticked options:
 *              • WakeClaw helper: run `agentvoice-pair` on the host. It
 *                auto-detects Hermes, OpenClaw, and Tailscale, then prints one
 *                QR for this app to scan.
 *   Step 4 — Done
 */
class AgentVoiceSetupActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { SimpleSetupWizard { finish() } } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimpleSetupWizard(onDone: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { BackendRepository.getInstance(context) }
    val backends by repo.backends.collectAsState()
    var step by remember { mutableStateOf(0) }
    var pickHermes by remember { mutableStateOf(true) }
    var pickOpenClaw by remember { mutableStateOf(true) }
    val totalSteps = 4

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.av_setup_title)) }) }) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(20.dp).verticalScroll(rememberScrollState()),
        ) {
            LinearProgressIndicator(progress = { (step + 1f) / totalSteps }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(20.dp))

            when (step) {
                0 -> WelcomePane()
                1 -> ChooseBackendsPane(
                    pickHermes = pickHermes, pickOpenClaw = pickOpenClaw,
                    onHermes = { pickHermes = it }, onOpenClaw = { pickOpenClaw = it },
                )
                2 -> ConfigurePane(includeHermes = pickHermes, includeOpenClaw = pickOpenClaw)
                3 -> DonePane(backends)
            }

            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (step in 1 until totalSteps - 1) OutlinedButton(onClick = { step-- }) { Text(stringResource(R.string.av_setup_back)) }
                Spacer(Modifier.weight(1f))
                if (step < totalSteps - 1) {
                    Button(
                        onClick = { step++ },
                        enabled = step != 1 || (pickHermes || pickOpenClaw),
                    ) { Text(if (step == 0) stringResource(R.string.av_setup_get_started) else stringResource(R.string.av_setup_next)) }
                } else {
                    Button(onClick = onDone) { Text(stringResource(R.string.av_setup_finish)) }
                }
            }
        }
    }
}

@Composable private fun WelcomePane() {
    Text(stringResource(R.string.av_setup_welcome_title), style = MaterialTheme.typography.headlineMedium)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.av_setup_welcome_body), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun ChooseBackendsPane(
    pickHermes: Boolean,
    pickOpenClaw: Boolean,
    onHermes: (Boolean) -> Unit,
    onOpenClaw: (Boolean) -> Unit,
) {
    Text(stringResource(R.string.av_setup_choose_title), style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(4.dp))
    Text(stringResource(R.string.av_setup_choose_hint), style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.av_setup_choose_recommended), style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(16.dp))

    BackendChoiceCard(
        title = stringResource(R.string.av_backend_hermes),
        subtitle = stringResource(R.string.av_backend_hermes_subtitle),
        checked = pickHermes, onCheckedChange = onHermes,
    )
    Spacer(Modifier.height(12.dp))
    BackendChoiceCard(
        title = stringResource(R.string.av_backend_openclaw),
        subtitle = stringResource(R.string.av_backend_openclaw_subtitle),
        checked = pickOpenClaw, onCheckedChange = onOpenClaw,
    )
}

@Composable
private fun BackendChoiceCard(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().toggleable(value = checked, onValueChange = onCheckedChange)) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange, colors = CheckboxDefaults.colors())
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ConfigurePane(includeHermes: Boolean, includeOpenClaw: Boolean) {
    UnifiedPairingCard(includeHermes = includeHermes, includeOpenClaw = includeOpenClaw)
}

@Composable
private fun UnifiedPairingCard(includeHermes: Boolean, includeOpenClaw: Boolean) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    var pairingReview by remember { mutableStateOf<EditablePairingPayload?>(null) }
    val installCommand = "curl -fsSL https://raw.githubusercontent.com/yuga-hashimoto/openclaw-assistant/main/integrations/agentvoice-pair/install.sh | bash"
    val pairCommand = "agentvoice-pair"
    val scopeText = when {
        includeHermes && includeOpenClaw -> stringResource(R.string.av_setup_scope_both)
        includeHermes -> stringResource(R.string.av_setup_scope_hermes)
        includeOpenClaw -> stringResource(R.string.av_setup_scope_openclaw)
        else -> stringResource(R.string.av_setup_scope_both)
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(stringResource(R.string.av_pairing_card_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(scopeText, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.av_pairing_card_step1), style = MaterialTheme.typography.bodyMedium)
            CopyCommandBlock(installCommand)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.av_pairing_card_step2), style = MaterialTheme.typography.bodyMedium)
            CopyCommandBlock(pairCommand)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.av_pairing_card_step3), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.av_pairing_card_note), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = {
                    val options = GmsBarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build()
                    GmsBarcodeScanning.getClient(context, options)
                        .startScan()
                        .addOnSuccessListener { barcode ->
                            val raw = barcode.rawValue?.trim().orEmpty()
                            val pairingPayload = parsePairingPayload(raw)
                            if (pairingPayload != null) {
                                pairingReview = pairingPayload.toEditablePairingPayload()
                                applyPairingPayload(context, pairingPayload, null)
                                status = context.getString(R.string.setup_code_applied)
                            } else if (raw.startsWith("agentvoice://")) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(raw)))
                            } else {
                                android.widget.Toast.makeText(
                                    context,
                                    context.getString(R.string.qr_scan_unavailable),
                                    android.widget.Toast.LENGTH_LONG
                                ).show()
                            }
                        }
                        .addOnFailureListener { /* cancelled */ }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.av_pairing_scan_qr)) }
            pairingReview?.let { draft ->
                Spacer(Modifier.height(12.dp))
                PairingPayloadReviewEditor(
                    value = draft,
                    onChange = { pairingReview = it },
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        draft.toPairingPayload()?.let {
                            applyPairingPayload(context, it, null)
                            status = context.getString(R.string.setup_code_applied)
                        }
                    },
                    enabled = draft.toPairingPayload() != null,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.av_pairing_apply_review))
                }
            }
            status?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DonePane(backends: List<AgentBackendConfig>) {
    Text(stringResource(R.string.av_setup_done_title), style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(8.dp))
    if (backends.isEmpty()) {
        Text(stringResource(R.string.av_setup_done_empty), style = MaterialTheme.typography.bodyMedium)
    } else {
        backends.forEach { b ->
            Text(
                "· ${b.displayName}${if (b.isPrimary) " (" + stringResource(R.string.primary_backend) + ")" else ""}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun CopyCommandBlock(text: String) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            OutlinedButton(
                onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("agentvoice command", text))
                    android.widget.Toast.makeText(
                        context,
                        context.getString(R.string.av_setup_command_copied),
                        android.widget.Toast.LENGTH_SHORT,
                    ).show()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.av_setup_copy_command))
            }
        }
    }
}
