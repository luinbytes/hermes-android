package com.nousresearch.hermes.ui

import com.nousresearch.hermes.data.normalizedProfile
import com.nousresearch.hermes.protocol.ProfileInfo
import com.nousresearch.hermes.protocol.BotSessionSummary
import com.nousresearch.hermes.protocol.StoredSession
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.AttachFile
import com.nousresearch.hermes.protocol.BotGroupEntry
import com.nousresearch.hermes.protocol.BotGroupAttachment
import com.nousresearch.hermes.protocol.BotGroupBlockingRequest
import com.nousresearch.hermes.protocol.BotGroupQuestion
import com.nousresearch.hermes.protocol.BotGroupCandidate
import com.nousresearch.hermes.protocol.BotGroupMember
import com.nousresearch.hermes.protocol.BotGroupRoom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal data class BotConversation(
    val profile: ProfileInfo,
    val latestSession: StoredSession? = null,
    val sourceLabel: String = "",
    val selected: Boolean = false,
    val unread: Boolean = false,
) {
    val hidden: Boolean
        get() = runCatching {
            profile.uiMeta?.get("hermes-bots")?.jsonObject?.get("hidden")?.jsonPrimitive?.booleanOrNull == true
        }.getOrDefault(false)

    val name: String
        get() = profile.displayName.ifBlank {
            if (profile.isDefault || profile.name == "default") "Hermes" else profile.name.replaceFirstChar(Char::uppercase)
        }

    val role: String
        get() = profile.description.ifBlank { listOfNotNull(profile.provider, profile.model).joinToString(" · ") }

    val identity: String
        get() = listOf(name, sourceLabel.takeIf(String::isNotBlank)).joinToString(" · ")

    val preview: String
        get() = (profile.canonicalSession ?: profile.preferredSession ?: profile.lastSession)?.let { summary ->
            summary.preview.ifBlank { summary.title }
        }?.takeIf(String::isNotBlank)
            ?: latestSession?.title?.takeIf(String::isNotBlank)
            ?: role.ifBlank { "No conversations yet — say hi" }

    val activityTimestamp: Double
        get() = listOfNotNull(profile.canonicalSession ?: profile.preferredSession, profile.lastSession)
            .maxOfOrNull(BotSessionSummary::lastActive)
            ?: latestSession?.lastActive
            ?: 0.0

    fun matches(query: String): Boolean {
        val value = query.trim()
        return value.isBlank() || listOf(profile.name, identity, role, preview).any { it.contains(value, ignoreCase = true) }
    }

    fun isActive(nowMillis: Long, busyProfile: String?): Boolean {
        val nowSeconds = nowMillis / 1000.0
        val activity = activityTimestamp
        val workerActive = profile.workerSession?.lastActive?.let { it > 0 && nowSeconds - it < 150 } == true
        return latestSession?.isActive == true || workerActive ||
            (activity > 0 && nowSeconds - activity < 90) || profile.name == busyProfile
    }
}

internal enum class ChatInboxMode { BOTS, SESSIONS }

@Composable
internal fun BotInboxSelector(
    mode: ChatInboxMode,
    onModeChange: (ChatInboxMode) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val button: @Composable (ChatInboxMode, String) -> Unit = { target, label ->
            if (mode == target) {
                Button(onClick = { onModeChange(target) }, modifier = Modifier.weight(1f)) { Text(label) }
            } else {
                OutlinedButton(onClick = { onModeChange(target) }, modifier = Modifier.weight(1f)) { Text(label) }
            }
        }
        button(ChatInboxMode.BOTS, "Bots")
        button(ChatInboxMode.SESSIONS, "Sessions")
    }
}

