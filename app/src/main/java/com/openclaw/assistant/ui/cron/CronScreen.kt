package com.openclaw.assistant.ui.cron

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.openclaw.assistant.OpenClawApplication
import com.openclaw.assistant.R
import com.openclaw.assistant.backend.AgentBackendConfig
import com.openclaw.assistant.backend.BackendRepository
import com.openclaw.assistant.backend.BackendType
import com.openclaw.assistant.backend.HermesConfigApi
import com.openclaw.assistant.backend.HermesCronJob
import com.openclaw.assistant.backend.OpenClawCronApi
import com.openclaw.assistant.backend.OpenClawCronJob
import kotlinx.coroutines.launch

private enum class CronOwner {
    HermesAgent,
    OpenClaw,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CronScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val backendRepository = remember { BackendRepository.getInstance(context) }
    val backends by backendRepository.backends.collectAsState()
    var selectedOwner by rememberSaveable { mutableStateOf(CronOwner.HermesAgent) }

    val hermesBackend = remember(backends) { CronOwners.hermesBackend(backends) }
    val openClawBackends = remember(backends) { CronOwners.openClawBackends(backends) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cron_title)) }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                CronOwnerSelector(
                    selectedOwner = selectedOwner,
                    onOwnerSelected = { selectedOwner = it },
                )
                Box(modifier = Modifier.weight(1f)) {
                    when (selectedOwner) {
                        CronOwner.HermesAgent -> {
                            if (hermesBackend == null) {
                                EmptyCronState(
                                    title = stringResource(R.string.cron_hermes_not_configured),
                                    body = stringResource(R.string.cron_hermes_not_configured_desc),
                                )
                            } else {
                                CronJobList(backend = hermesBackend)
                            }
                        }
                        CronOwner.OpenClaw -> {
                            if (openClawBackends.isEmpty()) {
                                EmptyCronState(
                                    title = stringResource(R.string.cron_openclaw_title),
                                    body = stringResource(R.string.cron_openclaw_not_configured_desc),
                                )
                            } else {
                                OpenClawCronJobList()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Which backend owns each half of the Cron tab.
 *
 * Both OpenClaw kinds run scheduled jobs, so counting only the gateway would
 * hide them on an HTTP-only install; Hermes runs its own, unrelated scheduler.
 */
internal object CronOwners {
    fun hermesBackend(backends: List<AgentBackendConfig>): AgentBackendConfig? =
        backends.firstOrNull { it.enabled && it.type == BackendType.HERMES_API_SERVER }

    fun openClawBackends(backends: List<AgentBackendConfig>): List<AgentBackendConfig> =
        backends.filter {
            it.enabled && (it.type == BackendType.OPENCLAW_GATEWAY || it.type == BackendType.OPENCLAW_HTTP)
        }
}

@Composable
private fun CronOwnerSelector(
    selectedOwner: CronOwner,
    onOwnerSelected: (CronOwner) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = selectedOwner == CronOwner.HermesAgent,
            onClick = { onOwnerSelected(CronOwner.HermesAgent) },
            leadingIcon = { Icon(Icons.Default.SmartToy, contentDescription = null) },
            label = { Text(stringResource(R.string.cron_owner_hermes_agent)) },
        )
        FilterChip(
            selected = selectedOwner == CronOwner.OpenClaw,
            onClick = { onOwnerSelected(CronOwner.OpenClaw) },
            leadingIcon = { Icon(Icons.Default.Terminal, contentDescription = null) },
            label = { Text(stringResource(R.string.cron_owner_openclaw)) },
        )
    }
}

@Composable
private fun EmptyCronState(title: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun CronJobList(backend: AgentBackendConfig) {
    val context = LocalContext.current
    val api = remember { HermesConfigApi() }
    val scope = rememberCoroutineScope()

    var jobs by remember { mutableStateOf<List<HermesCronJob>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingJob by remember { mutableStateOf<HermesCronJob?>(null) }
    var jobToDelete by remember { mutableStateOf<HermesCronJob?>(null) }

    fun refresh() {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                jobs = api.fetchJobs(backend)
            } catch (e: Exception) {
                errorMessage = e.message ?: context.getString(R.string.cron_error_fetch)
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(backend) {
        refresh()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (errorMessage != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = errorMessage!!,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { refresh() }) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_try_again))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_try_again))
                }
            }
        } else if (jobs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.cron_no_jobs),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { refresh() }) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_try_again))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_try_again))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        text = backend.baseUrl.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(jobs, key = { it.id }) { job ->
                    CronJobCard(
                        job = job,
                        onToggle = { enabled ->
                            scope.launch {
                                try {
                                    // Pause/resume are their own endpoints; a plain
                                    // enabled flag does not suspend the schedule.
                                    if (enabled) api.resumeJob(backend, job.id) else api.pauseJob(backend, job.id)
                                    refresh()
                                } catch (e: Exception) {
                                    errorMessage = e.message ?: context.getString(R.string.cron_error_save)
                                }
                            }
                        },
                        onRunNow = {
                            scope.launch {
                                try {
                                    api.runJobNow(backend, job.id)
                                    refresh()
                                } catch (e: Exception) {
                                    errorMessage = e.message ?: context.getString(R.string.cron_error_save)
                                }
                            }
                        },
                        onEdit = { editingJob = job },
                        onDelete = { jobToDelete = job }
                    )
                }
            }
        }

        // Add Job FAB
        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cron_add_job))
        }
    }

    // Add Job Dialog
    if (showAddDialog) {
        JobEditDialog(
            title = stringResource(R.string.cron_add_job),
            onDismiss = { showAddDialog = false },
            onSave = { name, expr, prompt, repeat ->
                scope.launch {
                    isLoading = true
                    try {
                        api.createJob(backend, name, expr, prompt, repeat = repeat)
                        showAddDialog = false
                        refresh()
                    } catch (e: Exception) {
                        errorMessage = e.message ?: context.getString(R.string.cron_error_save)
                        isLoading = false
                    }
                }
            }
        )
    }

    // Edit Job Dialog
    editingJob?.let { job ->
        JobEditDialog(
            title = stringResource(R.string.cron_edit_job),
            initialName = job.name,
            initialSchedule = job.scheduleLabel(),
            initialPrompt = job.prompt,
            initialRepeat = job.repeat?.times,
            onDismiss = { editingJob = null },
            onSave = { name, expr, prompt, repeat ->
                scope.launch {
                    isLoading = true
                    try {
                        api.updateJob(
                            backend,
                            jobId = job.id,
                            name = name,
                            schedule = expr,
                            prompt = prompt,
                            repeat = repeat
                        )
                        editingJob = null
                        refresh()
                    } catch (e: Exception) {
                        errorMessage = e.message ?: context.getString(R.string.cron_error_save)
                        isLoading = false
                    }
                }
            }
        )
    }

    // Delete Confirmation Dialog
    jobToDelete?.let { job ->
        AlertDialog(
            onDismissRequest = { jobToDelete = null },
            title = { Text(stringResource(R.string.cron_delete_job)) },
            text = { Text(stringResource(R.string.cron_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            try {
                                api.deleteJob(backend, job.id)
                                jobToDelete = null
                                refresh()
                            } catch (e: Exception) {
                                errorMessage = e.message ?: context.getString(R.string.cron_error_delete)
                                isLoading = false
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.cron_delete_job), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { jobToDelete = null }) {
                    Text(stringResource(R.string.cron_cancel))
                }
            }
        )
    }
}

