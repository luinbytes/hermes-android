package com.nousresearch.hermes.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import com.nousresearch.hermes.data.AuthMode
import com.nousresearch.hermes.data.BackendConfig
import com.nousresearch.hermes.data.BotAgentDraft
import com.nousresearch.hermes.data.HermesState
import com.nousresearch.hermes.protocol.BotGroupBlockingRequest
import com.nousresearch.hermes.protocol.BotGroupCandidate
import com.nousresearch.hermes.protocol.BotGroupEntry
import com.nousresearch.hermes.protocol.BotGroupMember
import com.nousresearch.hermes.protocol.BotGroupRoom
import com.nousresearch.hermes.protocol.BotGroupSpeaker
import com.nousresearch.hermes.protocol.BotGroupUiState
import com.nousresearch.hermes.protocol.BotSessionSummary
import com.nousresearch.hermes.protocol.CronJob
import com.nousresearch.hermes.protocol.CronJobSchedule
import com.nousresearch.hermes.protocol.GatewayConnectionState
import com.nousresearch.hermes.protocol.ProfileAsset
import com.nousresearch.hermes.protocol.ProfileInfo
import com.nousresearch.hermes.protocol.StoredSession
import com.nousresearch.hermes.ui.theme.HermesSkin
import com.nousresearch.hermes.ui.theme.HermesTheme
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalLayoutApi::class)
class BotModeManagedDeviceQaTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun phoneAndExpandedNous() = captureRoster("nous", HermesSkin.NOUS)
    @Test fun phoneAndExpandedMidnight() = captureRoster("midnight", HermesSkin.MIDNIGHT)
    @Test fun phoneAndExpandedEmber() = captureRoster("ember", HermesSkin.EMBER)
    @Test fun phoneAndExpandedMono() = captureRoster("mono", HermesSkin.MONO)
    @Test fun phoneAndExpandedCyberpunk() = captureRoster("cyberpunk", HermesSkin.CYBERPUNK)
    @Test fun phoneAndExpandedSlate() = captureRoster("slate", HermesSkin.SLATE)

    @Test fun phoneAndExpandedLoading() = captureRoster("loading", HermesSkin.NOUS, qaState(QaVariant.LOADING))
    @Test fun phoneAndExpandedError() = captureRoster("error", HermesSkin.NOUS, qaState(QaVariant.ERROR))
    @Test fun phoneAndExpandedEmpty() = captureRoster("empty", HermesSkin.NOUS, qaState(QaVariant.EMPTY))
    @Test fun phoneAndExpandedLargeText() = captureRoster("large-text", HermesSkin.NOUS, fontScale = 1.8f)

    @Test
    fun sessionsFirstOptOutKeepsTheLegacyInboxAndData() {
        val state = qaState(QaVariant.POPULATED)
        var mutations = 0
        compose.setContent {
            HermesTheme(HermesSkin.NOUS, darkTheme = true) {
                Surface(Modifier.fillMaxSize().testTag(QA_ROOT)) {
                    RosterPane(
                        state,
                        botModeEnabled = false,
                        onSetBotHidden = { _, _, _ -> mutations++ },
                    )
                }
            }
        }
        compose.onNodeWithText("Bots").assertDoesNotExist()
        compose.onNodeWithText("Search conversations").assertExists()
        assertEquals(0, mutations)
        captureWindow("sessions-first")
    }

    @Test
    fun inboxChoiceSurvivesSavedStateRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            HermesTheme(HermesSkin.NOUS, darkTheme = true) {
                Surface(Modifier.fillMaxSize().testTag(QA_ROOT)) { RosterPane(qaState(QaVariant.POPULATED)) }
            }
        }
        compose.onNodeWithText("Sessions").performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Search conversations").assertExists()
        captureWindow("saved-state-restored")
    }

    @Test
    fun agentCreationShowsAdvancedFieldsAndRealIme() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1").close()
        val imeVisible = AtomicBoolean(false)
        val local = BackendConfig("mac", "Mac mini", "https://mac.test", AuthMode.TOKEN)
        compose.setContent {
            val visible = WindowInsets.isImeVisible
            SideEffect { imeVisible.set(visible) }
            HermesTheme(HermesSkin.NOUS, darkTheme = true) {
                BotAgentCreateDialog(
                    profiles = listOf(ProfileInfo(name = "coder", displayName = "Code Fox")),
                    backends = listOf(local),
                    activeBackendId = local.id,
                    initialClone = null,
                    onDismiss = {},
                    onCreate = { _: BotAgentDraft, _: String, _: String?, _: Boolean, _: Boolean, _: Boolean -> true },
                    onCreated = { _, _ -> },
                )
            }
        }
        compose.onNodeWithContentDescription("Advanced agent setup").performClick()
        compose.onNodeWithText("SOUL.md").assertExists()
        captureWindow("agent-advanced")
        compose.onNodeWithText("Agent handle").performClick().performTextInput("release-helper")
        compose.waitUntil(5_000) { imeVisible.get() }
        captureWindow("agent-ime")
    }

    @Test
    fun routinesRemainUsableAtLargeText() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.8f)) {
                HermesTheme(HermesSkin.NOUS, darkTheme = true) {
                    BotRoutinesDialog(
                        state = qaState(QaVariant.POPULATED).copy(
                            cronJobs = listOf(
                                CronJob(
                                    enabled = true,
                                    id = "daily",
                                    name = "[bot:coder] Daily brief",
                                    prompt = "Summarize release readiness",
                                    schedule = CronJobSchedule(expr = "0 9 * * *", display = "Daily at 09:00"),
                                ),
                            ),
                        ),
                        owner = "coder",
                        onRefresh = {},
                        onSetEnabled = { _, _ -> },
                        onTrigger = {},
                        onLoadRuns = {},
                        onOpenRun = {},
                        onCreate = { _, _, _, _ -> },
                        onUpdate = { _, _, _, _, _ -> },
                        onDelete = {},
                        onDismiss = {},
                    )
                }
            }
        }
        compose.onNodeWithText("Daily brief").assertExists()
        compose.onNodeWithContentDescription("Run job now").assertExists()
        captureWindow("routines-large-text")
    }

    @Test
    fun rosterSurvivesRealDeviceRotation() {
        compose.setContent {
            HermesTheme(HermesSkin.NOUS, darkTheme = true) {
                Surface(Modifier.fillMaxSize().testTag(QA_ROOT)) { RosterPane(qaState(QaVariant.POPULATED)) }
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        try {
            automation.executeShellCommand("settings put system accelerometer_rotation 0").close()
            automation.executeShellCommand("settings put system user_rotation 0").close()
            instrumentation.waitForIdleSync()
            val initialWidth = instrumentation.targetContext.resources.configuration.screenWidthDp
            compose.onNodeWithTag(QA_ROOT).assertExists()
            captureWindow("rotation-natural")
            automation.executeShellCommand("settings put system user_rotation 1").close()
            compose.waitUntil(10_000) {
                instrumentation.targetContext.resources.configuration.screenWidthDp != initialWidth
            }
            compose.onNodeWithTag(QA_ROOT).assertExists()
            compose.onNodeWithText("Bots").assertExists()
            captureWindow("rotation-left")
        } finally {
            automation.executeShellCommand("settings put system accelerometer_rotation 1").close()
        }
    }

    private fun captureRoster(
        name: String,
        skin: HermesSkin,
        state: HermesState = qaState(QaVariant.POPULATED),
        fontScale: Float = 1f,
    ) {
        val renderedDensity = AtomicReference<Float>()
        compose.setContent {
            val density = LocalDensity.current
            SideEffect { renderedDensity.set(density.density) }
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                HermesTheme(skin, darkTheme = true) {
                    Surface(Modifier.fillMaxSize().testTag(QA_ROOT)) { RosterPane(state) }
                }
            }
        }
        assertRosterState(state, requireNotNull(renderedDensity.get()))
        captureWindow(name)
    }

    @Composable
    private fun RosterPane(
        state: HermesState,
        botModeEnabled: Boolean = true,
        onSetBotHidden: (String, String, Boolean) -> Unit = { _, _, _ -> },
    ) {
        val compact = LocalConfiguration.current.screenWidthDp < 600
        if (compact) {
            Roster(state, botModeEnabled, onSetBotHidden, compact = true, Modifier.fillMaxSize())
        } else {
            Row(Modifier.fillMaxSize()) {
                Roster(state, botModeEnabled, onSetBotHidden, compact = false, Modifier.width(380.dp).fillMaxHeight())
                val room = state.botGroups.rooms.firstOrNull()
                if (room == null) {
                    Spacer(Modifier.weight(1f))
                } else {
                    BotGroupConversationScreen(
                        room = room,
                        running = false,
                        blockingRequests = state.botGroups.blockingRequests,
                        onAnswerBlocking = { _, _ -> },
                        onSend = { _, _, _ -> },
                        onEdit = {},
                        onBack = null,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    @Composable
    private fun Roster(
        state: HermesState,
        botModeEnabled: Boolean,
        onSetBotHidden: (String, String, Boolean) -> Unit,
        compact: Boolean,
        modifier: Modifier,
    ) {
        SessionRail(
            state = state,
            connection = GatewayConnectionState.Open,
            onRefresh = {},
            onSearchSessions = {},
            onSession = {},
            onBot = {},
            onGroup = {},
            onSetBotHidden = onSetBotHidden,
            onLoadBotAvatar = { _, _ -> ProfileAsset() },
            onDeleteSession = {},
            onArchiveSession = { _, _ -> },
            onPinSession = { _, _ -> },
            onNewSession = {},
            onArtifacts = {},
            onAutomations = {},
            onManage = {},
            onProfiles = {},
            onCreateGroup = {},
            selectedBotGroupId = state.botGroups.rooms.firstOrNull()?.roomId,
            onEditBot = {},
            onBotRoutines = {},
            onAppSettings = {},
            onBackends = {},
            botModeEnabled = botModeEnabled,
            compact = compact,
            modifier = modifier,
        )
    }

    private fun assertRosterState(state: HermesState, density: Float) {
        compose.onNodeWithText("Bots").assertExists()
        compose.onNodeWithText("Sessions").assertExists()
        compose.onNodeWithContentDescription("Refresh conversations").assertExists()
        when {
            state.sessionListLoading -> compose.onNodeWithText("Code Fox").assertDoesNotExist()
            state.sessionListError != null -> compose.onNodeWithText(state.sessionListError).assertExists()
            state.botCandidates.isEmpty() -> compose.onNodeWithText("NO BOTS YET").assertExists()
            else -> {
                compose.onNode(
                    hasContentDescription("Code Fox", substring = true) and
                        hasContentDescription("Mac mini", substring = true),
                ).assertExists()
                compose.onNode(
                    hasContentDescription("Launch room", substring = true) and
                        hasContentDescription("Needs you", substring = true),
                ).assertExists()
            }
        }
        val refresh = compose.onNodeWithContentDescription("Refresh conversations").fetchSemanticsNode()
        assertTrue(refresh.boundsInRoot.width / density >= 48f)
        assertTrue(refresh.boundsInRoot.height / density >= 48f)
        assertTrue(refresh.config.contains(SemanticsProperties.Role))
    }

    private fun captureWindow(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val screenshot = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val configuration = instrumentation.targetContext.resources.configuration
        val layout = if (configuration.screenWidthDp < 600) "phone" else "expanded"
        val outputName = "bot-mode-qa-$layout-api${android.os.Build.VERSION.SDK_INT}-$name.png"
        PlatformTestStorageRegistry.getInstance().openOutputFile(outputName).use { output ->
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        assertTrue(screenshot.width > 0 && screenshot.height > 0)
    }

    private enum class QaVariant { POPULATED, LOADING, ERROR, EMPTY }

    private companion object {
        const val QA_ROOT = "bot-mode-qa-root"

        fun qaState(variant: QaVariant): HermesState {
            val local = BackendConfig("mac", "Mac mini", "https://mac.test", AuthMode.TOKEN)
            val cloud = BackendConfig("cloud", "Cloud", "https://cloud.test", AuthMode.TOKEN)
            if (variant == QaVariant.EMPTY) return HermesState(backend = local, savedBackends = listOf(local, cloud))

            val now = System.currentTimeMillis() / 1_000.0
            val coderSession = StoredSession(
                sessionId = "bot-coder",
                title = "Bot Chat",
                rootTitle = "Bot Chat",
                profile = "coder",
                isActive = true,
                lastActive = now - 12,
            )
            val coder = ProfileInfo(
                name = "coder",
                displayName = "Code Fox",
                description = "Builds and reviews Android changes",
                provider = "Nous",
                model = "Hermes 4",
                canonicalSession = BotSessionSummary("bot-coder", title = "Bot Chat", preview = "The debug build is ready for QA", lastActive = now - 12),
            )
            val reviewer = ProfileInfo(
                name = "reviewer",
                displayName = "Reviewer",
                description = "Checks specifications and edge cases",
                canonicalSession = BotSessionSummary("bot-reviewer", title = "Bot Chat", preview = "No blockers found", lastActive = now - 75),
            )
            val hidden = ProfileInfo(
                name = "researcher",
                displayName = "Researcher",
                description = "Finds primary-source evidence",
                uiMeta = buildJsonObject { put("hermes-bots", buildJsonObject { put("hidden", true) }) },
            )
            val room = BotGroupRoom(
                name = "Launch room",
                roomId = "launch-room",
                members = listOf(
                    BotGroupMember("coder", "coder-mac", local.id, connectionLabel = local.label, sourceScoped = true),
                    BotGroupMember("reviewer", "reviewer-cloud", cloud.id, connectionLabel = cloud.label, sourceScoped = true),
                ),
                log = listOf(
                    BotGroupEntry("one", BotGroupSpeaker("user", "You"), "Please complete release QA", 1),
                    BotGroupEntry("two", BotGroupSpeaker("member", "Reviewer", cloud.label), "The implementation is green. Please approve publication.", 2),
                ),
            )
            val blocking = BotGroupBlockingRequest(
                roomId = room.roomId,
                member = room.members.last(),
                sessionId = "bot-reviewer",
                requestId = "approval-1",
                kind = "approval",
                prompt = "Publish the verified debug build?",
                command = "gh release upload debug-0f326206bdd6",
                choices = listOf("Approve", "Deny"),
            )
            return HermesState(
                backend = local,
                savedBackends = listOf(local, cloud),
                sessions = listOf(coderSession),
                activeStoredSession = coderSession,
                profiles = listOf(coder, hidden),
                botCandidates = listOf(
                    BotGroupCandidate(coder, local.id, local.label, "coder-mac"),
                    BotGroupCandidate(reviewer, cloud.id, cloud.label, "reviewer-cloud", offline = variant == QaVariant.ERROR),
                    BotGroupCandidate(hidden, local.id, local.label, "researcher-mac"),
                ),
                botGroups = BotGroupUiState(
                    rooms = listOf(room),
                    needsYouRoomIds = setOf(room.roomId),
                    blockingRequests = listOf(blocking),
                ),
                sessionListLoading = variant == QaVariant.LOADING,
                managementLoading = variant == QaVariant.LOADING,
                sessionListError = if (variant == QaVariant.ERROR) "Cloud is offline — showing last-known bots" else null,
                activeProfile = "coder",
                currentProfile = "coder",
            )
        }
    }
}