internal fun botConversations(
    profiles: List<ProfileInfo>,
    sessions: List<StoredSession>,
    selectedSession: StoredSession?,
    sourceLabel: String = "",
    unreadProfiles: Set<String> = emptySet(),
): List<BotConversation> {
    val latestByProfile = sessions
        .groupBy { it.profile.normalizedProfile() }
        .mapValues { (_, profileSessions) -> profileSessions.maxByOrNull(StoredSession::lastActive) }
    return profiles.map { profile ->
        val latest = (profile.canonicalSession ?: profile.preferredSession ?: profile.lastSession)?.toStoredSession(profile.name)
            ?: latestByProfile[profile.name.normalizedProfile()]
        BotConversation(
            profile = profile,
            latestSession = latest,
            sourceLabel = sourceLabel,
            selected = selectedSession?.profile.normalizedProfile() == profile.name.normalizedProfile(),
            unread = profile.name in unreadProfiles,
        )
    }.sortedWith(
        compareByDescending<BotConversation> { it.profile.isDefault }
            .thenByDescending(BotConversation::activityTimestamp)
            .thenBy { it.name.lowercase() },
    )
}

private fun BotSessionSummary.toStoredSession(profile: String): StoredSession = StoredSession(
    sessionId = resolvedId ?: id,
    id = id,
    title = title,
    profile = profile,
    source = source,
    messageCount = messageCount,
    startedAt = startedAt,
    lastActive = lastActive,
)