@Composable
fun CronJobCard(
    job: HermesCronJob,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRunNow: () -> Unit = {},
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = job.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = job.scheduleLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (job.paused) {
                        Text(
                            text = stringResource(R.string.cron_job_paused),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    job.nextRun?.takeIf { it.isNotBlank() && !job.paused }?.let { next ->
                        Text(
                            text = stringResource(R.string.cron_job_next_run, next),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = !job.paused,
                        onCheckedChange = onToggle
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = onRunNow) {
                        Icon(Icons.Default.PlayArrow, contentDescription = stringResource(R.string.cron_run_now))
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.cron_edit_job))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cron_delete_job), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.cron_job_prompt_value, job.prompt),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.cron_job_deliver_value, cronDeliverLabel(job.deliver)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
            )
            job.repeat?.times?.let { repeatTimes ->
                Spacer(modifier = Modifier.height(4.dp))
                val completed = job.repeat.completed ?: 0
                Text(
                    text = stringResource(R.string.cron_job_repeat_value, completed, repeatTimes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

@Composable
private fun cronDeliverLabel(deliver: String): String {
    return when (deliver.trim().lowercase()) {
        "local" -> stringResource(R.string.cron_deliver_local_only)
        "origin" -> stringResource(R.string.cron_deliver_origin)
        "all" -> stringResource(R.string.cron_deliver_all)
        else -> deliver.ifBlank { stringResource(R.string.cron_deliver_unknown) }
    }
}

@Composable
private fun OpenClawCronJobList() {
    val context = LocalContext.current
    val runtime = remember(context.applicationContext) {
        (context.applicationContext as OpenClawApplication).nodeRuntime
    }
    val api = remember(runtime) { OpenClawCronApi(runtime) }
    val scope = rememberCoroutineScope()

    var jobs by remember { mutableStateOf<List<OpenClawCronJob>>(emptyList()) }
    var deliveryPreviews by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var isLoading by remember { mutableStateOf(true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    var showAddDialog by remember { mutableStateOf(false) }
    var editingJob by remember { mutableStateOf<OpenClawCronJob?>(null) }
    var jobToDelete by remember { mutableStateOf<OpenClawCronJob?>(null) }
    var showDisableAllDialog by remember { mutableStateOf(false) }

    fun refresh() {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                val result = api.fetchJobs()
                jobs = result.jobs
                deliveryPreviews = result.deliveryPreviews.mapValues { it.value.label.orEmpty() }
            } catch (e: Exception) {
                errorMessage = e.message ?: context.getString(R.string.cron_error_fetch)
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (errorMessage != null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = errorMessage!!,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { refresh() }) {
                    Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.action_try_again))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.action_try_again))
                }
            }
        } else if (jobs.isEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.cron_no_jobs),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = { showAddDialog = true }) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cron_add_job))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.cron_add_job))
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (jobs.any { it.enabled }) {
                    item {
                        OutlinedButton(
                            onClick = { showDisableAllDialog = true },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.cron_disable_all))
                        }
                    }
                }
                items(jobs, key = { it.id }) { job ->
                    OpenClawCronJobCard(
                        job = job,
                        deliveryPreview = deliveryPreviews[job.id].orEmpty(),
                        onToggle = { enabled ->
                            scope.launch {
                                try {
                                    api.updateJob(job, enabled = enabled)
                                    refresh()
                                } catch (e: Exception) {
                                    errorMessage = e.message ?: context.getString(R.string.cron_error_save)
                                }
                            }
                        },
                        onEdit = { editingJob = job },
                        onDelete = { jobToDelete = job },
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { showAddDialog = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.cron_add_job))
        }
    }

    if (showAddDialog) {
        JobEditDialog(
            title = stringResource(R.string.cron_add_job),
            onDismiss = { showAddDialog = false },
            onSave = { name, expr, prompt, _ ->
                scope.launch {
                    isLoading = true
                    try {
                        api.createJob(name, expr, prompt)
                        showAddDialog = false
                        refresh()
                    } catch (e: Exception) {
                        errorMessage = e.message ?: context.getString(R.string.cron_error_save)
                        isLoading = false
                    }
                }
            }
        )
    }

    editingJob?.let { job ->
        JobEditDialog(
            title = stringResource(R.string.cron_edit_job),
            initialName = job.name,
            initialSchedule = job.schedule.expr.orEmpty(),
            initialPrompt = job.promptText(),
            onDismiss = { editingJob = null },
            onSave = { name, expr, prompt, _ ->
                scope.launch {
                    isLoading = true
                    try {
                        api.updateJob(job, name = name, expr = expr, prompt = prompt)
                        editingJob = null
                        refresh()
                    } catch (e: Exception) {
                        errorMessage = e.message ?: context.getString(R.string.cron_error_save)
                        isLoading = false
                    }
                }
            }
        )
    }

    if (showDisableAllDialog) {
        AlertDialog(
            onDismissRequest = { showDisableAllDialog = false },
            title = { Text(stringResource(R.string.cron_disable_all_confirm_title)) },
            text = { Text(stringResource(R.string.cron_disable_all_confirm_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            try {
                                api.disableAll(jobs)
                                showDisableAllDialog = false
                                refresh()
                            } catch (e: Exception) {
                                errorMessage = e.message ?: context.getString(R.string.cron_error_save)
                                isLoading = false
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.cron_disable_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableAllDialog = false }) {
                    Text(stringResource(R.string.cron_cancel))
                }
            },
        )
    }

    jobToDelete?.let { job ->
        AlertDialog(
            onDismissRequest = { jobToDelete = null },
            title = { Text(stringResource(R.string.cron_delete_job)) },
            text = { Text(stringResource(R.string.cron_delete_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            isLoading = true
                            try {
                                api.deleteJob(job.id)
                                jobToDelete = null
                                refresh()
                            } catch (e: Exception) {
                                errorMessage = e.message ?: context.getString(R.string.cron_error_delete)
                                isLoading = false
                            }
                        }
                    }
                ) {
                    Text(stringResource(R.string.cron_delete_job), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { jobToDelete = null }) {
                    Text(stringResource(R.string.cron_cancel))
                }
            }
        )
    }
}

