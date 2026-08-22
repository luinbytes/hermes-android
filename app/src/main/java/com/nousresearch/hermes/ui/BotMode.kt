package com.nousresearch.hermes.ui

import com.nousresearch.hermes.data.normalizedProfile
import com.nousresearch.hermes.protocol.ProfileInfo
import com.nousresearch.hermes.protocol.BotSessionSummary
import com.nousresearch.hermes.protocol.StoredSession
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Edit
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
