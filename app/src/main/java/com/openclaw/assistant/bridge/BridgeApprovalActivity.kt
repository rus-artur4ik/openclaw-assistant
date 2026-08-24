package com.openclaw.assistant.bridge

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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class BridgeApprovalActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val pending = BridgeApprovalRegistry.snapshot(requestId)
        val capability = pending?.capability ?: "(unknown)"
        val destructive = com.openclaw.assistant.bridge.grants.DestructiveVerbs.isDestructive(capability)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ApprovalPane(
                        capability = capability,
                        argumentsJson = pending?.arguments?.toString() ?: "{}",
                        destructive = destructive,
                        onApprove = { ttlMs ->
                            if (ttlMs > 0L && !destructive) {
                                com.openclaw.assistant.bridge.grants.BridgeGrants.grant(capability, ttlMs)
                            }
                            BridgeApprovalRegistry.respond(requestId, true); finish()
                        },
                        onDeny = { BridgeApprovalRegistry.respond(requestId, false); finish() },
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_REQUEST_ID = "requestId"
    }
}

@Composable
private fun ApprovalPane(
    capability: String,
    argumentsJson: String,
    destructive: Boolean,
    onApprove: (ttlMs: Long) -> Unit,
    onDeny: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text("WakeClaw - " + androidx.compose.ui.res.stringResource(com.openclaw.assistant.R.string.mobile_bridge_title), style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        if (destructive) {
            Text(
                "⚠ " + androidx.compose.ui.res.stringResource(com.openclaw.assistant.R.string.av_approval_destructive_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(androidx.compose.ui.res.stringResource(com.openclaw.assistant.R.string.av_approval_intro), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        Text(capability, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(16.dp))
        Text(androidx.compose.ui.res.stringResource(com.openclaw.assistant.R.string.av_approval_args), style = MaterialTheme.typography.labelLarge)
        Text(argumentsJson, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            OutlinedButton(onClick = onDeny) { Text(androidx.compose.ui.res.stringResource(com.openclaw.assistant.R.string.av_approval_deny)) }
            Button(onClick = { onApprove(0L) }) { Text(androidx.compose.ui.res.stringResource(com.openclaw.assistant.R.string.av_approval_once)) }
        }
        if (!destructive) {
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onApprove(10 * 60_000L) }) { Text(androidx.compose.ui.res.stringResource(com.openclaw.assistant.R.string.av_approval_10min)) }
                OutlinedButton(onClick = { onApprove(60 * 60_000L) }) { Text(androidx.compose.ui.res.stringResource(com.openclaw.assistant.R.string.av_approval_1hr)) }
                OutlinedButton(onClick = { onApprove(Long.MAX_VALUE) }) { Text(androidx.compose.ui.res.stringResource(com.openclaw.assistant.R.string.av_approval_always)) }
            }
        }
    }
}