@Composable
internal fun OpenClawCronJobCard(
    job: OpenClawCronJob,
    deliveryPreview: String,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = job.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = job.scheduleLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = job.enabled,
                        onCheckedChange = onToggle
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(onClick = onEdit, enabled = job.schedule.kind == "cron") {
                        Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.cron_edit_job))
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cron_delete_job), tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
            val prompt = job.promptText()
            if (prompt.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.cron_job_prompt_value, prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            val delivery = deliveryPreview.ifBlank { job.deliveryLabel() }
            if (delivery.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.cron_job_deliver_value, delivery),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
        }
    }
}

internal fun OpenClawCronJob.promptText(): String = payload.message ?: payload.text.orEmpty()

internal fun OpenClawCronJob.deliveryLabel(): String {
    val mode = delivery?.mode.orEmpty()
    val channel = delivery?.channel.orEmpty()
    val to = delivery?.to.orEmpty()
    return listOf(mode, channel, to).filter { it.isNotBlank() }.joinToString(" ")
}

internal fun OpenClawCronJob.scheduleLabel(): String {
    return when (schedule.kind) {
        "cron" -> listOfNotNull(schedule.expr, schedule.tz?.takeIf { it.isNotBlank() }).joinToString("  ")
        "every" -> schedule.everyMs?.let { "${it / 1000}s" } ?: schedule.kind
        "at" -> schedule.at ?: schedule.kind
        else -> schedule.kind
    }
}

