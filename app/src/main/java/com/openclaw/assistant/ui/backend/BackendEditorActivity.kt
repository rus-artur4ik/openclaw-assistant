package com.openclaw.assistant.ui.backend

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openclaw.assistant.R
import com.openclaw.assistant.backend.AgentBackendConfig
import com.openclaw.assistant.backend.AgentClientFactory
import com.openclaw.assistant.backend.AgentContextInspector
import com.openclaw.assistant.backend.AgentDiagnostics
import com.openclaw.assistant.backend.BackendRepository
import com.openclaw.assistant.backend.BackendType
import com.openclaw.assistant.backend.HermesConfigApi
import com.openclaw.assistant.backend.HermesModelOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BackendEditorActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_BACKEND_ID)
        setContent {
            MaterialTheme {
                BackendEditorScreen(existingId = id, onDone = { finish() })
            }
        }
    }

    companion object {
        private const val EXTRA_BACKEND_ID = "backendId"
        fun intent(context: Context, id: String?): Intent =
            Intent(context, BackendEditorActivity::class.java).apply { id?.let { putExtra(EXTRA_BACKEND_ID, it) } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackendEditorScreen(existingId: String?, onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repo = remember { BackendRepository.getInstance(context) }
    val backends by repo.backends.collectAsState()
    val existing = remember(existingId, backends) { backends.firstOrNull { it.id == existingId } }

    var type by remember { mutableStateOf(existing?.type ?: BackendType.HERMES_API_SERVER) }
    var displayName by remember { mutableStateOf(existing?.displayName ?: defaultName(type)) }
    var baseUrl by remember { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var token by remember { mutableStateOf(existing?.apiKeyOrToken.orEmpty()) }
    var host by remember { mutableStateOf(existing?.host.orEmpty()) }
    var port by remember { mutableStateOf(existing?.port?.toString().orEmpty()) }
    var useTls by remember { mutableStateOf(existing?.useTls ?: true) }
    var modelName by remember { mutableStateOf(existing?.modelName ?: "default") }
    var providerName by remember { mutableStateOf(existing?.providerName.orEmpty()) }
    var transport by remember {
        mutableStateOf(existing?.effectiveTransport ?: com.openclaw.assistant.backend.HermesTransportPreference.AUTO)
    }
    var memoryScopeKey by remember { mutableStateOf(existing?.memoryScopeKey.orEmpty()) }
    var agentContextName by remember { mutableStateOf(existing?.agentContextName.orEmpty()) }
    var agentContextDetail by remember { mutableStateOf(existing?.agentContextDetail.orEmpty()) }
    var preferredEndpointRole by remember { mutableStateOf(existing?.preferredEndpointRole.orEmpty()) }
    var useRunsApi by remember { mutableStateOf(existing?.useRunsApi ?: true) }
    var useStreaming by remember { mutableStateOf(existing?.useStreaming ?: true) }
    var setPrimary by remember { mutableStateOf(existing?.isPrimary ?: backends.isEmpty()) }
    var lanUrl by remember { mutableStateOf(existing?.secondaryUrls?.getOrNull(0).orEmpty()) }
    var tailscaleUrl by remember { mutableStateOf(existing?.secondaryUrls?.getOrNull(1).orEmpty()) }
    var publicUrl by remember { mutableStateOf(existing?.secondaryUrls?.getOrNull(2).orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }
    var hermesModels by remember { mutableStateOf<List<HermesModelOption>>(emptyList()) }
    var hermesProviders by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = rememberCoroutineScope()
    val defaultModelName = "default"
    val inspectingAgentContext = stringResource(R.string.backend_inspecting_agent_context)
    val loadingHermesModels = stringResource(R.string.backend_loading_hermes_models)
    val applyingHermesModel = stringResource(R.string.backend_applying_hermes_model)
    val testingLabel = stringResource(R.string.testing)
    val loadedModelsFormat = stringResource(R.string.backend_loaded_models)
    val loadedModelsProviderFormat = stringResource(R.string.backend_loaded_models_provider)
    val hermesModelUpdatedFormat = stringResource(R.string.backend_hermes_model_updated)
    val hermesModelsLoadFailedFormat = stringResource(R.string.backend_hermes_models_load_failed)
    val hermesModelUpdateFailedFormat = stringResource(R.string.backend_hermes_model_update_failed)

    Scaffold(topBar = { TopAppBar(title = { Text(if (existing == null) androidx.compose.ui.res.stringResource(com.openclaw.assistant.R.string.add_backend) else androidx.compose.ui.res.stringResource(com.openclaw.assistant.R.string.av_backends_edit)) }) }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.backend_type), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BackendType.values().forEach { t ->
                    FilterChip(selected = type == t, onClick = {
                        type = t
                        if (displayName.isBlank() || displayName == defaultName(t)) displayName = defaultName(t)
                    }, label = { Text(shortLabel(t)) })
                }
            }
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(value = displayName, onValueChange = { displayName = it }, label = { Text(stringResource(R.string.backend_display_name)) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.backend_agent_context), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = agentContextName,
                onValueChange = { agentContextName = it },
                label = { Text(stringResource(R.string.backend_agent_context_name)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = agentContextDetail,
                onValueChange = { agentContextDetail = it },
                label = { Text(stringResource(R.string.backend_agent_context_detail)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = preferredEndpointRole,
                onValueChange = { preferredEndpointRole = it },
                label = { Text(stringResource(R.string.backend_preferred_route_label)) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (type == BackendType.HERMES_API_SERVER) {
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    val config = buildConfig(
                        existing = existing,
                        type = type,
                        displayName = displayName,
                        baseUrl = baseUrl,
                        token = token,
                        host = host,
                        port = port,
                        useTls = useTls,
                        modelName = modelName,
                        useRunsApi = useRunsApi,
                        useStreaming = useStreaming,
                        isPrimary = setPrimary,
                        secondaryUrls = listOf(lanUrl, tailscaleUrl, publicUrl).filter { it.isNotBlank() },
                        agentContextName = agentContextName,
                        agentContextDetail = agentContextDetail,
                        preferredEndpointRole = preferredEndpointRole,
                    )
                    scope.launch {
                        status = inspectingAgentContext
                        val inspection = AgentContextInspector().inspect(config)
                        inspection.contextName?.let { agentContextName = it }
                        inspection.contextDetail?.let { agentContextDetail = it }
                        status = inspection.summary
                    }
                }, enabled = baseUrl.isNotBlank()) {
                    Text(stringResource(R.string.backend_inspect_agent_context))
                }
            }
            Spacer(Modifier.height(12.dp))

            when (type) {
                BackendType.HERMES_API_SERVER -> {
                    OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text(stringResource(R.string.backend_primary_url_label)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.backend_additional_endpoints_desc), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = lanUrl, onValueChange = { lanUrl = it }, label = { Text(stringResource(R.string.backend_lan_url)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = tailscaleUrl, onValueChange = { tailscaleUrl = it }, label = { Text(stringResource(R.string.backend_tailscale_url)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(value = publicUrl, onValueChange = { publicUrl = it }, label = { Text(stringResource(R.string.backend_public_url)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text(stringResource(R.string.av_import_api_key)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = modelName, onValueChange = { modelName = it }, label = { Text(androidx.compose.ui.res.stringResource(com.openclaw.assistant.R.string.av_import_model)) }, modifier = Modifier.fillMaxWidth())
                    Text(androidx.compose.ui.res.stringResource(com.openclaw.assistant.R.string.av_import_model_help), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            val config = buildConfig(existing, type, displayName, baseUrl, token, host, port, useTls, modelName, useRunsApi, useStreaming, setPrimary, listOf(lanUrl, tailscaleUrl, publicUrl).filter { it.isNotBlank() }, agentContextName, agentContextDetail, preferredEndpointRole, providerName, transport, memoryScopeKey)
                            scope.launch {
                                status = loadingHermesModels
                                runCatching { HermesConfigApi().fetchCatalog(config) }
                                    .onSuccess { catalog ->
                                        hermesModels = catalog.models
                                        hermesProviders = catalog.providers
                                        catalog.config?.model?.takeIf { it.isNotBlank() }?.let { modelName = it }
                                        catalog.config?.provider?.takeIf { it.isNotBlank() }?.let { providerName = it }
                                        status = buildString {
                                            val loaded = loadedModelsFormat.format(catalog.models.size)
                                            append(loaded)
                                            catalog.config?.provider?.takeIf { it.isNotBlank() }?.let { append(loadedModelsProviderFormat.format(it)) }
                                        }
                                    }
                                    .onFailure { status = hermesModelsLoadFailedFormat.format(it.message ?: it.javaClass.simpleName) }
                            }
                        }, enabled = baseUrl.isNotBlank()) {
                            Text(stringResource(R.string.backend_load_models))
                        }
                        OutlinedButton(onClick = {
                            val config = buildConfig(existing, type, displayName, baseUrl, token, host, port, useTls, modelName, useRunsApi, useStreaming, setPrimary, listOf(lanUrl, tailscaleUrl, publicUrl).filter { it.isNotBlank() }, agentContextName, agentContextDetail, preferredEndpointRole, providerName, transport, memoryScopeKey)
                            scope.launch {
                                status = applyingHermesModel
                                // Hermes has no writable global config over the API
                                // server (it advertises admin_config_rw: false), so the
                                // choice is stored here and sent with every request as
                                // model + provider, which the server always honours.
                                runCatching {
                                    repo.upsert(config)
                                    if (setPrimary) repo.setPrimary(config.id)
                                    com.openclaw.assistant.backend.HermesCapabilityCache.invalidate(config.id)
                                }
                                    .onSuccess { status = hermesModelUpdatedFormat.format(config.modelName ?: defaultModelName) }
                                    .onFailure { status = hermesModelUpdateFailedFormat.format(it.message ?: it.javaClass.simpleName) }
                            }
                        }, enabled = baseUrl.isNotBlank() && modelName.isNotBlank()) {
                            Text(stringResource(R.string.backend_apply_to_hermes))
                        }
                    }
                    if (hermesProviders.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.backend_providers_value, hermesProviders.joinToString(", ")), style = MaterialTheme.typography.bodySmall)
                    }
                    if (hermesModels.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            hermesModels.take(8).forEach { option ->
                                AssistChip(
                                    onClick = {
                                        modelName = option.id
                                        // Carry the provider with the model — without it
                                        // Hermes ignores the model on its
                                        // OpenAI-compatible endpoints.
                                        option.provider?.takeIf { it.isNotBlank() }?.let { providerName = it }
                                    },
                                    label = {
                                        Text(
                                            listOfNotNull(option.id, option.description?.takeIf { it.isNotBlank() }).joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                        )
                                    },
                                )
                            }
                            if (hermesModels.size > 8) {
                                Text(stringResource(R.string.backend_more_models, hermesModels.size - 8), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = providerName,
                        onValueChange = { providerName = it },
                        label = { Text(stringResource(R.string.backend_hermes_provider)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.backend_hermes_provider_help), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = memoryScopeKey,
                        onValueChange = { memoryScopeKey = it },
                        label = { Text(stringResource(R.string.backend_hermes_memory_scope)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(stringResource(R.string.backend_hermes_memory_scope_help), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.backend_hermes_transport), style = MaterialTheme.typography.titleSmall)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        hermesTransportChoices().forEach { (choice, label) ->
                            FilterChip(
                                selected = transport == choice,
                                onClick = { transport = choice },
                                label = { Text(label) },
                            )
                        }
                    }
                    Text(stringResource(R.string.backend_hermes_transport_help), style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = useStreaming, onCheckedChange = { useStreaming = it }); Text(androidx.compose.ui.res.stringResource(com.openclaw.assistant.R.string.av_hermes_stream_responses))
                    }
                }
                BackendType.OPENCLAW_GATEWAY -> {
                    OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text(stringResource(R.string.gateway_host)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit) }, label = { Text(stringResource(R.string.gateway_port)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text(stringResource(R.string.backend_openclaw_token)) }, modifier = Modifier.fillMaxWidth())
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(checked = useTls, onCheckedChange = { useTls = it }); Text(stringResource(R.string.backend_use_tls))
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.backend_openclaw_setup_hint),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                BackendType.OPENCLAW_HTTP -> {
                    OutlinedTextField(value = baseUrl, onValueChange = { baseUrl = it }, label = { Text(stringResource(R.string.backend_base_url)) }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = token, onValueChange = { token = it }, label = { Text(stringResource(R.string.auth_token_label)) }, modifier = Modifier.fillMaxWidth())
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = setPrimary, onCheckedChange = { setPrimary = it }); Text(stringResource(R.string.backend_mark_primary))
            }

            Spacer(Modifier.height(16.dp))
            val secondary = listOf(lanUrl, tailscaleUrl, publicUrl).filter { it.isNotBlank() }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val config = buildConfig(existing, type, displayName, baseUrl, token, host, port, useTls, modelName, useRunsApi, useStreaming, setPrimary, secondary, agentContextName, agentContextDetail, preferredEndpointRole, providerName, transport, memoryScopeKey)
                    repo.upsert(config)
                    if (setPrimary) repo.setPrimary(config.id)
                    onDone()
                }) { Text(stringResource(R.string.save)) }

                Button(onClick = {
                    val config = buildConfig(existing, type, displayName, baseUrl, token, host, port, useTls, modelName, useRunsApi, useStreaming, setPrimary, secondary, agentContextName, agentContextDetail, preferredEndpointRole, providerName, transport, memoryScopeKey)
                    scope.launch {
                        status = testingLabel
                        val r = withContext(Dispatchers.IO) { AgentClientFactory.create(config).testConnection() }
                        AgentDiagnostics.recordHealth(context, config, r.ok, r.latencyMs, if (r.ok) null else r.message)
                        status = if (r.ok) "✓ ${r.message}" else "✗ ${r.message}"
                    }
                }) { Text(stringResource(R.string.av_home_test_short)) }
            }
            status?.let { Spacer(Modifier.height(8.dp)); Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun buildConfig(
    existing: AgentBackendConfig?,
    type: BackendType,
    displayName: String,
    baseUrl: String,
    token: String,
    host: String,
    port: String,
    useTls: Boolean,
    modelName: String,
    useRunsApi: Boolean,
    useStreaming: Boolean,
    isPrimary: Boolean,
    secondaryUrls: List<String> = emptyList(),
    agentContextName: String = "",
    agentContextDetail: String = "",
    preferredEndpointRole: String = "",
    providerName: String = "",
    transport: com.openclaw.assistant.backend.HermesTransportPreference? = null,
    memoryScopeKey: String = "",
): AgentBackendConfig {
    val base = existing ?: AgentBackendConfig(displayName = displayName, type = type)
    return base.copy(
        displayName = displayName.ifBlank { defaultName(type) },
        type = type,
        baseUrl = baseUrl.ifBlank { null },
        apiKeyOrToken = token.ifBlank { null },
        host = host.ifBlank { null },
        port = port.toIntOrNull(),
        useTls = useTls,
        modelName = modelName.ifBlank { null },
        providerName = providerName.ifBlank { null },
        useRunsApi = useRunsApi,
        transport = transport,
        memoryScopeKey = memoryScopeKey.ifBlank { null },
        useStreaming = useStreaming,
        isPrimary = isPrimary,
        secondaryUrls = secondaryUrls,
        agentContextName = agentContextName.ifBlank { null },
        agentContextDetail = agentContextDetail.ifBlank { null },
        preferredEndpointRole = preferredEndpointRole.ifBlank { null },
    )
}

@Composable
private fun hermesTransportChoices(): List<Pair<com.openclaw.assistant.backend.HermesTransportPreference, String>> = listOf(
    com.openclaw.assistant.backend.HermesTransportPreference.AUTO to stringResource(R.string.backend_hermes_transport_auto),
    com.openclaw.assistant.backend.HermesTransportPreference.SESSION_CHAT to stringResource(R.string.backend_hermes_transport_session),
    com.openclaw.assistant.backend.HermesTransportPreference.RUNS to stringResource(R.string.backend_hermes_transport_runs),
    com.openclaw.assistant.backend.HermesTransportPreference.CHAT_COMPLETIONS to stringResource(R.string.backend_hermes_transport_chat),
)

private fun shortLabel(t: BackendType) = when (t) {
    BackendType.HERMES_API_SERVER -> "Hermes Agent"
    BackendType.OPENCLAW_GATEWAY -> "OpenClaw"
    BackendType.OPENCLAW_HTTP -> "OpenClaw API"
}

private fun defaultName(t: BackendType) = when (t) {
    BackendType.HERMES_API_SERVER -> "Hermes Agent"
    BackendType.OPENCLAW_GATEWAY -> "OpenClaw"
    BackendType.OPENCLAW_HTTP -> "OpenClaw API"
}