@Composable
internal fun BotRow(
    bot: BotConversation,
    nowMillis: Long,
    busyProfile: String? = null,
    onClick: () -> Unit,
    onToggleHidden: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    avatarData: String? = null,
) {
    val active = bot.isActive(nowMillis, busyProfile)
    val timestamp = formatSessionTimestamp(bot.activityTimestamp, nowMillis)
    val description = listOfNotNull(
        bot.identity,
        bot.role.takeIf(String::isNotBlank),
        bot.preview,
        timestamp.takeIf(String::isNotBlank),
        "Selected".takeIf { bot.selected },
        "Active".takeIf { active },
        "Unread".takeIf { bot.unread },
        "Hidden".takeIf { bot.hidden },
    ).joinToString(", ")
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(if (bot.selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                .clickable(onClick = onClick)
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = description
                    onClick { onClick(); true }
                    customActions = listOfNotNull(
                        onEdit?.let { edit -> CustomAccessibilityAction("Edit ${bot.name}") { edit(); true } },
                        onToggleHidden?.let { toggle ->
                            CustomAccessibilityAction(if (bot.hidden) "Unhide ${bot.name}" else "Hide ${bot.name}") {
                                toggle()
                                true
                            }
                        },
                    )
                }
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = if (active) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(48.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val avatar = remember(avatarData) { avatarData?.decodeAvatar() }
                    if (avatar != null) {
                        Image(avatar, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    } else {
                        Text(
                            bot.name.firstOrNull(Char::isLetterOrDigit)?.uppercase() ?: "H",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(bot.identity, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (bot.role.isNotBlank()) {
                    Text(
                        bot.role,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    bot.preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (timestamp.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(timestamp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (bot.unread) {
                Spacer(Modifier.width(8.dp))
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                    Text(
                        "NEW",
                        Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            if (active) {
                Spacer(Modifier.width(8.dp))
                Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
            }
            onEdit?.let { edit ->
                IconButton(onClick = edit) { Icon(Icons.Outlined.Edit, "Edit ${bot.name}") }
            }
            onToggleHidden?.let { toggle ->
                IconButton(onClick = toggle) {
                    Icon(
                        if (bot.hidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff,
                        if (bot.hidden) "Unhide ${bot.name}" else "Hide ${bot.name}",
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 76.dp))
    }
}

private fun String.decodeAvatar() = runCatching {
    val encoded = substringAfter(',', missingDelimiterValue = "")
    require(encoded.isNotEmpty())
    val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
    require(bytes.size <= 2_000_000)
    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

@Composable
internal fun BotGroupRow(
    room: BotGroupRoom,
    selected: Boolean,
    running: Boolean,
    needsYou: Boolean,
    onClick: () -> Unit,
) {
    val latest = room.log.lastOrNull()
    val description = buildString {
        append(room.name).append(", group with ").append(room.members.joinToString { it.handle.ifBlank { it.name } })
        latest?.let { append(", ").append(it.from.name).append(": ").append(it.text) }
        if (needsYou) append(", Needs you")
        if (running) append(", Active")
    }
    Row(
        Modifier.fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription = description
                onClick { onClick(); true }
            }
            .padding(horizontal = 16.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(Modifier.size(48.dp), CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
            room.image?.decodeAvatar()?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Group, null) }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(room.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                latest?.let { "${it.from.name}: ${it.text}" } ?: room.members.joinToString { it.name },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (needsYou) Text("YOU", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        if (running) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
    HorizontalDivider(Modifier.padding(start = 76.dp))
}

@Composable
internal fun BotGroupEditorDialog(
    room: BotGroupRoom?,
    candidates: List<BotGroupCandidate>,
    candidatesLoading: Boolean,
    unavailableSources: List<String>,
    onDismiss: () -> Unit,
    onSave: suspend (String, List<BotGroupMember>, String?) -> Unit,
    onDisband: (suspend () -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var name by rememberSaveable(room?.roomId) { mutableStateOf(room?.name.orEmpty()) }
    var selected by remember(room?.roomId) { mutableStateOf(room?.members?.map { "${it.connectionId}::${it.name}" }?.toSet().orEmpty()) }
    var image by remember(room?.roomId) { mutableStateOf(room?.image) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDisband by remember { mutableStateOf(false) }
    val allCandidates = remember(candidates, room) {
        candidates + room?.members.orEmpty().filter { member ->
            candidates.none { it.backendId == member.connectionId && it.profile.name == member.name }
        }.map { member ->
            BotGroupCandidate(
                profile = com.nousresearch.hermes.protocol.ProfileInfo(name = member.name),
                backendId = member.connectionId,
                backendLabel = member.connectionLabel.ifBlank { "Unavailable source" },
                handle = member.handle.ifBlank { member.name },
            )
        }
    }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) runCatching { profileAvatarDataUrl(context, uri, targetDataCharacters = 24_000) }
            .onSuccess { image = it }.onFailure { error = it.message }
    }
    if (confirmDisband) {
        AlertDialog(
            onDismissRequest = { confirmDisband = false },
            title = { Text("Disband ${room?.name}?") },
            text = { Text("The room disappears, but its bots, direct chats and hidden member sessions are kept.") },
            confirmButton = {
                TextButton(
                    enabled = !saving,
                    onClick = {
                        scope.launch {
                            saving = true
                            try {
                                onDisband?.invoke()
                                confirmDisband = false
                                onDismiss()
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (cause: Throwable) {
                                error = cause.message ?: "Hermes could not disband the group"
                                confirmDisband = false
                            } finally {
                                saving = false
                            }
                        }
                    },
                ) { Text(if (saving) "Disbanding…" else "Disband") }
            },
            dismissButton = { TextButton(onClick = { confirmDisband = false }) { Text("Cancel") } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(if (room == null) "New group" else "Group settings") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(name, { name = it.take(64) }, label = { Text("Group name") }, singleLine = true)
                Text("Pick 2–6 bots", style = MaterialTheme.typography.labelMedium)
                if (candidatesLoading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                if (unavailableSources.isNotEmpty()) {
                    Text(
                        "Unavailable: ${unavailableSources.joinToString()}. Reconnect them to add their bots.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                allCandidates.forEach { candidate ->
                    val profile = candidate.profile
                    val key = "${candidate.backendId}::${profile.name}"
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            selected = if (key in selected) selected - key else if (selected.size < 6) selected + key else selected
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(key in selected, onCheckedChange = null)
                        Column {
                            Text(profile.displayName.ifBlank { profile.name })
                            Text("@${candidate.handle} · ${candidate.backendLabel}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                OutlinedButton(onClick = { picker.launch("image/*") }) { Text(if (image == null) "Add picture" else "Change picture") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (room != null && onDisband != null) {
                    TextButton(onClick = { confirmDisband = true }) { Text("Disband group", color = MaterialTheme.colorScheme.error) }
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !saving && name.isNotBlank() && selected.size in 2..6,
                onClick = {
                    saving = true
                    scope.launch {
                        try {
                            val members = allCandidates.filter { "${it.backendId}::${it.profile.name}" in selected }.map { candidate ->
                                BotGroupMember(
                                    candidate.profile.name,
                                    candidate.handle,
                                    candidate.backendId,
                                    "gateway",
                                    candidate.backendLabel,
                                    sourceScoped = true,
                                )
                            }
                            onSave(name.trim(), members, image)
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (cause: Throwable) {
                            error = cause.message ?: "Hermes could not save the group"
                        } finally {
                            saving = false
                        }
                    }
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") } },
    )
}

@Composable
internal fun BotGroupConversationScreen(
    room: BotGroupRoom,
    running: Boolean,
    blockingRequests: List<BotGroupBlockingRequest>,
    onAnswerBlocking: suspend (String, Map<String, List<String>>) -> Unit,
    onSend: suspend (String, String, List<BotGroupAttachment>) -> Unit,
    onEdit: () -> Unit,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    var draft by rememberSaveable(room.roomId) { mutableStateOf("") }
    var thread by rememberSaveable(room.roomId) { mutableStateOf("main") }
    var error by remember { mutableStateOf<String?>(null) }
    var attachments by remember(room.roomId) { mutableStateOf<List<BotGroupAttachment>>(emptyList()) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val attachmentPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris ->
        runCatching { uris.take(5).map { botGroupAttachment(context, it) } }
            .onSuccess { attachments = it }
            .onFailure { error = it.message ?: "Android could not read that attachment" }
    }
    LaunchedEffect(room.log.size) { if (room.log.isNotEmpty()) listState.animateScrollToItem(room.log.lastIndex) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            onBack?.let { IconButton(onClick = it) { Icon(Icons.Outlined.Close, "Back to chats") } }
            Column(Modifier.weight(1f)) {
                Text(room.name, style = MaterialTheme.typography.titleLarge)
                Text(room.members.joinToString { "@${it.handle.ifBlank { it.name }}" }, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            IconButton(onClick = onEdit) { Icon(Icons.Outlined.Edit, "Group settings") }
        }
        HorizontalDivider()
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(room.log, key = BotGroupEntry::id) { entry ->
                val user = entry.from.kind == "user"
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
                    Text(
                        buildString { append(entry.from.name); if (entry.from.source.isNotBlank()) append(" · ${entry.from.source}") },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        shape = MaterialTheme.shapes.large,
                        color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    ) { Text(entry.text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) }
                    if (entry.thread != "main") Text("Thread", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    if (!user) TextButton(onClick = { thread = entry.thread.takeIf { it != "main" } ?: entry.id }) { Text("Reply") }
                }
            }
        }
        blockingRequests.forEach { request ->
            BotGroupBlockingCard(request, onAnswerBlocking)
        }
        error?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
        if (thread != "main") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Replying in thread", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                IconButton(onClick = { thread = "main" }) { Icon(Icons.Outlined.Close, "Close thread") }
            }
        }
        if (attachments.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(attachments.joinToString { it.name }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                IconButton(onClick = { attachments = emptyList() }) { Icon(Icons.Outlined.Close, "Remove attachments") }
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = { attachmentPicker.launch(arrayOf("image/*", "application/pdf", "text/*", "application/octet-stream")) }) {
                Icon(Icons.Outlined.AttachFile, "Attach files")
            }
            OutlinedTextField(
                draft,
                { draft = it.take(12_000) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message ${room.name}") },
                enabled = !running,
                maxLines = 6,
            )
            IconButton(
                enabled = draft.isNotBlank() && !running,
                onClick = {
                    val sending = draft
                    val sendingAttachments = attachments
                    draft = ""
                    attachments = emptyList()
                    scope.launch {
                        try {
                            onSend(sending, thread, sendingAttachments)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (cause: Throwable) {
                            draft = sending
                            attachments = sendingAttachments
                            error = cause.message ?: "Hermes could not send to the group"
                        }
                    }
                },
            ) { if (running) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else Icon(Icons.AutoMirrored.Outlined.Send, "Send") }
        }
    }
}

@Composable
private fun BotGroupBlockingCard(
    request: BotGroupBlockingRequest,
    onAnswer: suspend (String, Map<String, List<String>>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val questions = request.questions.ifEmpty {
        listOf(BotGroupQuestion("answer", request.prompt, request.choices))
    }
    var drafts by remember(request.requestId) { mutableStateOf(emptyMap<String, String>()) }
    var selections by remember(request.requestId) { mutableStateOf(emptyMap<String, Set<String>>()) }
    var saving by remember(request.requestId) { mutableStateOf(false) }
    var error by remember(request.requestId) { mutableStateOf<String?>(null) }
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.tertiaryContainer,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${request.member.name} needs you", style = MaterialTheme.typography.titleMedium)
            if (request.kind == "approval") {
                Text(request.prompt.ifBlank { "Approve this command?" })
                if (request.command.isNotBlank()) Text(request.command, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    request.choices.forEach { choice ->
                        OutlinedButton(
                            enabled = !saving,
                            onClick = {
                                saving = true
                                scope.launch {
                                    try {
                                        onAnswer(request.requestId, mapOf("choice" to listOf(choice)))
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (cause: Throwable) {
                                        error = cause.message ?: "Hermes could not send that approval"
                                        saving = false
                                    }
                                }
                            },
                        ) { Text(choice.replaceFirstChar(Char::uppercase)) }
                    }
                }
            } else {
                questions.forEach { question ->
                    Text(question.prompt.ifBlank { request.prompt })
                    if (question.choices.isEmpty()) {
                        OutlinedTextField(
                            drafts[question.id].orEmpty(),
                            { drafts = drafts + (question.id to it.take(4_000)) },
                            label = { Text("Answer") },
                            enabled = !saving,
                        )
                    } else {
                        question.choices.forEach { choice ->
                            val selected = choice in selections[question.id].orEmpty()
                            if (selected) {
                                Button(onClick = {
                                    selections = selections + (question.id to if (question.multiSelect) {
                                        selections[question.id].orEmpty() - choice
                                    } else emptySet())
                                }, enabled = !saving) { Text(choice) }
                            } else {
                                OutlinedButton(onClick = {
                                    selections = selections + (question.id to if (question.multiSelect) {
                                        selections[question.id].orEmpty() + choice
                                    } else setOf(choice))
                                }, enabled = !saving) { Text(choice) }
                            }
                        }
                    }
                }
                Button(
                    enabled = !saving && questions.all { question ->
                        if (question.choices.isEmpty()) !drafts[question.id].isNullOrBlank()
                        else selections[question.id].orEmpty().isNotEmpty()
                    },
                    onClick = {
                        saving = true
                        scope.launch {
                            try {
                                onAnswer(
                                    request.requestId,
                                    questions.associate { question ->
                                        question.id to if (question.choices.isEmpty()) {
                                            listOf(drafts[question.id].orEmpty())
                                        } else selections[question.id].orEmpty().toList()
                                    },
                                )
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (cause: Throwable) {
                                error = cause.message ?: "Hermes could not send that answer"
                                saving = false
                            }
                        }
                    },
                ) { Text("Send answer") }
            }
            if (saving) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

private fun botGroupAttachment(context: android.content.Context, uri: android.net.Uri): BotGroupAttachment {
    val mime = context.contentResolver.getType(uri)?.lowercase()?.takeIf(String::isNotBlank)
        ?: "application/octet-stream"
    val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }?.take(180) ?: "attachment"
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8_192)
        while (output.size() <= 10_000_000) {
            val count = input.read(buffer, 0, minOf(buffer.size, 10_000_001 - output.size()))
            if (count <= 0) break
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: throw IllegalArgumentException("Android could not read $name")
    require(bytes.size <= 10_000_000) { "$name is larger than 10 MB" }
    return BotGroupAttachment(name, mime, android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
}
