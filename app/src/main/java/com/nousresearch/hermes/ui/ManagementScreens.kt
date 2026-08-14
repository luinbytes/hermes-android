package com.nousresearch.hermes.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.data.BackendConfig
import com.nousresearch.hermes.data.HermesState
import com.nousresearch.hermes.data.ProfileIdentityDraft
import com.nousresearch.hermes.network.DashboardAuthProvider
import com.nousresearch.hermes.protocol.CronJob
import com.nousresearch.hermes.protocol.ProfileInfo
import com.nousresearch.hermes.protocol.SkillInfo
import com.nousresearch.hermes.protocol.SkillHubResult
import com.nousresearch.hermes.protocol.StoredSession
import com.nousresearch.hermes.protocol.ToolsetInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class CapabilityView { SKILLS, HUB, TOOLSETS }

@Composable
internal fun BackendsScreen(
    state: HermesState,
    onDiscoverPasswordProviders: suspend (String, Boolean) -> List<DashboardAuthProvider>,
    onConnect: (String, String, String, String, Boolean, String) -> Unit,
    onSelect: (String) -> Unit,
    onForget: (String) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var adding by rememberSaveable { mutableStateOf(false) }
    var reconnectId by remember { mutableStateOf<String?>(null) }
    var forgetId by remember { mutableStateOf<String?>(null) }
    val forgetBackend = state.savedBackends.firstOrNull { it.id == forgetId }
    Column(modifier.fillMaxSize()) {
        ManagementHeader("BACKENDS", "Saved Hermes installations", state.loading, null, onBack)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        ) {
            Text(
                "Connection metadata is stored in app-private preferences. Dashboard session cookies are encrypted separately with Android Keystore and are never displayed here.",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(onClick = { reconnectId = null; adding = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
            Icon(Icons.Outlined.Add, null)
            Spacer(Modifier.width(6.dp))
            Text("Add backend")
        }
        state.error?.let { ManagementError(it) }
        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(state.savedBackends, key = BackendConfig::id) { backend ->
                val selected = backend.id == state.backend?.id
                Surface(
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(backend.label, fontWeight = FontWeight.SemiBold)
                                Text(backend.baseUrl, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (selected) {
                                Text("CONNECTED", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            } else {
                                val reconnectRequired = backend.authMode != com.nousresearch.hermes.data.AuthMode.DASHBOARD_SESSION ||
                                    state.reconnectRequiredBackendId == backend.id
                                TextButton(
                                    onClick = {
                                        if (reconnectRequired) {
                                            reconnectId = backend.id
                                            adding = true
                                        } else {
                                            onSelect(backend.id)
                                        }
                                    },
                                ) { Text(if (reconnectRequired) "Reconnect" else "Connect") }
                            }
                            IconButton(onClick = { forgetId = backend.id }) { Icon(Icons.Outlined.Delete, "Forget ${backend.label}") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            backend.lastHermesVersion?.let { Text("HERMES $it", style = MaterialTheme.typography.labelSmall) }
                            if (backend.baseUrl.startsWith("http://")) {
                                Text("PRIVATE HTTP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                            } else {
                                Text("TLS", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        BackendConnectionDialog(
            initial = state.savedBackends.firstOrNull { it.id == reconnectId },
            onDiscoverPasswordProviders = onDiscoverPasswordProviders,
            onDismiss = { adding = false; reconnectId = null },
            onConnect = { label, url, username, password, allowPrivate, provider ->
                adding = false
                reconnectId = null
                onConnect(label, url, username, password, allowPrivate, provider)
            },
        )
    }
    forgetBackend?.let { backend ->
        AlertDialog(
            onDismissRequest = { forgetId = null },
            title = { Text("FORGET BACKEND?") },
            text = { Text("${backend.label} will be removed from this device and its encrypted Dashboard session deleted. Nothing is deleted from the Hermes server.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        forgetId = null
                        onForget(backend.id)
                    },
                ) { Text("Forget") }
            },
            dismissButton = { TextButton(onClick = { forgetId = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BackendConnectionDialog(
    initial: BackendConfig?,
    onDiscoverPasswordProviders: suspend (String, Boolean) -> List<DashboardAuthProvider>,
    onDismiss: () -> Unit,
    onConnect: (String, String, String, String, Boolean, String) -> Unit,
) {
    var label by remember(initial?.id) { mutableStateOf(initial?.label.orEmpty()) }
    var url by remember(initial?.id) { mutableStateOf(initial?.baseUrl.orEmpty()) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var allowPrivate by rememberSaveable(initial?.id) { mutableStateOf(initial?.allowInsecurePrivateNetwork == true) }
    var passwordProviders by remember { mutableStateOf(emptyList<DashboardAuthProvider>()) }
    var selectedProvider by remember { mutableStateOf<String?>(null) }
    var providerSource by remember { mutableStateOf<String?>(null) }
    var providerError by remember { mutableStateOf<String?>(null) }
    var discoveringProviders by remember { mutableStateOf(false) }
    val providerDiscoveryGate = remember { DashboardProviderDiscoveryGate() }
    val providerScope = rememberCoroutineScope()
    val providerKey = "${url.trim().trimEnd('/')}|$allowPrivate"

    fun clearProviderSelection() {
        providerDiscoveryGate.invalidate()
        discoveringProviders = false
        passwordProviders = emptyList()
        selectedProvider = null
        providerSource = null
        providerError = null
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "ADD HERMES BACKEND" else "RECONNECT HERMES BACKEND") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(label, { label = it.take(100) }, label = { Text("Connection name") }, singleLine = true)
                OutlinedTextField(
                    url,
                    { value -> url = value; clearProviderSelection() },
                    label = { Text("HTTPS URL") },
                    singleLine = true,
                )
                OutlinedTextField(
                    username,
                    { username = it },
                    label = { Text("Dashboard username") },
                    singleLine = true,
                )
                OutlinedTextField(
                    password,
                    { password = it },
                    label = { Text("Dashboard password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Use private-network HTTP")
                        Text("Only literal LAN, loopback, or Tailscale IPs.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(
                        checked = allowPrivate,
                        onCheckedChange = { allowPrivate = it; clearProviderSelection() },
                        modifier = Modifier.semantics { contentDescription = "Use private-network HTTP" },
                    )
                }
                if (passwordProviders.size > 1 && providerSource == providerKey) {
                    DashboardPasswordProviderSelector(
                        providers = passwordProviders,
                        selectedProvider = selectedProvider,
                        onSelected = { selectedProvider = it; providerError = null },
                    )
                }
                DashboardOAuthAvailabilityNotice()
                providerError?.let { ManagementError(it) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val submit: (String) -> Unit = { provider ->
                        val submittedPassword = password
                        password = ""
                        onConnect(label, url, username, submittedPassword, allowPrivate, provider)
                    }
                    if (providerSource == providerKey) {
                        selectedProvider?.let(submit)
                    } else {
                        val requestToken = providerDiscoveryGate.begin()
                        if (requestToken != null) {
                            val requestedUrl = url
                            val requestedAllowPrivate = allowPrivate
                            discoveringProviders = true
                            providerError = null
                            providerScope.launch {
                                try {
                                    val providers = onDiscoverPasswordProviders(requestedUrl, requestedAllowPrivate)
                                    if (providerDiscoveryGate.isCurrent(requestToken)) {
                                        providerSource = "${requestedUrl.trim().trimEnd('/')}|$requestedAllowPrivate"
                                        passwordProviders = providers
                                        selectedProvider = providers.singleOrNull()?.name
                                        if (providers.size == 1) submit(providers.single().name)
                                    }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (failure: Throwable) {
                                    if (providerDiscoveryGate.isCurrent(requestToken)) {
                                        clearProviderSelection()
                                        providerError = failure.message ?: "Could not load Dashboard sign-in providers."
                                    }
                                } finally {
                                    val current = providerDiscoveryGate.isCurrent(requestToken)
                                    providerDiscoveryGate.finish(requestToken)
                                    if (current) discoveringProviders = false
                                }
                            }
                        }
                    }
                },
                enabled = !discoveringProviders && url.isNotBlank() && username.isNotBlank() && password.isNotEmpty() &&
                    (providerSource != providerKey || passwordProviders.size == 1 || selectedProvider != null),
            ) {
                if (discoveringProviders) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                } else {
                    Text(if (providerSource == providerKey) "Test and save" else "Check sign-in options")
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun SkillsScreen(
    state: HermesState,
    onRefresh: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onRefreshToolsets: () -> Unit,
    onToggleToolset: (String, Boolean) -> Unit,
    onLoadHub: (String) -> Unit,
    onReview: (String) -> Unit,
    onCloseReview: () -> Unit,
    onInstall: () -> Unit,
    onUninstall: (String) -> Unit,
    onUpdate: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var view by rememberSaveable { mutableStateOf(CapabilityView.SKILLS) }
    var uninstallName by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { onRefresh() }
    val visible = state.skills.filter {
        query.isBlank() || it.name.contains(query, true) || it.description.contains(query, true) || it.category.orEmpty().contains(query, true)
    }
    val visibleToolsets = state.toolsets.filter {
        query.isBlank() || it.name.contains(query, true) || it.label.contains(query, true) ||
            it.description.contains(query, true) || it.tools.any { tool -> tool.contains(query, true) }
    }
    val refresh: () -> Unit = {
        when (view) {
            CapabilityView.SKILLS -> onRefresh()
            CapabilityView.HUB -> onLoadHub(query)
            CapabilityView.TOOLSETS -> onRefreshToolsets()
        }
    }
    Column(modifier.fillMaxSize()) {
        ManagementHeader(
            "CAPABILITIES",
            when (view) {
                CapabilityView.SKILLS -> "Installed skills"
                CapabilityView.HUB -> "Review before installing"
                CapabilityView.TOOLSETS -> "Server tools for ${state.activeProfile}"
            },
            state.managementLoading || state.skillHubLoading || state.toolsetsLoading,
            refresh,
            onBack,
        )
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CapabilityTab("Skills", view == CapabilityView.SKILLS, Modifier.weight(1f)) {
                view = CapabilityView.SKILLS
            }
            CapabilityTab("Hub", view == CapabilityView.HUB, Modifier.weight(1f)) {
                view = CapabilityView.HUB
                onLoadHub("")
            }
            CapabilityTab("Tools", view == CapabilityView.TOOLSETS, Modifier.weight(1f)) {
                view = CapabilityView.TOOLSETS
                onRefreshToolsets()
            }
        }
        if (view == CapabilityView.SKILLS) {
            OutlinedButton(
                onClick = onUpdate,
                enabled = state.skillAction?.running != true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            ) { Text("Update all skills", maxLines = 1, style = MaterialTheme.typography.labelMedium) }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it.take(200) },
            placeholder = {
                Text(
                    when (view) {
                        CapabilityView.SKILLS -> "Search installed skills"
                        CapabilityView.HUB -> "Search the Hermes skills hub"
                        CapabilityView.TOOLSETS -> "Search toolsets and tools"
                    },
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        )
        if (view == CapabilityView.HUB) {
            Button(
                onClick = { onLoadHub(query) },
                enabled = query.isNotBlank() && !state.skillHubLoading,
                modifier = Modifier.padding(horizontal = 12.dp),
            ) { Text("Search hub") }
        }
        if (view != CapabilityView.TOOLSETS) state.skillAction?.let { action ->
            Text(
                when {
                    action.running -> "Skill operation running on Hermes${action.pid?.let { " / PID $it" }.orEmpty()}"
                    action.exitCode == 0 -> "Skill operation completed"
                    action.error != null -> action.error
                    else -> "Skill operation exited ${action.exitCode ?: "without status"}"
                },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = if (action.exitCode != null && action.exitCode != 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }
        if (state.error != null) ManagementError(state.error)
        if (view == CapabilityView.TOOLSETS) {
            state.toolsetNotice?.let {
                Text(it, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            state.toolsetError?.let { ManagementError(it) }
            if (!state.toolsetsLoading && visibleToolsets.isEmpty()) {
                ManagementEmpty(if (state.toolsets.isEmpty()) "No configurable toolsets were returned by Hermes." else "No matching toolsets.")
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visibleToolsets, key = ToolsetInfo::name) { toolset ->
                        ToolsetRow(toolset, state.toolsetsLoading, onToggleToolset, Modifier.padding(horizontal = 12.dp))
                    }
                }
            }
        } else if (view == CapabilityView.HUB) {
            if (!state.skillHubLoading && state.skillHubResults.isEmpty()) {
                ManagementEmpty("No hub results. Search by capability, tool or workflow.")
            } else {
                LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.skillHubResults, key = SkillHubResult::identifier) { skill ->
                        SkillHubRow(skill, onReview, Modifier.padding(horizontal = 12.dp))
                    }
                }
            }
        } else if (!state.managementLoading && visible.isEmpty()) {
            ManagementEmpty(if (state.skills.isEmpty()) "No installed skills were returned by Hermes." else "No matching skills.")
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(visible, key = SkillInfo::name) { skill ->
                    SkillRow(skill, onToggle, { uninstallName = it }, Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }

    state.skillHubReview?.let { review ->
        val blocked = review.scan.policy == "block"
        AlertDialog(
            onDismissRequest = onCloseReview,
            title = { Text("REVIEW ${review.preview.name.uppercase()}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${review.preview.trustLevel.uppercase()} / ${review.preview.source} / ${review.scan.verdict.uppercase()}")
                    Text(review.scan.summary.ifBlank { review.preview.description }, style = MaterialTheme.typography.bodySmall)
                    review.scan.policyReason?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (review.scan.findings.isNotEmpty()) {
                        Text(
                            review.scan.findings.take(8).joinToString("\n") { "${it.severity.uppercase()} · ${it.file}${it.line?.let { line -> ":$line" }.orEmpty()} · ${it.description}" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text("FILES / ${review.preview.files.joinToString().ifBlank { "SKILL.md only" }}", style = MaterialTheme.typography.labelSmall)
                    Text(review.preview.skillMarkdown.take(4_000), style = MaterialTheme.typography.bodySmall, maxLines = 14, overflow = TextOverflow.Ellipsis)
                }
            },
            confirmButton = {
                Button(onClick = onInstall, enabled = !blocked) {
                    Text(if (review.scan.policy == "ask") "Accept risk and install" else if (blocked) "Blocked by Hermes" else "Install")
                }
            },
            dismissButton = { TextButton(onClick = onCloseReview) { Text("Cancel") } },
        )
    }
    uninstallName?.let { name ->
        AlertDialog(
            onDismissRequest = { uninstallName = null },
            title = { Text("REMOVE SKILL") },
            text = { Text("Uninstall $name from the selected Hermes profile?") },
            confirmButton = { TextButton(onClick = { uninstallName = null; onUninstall(name) }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { uninstallName = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun CapabilityTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(label, maxLines = 1, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
internal fun CronScreen(
    state: HermesState,
    onRefresh: () -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onTrigger: (String) -> Unit,
    onLoadRuns: (String) -> Unit,
    onOpenRun: (StoredSession) -> Unit,
    onCreate: (String, String, String, String) -> Unit,
    onUpdate: (String, String, String, String, String) -> Unit,
    onDelete: (String) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var editorJobId by remember { mutableStateOf<String?>(null) }
    var creating by rememberSaveable { mutableStateOf(false) }
    var deleteJobId by remember { mutableStateOf<String?>(null) }
    var expandedJobId by remember { mutableStateOf<String?>(null) }
    var pendingToggle by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var pendingRunJobId by remember { mutableStateOf<String?>(null) }
    val editorJob = state.cronJobs.firstOrNull { it.id == editorJobId }
    val deleteJob = state.cronJobs.firstOrNull { it.id == deleteJobId }
    LaunchedEffect(Unit) { onRefresh() }
    Column(modifier.fillMaxSize()) {
        ManagementHeader("AUTOMATIONS", "Server-side Hermes cron", state.managementLoading, onRefresh, onBack)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        ) {
            Text(
                "Schedules execute on the Hermes backend, not on this Android device. Android notifications are a separate delivery surface.",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(onClick = { creating = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
            Icon(Icons.Outlined.Add, null)
            Spacer(Modifier.width(6.dp))
            Text("Create job")
        }
        if (state.error != null) ManagementError(state.error)
        if (!state.managementLoading && state.cronJobs.isEmpty()) {
            ManagementEmpty("No cron jobs are configured on this Hermes backend.")
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.cronJobs, key = CronJob::id) { job ->
                    CronRow(
                        job,
                        onSetEnabled = { id, enabled -> pendingToggle = id to enabled },
                        onTrigger = { pendingRunJobId = it },
                        runs = state.cronRuns[job.id],
                        expanded = expandedJobId == job.id,
                        onHistory = {
                            val expanding = expandedJobId != job.id
                            expandedJobId = if (expanding) job.id else null
                            if (expanding) onLoadRuns(job.id)
                        },
                        onOpenRun = onOpenRun,
                        onEdit = { editorJobId = job.id },
                        onDelete = { deleteJobId = job.id },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }

    if (creating) {
        CronEditorDialog(
            job = null,
            onDismiss = { creating = false },
            onSave = { name, prompt, schedule, deliver ->
                creating = false
                onCreate(name, prompt, schedule, deliver)
            },
        )
    }
    editorJob?.let { job ->
        CronEditorDialog(
            job = job,
            onDismiss = { editorJobId = null },
            onSave = { name, prompt, schedule, deliver ->
                editorJobId = null
                onUpdate(job.id, name, prompt, schedule, deliver)
            },
        )
    }
    deleteJob?.let { job ->
        AlertDialog(
            onDismissRequest = { deleteJobId = null },
            title = { Text("DELETE CRON JOB?") },
            text = { Text("${job.name ?: job.id} will stop running on the Hermes backend. Existing run sessions are not deleted.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteJobId = null
                        onDelete(job.id)
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteJobId = null }) { Text("Cancel") } },
        )
    }
    pendingToggle?.let { (jobId, enabled) ->
        val job = state.cronJobs.firstOrNull { it.id == jobId }
        AlertDialog(
            onDismissRequest = { pendingToggle = null },
            title = { Text(if (enabled) "RESUME CRON JOB?" else "PAUSE CRON JOB?") },
            text = {
                Text(
                    "${if (enabled) "Resume" else "Pause"} ${job?.name?.takeIf(String::isNotBlank) ?: jobId} " +
                        "for profile ${state.activeProfile}? ${if (enabled) "Future scheduled runs will resume." else "Future scheduled runs will stop until resumed."}",
                )
            },
            confirmButton = {
                TextButton(onClick = { pendingToggle = null; onSetEnabled(jobId, enabled) }) {
                    Text(if (enabled) "Resume" else "Pause")
                }
            },
            dismissButton = { TextButton(onClick = { pendingToggle = null }) { Text("Cancel") } },
        )
    }
    pendingRunJobId?.let { jobId ->
        val job = state.cronJobs.firstOrNull { it.id == jobId }
        AlertDialog(
            onDismissRequest = { pendingRunJobId = null },
            title = { Text("RUN CRON JOB NOW?") },
            text = {
                Text(
                    "Run ${job?.name?.takeIf(String::isNotBlank) ?: jobId} now for profile ${state.activeProfile}? " +
                        "Hermes will start backend work immediately and may deliver the configured result.",
                )
            },
            confirmButton = {
                TextButton(onClick = { pendingRunJobId = null; onTrigger(jobId) }) { Text("Run now") }
            },
            dismissButton = { TextButton(onClick = { pendingRunJobId = null }) { Text("Cancel") } },
        )
    }
}

@Composable
internal fun ProfilesScreen(
    state: HermesState,
    onRefresh: () -> Unit,
    onStartSession: (String) -> Unit,
    onCreate: (String, String, Boolean, Boolean) -> Unit,
    onRename: (String, String) -> Unit,
    onSetActive: (String) -> Unit,
    onDelete: (String) -> Unit,
    onLoadIdentity: suspend (String) -> ProfileIdentityDraft,
    onSaveSoul: suspend (String, String) -> Unit,
    onSaveModel: suspend (String, String, String) -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var creating by rememberSaveable { mutableStateOf(false) }
    var renameProfileName by remember { mutableStateOf<String?>(null) }
    var deleteProfileName by remember { mutableStateOf<String?>(null) }
    var identityName by remember { mutableStateOf<String?>(null) }
    var identityLoaded by rememberSaveable { mutableStateOf(false) }
    var identityLoading by remember { mutableStateOf(false) }
    var originalSoul by remember { mutableStateOf("") }
    var soulDraft by remember { mutableStateOf("") }
    var setupCommand by remember { mutableStateOf("") }
    var originalProvider by remember { mutableStateOf("") }
    var providerDraft by remember { mutableStateOf("") }
    var originalModel by remember { mutableStateOf("") }
    var modelDraft by remember { mutableStateOf("") }
    var identityError by remember { mutableStateOf<String?>(null) }
    var identityNotice by remember { mutableStateOf<String?>(null) }
    var confirmDiscardIdentity by rememberSaveable { mutableStateOf(false) }
    val renameProfile = state.profiles.firstOrNull { it.name == renameProfileName }
    val deleteProfile = state.profiles.firstOrNull { it.name == deleteProfileName }
    LaunchedEffect(Unit) { onRefresh() }
    LaunchedEffect(identityName, identityLoaded) {
        val name = identityName ?: return@LaunchedEffect
        if (identityLoaded) return@LaunchedEffect
        identityLoading = true
        identityError = null
        try {
            val identity = onLoadIdentity(name)
            if (identityName == name) {
                originalSoul = identity.soul
                soulDraft = identity.soul
                setupCommand = identity.setupCommand
                originalProvider = identity.provider
                providerDraft = identity.provider
                originalModel = identity.model
                modelDraft = identity.model
                identityLoaded = true
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            identityError = error.message ?: "Hermes could not load this profile identity"
        } finally {
            identityLoading = false
        }
    }

    fun closeIdentity() {
        identityName = null
        identityLoaded = false
        identityLoading = false
        originalSoul = ""
        soulDraft = ""
        setupCommand = ""
        originalProvider = ""
        providerDraft = ""
        originalModel = ""
        modelDraft = ""
        identityError = null
        identityNotice = null
        confirmDiscardIdentity = false
    }

    fun requestCloseIdentity() {
        if (profileIdentityDirty(originalSoul, soulDraft, originalProvider, providerDraft, originalModel, modelDraft)) {
            confirmDiscardIdentity = true
        } else {
            closeIdentity()
        }
    }
    Column(modifier.fillMaxSize()) {
        ManagementHeader("PROFILES", "Isolated Hermes workspaces", state.managementLoading, onRefresh, onBack)
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        ) {
            Text(
                "Running profile: ${state.currentProfile}. Sticky default: ${state.activeProfile}. The sticky default affects future Hermes CLI processes; starting a session here scopes this live connection explicitly.",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(onClick = { creating = true }, modifier = Modifier.padding(horizontal = 12.dp)) {
            Icon(Icons.Outlined.Add, null)
            Spacer(Modifier.width(6.dp))
            Text("Create profile")
        }
        if (state.error != null) ManagementError(state.error)
        if (!state.managementLoading && state.profiles.isEmpty()) {
            ManagementEmpty("No profiles were returned by this Hermes backend.")
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.profiles, key = ProfileInfo::name) { profile ->
                    ProfileRow(
                        profile = profile,
                        isActive = profile.name == state.activeProfile,
                        isCurrent = profile.name == state.currentProfile,
                        onStartSession = { onStartSession(profile.name) },
                        onRename = { renameProfileName = profile.name },
                        onEditIdentity = {
                            identityName = profile.name
                            identityLoaded = false
                            identityError = null
                            identityNotice = null
                        },
                        onSetActive = { onSetActive(profile.name) },
                        onDelete = { deleteProfileName = profile.name },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            }
        }
    }

    if (creating) {
        ProfileCreateDialog(
            onDismiss = { creating = false },
            onCreate = { name, cloneFrom, cloneAll, noSkills ->
                creating = false
                onCreate(name, cloneFrom, cloneAll, noSkills)
            },
        )
    }
    renameProfile?.let { profile ->
        ProfileRenameDialog(
            profile = profile,
            onDismiss = { renameProfileName = null },
            onRename = { newName ->
                renameProfileName = null
                onRename(profile.name, newName)
            },
        )
    }
    deleteProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteProfileName = null },
            title = { Text("DELETE PROFILE?") },
            text = { Text("${profile.name} and its isolated config, sessions, skills, and memory will be deleted from the Hermes server. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteProfileName = null
                        onDelete(profile.name)
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteProfileName = null }) { Text("Cancel") } },
        )
    }
    identityName?.let { name ->
        AlertDialog(
            onDismissRequest = ::requestCloseIdentity,
            title = { Text("PROFILE IDENTITY / $name") },
            text = {
                if (identityLoading && !identityLoaded) {
                    CircularProgressIndicator()
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "Stored on ${state.backend?.label.orEmpty()} for profile $name.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (setupCommand.isNotBlank()) {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                                Column(Modifier.padding(10.dp)) {
                                    Text("HOST SETUP COMMAND", style = MaterialTheme.typography.labelMedium)
                                    Text(setupCommand, style = MaterialTheme.typography.bodySmall)
                                    TextButton(
                                        onClick = {
                                            context.getSystemService(ClipboardManager::class.java)
                                                .setPrimaryClip(ClipData.newPlainText("Hermes profile setup command", setupCommand))
                                            identityNotice = "Setup command copied. Run it only on the Hermes host."
                                        },
                                    ) { Text("Copy command") }
                                }
                            }
                        }
                        OutlinedTextField(
                            value = soulDraft,
                            onValueChange = { soulDraft = it.take(131_072); identityNotice = null },
                            label = { Text("SOUL.md") },
                            minLines = 7,
                            maxLines = 14,
                            enabled = identityLoaded && !identityLoading,
                            supportingText = { Text("Full profile persona; ${soulDraft.length}/131072 characters") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = providerDraft,
                                onValueChange = { providerDraft = it.take(200); identityNotice = null },
                                label = { Text("Provider") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = modelDraft,
                                onValueChange = { modelDraft = it.take(200); identityNotice = null },
                                label = { Text("Model") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = {
                                    identityLoading = true
                                    identityError = null
                                    scope.launch {
                                        try {
                                            onSaveSoul(name, soulDraft)
                                            originalSoul = soulDraft
                                            identityNotice = "SOUL.md saved"
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (error: Throwable) {
                                            identityError = error.message ?: "Hermes could not save SOUL.md"
                                        } finally {
                                            identityLoading = false
                                        }
                                    }
                                },
                                enabled = identityLoaded && !identityLoading && soulDraft != originalSoul,
                            ) { Text("Save SOUL") }
                            Button(
                                onClick = {
                                    identityLoading = true
                                    identityError = null
                                    scope.launch {
                                        try {
                                            onSaveModel(name, providerDraft, modelDraft)
                                            originalProvider = providerDraft.trim()
                                            originalModel = modelDraft.trim()
                                            providerDraft = originalProvider
                                            modelDraft = originalModel
                                            identityNotice = "Profile model saved"
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (error: Throwable) {
                                            identityError = error.message ?: "Hermes could not save the profile model"
                                        } finally {
                                            identityLoading = false
                                        }
                                    }
                                },
                                enabled = identityLoaded && !identityLoading && providerDraft.isNotBlank() && modelDraft.isNotBlank() &&
                                    (providerDraft != originalProvider || modelDraft != originalModel),
                            ) { Text("Save model") }
                        }
                        identityNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                        identityError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = ::requestCloseIdentity) { Text("Close") } },
        )
    }
    if (confirmDiscardIdentity) {
        AlertDialog(
            onDismissRequest = { confirmDiscardIdentity = false },
            title = { Text("DISCARD PROFILE CHANGES?") },
            text = { Text("Unsaved SOUL, provider, or model edits will be lost.") },
            confirmButton = { TextButton(onClick = ::closeIdentity) { Text("Discard") } },
            dismissButton = { TextButton(onClick = { confirmDiscardIdentity = false }) { Text("Keep editing") } },
        )
    }
}

internal fun profileIdentityDirty(
    originalSoul: String,
    soul: String,
    originalProvider: String,
    provider: String,
    originalModel: String,
    model: String,
): Boolean = soul != originalSoul || provider != originalProvider || model != originalModel

@Composable
private fun ProfileRow(
    profile: ProfileInfo,
    isActive: Boolean,
    isCurrent: Boolean,
    onStartSession: () -> Unit,
    onRename: () -> Unit,
    onEditIdentity: () -> Unit,
    onSetActive: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val protected = profile.isDefault || isCurrent
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Person, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(profile.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(profile.provider, profile.model).joinToString(" / ").ifBlank { "Model not assigned" },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = onStartSession) { Icon(Icons.Outlined.PlayArrow, "Start session in ${profile.name}") }
                IconButton(onClick = onEditIdentity) { Icon(Icons.Outlined.Description, "Edit identity for ${profile.name}") }
                if (!profile.isDefault) IconButton(onClick = onRename) { Icon(Icons.Outlined.Edit, "Rename ${profile.name}") }
                if (!protected) IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Delete ${profile.name}") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (profile.isDefault) Text("DEFAULT ROOT", style = MaterialTheme.typography.labelSmall)
                if (isCurrent) Text("SERVER PROCESS", style = MaterialTheme.typography.labelSmall)
                Text("${profile.skillCount} SKILLS", style = MaterialTheme.typography.labelSmall)
                if (isActive) {
                    Text("STICKY DEFAULT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                } else {
                    TextButton(onClick = onSetActive) {
                        Icon(Icons.Outlined.Star, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Make default")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileCreateDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, Boolean, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var cloneFrom by remember { mutableStateOf("") }
    var cloneAll by rememberSaveable { mutableStateOf(false) }
    var noSkills by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("CREATE PROFILE") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(100) }, label = { Text("Profile name") }, singleLine = true)
                OutlinedTextField(
                    cloneFrom,
                    { cloneFrom = it.take(100) },
                    label = { Text("Clone source (optional)") },
                    supportingText = { Text("Leave empty for a fresh profile. Use an exact existing profile name.") },
                    singleLine = true,
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Clone sessions and full state", Modifier.weight(1f))
                    Switch(
                        checked = cloneAll,
                        onCheckedChange = { cloneAll = it },
                        modifier = Modifier.semantics { contentDescription = "Clone sessions and full state" },
                    )
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Start without bundled skills", Modifier.weight(1f))
                    Switch(
                        checked = noSkills,
                        onCheckedChange = { noSkills = it },
                        modifier = Modifier.semantics { contentDescription = "Start without bundled skills" },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onCreate(name, cloneFrom, cloneAll, noSkills) }, enabled = name.isNotBlank()) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProfileRenameDialog(
    profile: ProfileInfo,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var name by remember(profile.name) { mutableStateOf(profile.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("RENAME PROFILE") },
        text = { OutlinedTextField(name, { name = it.take(100) }, label = { Text("New name") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank() && name != profile.name) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ManagementHeader(
    title: String,
    subtitle: String,
    loading: Boolean,
    onRefresh: (() -> Unit)?,
    onBack: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") } }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (loading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
        onRefresh?.let {
            IconButton(onClick = it, enabled = !loading) { Icon(Icons.Outlined.Refresh, "Refresh $title") }
        }
    }
    HorizontalDivider()
}

@Composable
private fun SkillRow(
    skill: SkillInfo,
    onToggle: (String, Boolean) -> Unit,
    onUninstall: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(skill.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    skill.provenance ?: skill.category ?: "general",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(skill.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                skill.usage?.let { Text("$it observed actions", style = MaterialTheme.typography.labelSmall) }
            }
            Switch(
                checked = skill.enabled,
                onCheckedChange = { onToggle(skill.name, it) },
                modifier = Modifier.semantics { contentDescription = "Enable ${skill.name}" },
            )
            IconButton(onClick = { onUninstall(skill.name) }) { Icon(Icons.Outlined.Delete, "Uninstall ${skill.name}") }
        }
    }
}

@Composable
private fun ToolsetRow(
    toolset: ToolsetInfo,
    loading: Boolean,
    onToggle: (String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(toolset.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${toolset.platformLabel.uppercase()} / ${if (toolset.configured) "CONFIGURED" else "SETUP MAY BE REQUIRED"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(toolset.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (toolset.tools.isNotEmpty()) {
                    Text(
                        toolset.tools.take(8).joinToString(" · ") + if (toolset.tools.size > 8) " · +${toolset.tools.size - 8}" else "",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Switch(
                checked = toolset.enabled,
                onCheckedChange = { onToggle(toolset.name, it) },
                enabled = !loading,
                modifier = Modifier.semantics { contentDescription = "Enable ${toolset.label}" },
            )
        }
    }
}

@Composable
private fun SkillHubRow(skill: SkillHubResult, onReview: (String) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(skill.name, fontWeight = FontWeight.SemiBold)
                    Text("${skill.trustLevel.uppercase()} / ${skill.source}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Button(onClick = { onReview(skill.identifier) }) { Text("Review") }
            }
            Text(skill.description, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (skill.tags.isNotEmpty()) Text(skill.tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CronRow(
    job: CronJob,
    onSetEnabled: (String, Boolean) -> Unit,
    onTrigger: (String) -> Unit,
    runs: List<StoredSession>?,
    expanded: Boolean,
    onHistory: () -> Unit,
    onOpenRun: (StoredSession) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Schedule, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(job.name?.takeIf(String::isNotBlank) ?: job.id, fontWeight = FontWeight.SemiBold)
                    Text(
                        job.scheduleDisplay ?: job.schedule?.display ?: job.schedule?.expr ?: "Schedule unavailable",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                IconButton(onClick = { onSetEnabled(job.id, !job.enabled) }) {
                    Icon(if (job.enabled) Icons.Outlined.Pause else Icons.Outlined.PlayArrow, if (job.enabled) "Pause job" else "Resume job")
                }
                IconButton(onClick = { onTrigger(job.id) }) { Icon(Icons.Outlined.PlayArrow, "Run job now") }
                IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Edit job") }
                IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Delete job") }
            }
            job.prompt?.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(if (job.enabled) "ENABLED" else "PAUSED", style = MaterialTheme.typography.labelSmall)
                job.nextRunAt?.let { Text("NEXT $it", style = MaterialTheme.typography.labelSmall) }
                job.deliver?.let { Text("DELIVER $it", style = MaterialTheme.typography.labelSmall) }
            }
            job.lastError?.let { Text("Last failure: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = onHistory) {
                Icon(Icons.Outlined.History, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (expanded) "Hide runs" else "Recent runs")
            }
            if (expanded) {
                when {
                    runs == null -> Text("Loading run history…", style = MaterialTheme.typography.bodySmall)
                    runs.isEmpty() -> Text("No executions have produced sessions for this job.", style = MaterialTheme.typography.bodySmall)
                    else -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        runs.forEach { run ->
                            Surface(
                                onClick = { onOpenRun(run) },
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(run.displayTitle, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(
                                            listOfNotNull(run.profile, run.model).joinToString(" / ").ifBlank { run.durableId },
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    if (run.isActive) Text("ACTIVE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CronEditorDialog(
    job: CronJob?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String) -> Unit,
) {
    var name by remember(job?.id) { mutableStateOf(job?.name.orEmpty()) }
    var prompt by remember(job?.id) { mutableStateOf(job?.prompt.orEmpty()) }
    var schedule by remember(job?.id) {
        mutableStateOf(job?.schedule?.expr ?: job?.scheduleDisplay.orEmpty())
    }
    var deliver by remember(job?.id) { mutableStateOf(job?.deliver.orEmpty()) }
    val valid = prompt.isNotBlank() && schedule.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (job == null) "CREATE CRON JOB" else "EDIT CRON JOB") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(name, { name = it.take(200) }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(prompt, { prompt = it }, label = { Text("Hermes prompt") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 7)
                OutlinedTextField(
                    schedule,
                    { schedule = it },
                    label = { Text("Exact schedule") },
                    supportingText = { Text("Cron expression or schedule form accepted by this Hermes server; timezone is evaluated server-side.") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(deliver, { deliver = it }, label = { Text("Delivery destination (optional)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, prompt, schedule, deliver) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun ManagementError(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun ManagementEmpty(message: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