@Composable
fun JobEditDialog(
    title: String,
    initialName: String = "",
    initialSchedule: String = "",
    initialPrompt: String = "",
    initialRepeat: Int? = null,
    onDismiss: () -> Unit,
    onSave: (name: String, expr: String, prompt: String, repeat: Int?) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var expr by remember { mutableStateOf(initialSchedule) }
    var prompt by remember { mutableStateOf(initialPrompt) }
    var repeatStr by remember { mutableStateOf(initialRepeat?.toString() ?: "") }

    var errorText by remember { mutableStateOf<String?>(null) }
    val emptyFieldsMessage = stringResource(R.string.cron_empty_fields)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (errorText != null) {
                    Text(errorText!!, color = MaterialTheme.colorScheme.error)
                }
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.cron_job_name)) },
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = expr,
                    onValueChange = { expr = it },
                    label = { Text(stringResource(R.string.cron_job_schedule)) },
                    modifier = Modifier.fillMaxWidth()
                )
                TextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.cron_job_prompt)) },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3
                )
                TextField(
                    value = repeatStr,
                    onValueChange = { repeatStr = it },
                    label = { Text(stringResource(R.string.cron_job_repeat)) },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (name.isBlank() || expr.isBlank()) {
                        errorText = emptyFieldsMessage
                    } else {
                        val repeatVal = repeatStr.toIntOrNull()
                        onSave(name, expr, prompt, repeatVal)
                    }
                }
            ) {
                Text(stringResource(R.string.cron_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cron_cancel))
            }
        }
    )
}
