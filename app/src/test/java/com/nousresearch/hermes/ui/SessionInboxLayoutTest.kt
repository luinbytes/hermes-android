package com.nousresearch.hermes.ui

import android.Manifest
import android.app.NotificationManager
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.protocol.StoredSession
import com.nousresearch.hermes.protocol.BotSessionSummary
import com.nousresearch.hermes.protocol.ProfileInfo
import com.nousresearch.hermes.protocol.BotGroupEntry
import com.nousresearch.hermes.protocol.BotGroupMember
import com.nousresearch.hermes.protocol.BotGroupRoom
import com.nousresearch.hermes.protocol.BotGroupSpeaker
import com.nousresearch.hermes.protocol.BotGroupBlockingRequest
import com.nousresearch.hermes.protocol.BotGroupQuestion
import com.nousresearch.hermes.data.BackendConfig
import com.nousresearch.hermes.data.AuthMode
import com.nousresearch.hermes.data.HermesState
import com.nousresearch.hermes.protocol.CronJob
import com.nousresearch.hermes.protocol.CronJobSchedule
import com.nousresearch.hermes.platform.createHermesNotificationChannels
import com.nousresearch.hermes.ui.theme.HermesTheme
import java.time.Instant
import java.time.ZoneId
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlinx.serialization.json.put

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionInboxLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun conversationRowFitsCompactWidthAndExposesItsState() {
        assertConversationRowFits(width = 360, compact = true, click = true)
    }

    @Test
    fun conversationRowFitsExpandedRailAtLargeText() {
        assertConversationRowFits(width = 330, compact = false, click = false)
    }

    @Test
    fun selectedConversationDoesNotAnnounceAnInactiveRuntime() {
        assertConversationRowFits(width = 360, compact = true, click = false, active = false)
    }

    @Test
    fun botAndSessionInboxModesRemainAdjacentActions() {
        var selected = ChatInboxMode.BOTS
        compose.setContent {
            HermesTheme { BotInboxSelector(selected) { selected = it } }
        }

        compose.onNodeWithText("Sessions").performClick()
        assertEquals(ChatInboxMode.SESSIONS, selected)
        compose.onNodeWithText("Bots").performClick()
        assertEquals(ChatInboxMode.BOTS, selected)
    }

    @Test
    fun botRowFitsPhoneAndExposesMessagingState() {
        compose.setContent {
            HermesTheme {
                Surface(Modifier.width(360.dp)) {
                    BotRow(
                        bot = BotConversation(
                            profile = ProfileInfo(
                                name = "coder",
                                displayName = "Code Fox",
                                description = "Builds Android apps",
                                uiMeta = kotlinx.serialization.json.buildJsonObject {
                                    put("hermes-bots", kotlinx.serialization.json.buildJsonObject { put("hidden", true) })
                                },
                            ),
                            latestSession = StoredSession(sessionId = "chat", profile = "coder", title = "Fix the release", lastActive = 10.0),
                            selected = true,
                            sourceLabel = "Mac mini",
                            unread = true,
                        ),
                        nowMillis = 20_000L,
                        onClick = {},
                        onToggleHidden = {},
                    )
                }
            }
        }

        compose.onNode(
            hasContentDescription("Code Fox", substring = true) and
                hasContentDescription("Fix the release", substring = true) and
                hasContentDescription("Mac mini", substring = true) and
                hasContentDescription("Builds Android apps", substring = true) and
                hasContentDescription("Selected", substring = true) and
                hasContentDescription("Active", substring = true) and
                hasContentDescription("Unread", substring = true) and
                hasContentDescription("Hidden", substring = true),
        ).assertExists()
    }

    @Test
    fun botRoutineDialogKeepsOwnerTagAndStructuredScheduleAtLargeText() {
        var created = emptyList<String>()
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 1.8f)) {
                HermesTheme {
                    Surface(Modifier.width(360.dp)) {
                        BotRoutinesDialog(
                            state = HermesState(
                                cronJobs = listOf(
                                    CronJob(
                                        enabled = true,
                                        id = "daily",
                                        name = "[bot:coder] Existing brief",
                                        prompt = "Summarize the day",
                                        schedule = CronJobSchedule(expr = "0 9 * * *"),
                                    ),
                                ),
                            ),
                            owner = "coder",
                            onRefresh = {},
                            onSetEnabled = { _, _ -> },
                            onTrigger = {},
                            onLoadRuns = {},
                            onOpenRun = {},
                            onCreate = { name, prompt, schedule, deliver -> created = listOf(name, prompt, schedule, deliver) },
                            onUpdate = { _, _, _, _, _ -> },
                            onDelete = {},
                            onDismiss = {},
                        )
                    }
                }
            }
        }

        compose.onNodeWithText("Existing brief").assertExists()
        compose.onNodeWithText("New routine").performClick()
        compose.onNodeWithText("Name").performTextInput("Morning plan")
        compose.onNodeWithText("Hermes prompt").performTextInput("Plan today's work")
        compose.onNodeWithText("Save").performClick()
        compose.waitForIdle()

        assertEquals(listOf("[bot:coder] Morning plan", "Plan today's work", "0 9 * * *", ""), created)
    }

    @Test
    fun botActivityProducerPostsOnlyAfterVisibleStateChanges() {
        val context = RuntimeEnvironment.getApplication()
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancelAll()
        createHermesNotificationChannels(context)
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val backend = BackendConfig("mac", "Mac mini", "https://hermes.test", AuthMode.TOKEN)
        val initialBot = ProfileInfo(
            name = "coder",
            canonicalSession = BotSessionSummary("bot-chat", title = "Bot Chat", lastActive = 1.0),
        )
        val room = BotGroupRoom(
            name = "Launch",
            roomId = "launch",
            members = listOf(BotGroupMember("coder", "coder", backend.id, connectionLabel = backend.label)),
            log = listOf(BotGroupEntry("reply", BotGroupSpeaker("member", "coder", backend.label), "@user approve", 1)),
        )
        val observed = mutableStateOf(
            HermesState(
                backend = backend,
                profiles = listOf(initialBot),
                botGroups = com.nousresearch.hermes.protocol.BotGroupUiState(rooms = listOf(room)),
                cronJobs = listOf(CronJob(enabled = true, id = "daily", name = "[bot:coder] Daily", lastRunAt = "first")),
            ),
        )
        compose.setContent { BotActivityNotifications(observed.value) }
        compose.waitForIdle()
        assertTrue(manager.activeNotifications.isEmpty())

        compose.runOnIdle {
            observed.value = observed.value.copy(
                profiles = listOf(initialBot.copy(canonicalSession = initialBot.canonicalSession?.copy(lastActive = 2.0))),
                botGroups = observed.value.botGroups.copy(needsYouRoomIds = setOf(room.roomId)),
                cronJobs = listOf(CronJob(enabled = true, id = "daily", name = "[bot:coder] Daily", lastRunAt = "second")),
            )
        }
        compose.waitForIdle()
        val notificationTitles = manager.activeNotifications.map {
            it.notification.extras.getString(android.app.Notification.EXTRA_TITLE)
        }
        assertEquals(notificationTitles.toString(), 3, manager.activeNotifications.size)
        manager.cancelAll()
    }

    @Test
    fun remoteGroupSpeakerStaysPrivateUntilItsVisibilityIsKnown() {
        val member = BotGroupMember("reviewer", "reviewer-cloud", "cloud", connectionLabel = "Cloud")
        val room = BotGroupRoom(
            name = "Remote",
            roomId = "remote",
            members = listOf(member),
            log = listOf(BotGroupEntry("reply", BotGroupSpeaker("member", "reviewer", "Cloud"), "@user approve", 1)),
        )

        assertTrue(botGroupSpeakerHidden(room, emptyList(), backendId = "mac"))
        val remoteBot = com.nousresearch.hermes.protocol.BotGroupCandidate(
            ProfileInfo(
                name = "reviewer",
                uiMeta = kotlinx.serialization.json.buildJsonObject {
                    put("hermes-bots", kotlinx.serialization.json.buildJsonObject { put("hidden", false) })
                },
            ),
            "cloud",
            "Cloud",
            "reviewer-cloud",
        )
        assertTrue(
            botGroupSpeakerHidden(
                room,
                emptyList(),
                backendId = "mac",
                candidates = listOf(remoteBot.copy(profile = ProfileInfo(name = "reviewer"))),
            ),
        )
        val hiddenRemoteBot = remoteBot.copy(
            profile = remoteBot.profile.copy(
                uiMeta = kotlinx.serialization.json.buildJsonObject {
                    put("hermes-bots", kotlinx.serialization.json.buildJsonObject { put("hidden", true) })
                },
            ),
        )
        assertTrue(botGroupSpeakerHidden(room, emptyList(), backendId = "mac", candidates = listOf(hiddenRemoteBot)))
        org.junit.Assert.assertFalse(
            botGroupSpeakerHidden(room, emptyList(), backendId = "mac", candidates = listOf(remoteBot)),
        )
        assertTrue(botGroupSpeakerHidden(room.copy(members = room.members + member), emptyList(), "mac", listOf(remoteBot)))
        assertTrue(
            botGroupSpeakerHidden(
                room.copy(members = listOf(member.copy(connectionId = "", sourceScoped = true))),
                listOf(BotConversation(ProfileInfo(name = "reviewer"))),
                "mac",
                listOf(remoteBot),
            ),
        )
    }

    @Test
    fun groupRowExposesSourceQualifiedMembersAndNeedsYouState() {
        var clicks = 0
        val room = BotGroupRoom(
            name = "Launch room",
            roomId = "room-1",
            members = listOf(
                BotGroupMember("coder", "coder-mac", "mac", connectionLabel = "Mac mini"),
                BotGroupMember("coder", "coder-cloud", "cloud", connectionLabel = "Cloud"),
            ),
            log = listOf(BotGroupEntry("entry", BotGroupSpeaker("member", "coder", "Cloud"), "@user approve this", 1)),
        )
        compose.setContent {
            HermesTheme {
                Surface(Modifier.width(360.dp)) {
                    BotGroupRow(room, selected = true, running = true, needsYou = true) { clicks++ }
                }
            }
        }

        compose.onNode(
            hasContentDescription("Launch room", substring = true) and
                hasContentDescription("coder-mac", substring = true) and
                hasContentDescription("coder-cloud", substring = true) and
                hasContentDescription("Needs you", substring = true) and
                hasContentDescription("Active", substring = true),
        ).performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun groupConversationStartsAnAttributedThreadAndSendsThroughIt() {
        var sentText = ""
        var sentThread = ""
        val room = BotGroupRoom(
            name = "Launch room",
            roomId = "room-1",
            members = listOf(
                BotGroupMember("coder", "coder-mac", "mac"),
                BotGroupMember("reviewer", "reviewer", "mac"),
            ),
            log = listOf(BotGroupEntry("answer-1", BotGroupSpeaker("member", "coder", "Mac mini"), "Ready to ship", 1)),
        )
        compose.setContent {
            HermesTheme {
                BotGroupConversationScreen(
                    room = room,
                    running = false,
                    onSend = { text, thread, _ -> sentText = text; sentThread = thread },
                    blockingRequests = emptyList(),
                    onAnswerBlocking = { _, _ -> },
                    onEdit = {},
                    onBack = null,
                )
            }
        }

        compose.onNodeWithText("Reply").performClick()
        compose.onNodeWithText("Replying in thread").assertExists()
        compose.onNodeWithText("Message Launch room").performTextInput("Looks good")
        compose.onNodeWithContentDescription("Send").performClick()
        compose.waitForIdle()

        assertEquals("Looks good", sentText)
        assertEquals("answer-1", sentThread)
    }

    @Test
    fun groupBatchClarificationCollectsEveryAnswerBeforeResponding() {
        var answers = emptyMap<String, List<String>>()
        val member = BotGroupMember("coder", "coder-mac", "mac")
        val room = BotGroupRoom(
            name = "Launch room",
            roomId = "room-1",
            members = listOf(member, BotGroupMember("reviewer", "reviewer", "mac")),
            log = listOf(BotGroupEntry("entry", BotGroupSpeaker("user", "You"), "Plan launch", 1)),
        )
        compose.setContent {
            HermesTheme {
                BotGroupConversationScreen(
                    room = room,
                    running = true,
                    blockingRequests = listOf(
                        BotGroupBlockingRequest(
                            roomId = room.roomId,
                            member = member,
                            sessionId = "live",
                            requestId = "clarify-1",
                            kind = "clarify",
                            prompt = "Launch details",
                            questions = listOf(
                                BotGroupQuestion("regions", "Which regions?", listOf("EU", "US"), multiSelect = true),
                                BotGroupQuestion("owner", "Who owns it?"),
                            ),
                        ),
                    ),
                    onAnswerBlocking = { _, value -> answers = value },
                    onSend = { _, _, _ -> },
                    onEdit = {},
                    onBack = null,
                )
            }
        }

        compose.onNodeWithText("EU").performClick()
        compose.onNodeWithText("Who owns it?").assertExists()
        compose.onAllNodesWithText("Answer")[0].performTextInput("Dana")
        compose.onNodeWithText("Send answer").performClick()
        compose.waitForIdle()

        assertEquals(listOf("EU"), answers["regions"])
        assertEquals(listOf("Dana"), answers["owner"])
    }

    @Test
    fun groupDisbandFailureReturnsToSettingsWithAnActionableError() {
        val room = BotGroupRoom(
            name = "Launch room",
            roomId = "room-1",
            members = listOf(
                BotGroupMember("coder", "coder", "personal"),
                BotGroupMember("reviewer", "reviewer", "personal"),
            ),
            log = listOf(BotGroupEntry("entry", text = "Group created", at = 1)),
        )
        compose.setContent {
            HermesTheme {
                BotGroupEditorDialog(
                    room = room,
                    candidates = emptyList(),
                    candidatesLoading = false,
                    unavailableSources = emptyList(),
                    onDismiss = {},
                    onSave = { _, _, _ -> },
                    onDisband = { error("Network unavailable") },
                )
            }
        }

        compose.onNodeWithText("Disband group").performScrollTo().performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("Disband", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Disband", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Network unavailable").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Network unavailable").assertExists()
    }

    @Test
    fun quickAgentCreationTargetsTheSelectedBackendAndReturnsToChat() {
        var createdBackend = ""
        var opened = ""
        compose.setContent {
            HermesTheme {
                BotAgentCreateDialog(
                    profiles = listOf(ProfileInfo(name = "default")),
                    backends = listOf(
                        BackendConfig(
                            id = "personal",
                            label = "Personal",
                            baseUrl = "https://hermes.example",
                            authMode = AuthMode.DASHBOARD_SESSION,
                        ),
                    ),
                    activeBackendId = "personal",
                    initialClone = null,
                    onDismiss = {},
                    onCreate = { draft, backendId, _, _, _, _ ->
                        createdBackend = "$backendId:${draft.name}"
                        true
                    },
                    onCreated = { name, backendId -> opened = "$backendId:$name" },
                )
            }
        }

        compose.onNodeWithText("Agent handle").performTextInput("helper")
        compose.onNodeWithText("Create and chat").performClick()
        compose.waitForIdle()

        assertEquals("personal:helper", createdBackend)
        assertEquals("personal:helper", opened)
    }

    @Test
    fun duplicatedAgentOpensWithAdvancedCloneControls() {
        compose.setContent {
            HermesTheme {
                BotAgentCreateDialog(
                    profiles = listOf(ProfileInfo(name = "coder")),
                    backends = listOf(
                        BackendConfig(
                            id = "personal",
                            label = "Personal",
                            baseUrl = "https://hermes.example",
                            authMode = AuthMode.DASHBOARD_SESSION,
                        ),
                    ),
                    activeBackendId = "personal",
                    initialClone = "coder",
                    onDismiss = {},
                    onCreate = { _, _, _, _, _, _ -> true },
                    onCreated = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("Clone source (optional)").assertExists()
        compose.onNodeWithText("SOUL.md").assertExists()
        compose.onNodeWithContentDescription("Clone sessions and full state").assertExists()
    }

    @Test
    fun blankSessionProfileMatchesTheDefaultActiveProfile() {
        val active = StoredSession(sessionId = "session-1", profile = "default")
        val listed = StoredSession(sessionId = "session-1", profile = "")

        assertTrue(sameSession(active, listed))
    }

    @Test
    fun summaryUsesAvailableSessionMetadataWithoutInventingMessageContent() {
        assertEquals(
            "default · hermes-4 · 7 messages",
            sessionSummary(
                StoredSession(
                    profile = "default",
                    source = "android",
                    model = "hermes-4",
                    messageCount = 7,
                ),
            ),
        )
        assertEquals("telegram · 1 message", sessionSummary(StoredSession(source = "telegram", messageCount = 1)))
        assertEquals("openai", sessionSummary(StoredSession(model = " ", provider = "openai", source = "android")))
        assertEquals("telegram", sessionSummary(StoredSession(model = "", provider = " ", source = "telegram")))
        assertEquals("Conversation", sessionSummary(StoredSession()))
    }

    @Test
    fun timestampUsesMessagingStyleCalendarLabels() {
        val zone = ZoneId.of("UTC")
        val locale = Locale.US
        val now = Instant.parse("2026-08-12T15:30:00Z").toEpochMilli()

        assertEquals("9:15 AM", formatSessionTimestamp(epoch("2026-08-12T09:15:00Z"), now, zone, locale))
        assertEquals("Yesterday", formatSessionTimestamp(epoch("2026-08-11T09:15:00Z"), now, zone, locale))
        assertEquals("Fri", formatSessionTimestamp(epoch("2026-08-07T09:15:00Z"), now, zone, locale))
        assertEquals("Jul 2", formatSessionTimestamp(epoch("2026-07-02T09:15:00Z"), now, zone, locale))
        assertEquals("Dec 31, 2025", formatSessionTimestamp(epoch("2025-12-31T09:15:00Z"), now, zone, locale))
        assertEquals("", formatSessionTimestamp(0.0, now, zone, locale))

        val frenchDate = formatSessionTimestamp(epoch("2026-07-02T09:15:00Z"), now, zone, Locale.FRANCE)
        assertTrue(frenchDate.indexOf('2') < frenchDate.lowercase(Locale.FRANCE).indexOf("juil"))

        val deviceTimeFormat = SimpleDateFormat("HH:mm", locale).apply {
            timeZone = TimeZone.getTimeZone(zone)
        }
        assertEquals(
            "09:15",
            formatSessionTimestamp(epoch("2026-08-12T09:15:00Z"), now, zone, locale, deviceTimeFormat),
        )
    }

    @Test
    fun timestampClockWaitsUntilTheNextLocalDate() {
        val now = Instant.parse("2026-08-12T23:59:30Z").toEpochMilli()

        assertEquals(30_000L, sessionTimestampRolloverDelayMillis(now, ZoneId.of("UTC")))
    }

    private fun assertConversationRowFits(width: Int, compact: Boolean, click: Boolean, active: Boolean = true) {
        var clicks = 0
        compose.setContent {
            HermesTheme {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                    Surface(Modifier.width(width.dp).testTag("inbox-root")) {
                        SessionRow(
                            session = StoredSession(
                                title = "Release planning",
                                profile = "default",
                                model = "hermes-4",
                                pinned = true,
                                messageCount = 12,
                            ),
                            selected = true,
                            compact = compact,
                            nowMillis = 0L,
                            active = active,
                            onClick = { clicks += 1 },
                            onPin = {},
                            onArchive = {},
                            onDelete = {},
                        )
                    }
                }
            }
        }

        val row = compose.onNode(
            hasContentDescription("Release planning", substring = true) and
                hasContentDescription("default · hermes-4 · 12 messages", substring = true) and
                hasContentDescription("Pinned", substring = true),
        )
        row.assertExists()
        compose.onNode(
            hasContentDescription("Release planning", substring = true) and
                hasContentDescription("Selected", substring = true),
        ).assertExists()
        compose.onNode(
            hasContentDescription("Release planning", substring = true) and
                hasContentDescription("Active", substring = true),
        ).run { if (active) assertExists() else assertDoesNotExist() }
        if (click) row.performClick()

        val rootWidth = compose.onNodeWithTag("inbox-root").fetchSemanticsNode().boundsInRoot.width
        val rowBounds = row.fetchSemanticsNode().boundsInRoot
        assertTrue("Conversation row is clipped", rowBounds.left >= 0f && rowBounds.right <= rootWidth)
        assertEquals(if (click) 1 else 0, clicks)
    }

    private fun epoch(value: String): Double = Instant.parse(value).epochSecond.toDouble()
}
