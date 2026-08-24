package com.openclaw.assistant.ui.backend

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.assistant.backend.AgentBackendConfig
import com.openclaw.assistant.backend.AgentClientFactory
import com.openclaw.assistant.backend.AgentDiagnostics
import com.openclaw.assistant.backend.BackendRepository
import com.openclaw.assistant.backend.BackendType
import com.openclaw.assistant.backend.ConnectionTestResult
import com.openclaw.assistant.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BackendListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                BackendListScreen(
                    onAdd = { startActivity(BackendEditorActivity.intent(this, null)) },
                    onEdit = { id -> startActivity(BackendEditorActivity.intent(this, id)) },
                    onGuidedHermes = {
                        startActivity(com.openclaw.assistant.ui.setup.HermesSetupActivity.intent(this))
                    },
                )
            }
        }
    }
}

class BackendListViewModel(app: Application) : AndroidViewModel(app) {
    val repo = BackendRepository.getInstance(app)
    val backends = repo.backends

    fun setPrimary(id: String) = repo.setPrimary(id)
    fun setEnabled(id: String, enabled: Boolean) = repo.setEnabled(id, enabled)
    fun delete(id: String) = repo.delete(id)

    suspend fun testConnection(config: AgentBackendConfig): ConnectionTestResult = withContext(Dispatchers.IO) {
        val result = AgentClientFactory.create(config).testConnection()
        AgentDiagnostics.recordHealth(getApplication<Application>().applicationContext, config, result.ok, result.latencyMs, if (result.ok) null else result.message)
        result
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackendListScreen(
    onAdd: () -> Unit,
    onEdit: (String) -> Unit,
    onGuidedHermes: () -> Unit = {},
    viewModel: BackendListViewModel = androidx.lifecycle.viewmodel.compose.viewModel(),
) {
    val backends by viewModel.backends.collectAsState()
    val scope = rememberCoroutineScope()
    val testResults = remember { mutableStateOf(mapOf<String, ConnectionTestResult>()) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(androidx.compose.ui.res.stringResource(com.openclaw.assistant.R.string.backends_title)) }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.add_backend)) },
            )
        },
    ) { padding ->
        if (backends.isEmpty()) {
            EmptyState(padding, onGuidedHermes)
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
                items(backends, key = { it.id }) { backend ->
                    BackendRow(
                        config = backend,
                        testResult = testResults.value[backend.id],
                        onEdit = { onEdit(backend.id) },
                        onSetPrimary = { viewModel.setPrimary(backend.id) },
                        onToggleEnabled = { viewModel.setEnabled(backend.id, it) },
                        onDelete = { viewModel.delete(backend.id) },
                        onTest = {
                            scope.launch {
                                val r = viewModel.testConnection(backend)
                                testResults.value = testResults.value + (backend.id to r)
                            }
                        },
                    )
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun EmptyState(padding: PaddingValues, onGuidedHermes: () -> Unit = {}) {
    Column(
        modifier = Modifier.fillMaxSize().padding(padding).padding(32.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.backends_empty), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.backends_empty_desc),
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        // The guided flow only needs an address, so offer it before the form.
        Button(onClick = onGuidedHermes, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.hermes_setup_title))
        }
    }
}

@Composable
private fun BackendRow(
    config: AgentBackendConfig,
    testResult: ConnectionTestResult?,
    onEdit: () -> Unit,
    onSetPrimary: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onTest: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(elevation = CardDefaults.cardElevation(2.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(config.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(config.typeLabel(), style = MaterialTheme.typography.bodySmall)
                }
                if (config.isPrimary) AssistChip(onClick = {}, label = { Text(stringResource(R.string.primary_backend)) })
                Switch(checked = config.enabled, onCheckedChange = onToggleEnabled)
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more_options))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.av_backends_edit)) }, onClick = { menuOpen = false; onEdit() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.av_backends_set_primary)) }, enabled = !config.isPrimary, onClick = { menuOpen = false; onSetPrimary() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.test_connection)) }, onClick = { menuOpen = false; onTest() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.delete)) }, onClick = { menuOpen = false; onDelete() })
                }
            }
            testResult?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    if (it.ok) "✓ ${it.message}" + (it.latencyMs?.let { l -> " (${l}ms)" } ?: "")
                    else "✗ ${it.message}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun AgentBackendConfig.typeLabel(): String = when (type) {
    BackendType.HERMES_API_SERVER -> stringResource(R.string.backend_type_hermes_with_url, baseUrl ?: stringResource(R.string.no_url))
    BackendType.OPENCLAW_GATEWAY -> stringResource(R.string.backend_type_openclaw_with_host, host ?: "?", port?.toString() ?: "?")
    BackendType.OPENCLAW_HTTP -> stringResource(R.string.backend_type_openclaw_api_with_url, baseUrl ?: stringResource(R.string.no_url))
}
