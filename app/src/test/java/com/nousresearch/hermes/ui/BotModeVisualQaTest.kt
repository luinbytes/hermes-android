package com.nousresearch.hermes.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.data.AuthMode
import com.nousresearch.hermes.data.BackendConfig
import com.nousresearch.hermes.data.HermesState
import com.nousresearch.hermes.protocol.BotGroupBlockingRequest
import com.nousresearch.hermes.protocol.BotGroupCandidate
import com.nousresearch.hermes.protocol.BotGroupEntry
import com.nousresearch.hermes.protocol.BotGroupMember
import com.nousresearch.hermes.protocol.BotGroupRoom
import com.nousresearch.hermes.protocol.BotGroupSpeaker
import com.nousresearch.hermes.protocol.BotGroupUiState
import com.nousresearch.hermes.protocol.BotSessionSummary
import com.nousresearch.hermes.protocol.GatewayConnectionState
import com.nousresearch.hermes.protocol.ProfileAsset
import com.nousresearch.hermes.protocol.ProfileInfo
import com.nousresearch.hermes.protocol.StoredSession
import com.nousresearch.hermes.ui.theme.HermesSkin
import com.nousresearch.hermes.ui.theme.HermesTheme
import java.io.File
import java.io.FileOutputStream
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w1200dp-h800dp")
class BotModeVisualQaTest {
    @get:Rule
    val compose = createComposeRule()

    @Test fun phoneNous() = captureRoster("phone-nous", HermesSkin.NOUS)
    @Test fun phoneMidnight() = captureRoster("phone-midnight", HermesSkin.MIDNIGHT)
    @Test fun phoneEmber() = captureRoster("phone-ember", HermesSkin.EMBER)
    @Test fun phoneMono() = captureRoster("phone-mono", HermesSkin.MONO)
    @Test fun phoneCyberpunk() = captureRoster("phone-cyberpunk", HermesSkin.CYBERPUNK)
    @Test fun phoneSlate() = captureRoster("phone-slate", HermesSkin.SLATE)

    @Test fun expandedNous() = captureExpanded("expanded-nous", HermesSkin.NOUS)
    @Test fun expandedMidnight() = captureExpanded("expanded-midnight", HermesSkin.MIDNIGHT)
    @Test fun expandedEmber() = captureExpanded("expanded-ember", HermesSkin.EMBER)
    @Test fun expandedMono() = captureExpanded("expanded-mono", HermesSkin.MONO)
    @Test fun expandedCyberpunk() = captureExpanded("expanded-cyberpunk", HermesSkin.CYBERPUNK)
    @Test fun expandedSlate() = captureExpanded("expanded-slate", HermesSkin.SLATE)

    @Test fun phoneLargeText() = captureRoster("phone-large-text", HermesSkin.NOUS, fontScale = 1.8f)
    @Test fun expandedLargeText() = captureExpanded("expanded-large-text", HermesSkin.NOUS, fontScale = 1.4f)
    @Test fun phoneLoading() = captureRoster("phone-loading", HermesSkin.NOUS, state = qaState(QaVariant.LOADING))
    @Test fun phoneError() = captureRoster("phone-error", HermesSkin.NOUS, state = qaState(QaVariant.ERROR))
    @Test fun phoneEmpty() = captureRoster("phone-empty", HermesSkin.NOUS, state = qaState(QaVariant.EMPTY))

    private fun captureRoster(
        name: String,
        skin: HermesSkin,
        fontScale: Float = 1f,
        state: HermesState = qaState(QaVariant.POPULATED),
    ) = capture(name, skin, 360, 800, fontScale) {
        RosterPane(state, compact = true, modifier = Modifier.fillMaxSize())
    }

    private fun captureExpanded(
        name: String,
        skin: HermesSkin,
        fontScale: Float = 1f,
        state: HermesState = qaState(QaVariant.POPULATED),
    ) = capture(name, skin, 1200, 800, fontScale) {
        Row(Modifier.fillMaxSize()) {
            RosterPane(state, compact = false, modifier = Modifier.width(380.dp).fillMaxHeight())
            val room = state.botGroups.rooms.single()
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

    private fun capture(
        name: String,
        skin: HermesSkin,
        width: Int,
        height: Int,
        fontScale: Float,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                HermesTheme(skin = skin, darkTheme = true) {
                    Surface(Modifier.width(width.dp).fillMaxHeight().testTag(QA_ROOT)) { content() }
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Bots").assertExists()
        val image = compose.onNodeWithTag(QA_ROOT).captureToImage().asAndroidBitmap()
        assertTrue("QA frame height changed", image.height == height)
        val output = File(outputDirectory(), "$name.png")
        FileOutputStream(output).use { stream ->
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream))
        }
        assertTrue("QA frame was not written", output.length() > 0)
    }

    @Composable
    private fun RosterPane(state: HermesState, compact: Boolean, modifier: Modifier) {
        SessionRail(
            state = state,
            connection = GatewayConnectionState.Open,
            onRefresh = {},
            onSearchSessions = {},
            onSession = {},
            onBot = {},
            onGroup = {},
            onSetBotHidden = { _, _, _ -> },
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
            botModeEnabled = true,
            compact = compact,
            modifier = modifier,
        )
    }

    private fun outputDirectory(): File {
        val workspace = System.getenv("GITHUB_WORKSPACE")
        val directory = if (workspace == null) {
            File(System.getProperty("java.io.tmpdir"), "hermes-bot-mode-qa")
        } else {
            File(workspace, "app/build/reports/bot-mode-qa")
        }
        assertTrue(directory.mkdirs() || directory.isDirectory)
        return directory
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
                uiMeta = buildJsonObject {
                    put("hermes-bots", buildJsonObject { put("hidden", true) })
                },
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
            val candidates = listOf(
                BotGroupCandidate(coder, local.id, local.label, "coder-mac"),
                BotGroupCandidate(reviewer, cloud.id, cloud.label, "reviewer-cloud", offline = variant == QaVariant.ERROR),
                BotGroupCandidate(hidden, local.id, local.label, "researcher-mac"),
            )
            return HermesState(
                backend = local,
                savedBackends = listOf(local, cloud),
                sessions = listOf(coderSession),
                activeStoredSession = coderSession,
                profiles = listOf(coder, hidden),
                botCandidates = candidates,
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
