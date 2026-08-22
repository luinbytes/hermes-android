package com.nousresearch.hermes.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.nousresearch.hermes.data.HermesState
import com.nousresearch.hermes.data.BackendConfig
import com.nousresearch.hermes.data.SessionRestorationStatus
import com.nousresearch.hermes.R
import com.nousresearch.hermes.data.DiagnosticAction
import com.nousresearch.hermes.data.PendingAttachment
import com.nousresearch.hermes.data.AttachmentPhase
import com.nousresearch.hermes.data.ProfileIdentityDraft
import com.nousresearch.hermes.data.SlashSuggestion
import com.nousresearch.hermes.data.normalizedProfile
import com.nousresearch.hermes.domain.MessageRole
import com.nousresearch.hermes.domain.ComposerBrowseState
import com.nousresearch.hermes.domain.ComposerHistory
import com.nousresearch.hermes.domain.ComposerQueue
import com.nousresearch.hermes.domain.QueuedPrompt
import com.nousresearch.hermes.domain.SensitiveInputKind
import com.nousresearch.hermes.domain.TimelineItem
import com.nousresearch.hermes.domain.ToolState
import com.nousresearch.hermes.domain.indexOfServerMessage
import com.nousresearch.hermes.domain.presentation
import com.nousresearch.hermes.protocol.GatewayConnectionState
import com.nousresearch.hermes.protocol.SessionSearchHit
import com.nousresearch.hermes.protocol.StoredSession
import com.nousresearch.hermes.platform.HermesEntryDelivery
import com.nousresearch.hermes.platform.HermesEntryRequest
import com.nousresearch.hermes.platform.newCameraCaptureUri
import com.nousresearch.hermes.platform.safeExternalUrl
import com.nousresearch.hermes.platform.textShareIntent
import com.nousresearch.hermes.ui.theme.HermesTheme
import com.nousresearch.hermes.ui.theme.HermesSkin
import com.nousresearch.hermes.ui.theme.Warning as WarningColor
import com.nousresearch.hermes.ui.navigation.HermesDestinationRoute
import com.nousresearch.hermes.ui.navigation.HermesNavigator
import com.nousresearch.hermes.ui.navigation.HermesRoute
import com.nousresearch.hermes.ui.navigation.AutomationDestination
import com.nousresearch.hermes.ui.navigation.ManageDestination
import com.nousresearch.hermes.ui.navigation.ManageSection
import com.nousresearch.hermes.ui.navigation.ManagementDestination
import com.nousresearch.hermes.ui.navigation.SessionIdentity
import com.nousresearch.hermes.ui.navigation.AutomationResourceIdentity
import com.nousresearch.hermes.ui.navigation.conversationMutationsEnabled
import com.nousresearch.hermes.ui.navigation.resolveEntryDestination
import com.nousresearch.hermes.ui.navigation.resolveRestoredRoute
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.highlightedCodeBlock
import com.mikepenz.markdown.compose.elements.highlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownAnimations
import com.nousresearch.hermes.network.DashboardAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

private const val MAX_VISIBLE_COMPOSER_HISTORY = 20
private const val MAX_PENDING_ATTACHMENTS = 5
private enum class WorkspaceContent {
    SESSIONS, CHAT, ARTIFACTS, AUTOMATIONS, MANAGE, APP_SETTINGS,
    SKILLS, CRON, WEBHOOKS, PROFILES, BACKENDS, FILES, DIAGNOSTICS,
    PROVIDERS, MESSAGING, MCP, USAGE, BILLING, AGENTS, COMMAND_CENTER,
    STARMAP, HOST_CAPABILITIES, CONFIG,
}

private data class ModelActions(
    val refresh: () -> Unit,
    val select: (String, String) -> Unit,
    val confirm: () -> Unit,
    val cancel: () -> Unit,
    val reasoning: (String) -> Unit,
    val fast: (Boolean) -> Unit,
    val yolo: (Boolean) -> Unit,
)

private data class SessionActionCallbacks(
    val rename: (String) -> Unit,
    val branch: (String) -> Unit,
    val retry: () -> Unit,
    val undo: () -> Unit,
    val compress: (String) -> Unit,
    val reset: () -> Unit,
    val archive: () -> Unit,
    val refreshCheckpoints: () -> Unit,
    val previewCheckpoint: (String) -> Unit,
    val restoreCheckpoint: (String) -> Unit,
)

private data class QueueActions(
    val enqueueDraft: () -> Unit,
    val update: (String, String) -> Unit,
    val remove: (String) -> Unit,
    val sendNow: (String) -> Unit,
)

private data class ManagementActions(
    val refreshArtifacts: (BackendConfig, String) -> Unit,
    val updateArtifactQuery: (String) -> Unit,
    val updateArtifactFilter: (com.nousresearch.hermes.data.ArtifactIndexFilter) -> Unit,
    val loadArtifactPreview: suspend (BackendConfig, com.nousresearch.hermes.domain.DetectedArtifact) -> com.nousresearch.hermes.data.ArtifactPreviewContent,
    val refreshSkills: () -> Unit,
    val toggleSkill: (String, Boolean) -> Unit,
    val loadSkillHub: (String) -> Unit,
    val reviewSkill: (String) -> Unit,
    val closeSkillReview: () -> Unit,
    val installReviewedSkill: () -> Unit,
    val uninstallSkill: (String) -> Unit,
    val updateSkills: () -> Unit,
    val refreshCron: () -> Unit,
    val refreshCronRuns: (String) -> Unit,
    val setCronEnabled: (String, Boolean) -> Unit,
    val triggerCron: (String) -> Unit,
    val createCron: (String, String, String, String) -> Unit,
    val updateCron: (String, String, String, String, String) -> Unit,
    val deleteCron: (String) -> Unit,
    val refreshProfiles: () -> Unit,
    val refreshBotRoster: () -> Unit,
    val setBotHidden: (String, Boolean) -> Unit,
    val createProfile: (String, String, Boolean, Boolean) -> Unit,
    val renameProfile: (String, String) -> Unit,
    val setActiveProfile: (String) -> Unit,
    val deleteProfile: (String) -> Unit,
    val profileIdentity: suspend (String) -> ProfileIdentityDraft,
    val saveProfileSoul: suspend (String, String) -> Unit,
    val saveProfileModel: suspend (String, String, String) -> Unit,
    val refreshStarmap: (String) -> Unit,
    val loadLearningNode: (String, String) -> Unit,
    val closeLearningNode: () -> Unit,
    val updateLearningNode: (String, String, String) -> Unit,
    val deleteLearningNode: (String, String) -> Unit,
    val runDiagnostic: (DiagnosticAction) -> Unit,
    val refreshHostMaintenance: (Boolean) -> Unit,
    val prepareHostBackup: () -> Unit,
    val saveHostBackup: (Uri) -> Unit,
    val cancelHostBackup: () -> Unit,
    val refreshProviders: () -> Unit,
    val startProviderOAuth: (String) -> Unit,
    val submitProviderOAuth: (String) -> Unit,
    val cancelProviderOAuth: () -> Unit,
    val disconnectProviderOAuth: (String) -> Unit,
    val saveProviderSetting: (String, String, String) -> Unit,
    val deleteProviderSetting: (String) -> Unit,
    val refreshMessaging: () -> Unit,
    val setMessagingEnabled: (String, Boolean) -> Unit,
    val saveMessagingSettings: (String, Map<String, String>) -> Unit,
    val clearMessagingSetting: (String, String) -> Unit,
    val testMessagingPlatform: (String) -> Unit,
    val restartMessagingGateway: () -> Unit,
    val refreshMcp: () -> Unit,
    val testMcpServer: (String) -> Unit,
    val setMcpServerEnabled: (String, Boolean) -> Unit,
    val removeMcpServer: (String) -> Unit,
    val installMcpCatalogEntry: (String, Map<String, String>) -> Unit,
    val refreshToolsets: () -> Unit,
    val setToolsetEnabled: (String, Boolean) -> Unit,
    val refreshServerConfig: () -> Unit,
    val updateServerConfig: (String, kotlinx.serialization.json.JsonElement) -> Unit,
    val refreshUsage: (Int) -> Unit,
    val refreshBilling: () -> Unit,
    val chargeBillingCredits: (String) -> Unit,
    val updateBillingAutoReload: (Boolean, String, String) -> Unit,
    val startBillingStepUp: () -> Unit,
    val acknowledgeUnconfirmedBillingCharge: () -> Unit,
    val refreshAgents: () -> Unit,
    val refreshSpawnTrees: () -> Unit,
    val loadSpawnTree: (String) -> Unit,
    val setDelegationPaused: (Boolean) -> Unit,
    val interruptSubagent: (String) -> Unit,
    val stopBackgroundProcess: (String) -> Unit,
)

@Composable
fun HermesApp(
    secureScreen: Boolean = false,
    onSecureScreenChange: (Boolean) -> Unit = {},
    biometricReentry: Boolean = false,
    biometricAvailable: Boolean = false,
    onBiometricReentryChange: (Boolean) -> Unit = {},
    skin: HermesSkin = HermesSkin.NOUS,
    onSkinChange: (HermesSkin) -> Unit = {},
    botModeEnabled: Boolean = false,
    onBotModeEnabledChange: (Boolean) -> Unit = {},
    onWorkspaceReady: () -> Unit = {},
    entryDelivery: HermesEntryDelivery? = null,
    onEntryConsumed: (String) -> Unit = {},
    onEntryFailed: (String, String) -> Unit = { _, _ -> },
    onEntryRetry: (String) -> Unit = {},
    onEntryDiscard: (String) -> Unit = {},
    viewModel: HermesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val startupReady by viewModel.startupReady.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val artifactIndex by viewModel.artifactIndex.collectAsStateWithLifecycle()
    val artifactPreferences by viewModel.artifactPreferences.collectAsStateWithLifecycle()
    val hostBackup by viewModel.hostBackup.collectAsStateWithLifecycle()
    LaunchedEffect(startupReady) {
        if (startupReady) onWorkspaceReady()
    }
    LaunchedEffect(state.backend?.id) { viewModel.bindHostBackupBackend(state.backend?.id) }
    val latestConnectionState = rememberUpdatedState(connection)
    val latestHermesState = rememberUpdatedState(state)
    val entryRequestScope = rememberCoroutineScope()
    val modelActions = remember(viewModel) {
        ModelActions(
            refresh = viewModel::refreshModels,
            select = viewModel::selectModel,
            confirm = viewModel::confirmModel,
            cancel = viewModel::cancelModel,
            reasoning = viewModel::setReasoning,
            fast = viewModel::setFast,
            yolo = viewModel::setYolo,
        )
    }
    val sessionActions = remember(viewModel) {
        SessionActionCallbacks(
            rename = viewModel::renameActive,
            branch = viewModel::branchActive,
            retry = viewModel::retryLastMessage,
            undo = viewModel::undoLastTurn,
            compress = viewModel::compressActive,
            reset = viewModel::resetActive,
            archive = viewModel::archiveActive,
            refreshCheckpoints = viewModel::refreshCheckpoints,
            previewCheckpoint = viewModel::previewCheckpoint,
            restoreCheckpoint = viewModel::restoreCheckpoint,
        )
    }
    val queueActions = remember(viewModel) {
        QueueActions(
            enqueueDraft = viewModel::queueDraft,
            update = viewModel::updateQueuedPrompt,
            remove = viewModel::removeQueuedPrompt,
            sendNow = viewModel::sendQueuedPromptNow,
        )
    }
    val managementActions = remember(viewModel) {
        ManagementActions(
            refreshArtifacts = viewModel::refreshArtifacts,
            updateArtifactQuery = viewModel::updateArtifactQuery,
            updateArtifactFilter = viewModel::updateArtifactFilter,
            loadArtifactPreview = viewModel::loadArtifactPreview,
            refreshSkills = viewModel::refreshSkills,
            toggleSkill = viewModel::toggleSkill,
            loadSkillHub = viewModel::loadSkillHub,
            reviewSkill = viewModel::reviewSkill,
            closeSkillReview = viewModel::closeSkillReview,
            installReviewedSkill = viewModel::installReviewedSkill,
            uninstallSkill = viewModel::uninstallSkill,
            updateSkills = viewModel::updateSkills,
            refreshCron = viewModel::refreshCron,
            refreshCronRuns = viewModel::refreshCronRuns,
            setCronEnabled = viewModel::setCronEnabled,
            triggerCron = viewModel::triggerCron,
            createCron = viewModel::createCron,
            updateCron = viewModel::updateCron,
            deleteCron = viewModel::deleteCron,
            refreshProfiles = viewModel::refreshProfiles,
            refreshBotRoster = viewModel::refreshBotRoster,
            setBotHidden = viewModel::setBotHidden,
            createProfile = viewModel::createProfile,
            renameProfile = viewModel::renameProfile,
            setActiveProfile = viewModel::setActiveProfile,
            deleteProfile = viewModel::deleteProfile,
            profileIdentity = viewModel::profileIdentity,
            saveProfileSoul = viewModel::saveProfileSoul,
            saveProfileModel = viewModel::saveProfileModel,
            refreshStarmap = viewModel::refreshStarmap,
            loadLearningNode = viewModel::loadLearningNode,
            closeLearningNode = viewModel::closeLearningNode,
            updateLearningNode = viewModel::updateLearningNode,
            deleteLearningNode = viewModel::deleteLearningNode,
            runDiagnostic = viewModel::runDiagnostic,
            refreshHostMaintenance = viewModel::refreshHostMaintenance,
            prepareHostBackup = viewModel::prepareHostBackup,
            saveHostBackup = viewModel::saveHostBackup,
            cancelHostBackup = viewModel::cancelHostBackup,
            refreshProviders = viewModel::refreshProviders,
            startProviderOAuth = viewModel::startProviderOAuth,
            submitProviderOAuth = viewModel::submitProviderOAuth,
            cancelProviderOAuth = viewModel::cancelProviderOAuth,
            disconnectProviderOAuth = viewModel::disconnectProviderOAuth,
            saveProviderSetting = viewModel::saveProviderSetting,
            deleteProviderSetting = viewModel::deleteProviderSetting,
            refreshMessaging = viewModel::refreshMessaging,
            setMessagingEnabled = viewModel::setMessagingEnabled,
            saveMessagingSettings = viewModel::saveMessagingSettings,
            clearMessagingSetting = viewModel::clearMessagingSetting,
            testMessagingPlatform = viewModel::testMessagingPlatform,
            restartMessagingGateway = viewModel::restartMessagingGateway,
            refreshMcp = viewModel::refreshMcp,
            testMcpServer = viewModel::testMcpServer,
            setMcpServerEnabled = viewModel::setMcpServerEnabled,
            removeMcpServer = viewModel::removeMcpServer,
            installMcpCatalogEntry = viewModel::installMcpCatalogEntry,
            refreshToolsets = viewModel::refreshToolsets,
            setToolsetEnabled = viewModel::setToolsetEnabled,
            refreshServerConfig = viewModel::refreshServerConfig,
            updateServerConfig = viewModel::updateServerConfig,
            refreshUsage = viewModel::refreshUsage,
            refreshBilling = viewModel::refreshBilling,
            chargeBillingCredits = viewModel::chargeBillingCredits,
            updateBillingAutoReload = viewModel::updateBillingAutoReload,
            startBillingStepUp = viewModel::startBillingStepUp,
            acknowledgeUnconfirmedBillingCharge = viewModel::acknowledgeUnconfirmedBillingCharge,
            refreshAgents = viewModel::refreshAgents,
            refreshSpawnTrees = viewModel::refreshSpawnTrees,
            loadSpawnTree = viewModel::loadSpawnTree,
            setDelegationPaused = viewModel::setDelegationPaused,
            interruptSubagent = viewModel::interruptSubagent,
            stopBackgroundProcess = viewModel::stopBackgroundProcess,
        )
    }
    val appNavController = rememberNavController()
    val navigator = remember(appNavController) { HermesNavigator(appNavController) }
    val currentEntry by appNavController.currentBackStackEntryAsState()
    var recoveryNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(
        entryDelivery?.request?.id,
        entryDelivery?.attempt,
        entryDelivery?.failureMessage,
        state.backend?.id,
        state.status,
    ) {
        val delivery = entryDelivery ?: return@LaunchedEffect
        if (delivery.failureMessage != null) return@LaunchedEffect
        when (val request = delivery.request) {
            is HermesEntryRequest.OpenDestination -> {
                val requested = request.route
                if (requested is HermesDestinationRoute.AppSettings) {
                    navigator.openProductRoute(requested)
                    onEntryConsumed(request.id)
                    return@LaunchedEffect
                }
                val ready = snapshotFlow { latestHermesState.value }.first {
                    it.backend != null && it.status != null && !it.loading
                }
                val backend = checkNotNull(ready.backend)
                snapshotFlow { latestConnectionState.value }.first { it == GatewayConnectionState.Open }
                val authority = viewModel.entryAuthoritySnapshot(
                    includeCronJobs = requested is HermesDestinationRoute.Automations &&
                        requested.destination == AutomationDestination.CRON &&
                        requested.resourceId != null,
                ) ?: run {
                    onEntryFailed(
                        request.id,
                        viewModel.state.value.error ?: "Hermes could not verify this destination.",
                    )
                    return@LaunchedEffect
                }
                val authoritativeSessions = ready.sessions.mapTo(mutableSetOf()) { session ->
                    SessionIdentity(
                        backendId = backend.id,
                        profileId = session.profile?.takeIf(String::isNotBlank) ?: ready.currentProfile,
                        sessionId = session.durableId,
                    )
                }
                val resolution = resolveEntryDestination(
                    route = requested,
                    availableBackendIds = (ready.savedBackends.map { it.id } + backend.id).toSet(),
                    authenticatedBackendId = backend.id,
                    authoritativeSessions = authoritativeSessions,
                    authoritativeProfileIds = authority.profileIds,
                    fallbackProfileId = ready.currentProfile,
                    authoritativeAutomationResources = authority.cronJobIds.mapTo(mutableSetOf()) {
                        AutomationResourceIdentity(AutomationDestination.CRON, it)
                    },
                )
                recoveryNotice = resolution.explanation
                val resolved = resolution.route
                if (resolved is HermesRoute.BackendPicker && resolved.returnBackendId != null) {
                    navigator.replace(resolved)
                    return@LaunchedEffect
                }
                if (resolved is HermesDestinationRoute) {
                    navigator.openProductRoute(resolved)
                } else {
                    navigator.replace(resolved)
                }
                onEntryConsumed(request.id)
            }
            is HermesEntryRequest.ImportDraft -> {
                snapshotFlow { latestHermesState.value }.first {
                    it.backend != null && it.status != null && !it.loading
                }
                snapshotFlow { latestConnectionState.value }.first { it == GatewayConnectionState.Open }
                val imported = try {
                    viewModel.ingestSharedContent(request.content)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    onEntryFailed(
                        request.id,
                        error.message?.takeIf(String::isNotBlank)
                            ?: "Hermes could not add this shared content to a draft.",
                    )
                    return@LaunchedEffect
                }
                if (!imported) {
                    onEntryFailed(
                        request.id,
                        viewModel.state.value.error ?: "Hermes could not add this shared content to a draft.",
                    )
                    return@LaunchedEffect
                }
                val current = viewModel.state.value
                val backend = current.backend
                val session = current.activeStoredSession
                if (backend != null && session != null && session.durableId.isNotBlank()) {
                    navigator.openConversation(
                        backendId = backend.id,
                        profileId = session.profile?.takeIf(String::isNotBlank) ?: current.currentProfile,
                        sessionId = session.durableId,
                    )
                }
                onEntryConsumed(request.id)
            }
            is HermesEntryRequest.NewChat -> {
                val ready = snapshotFlow { latestHermesState.value }.first {
                    it.backend != null && it.status != null && !it.loading
                }
                snapshotFlow { latestConnectionState.value }.first { it == GatewayConnectionState.Open }
                if (!viewModel.newSessionFromEntry(ready.currentProfile)) {
                    onEntryFailed(
                        request.id,
                        viewModel.state.value.error ?: "Hermes could not create a new chat.",
                    )
                    return@LaunchedEffect
                }
                val current = viewModel.state.value
                val backend = current.backend
                val session = current.activeStoredSession
                if (backend != null && session != null && session.durableId.isNotBlank()) {
                    navigator.openConversation(
                        backendId = backend.id,
                        profileId = session.profile?.takeIf(String::isNotBlank) ?: current.currentProfile,
                        sessionId = session.durableId,
                    )
                } else if (backend != null) {
                    navigator.openChats(backend.id, current.currentProfile)
                }
                onEntryConsumed(request.id)
            }
        }
    }
    LaunchedEffect(
        state.backend?.id,
        state.savedBackends.size,
        state.status,
        state.loading,
        state.error,
        connection,
        recoveryNotice,
        currentEntry?.destination?.route,
    ) {
        val destination = currentEntry?.destination ?: return@LaunchedEffect
        val onboarding = destination.hasRoute<HermesRoute.Onboarding>()
        val appSettings = destination.hasRoute<HermesDestinationRoute.AppSettings>()
        val backendPicker = if (destination.hasRoute<HermesRoute.BackendPicker>()) {
            currentEntry?.toRoute<HermesRoute.BackendPicker>()
        } else {
            null
        }
        val backend = state.backend
        if (backend == null) {
            if (appSettings) return@LaunchedEffect
            if (state.savedBackends.isEmpty()) {
                if (!onboarding) navigator.openOnboarding(clearHistory = true)
            } else if (backendPicker == null) {
                recoveryNotice = "Choose and authenticate a Hermes backend to continue."
                navigator.openBackendPicker(clearHistory = true)
            }
            return@LaunchedEffect
        }
        val authenticationExpired = state.error?.lowercase()?.let { error ->
            listOf("401", "unauthorized", "authentication", "sign in", "login").any(error::contains)
        } == true
        if (authenticationExpired && !state.loading && backendPicker == null && !appSettings) {
            recoveryNotice = "Your Hermes authentication expired. Reconnect before continuing."
            navigator.openBackendPicker(clearHistory = true)
        } else if (state.status != null) {
            val recoveryPicker = backendPicker != null &&
                backendPicker.returnBackendId != backend.id && recoveryNotice == null
            if (onboarding || recoveryPicker) {
                recoveryNotice = null
                navigator.openAtlas(backend.id, state.currentProfile, clearHistory = true)
            }
        }
    }

    @Composable
    fun WorkspaceRoute(route: HermesRoute) {
        if (state.backend == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return
        }
        HermesWorkspace(
            route = route,
            navigator = navigator,
            onRecovery = { recoveryNotice = it },
            state = state,
            artifactIndex = artifactIndex,
            artifactPreferences = artifactPreferences,
            hostBackup = hostBackup,
            connection = connection,
            onRefresh = viewModel::refresh,
            onSearchSessions = viewModel::searchSessions,
            onSession = viewModel::openSession,
            onDeleteSession = viewModel::deleteSession,
            onArchiveSession = viewModel::archiveSession,
            onPinSession = viewModel::pinSession,
            onNewSession = viewModel::newSession,
            onSend = viewModel::send,
            onSteer = viewModel::steer,
            onDraftChange = viewModel::updateDraft,
            onCompleteSlash = viewModel::completeSlash,
            onExecuteSlash = viewModel::executeSlash,
            onAttach = { uris -> uris.forEach(viewModel::attach) },
            onRetryAttachment = viewModel::retryAttachment,
            onCancelAttachment = viewModel::cancelAttachment,
            onRemoveAttachment = viewModel::removeAttachment,
            onInterrupt = viewModel::interrupt,
            onApprove = viewModel::approve,
            onClarify = viewModel::clarify,
            onSensitiveInput = viewModel::submitSensitiveInput,
            modelActions = modelActions,
            sessionActions = sessionActions,
            queueActions = queueActions,
            managementActions = managementActions,
            onDiscoverPasswordProviders = viewModel::discoverDashboardPasswordProviders,
            onConnectBackend = viewModel::connect,
            onSelectBackend = viewModel::selectBackend,
            onForgetBackend = viewModel::forgetBackend,
            secureScreen = secureScreen,
            onSecureScreenChange = onSecureScreenChange,
            biometricReentry = biometricReentry,
            biometricAvailable = biometricAvailable,
            onBiometricReentryChange = onBiometricReentryChange,
            skin = skin,
            onSkinChange = onSkinChange,
            botModeEnabled = botModeEnabled,
            onBotModeEnabledChange = onBotModeEnabledChange,
        )
    }
    HermesTheme(skin) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize()) {
                NousBackdrop(skin = skin, modifier = Modifier.fillMaxSize())
                NavHost(
                    navController = appNavController,
                    startDestination = HermesRoute.Onboarding,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    composable<HermesRoute.Onboarding> {
                        OnboardingScreen(
                            busy = state.loading,
                            error = state.error,
                            onDiscoverPasswordProviders = viewModel::discoverDashboardPasswordProviders,
                            onConnect = { label, url, username, password, insecure, provider ->
                                recoveryNotice = null
                                viewModel.connect(label, url, username, password, insecure, provider)
                            },
                        )
                    }
                    composable<HermesRoute.BackendPicker> { entry ->
                        val route = entry.toRoute<HermesRoute.BackendPicker>()
                        BackendsScreen(
                            state = state,
                            onDiscoverPasswordProviders = viewModel::discoverDashboardPasswordProviders,
                            onConnect = { label, url, username, password, insecure, provider ->
                                recoveryNotice = null
                                viewModel.connect(label, url, username, password, insecure, provider)
                            },
                            onSelect = { id ->
                                recoveryNotice = null
                                viewModel.selectBackend(id)
                            },
                            onForget = viewModel::forgetBackend,
                            onBack = route.returnBackendId?.let { { navigator.back(it, route.profileId ?: "default") } },
                            modifier = Modifier.fillMaxSize().statusBarsPadding(),
                        )
                    }
                    composable<HermesRoute.SessionAtlas> { WorkspaceRoute(it.toRoute<HermesRoute.SessionAtlas>()) }
                    composable<HermesRoute.Conversation> { WorkspaceRoute(it.toRoute<HermesRoute.Conversation>()) }
                    composable<HermesRoute.Files> { WorkspaceRoute(it.toRoute<HermesRoute.Files>()) }
                    composable<HermesRoute.Management> { WorkspaceRoute(it.toRoute<HermesRoute.Management>()) }
                    composable<HermesDestinationRoute.Chats> { WorkspaceRoute(it.toRoute<HermesDestinationRoute.Chats>()) }
                    composable<HermesDestinationRoute.Artifacts> { WorkspaceRoute(it.toRoute<HermesDestinationRoute.Artifacts>()) }
                    composable<HermesDestinationRoute.Automations> { WorkspaceRoute(it.toRoute<HermesDestinationRoute.Automations>()) }
                    composable<HermesDestinationRoute.Manage> { WorkspaceRoute(it.toRoute<HermesDestinationRoute.Manage>()) }
                    composable<HermesDestinationRoute.AppSettings> {
                        AppSettingsScreen(
                            secureScreen = secureScreen,
                            onSecureScreenChange = onSecureScreenChange,
                            biometricReentry = biometricReentry,
                            biometricAvailable = biometricAvailable,
                            onBiometricReentryChange = onBiometricReentryChange,
                            skin = skin,
                            onSkinChange = onSkinChange,
                            botModeEnabled = botModeEnabled,
                            onBotModeEnabledChange = onBotModeEnabledChange,
                            onBack = { appNavController.popBackStack() },
                            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
                        )
                    }
                }
                recoveryNotice?.let { notice ->
                    Surface(
                        modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp),
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                    ) {
                        Text(notice, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
            }
        }
        entryDelivery?.failureMessage?.let { failure ->
            AlertDialog(
                onDismissRequest = {},
                title = { Text("Hermes could not finish this request") },
                text = { Text(failure) },
                confirmButton = {
                    TextButton(onClick = { onEntryRetry(entryDelivery.request.id) }) { Text("Retry") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            entryRequestScope.launch {
                                (entryDelivery.request as? HermesEntryRequest.ImportDraft)?.let {
                                    viewModel.discardSharedContent(it.content)
                                }
                                onEntryDiscard(entryDelivery.request.id)
                            }
                        },
                    ) { Text("Discard") }
                },
            )
        }
    }
}

@Composable
internal fun OnboardingScreen(
    busy: Boolean,
    error: String?,
    onDiscoverPasswordProviders: suspend (String, Boolean) -> List<DashboardAuthProvider>,
    onConnect: (String, String, String, String, Boolean, String) -> Unit,
) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var label by remember { mutableStateOf("My Hermes") }
    var url by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var privateHttp by rememberSaveable { mutableStateOf(false) }
    var passwordProviders by remember { mutableStateOf(emptyList<DashboardAuthProvider>()) }
    var selectedProvider by remember { mutableStateOf<String?>(null) }
    var providerSource by remember { mutableStateOf<String?>(null) }
    var providerError by remember { mutableStateOf<String?>(null) }
    var discoveringProviders by remember { mutableStateOf(false) }
    val providerDiscoveryGate = remember { DashboardProviderDiscoveryGate() }
    val providerScope = rememberCoroutineScope()
    val providerKey = "${url.trim().trimEnd('/')}|$privateHttp"

    fun clearProviderSelection() {
        providerDiscoveryGate.invalidate()
        discoveringProviders = false
        passwordProviders = emptyList()
        selectedProvider = null
        providerSource = null
        providerError = null
    }

    Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val direction = if (targetState > initialState) {
                    AnimatedContentTransitionScope.SlideDirection.Left
                } else {
                    AnimatedContentTransitionScope.SlideDirection.Right
                }
                (slideIntoContainer(direction, tween(320)) + fadeIn(tween(220))) togetherWith
                    (slideOutOfContainer(direction, tween(320)) + fadeOut(tween(220)))
            },
            label = "onboarding",
            modifier = Modifier.align(Alignment.Center).padding(18.dp).widthIn(max = 560.dp),
        ) { activeStep ->
            if (activeStep == 0) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        BrandGlyphSmall()
                        Column {
                            Text("HERMES", style = MaterialTheme.typography.titleLarge)
                            Text("AGENT / ANDROID", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                    Text("OPEN SOURCE  ·  NATIVE ANDROID", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "THE AGENT\nTHAT GROWS\nWITH YOU",
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        "Your Hermes sessions, tools, skills, memory and approvals. Native on Android; agent state stays on the backend you control.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = { step = 1 }, modifier = Modifier.fillMaxWidth()) { Text("CONNECT TO HERMES") }
                    Image(
                        painter = painterResource(R.drawable.hermes_hero_art),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                        contentScale = ContentScale.Fit,
                    )
                    ArchitectureStrip()
                }
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()).imePadding(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(
                            onClick = {
                                password = ""
                                clearProviderSelection()
                                step = 0
                            },
                        ) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back") }
                        Text("BACKEND LINK", style = MaterialTheme.typography.headlineMedium)
                    }
                    HermesField(label, { label = it }, "Connection name")
                    HermesField(url, { value -> url = value; clearProviderSelection() }, "Hermes backend URL", KeyboardType.Uri)
                    HermesField(username, { username = it }, "Dashboard username")
                    HermesField(password, { password = it }, "Dashboard password", KeyboardType.Password, secret = true)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .semantics(mergeDescendants = true) {}
                            .toggleable(
                                value = privateHttp,
                                role = Role.Switch,
                                onValueChange = { privateHttp = it; clearProviderSelection() },
                            )
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Use private-network HTTP", style = MaterialTheme.typography.titleMedium)
                            Text("Only literal LAN, loopback or Tailscale IPs. HTTPS is required otherwise.", style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = privateHttp, onCheckedChange = null)
                    }
                    if (passwordProviders.size > 1 && providerSource == providerKey) {
                        DashboardPasswordProviderSelector(
                            providers = passwordProviders,
                            selectedProvider = selectedProvider,
                            onSelected = { selectedProvider = it; providerError = null },
                        )
                    }
                    DashboardOAuthAvailabilityNotice()
                    (providerError ?: error)?.let { ErrorBanner(it) }
                    Button(
                        enabled = !busy && !discoveringProviders && url.isNotBlank() && username.isNotBlank() &&
                            password.isNotEmpty() &&
                            (providerSource != providerKey || passwordProviders.size == 1 || selectedProvider != null),
                        onClick = {
                            val submit: (String) -> Unit = { provider ->
                                val submittedPassword = password
                                password = ""
                                onConnect(label, url, username, submittedPassword, privateHttp, provider)
                            }
                            if (providerSource == providerKey) {
                                selectedProvider?.let(submit)
                            } else {
                                val requestToken = providerDiscoveryGate.begin()
                                if (requestToken != null) {
                                    val requestedUrl = url
                                    val requestedPrivateHttp = privateHttp
                                    discoveringProviders = true
                                    providerError = null
                                    providerScope.launch {
                                        try {
                                            val providers = onDiscoverPasswordProviders(requestedUrl, requestedPrivateHttp)
                                            if (providerDiscoveryGate.isCurrent(requestToken)) {
                                                providerSource = "${requestedUrl.trim().trimEnd('/')}|$requestedPrivateHttp"
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
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (busy || discoveringProviders) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(if (providerSource == providerKey) "Test HTTP + WebSocket and save" else "Check sign-in options and save")
                        }
                    }
                    Text(
                        "Only the returned Dashboard session cookies are encrypted with Android Keystore. Your password is never saved or restored as UI state.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun HermesField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    secret: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Next),
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
        ),
    )
}

@Composable
private fun BrandGlyph() {
    Image(
        painter = painterResource(R.drawable.hermes_badge),
        contentDescription = "Hermes Agent",
        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)),
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun ArchitectureStrip() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("ANDROID", style = MaterialTheme.typography.labelMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Icons.Outlined.SwapHoriz, null, modifier = Modifier.size(16.dp))
                Text("HTTPS / WSS", style = MaterialTheme.typography.labelMedium)
            }
            Text("HERMES SERVE", style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun HermesWorkspace(
    route: HermesRoute,
    navigator: HermesNavigator,
    onRecovery: (String?) -> Unit,
    state: HermesState,
    artifactIndex: ArtifactIndexUiState,
    artifactPreferences: ArtifactBrowserPreferences,
    hostBackup: HostBackupUiState,
    connection: GatewayConnectionState,
    onRefresh: () -> Unit,
    onSearchSessions: (String) -> Unit,
    onSession: (StoredSession) -> Unit,
    onDeleteSession: (StoredSession) -> Unit,
    onArchiveSession: (String, StoredSession) -> Unit,
    onPinSession: (String, StoredSession) -> Unit,
    onNewSession: (String?) -> Unit,
    onSend: (String) -> Unit,
    onSteer: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onCompleteSlash: (String) -> Unit,
    onExecuteSlash: (String) -> Unit,
    onAttach: (List<android.net.Uri>) -> Unit,
    onRetryAttachment: (String) -> Unit,
    onCancelAttachment: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onInterrupt: () -> Unit,
    onApprove: (String) -> Unit,
    onClarify: (String) -> Unit,
    onSensitiveInput: (String) -> Unit,
    modelActions: ModelActions,
    sessionActions: SessionActionCallbacks,
    queueActions: QueueActions,
    managementActions: ManagementActions,
    onDiscoverPasswordProviders: suspend (String, Boolean) -> List<DashboardAuthProvider>,
    onConnectBackend: (String, String, String, String, Boolean, String) -> Unit,
    onSelectBackend: (String) -> Unit,
    onForgetBackend: (String) -> Unit,
    secureScreen: Boolean,
    onSecureScreenChange: (Boolean) -> Unit,
    biometricReentry: Boolean,
    biometricAvailable: Boolean,
    onBiometricReentryChange: (Boolean) -> Unit,
    skin: HermesSkin,
    onSkinChange: (HermesSkin) -> Unit,
    botModeEnabled: Boolean,
    onBotModeEnabledChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val openExternalUrl: (String) -> Unit = remember(context) {
        { value ->
            safeExternalUrl(value)?.let { safe ->
                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safe))) }
            }
        }
    }
    val copyProviderText: (String, String) -> Unit = remember(context) {
        { label, value ->
            runCatching {
                context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText(label, value),
                )
            }
        }
    }
    Box(Modifier.fillMaxSize()) {
        val workspaceConfiguration = currentAdaptiveWorkspaceConfiguration()
        val expanded = workspaceConfiguration.layout == AdaptiveWorkspaceLayout.EXPANDED
        val composerAdaptiveFocusState = rememberAdaptiveFocusState()
        val backendId = requireNotNull(state.backend).id
        val profileId = route.profileIdOr(state.currentProfile)
        LaunchedEffect(botModeEnabled, backendId) {
            while (botModeEnabled) {
                managementActions.refreshBotRoster()
                delay(15_000)
            }
        }
        val supportingSessionId = state.activeStoredSession?.durableId ?: state.runtimeSessionId.orEmpty()
        var supportingToolId by remember(backendId, profileId, supportingSessionId) {
            mutableStateOf<String?>(null)
        }
        var expandedToolIds by remember(backendId, profileId, supportingSessionId) {
            mutableStateOf(emptyList<String>())
        }
        val timelineTools = state.timeline.items.filterIsInstance<TimelineItem.Tool>()
        val availableToolIds = timelineTools.mapTo(mutableSetOf()) { it.id }
        val supportingTool = timelineTools.firstOrNull { it.id == supportingToolId }
        LaunchedEffect(supportingToolId, availableToolIds) {
            expandedToolIds = expandedToolIds.filter(availableToolIds::contains)
            if (supportingToolId != null && supportingToolId !in availableToolIds) supportingToolId = null
        }
        var pendingNewConversationFromId by remember { mutableStateOf<String?>(null) }
        val openStoredSession: (StoredSession) -> Unit = { session ->
            pendingNewConversationFromId = null
            onRecovery(null)
            navigator.openConversation(
                backendId = backendId,
                profileId = session.profile?.takeIf(String::isNotBlank) ?: state.currentProfile,
                sessionId = session.durableId,
            )
        }
        val createConversation: (String?) -> Unit = { profile ->
            pendingNewConversationFromId = state.activeStoredSession?.durableId.orEmpty()
            onNewSession(profile)
        }
        LaunchedEffect(pendingNewConversationFromId, state.activeStoredSession?.durableId) {
            val previousSessionId = pendingNewConversationFromId
            val session = state.activeStoredSession
            if (previousSessionId != null && session != null &&
                session.durableId.isNotBlank() && session.durableId != previousSessionId
            ) {
                pendingNewConversationFromId = null
                navigator.openConversation(
                    backendId = backendId,
                    profileId = session.profile?.takeIf(String::isNotBlank) ?: state.currentProfile,
                    sessionId = session.durableId,
                )
            }
        }
        fun navigate(destination: WorkspaceContent) {
            onRecovery(null)
            when (destination) {
                WorkspaceContent.SESSIONS -> navigator.openAtlas(backendId, profileId)
                WorkspaceContent.FILES -> navigator.openFiles(
                    backendId,
                    profileId,
                    state.runtimeInfo.cwd.takeIf(String::isNotBlank),
                )
                WorkspaceContent.BACKENDS -> navigator.openBackendPicker(backendId, profileId)
                WorkspaceContent.CHAT -> Unit
                else -> navigator.openManagement(backendId, profileId, destination.toManagementDestination())
            }
        }

        fun openNativeEntry(entry: NativeDestinationEntry) {
            onRecovery(null)
            when (entry.action) {
                NativeDestinationAction.REMOTE_FILES -> navigator.openFiles(
                    backendId,
                    profileId,
                    state.runtimeInfo.cwd.takeIf(String::isNotBlank),
                )
                NativeDestinationAction.CRON -> navigator.openAutomations(backendId, profileId, AutomationDestination.CRON)
                NativeDestinationAction.COMMAND_CENTER -> navigator.openAutomations(
                    backendId,
                    profileId,
                    AutomationDestination.COMMAND_CENTER,
                )
                NativeDestinationAction.AGENTS -> navigator.openAutomations(
                    backendId,
                    profileId,
                    AutomationDestination.AGENTS,
                )
                NativeDestinationAction.SKILLS -> navigator.openManage(
                    backendId,
                    profileId,
                    ManageSection.CAPABILITIES,
                    ManageDestination.SKILLS,
                )
                NativeDestinationAction.MCP -> navigator.openManage(
                    backendId,
                    profileId,
                    ManageSection.CAPABILITIES,
                    ManageDestination.MCP,
                )
                NativeDestinationAction.PROFILES -> navigator.openManage(
                    backendId,
                    profileId,
                    ManageSection.PROFILES_AND_MODELS,
                    ManageDestination.PROFILES,
                )
                NativeDestinationAction.PROVIDERS -> navigator.openManage(
                    backendId,
                    profileId,
                    ManageSection.PROFILES_AND_MODELS,
                    ManageDestination.PROVIDERS,
                )
                NativeDestinationAction.MESSAGING -> navigator.openManage(
                    backendId,
                    profileId,
                    ManageSection.CONNECTIONS_AND_DELIVERY,
                    ManageDestination.MESSAGING,
                )
                NativeDestinationAction.BACKENDS -> navigator.openBackendPicker(backendId, profileId)
                NativeDestinationAction.SERVER_SETTINGS -> navigator.openManage(
                    backendId,
                    profileId,
                    ManageSection.SERVER_AND_ACCOUNT,
                    ManageDestination.CONFIG,
                )
                NativeDestinationAction.USAGE -> navigator.openManage(
                    backendId,
                    profileId,
                    ManageSection.SERVER_AND_ACCOUNT,
                    ManageDestination.USAGE,
                )
                NativeDestinationAction.BILLING -> navigator.openManage(
                    backendId,
                    profileId,
                    ManageSection.SERVER_AND_ACCOUNT,
                    ManageDestination.BILLING,
                )
                NativeDestinationAction.REMOTE_DIAGNOSTICS -> navigator.openManage(
                    backendId,
                    profileId,
                    ManageSection.SERVER_AND_ACCOUNT,
                    ManageDestination.DIAGNOSTICS,
                )
                NativeDestinationAction.STARMAP -> navigator.openManage(
                    backendId,
                    profileId,
                    ManageSection.MEMORY_AND_LEARNING,
                    ManageDestination.STARMAP,
                )
                null -> Unit
            }
        }

        @Composable
        fun RowScope.WorkspaceDestinationContent(
            destination: WorkspaceContent,
            compact: Boolean,
            filesPath: String? = null,
            conversationReady: Boolean = true,
        ) {
            @Composable
            fun ConversationContent() {
                if (conversationReady) {
                    ChatSurface(
                        state, connection, profileId, onSend, onSteer, onDraftChange, onCompleteSlash, onExecuteSlash,
                        onAttach, onRetryAttachment, onCancelAttachment, onRemoveAttachment, onInterrupt,
                        onApprove, onClarify, onSensitiveInput, modelActions, sessionActions, queueActions,
                        Modifier.weight(1f),
                        compactLayout = compact,
                        adaptiveFocusState = composerAdaptiveFocusState,
                        expandedToolIds = expandedToolIds.toSet(),
                        toolDisclosureKey = { tool ->
                            scopedToolPaneKey(backendId, profileId, supportingSessionId, tool.id)
                        },
                        onToolExpandedChange = { tool, toolExpanded ->
                            if (toolExpanded) {
                                expandedToolIds = (expandedToolIds + tool.id).distinct()
                                supportingToolId = tool.id
                            } else {
                                expandedToolIds = expandedToolIds - tool.id
                                if (supportingToolId == tool.id) supportingToolId = null
                            }
                        },
                        onBack = if (compact) ({ navigator.back(backendId, profileId) }) else null,
                        onFiles = { navigate(WorkspaceContent.FILES) },
                        focusMessageId = (route as? HermesDestinationRoute.Chats)?.messageId,
                    )
                } else {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            when (destination) {
                    WorkspaceContent.ARTIFACTS -> ArtifactsScreen(
                        backend = requireNotNull(state.backend),
                        profileId = profileId,
                        indexState = artifactIndex,
                        preferences = artifactPreferences,
                        selectedArtifactId = (route as? HermesDestinationRoute.Artifacts)?.artifactId,
                        expanded = !compact,
                        onRefresh = { managementActions.refreshArtifacts(requireNotNull(state.backend), profileId) },
                        onQueryChange = managementActions.updateArtifactQuery,
                        onFilterChange = managementActions.updateArtifactFilter,
                        onSelect = { entry -> navigator.openArtifacts(backendId, profileId, artifactId = entry.artifact.id) },
                        onOpenChat = { entry ->
                            navigator.openConversation(
                                backendId = backendId,
                                profileId = entry.artifact.origin.profileId,
                                sessionId = entry.artifact.origin.sessionId,
                                messageId = entry.artifact.origin.messageId,
                            )
                        },
                        onBack = { navigator.back(backendId, profileId) },
                        loadPreview = { entry ->
                            managementActions.loadArtifactPreview(requireNotNull(state.backend), entry.artifact)
                        },
                        modifier = Modifier.weight(1f),
                    )
                    WorkspaceContent.AUTOMATIONS -> NativeDestinationScreen(
                        destination = NativeDestination.AUTOMATIONS,
                        onBack = { navigator.back(backendId, profileId) },
                        onOpenEntry = ::openNativeEntry,
                        modifier = Modifier.weight(1f),
                    )
                    WorkspaceContent.MANAGE -> NativeDestinationScreen(
                        destination = NativeDestination.MANAGE,
                        onBack = { navigator.back(backendId, profileId) },
                        onOpenEntry = ::openNativeEntry,
                        modifier = Modifier.weight(1f),
                    )
                    WorkspaceContent.APP_SETTINGS -> AppSettingsScreen(
                        secureScreen = secureScreen,
                        onSecureScreenChange = onSecureScreenChange,
                        biometricReentry = biometricReentry,
                        biometricAvailable = biometricAvailable,
                        onBiometricReentryChange = onBiometricReentryChange,
                        skin = skin,
                        onSkinChange = onSkinChange,
                        botModeEnabled = botModeEnabled,
                        onBotModeEnabledChange = onBotModeEnabledChange,
                        onBack = { navigator.back(backendId, profileId) },
                        modifier = Modifier.weight(1f),
                    )
                    WorkspaceContent.SKILLS -> SkillsScreen(
                        state, managementActions.refreshSkills, managementActions.toggleSkill,
                        managementActions.refreshToolsets, managementActions.setToolsetEnabled,
                        managementActions.loadSkillHub, managementActions.reviewSkill, managementActions.closeSkillReview,
                        managementActions.installReviewedSkill, managementActions.uninstallSkill, managementActions.updateSkills,
                        { navigator.back(backendId, profileId) }, Modifier.weight(1f),
                    )
                    WorkspaceContent.CRON -> CronScreen(
                        state, managementActions.refreshCron, managementActions.setCronEnabled,
                        managementActions.triggerCron, managementActions.refreshCronRuns,
                        openStoredSession,
                        managementActions.createCron,
                        managementActions.updateCron, managementActions.deleteCron,
                        { navigator.back(backendId, profileId) }, Modifier.weight(1f),
                    )
                    WorkspaceContent.PROFILES -> ProfilesScreen(
                        state = state,
                        onRefresh = managementActions.refreshProfiles,
                        onStartSession = createConversation,
                        onCreate = managementActions.createProfile,
                        onRename = managementActions.renameProfile,
                        onSetActive = managementActions.setActiveProfile,
                        onDelete = managementActions.deleteProfile,
                        onLoadIdentity = managementActions.profileIdentity,
                        onSaveSoul = managementActions.saveProfileSoul,
                        onSaveModel = managementActions.saveProfileModel,
                        onBack = { navigator.back(backendId, profileId) },
                        modifier = Modifier.weight(1f),
                    )
                    WorkspaceContent.BACKENDS -> BackendsScreen(
                        state = state,
                        onDiscoverPasswordProviders = onDiscoverPasswordProviders,
                        onConnect = onConnectBackend,
                        onSelect = onSelectBackend,
                        onForget = onForgetBackend,
                        onBack = { navigator.back(backendId, profileId) },
                        modifier = Modifier.weight(1f),
                    )
                    WorkspaceContent.FILES -> WorkspaceFilesScreen(
                        backend = requireNotNull(state.backend),
                        initialPath = filesPath ?: state.runtimeInfo.cwd.takeIf(String::isNotBlank),
                        onBack = { navigator.back(backendId, profileId) },
                        modifier = Modifier.weight(1f),
                    )
                    WorkspaceContent.DIAGNOSTICS -> DiagnosticsScreen(
                        state = state,
                        connection = connection,
                        onRun = managementActions.runDiagnostic,
                        onRefreshHost = managementActions.refreshHostMaintenance,
                        backup = hostBackup,
                        onPrepareBackup = managementActions.prepareHostBackup,
                        onSaveBackup = managementActions.saveHostBackup,
                        onCancelBackup = managementActions.cancelHostBackup,
                        onBack = { navigator.back(backendId, profileId) },
                        modifier = Modifier.weight(1f),
                    )
                    WorkspaceContent.WEBHOOKS -> NativeDestinationScreen(
                        destination = NativeDestination.AUTOMATIONS,
                        onBack = { navigator.back(backendId, profileId) },
                        modifier = Modifier.weight(1f),
                    )
                    WorkspaceContent.COMMAND_CENTER -> AgentsScreen(
                        state = state,
                        onRefresh = managementActions.refreshAgents,
                        onRefreshArchives = managementActions.refreshSpawnTrees,
                        onLoadArchive = managementActions.loadSpawnTree,
                        onSetPaused = managementActions.setDelegationPaused,
                        onInterrupt = managementActions.interruptSubagent,
                        onStopProcess = managementActions.stopBackgroundProcess,
                        onOpenSession = openStoredSession,
                        onBack = { navigator.back(backendId, profileId) },
                        modifier = Modifier.weight(1f),
                    )
                    WorkspaceContent.STARMAP -> StarmapScreen(
                        state = state,
                        profile = profileId,
                        onRefresh = managementActions.refreshStarmap,
                        onOpenNode = managementActions.loadLearningNode,
                        onCloseNode = managementActions.closeLearningNode,
                        onUpdateNode = managementActions.updateLearningNode,
                        onDeleteNode = managementActions.deleteLearningNode,
                        onBack = { navigator.back(backendId, profileId) },
                        modifier = Modifier.weight(1f),
                    )
                    WorkspaceContent.HOST_CAPABILITIES -> HostCapabilitiesScreen(
                        onBack = { navigator.back(backendId, profileId) },
                        modifier = Modifier.weight(1f),
                    )
                    WorkspaceContent.PROVIDERS -> ProvidersScreen(
                        state = state,
                        onRefresh = managementActions.refreshProviders,
                        onSave = managementActions.saveProviderSetting,
                        onDelete = managementActions.deleteProviderSetting,
                        onStartOAuth = managementActions.startProviderOAuth,
                        onSubmitOAuth = managementActions.submitProviderOAuth,
                        onCancelOAuth = managementActions.cancelProviderOAuth,
                        onDisconnectOAuth = managementActions.disconnectProviderOAuth,
                        onOpenUrl = openExternalUrl,
                        onCopy = copyProviderText,
                        onBack = { navigator.back(backendId, profileId) },
                        modifier = Modifier.weight(1f),
                    )
                    WorkspaceContent.MESSAGING -> MessagingScreen(
                        state = state,
                        onRefresh = managementActions.refreshMessaging,
                        onSetEnabled = managementActions.setMessagingEnabled,
                        onSave = managementActions.saveMessagingSettings,
                        onClear = managementActions.clearMessagingSetting,
                        onTest = managementActions.testMessagingPlatform,
                        onRestartGateway = managementActions.restartMessagingGateway,
                        onBack = { navigator.back(backendId, profileId) },
                        modifier = Modifier.weight(1f),
                    )
                    WorkspaceContent.MCP -> McpScreen(
                        state = state,
                        onRefresh = managementActions.refreshMcp,
                        onTest = managementActions.testMcpServer,
                        onSetEnabled = managementActions.setMcpServerEnabled,
                        onRemove = managementActions.removeMcpServer,
                        onInstall = managementActions.installMcpCatalogEntry,
                        onBack = { navigator.back(backendId, profileId) },
                        modifier = Modifier.weight(1f),
                    )
                    WorkspaceContent.USAGE -> UsageScreen(
                        state = state,
                        onRefresh = managementActions.refreshUsage,
                        onBack = { navigator.back(backendId, profileId) },
                        modifier = Modifier.weight(1f),
                    )
                    WorkspaceContent.BILLING -> BillingScreen(
                        state = state,
                        onRefresh = managementActions.refreshBilling,
                        onCharge = managementActions.chargeBillingCredits,
                        onUpdateAutoReload = managementActions.updateBillingAutoReload,
                        onStepUp = managementActions.startBillingStepUp,
                        onAcknowledgeUnconfirmedCharge = managementActions.acknowledgeUnconfirmedBillingCharge,
                        onOpenUrl = openExternalUrl,
                        onBack = { navigator.back(backendId, profileId) },
                        modifier = Modifier.weight(1f),
                    )
                    WorkspaceContent.AGENTS -> AgentsScreen(
                        state = state,
                        onRefresh = managementActions.refreshAgents,
                        onRefreshArchives = managementActions.refreshSpawnTrees,
                        onLoadArchive = managementActions.loadSpawnTree,
                        onSetPaused = managementActions.setDelegationPaused,
                        onInterrupt = managementActions.interruptSubagent,
                        onStopProcess = managementActions.stopBackgroundProcess,
                        onOpenSession = openStoredSession,
                        onBack = { navigator.back(backendId, profileId) },
                        modifier = Modifier.weight(1f),
                    )
                    WorkspaceContent.CONFIG -> ServerConfigScreen(
                        state = state,
                        onRefresh = managementActions.refreshServerConfig,
                        onUpdate = managementActions.updateServerConfig,
                        onBack = { navigator.back(backendId, profileId) },
                        modifier = Modifier.weight(1f),
                    )
                    WorkspaceContent.SESSIONS -> if (compact) {
                        SessionRail(
                            state, connection, onRefresh, onSearchSessions,
                            onSession = openStoredSession,
                            onBot = { bot -> bot.latestSession?.let(openStoredSession) ?: createConversation(bot.profile.name) },
                            onSetBotHidden = managementActions.setBotHidden,
                            onDeleteSession = onDeleteSession,
                            onArchiveSession = onArchiveSession,
                            onPinSession = onPinSession,
                            onNewSession = { createConversation(null) },
                            onArtifacts = { navigator.openArtifacts(backendId, profileId) },
                            onAutomations = { navigator.openAutomations(backendId, profileId) },
                            onManage = { navigator.openManage(backendId, profileId) },
                            onAppSettings = { navigator.openAppSettings() },
                            onBackends = { navigate(WorkspaceContent.BACKENDS) },
                            botModeEnabled = botModeEnabled,
                            compact = true,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                        )
                    } else {
                        ConversationContent()
                    }

                    WorkspaceContent.CHAT -> ConversationContent()
            }
        }

        @Composable
        fun WorkspaceRouteContent(
            destination: WorkspaceContent,
            filesPath: String? = null,
            conversationReady: Boolean = true,
        ) {
            AdaptiveWorkspaceShell(
                configuration = workspaceConfiguration,
                destination = destination,
                destinations = WorkspaceContent.entries,
                isListDestination = { it == WorkspaceContent.SESSIONS },
                paneModifier = { paneDestination, compact ->
                    if (compact && paneDestination != WorkspaceContent.CHAT) {
                        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                    } else {
                        Modifier.fillMaxSize()
                    }
                },
                expandedNavigation = {
                    SessionRail(
                        state, connection, onRefresh, onSearchSessions,
                        onSession = openStoredSession,
                        onBot = { bot -> bot.latestSession?.let(openStoredSession) ?: createConversation(bot.profile.name) },
                        onSetBotHidden = managementActions.setBotHidden,
                        onDeleteSession = onDeleteSession,
                        onArchiveSession = onArchiveSession,
                        onPinSession = onPinSession,
                        onNewSession = { createConversation(null) },
                        onArtifacts = { navigator.openArtifacts(backendId, profileId) },
                        onAutomations = { navigator.openAutomations(backendId, profileId) },
                        onManage = { navigator.openManage(backendId, profileId) },
                        onAppSettings = { navigator.openAppSettings() },
                        onBackends = { navigate(WorkspaceContent.BACKENDS) },
                        botModeEnabled = botModeEnabled,
                        modifier = Modifier.width(330.dp).fillMaxHeight(),
                    )
                    HorizontalDivider(Modifier.fillMaxHeight().width(1.dp))
                },
                modifier = if (expanded) {
                    Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                } else {
                    Modifier.fillMaxSize()
                },
                supportingPaneKey = supportingTool
                    ?.takeIf { destination == WorkspaceContent.CHAT }
                    ?.let { tool ->
                        scopedToolPaneKey(backendId, profileId, supportingSessionId, tool.id)
                    },
                supportingPane = supportingTool
                    ?.takeIf { destination == WorkspaceContent.CHAT }
                    ?.let { tool ->
                        {
                            ToolSupportingPane(
                                tool = tool,
                                onClose = {
                                    expandedToolIds = expandedToolIds - tool.id
                                    supportingToolId = null
                                },
                            )
                        }
                    },
            ) { activeDestination, compact ->
                WorkspaceDestinationContent(
                    destination = activeDestination,
                    compact = compact,
                    filesPath = filesPath,
                    conversationReady = conversationReady,
                )
            }
        }

        val authoritativeReady = state.status != null && connection == GatewayConnectionState.Open
        val authoritativeSessions = (state.sessions + listOfNotNull(state.activeStoredSession)).mapTo(mutableSetOf()) { session ->
            SessionIdentity(
                backendId = backendId,
                profileId = session.profile?.takeIf(String::isNotBlank) ?: state.currentProfile,
                sessionId = session.durableId,
            )
        }
        val resolution = if (authoritativeReady) {
            resolveRestoredRoute(
                route = route,
                availableBackendIds = (state.savedBackends.map { it.id } + backendId).toSet(),
                authenticatedBackendId = backendId,
                authoritativeSessions = authoritativeSessions,
            )
        } else {
            null
        }
        LaunchedEffect(route, resolution) {
            if (resolution != null && resolution.route != route) {
                onRecovery(resolution.explanation)
                navigator.replace(resolution.route)
            }
        }

        val restorationNeedsAtlas = state.restoration.status in setOf(
            SessionRestorationStatus.RECOVERY_REQUIRED,
            SessionRestorationStatus.SESSION_UNAVAILABLE,
            SessionRestorationStatus.PROFILE_MISMATCH,
        )
        LaunchedEffect(route, restorationNeedsAtlas, state.restoration.explanation) {
            if (restorationNeedsAtlas && route !is HermesRoute.SessionAtlas) {
                onRecovery(state.restoration.explanation)
                navigator.replace(
                    HermesRoute.SessionAtlas(
                        backendId = backendId,
                        profileId = state.restoration.target?.profile ?: route.profileIdOr(state.currentProfile),
                    ),
                )
            }
        }

        val restorationInProgress = state.restoration.status == SessionRestorationStatus.AUTHENTICATING ||
            state.restoration.status == SessionRestorationStatus.REHYDRATING
        val atlasRecoveryReady = route is HermesRoute.SessionAtlas && restorationNeedsAtlas
        val restorationGateClosed = !state.restoration.mutationsEnabled && !atlasRecoveryReady
        if (!authoritativeReady || restorationInProgress || restorationGateClosed || resolution == null || resolution.route != route || !resolution.mutationsEnabled) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            when (route) {
                is HermesRoute.SessionAtlas -> WorkspaceRouteContent(WorkspaceContent.SESSIONS)
                is HermesRoute.Files -> WorkspaceRouteContent(WorkspaceContent.FILES, filesPath = route.path)
                is HermesRoute.Management -> WorkspaceRouteContent(route.destination.toWorkspaceContent())
                is HermesDestinationRoute.Chats -> {
                    if (route.sessionId == null) {
                        WorkspaceRouteContent(WorkspaceContent.SESSIONS)
                    } else {
                        val active = state.activeStoredSession
                        val activeProfile = active?.profile?.takeIf(String::isNotBlank) ?: state.currentProfile
                        val legacyRoute = HermesRoute.Conversation(route.backendId, route.profileId, route.sessionId)
                        val conversationReady = conversationMutationsEnabled(
                            route = legacyRoute,
                            activeBackendId = backendId,
                            activeSession = active?.let { SessionIdentity(backendId, activeProfile, it.durableId) },
                            runtimeStoredSessionId = state.runtimeInfo.storedSessionId,
                            runtimeSessionId = state.runtimeSessionId,
                        )
                        LaunchedEffect(route, state.loading, conversationReady) {
                            if (!conversationReady && !state.loading) {
                                state.sessions.firstOrNull { candidate ->
                                    candidate.durableId == route.sessionId &&
                                        (candidate.profile?.takeIf(String::isNotBlank) ?: state.currentProfile) == route.profileId
                                }?.let(onSession)
                            }
                        }
                        WorkspaceRouteContent(WorkspaceContent.CHAT, conversationReady = conversationReady)
                    }
                }
                is HermesDestinationRoute.Artifacts -> when {
                    route.filePath != null -> WorkspaceRouteContent(WorkspaceContent.FILES, filesPath = route.filePath)
                    else -> WorkspaceRouteContent(WorkspaceContent.ARTIFACTS)
                }
                is HermesDestinationRoute.Automations -> if (route.resourceId != null) {
                    ScopedDestinationScreen(
                        title = route.destination?.name?.replace('_', ' ') ?: "Automation",
                        resourceId = route.resourceId,
                        onBack = { navigator.back(backendId, profileId) },
                        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
                    )
                } else {
                    WorkspaceRouteContent(
                        when (route.destination) {
                            null -> WorkspaceContent.AUTOMATIONS
                            AutomationDestination.CRON -> WorkspaceContent.CRON
                            AutomationDestination.AGENTS -> WorkspaceContent.AGENTS
                            AutomationDestination.WEBHOOKS -> WorkspaceContent.WEBHOOKS
                            AutomationDestination.COMMAND_CENTER -> WorkspaceContent.COMMAND_CENTER
                        },
                    )
                }
                is HermesDestinationRoute.Manage -> if (route.resourceId != null) {
                    ScopedDestinationScreen(
                        title = route.destination?.name?.replace('_', ' ') ?: "Managed resource",
                        resourceId = route.resourceId,
                        onBack = { navigator.back(backendId, profileId) },
                        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
                    )
                } else {
                    WorkspaceRouteContent(route.destination?.toWorkspaceContent() ?: WorkspaceContent.MANAGE)
                }
                is HermesDestinationRoute.AppSettings -> Unit
                is HermesRoute.Conversation -> {
                    val active = state.activeStoredSession
                    val activeProfile = active?.profile?.takeIf(String::isNotBlank) ?: state.currentProfile
                    val conversationReady = conversationMutationsEnabled(
                        route = route,
                        activeBackendId = backendId,
                        activeSession = active?.let { SessionIdentity(backendId, activeProfile, it.durableId) },
                        runtimeStoredSessionId = state.runtimeInfo.storedSessionId,
                        runtimeSessionId = state.runtimeSessionId,
                    )
                    LaunchedEffect(route, state.loading, conversationReady) {
                        if (!conversationReady && !state.loading) {
                            state.sessions.firstOrNull { candidate ->
                                candidate.durableId == route.sessionId &&
                                    (candidate.profile?.takeIf(String::isNotBlank) ?: state.currentProfile) == route.profileId
                            }?.let(onSession)
                        }
                    }
                    WorkspaceRouteContent(WorkspaceContent.CHAT, conversationReady = conversationReady)
                }
                HermesRoute.Onboarding, is HermesRoute.BackendPicker -> Unit
            }
        }
    }
}

private fun WorkspaceContent.toManagementDestination(): ManagementDestination = when (this) {
    WorkspaceContent.SKILLS -> ManagementDestination.SKILLS
    WorkspaceContent.CRON -> ManagementDestination.CRON
    WorkspaceContent.WEBHOOKS -> ManagementDestination.WEBHOOKS
    WorkspaceContent.PROFILES -> ManagementDestination.PROFILES
    WorkspaceContent.DIAGNOSTICS -> ManagementDestination.DIAGNOSTICS
    WorkspaceContent.PROVIDERS -> ManagementDestination.PROVIDERS
    WorkspaceContent.MESSAGING -> ManagementDestination.MESSAGING
    WorkspaceContent.MCP -> ManagementDestination.MCP
    WorkspaceContent.USAGE -> ManagementDestination.USAGE
    WorkspaceContent.BILLING -> ManagementDestination.BILLING
    WorkspaceContent.AGENTS -> ManagementDestination.AGENTS
    WorkspaceContent.COMMAND_CENTER -> ManagementDestination.COMMAND_CENTER
    WorkspaceContent.STARMAP -> ManagementDestination.STARMAP
    WorkspaceContent.HOST_CAPABILITIES -> ManagementDestination.HOST_CAPABILITIES
    WorkspaceContent.CONFIG -> ManagementDestination.CONFIG
    WorkspaceContent.SESSIONS,
    WorkspaceContent.CHAT,
    WorkspaceContent.ARTIFACTS,
    WorkspaceContent.AUTOMATIONS,
    WorkspaceContent.MANAGE,
    WorkspaceContent.APP_SETTINGS,
    WorkspaceContent.BACKENDS,
    WorkspaceContent.FILES,
    -> error("$this is not a management destination")
}

private fun ManagementDestination.toWorkspaceContent(): WorkspaceContent = when (this) {
    ManagementDestination.SKILLS -> WorkspaceContent.SKILLS
    ManagementDestination.CRON -> WorkspaceContent.CRON
    ManagementDestination.WEBHOOKS -> WorkspaceContent.WEBHOOKS
    ManagementDestination.PROFILES -> WorkspaceContent.PROFILES
    ManagementDestination.BACKENDS -> WorkspaceContent.BACKENDS
    ManagementDestination.DIAGNOSTICS -> WorkspaceContent.DIAGNOSTICS
    ManagementDestination.PROVIDERS -> WorkspaceContent.PROVIDERS
    ManagementDestination.MESSAGING -> WorkspaceContent.MESSAGING
    ManagementDestination.MCP -> WorkspaceContent.MCP
    ManagementDestination.USAGE -> WorkspaceContent.USAGE
    ManagementDestination.BILLING -> WorkspaceContent.BILLING
    ManagementDestination.AGENTS -> WorkspaceContent.AGENTS
    ManagementDestination.COMMAND_CENTER -> WorkspaceContent.COMMAND_CENTER
    ManagementDestination.STARMAP -> WorkspaceContent.STARMAP
    ManagementDestination.HOST_CAPABILITIES -> WorkspaceContent.HOST_CAPABILITIES
    ManagementDestination.CONFIG -> WorkspaceContent.CONFIG
}

private fun ManageDestination.toWorkspaceContent(): WorkspaceContent = when (this) {
    ManageDestination.SKILLS -> WorkspaceContent.SKILLS
    ManageDestination.MCP -> WorkspaceContent.MCP
    ManageDestination.HOST_CAPABILITIES -> WorkspaceContent.HOST_CAPABILITIES
    ManageDestination.PROFILES -> WorkspaceContent.PROFILES
    ManageDestination.BACKENDS -> WorkspaceContent.BACKENDS
    ManageDestination.PROVIDERS -> WorkspaceContent.PROVIDERS
    ManageDestination.MESSAGING -> WorkspaceContent.MESSAGING
    ManageDestination.STARMAP -> WorkspaceContent.STARMAP
    ManageDestination.DIAGNOSTICS -> WorkspaceContent.DIAGNOSTICS
    ManageDestination.USAGE -> WorkspaceContent.USAGE
    ManageDestination.BILLING -> WorkspaceContent.BILLING
    ManageDestination.CONFIG -> WorkspaceContent.CONFIG
}

private fun HermesRoute.profileIdOr(fallback: String): String = when (this) {
    is HermesRoute.BackendPicker -> profileId
    is HermesRoute.SessionAtlas -> profileId
    is HermesRoute.Conversation -> profileId
    is HermesRoute.Files -> profileId
    is HermesRoute.Management -> profileId
    is HermesDestinationRoute.Chats -> profileId
    is HermesDestinationRoute.Artifacts -> profileId
    is HermesDestinationRoute.Automations -> profileId
    is HermesDestinationRoute.Manage -> profileId
    is HermesDestinationRoute.AppSettings -> null
    HermesRoute.Onboarding -> null
}.orEmpty().ifBlank { fallback }

internal fun scopedToolPaneKey(
    backendId: String,
    profileId: String,
    sessionId: String,
    toolId: String,
): String = listOf(backendId, profileId, sessionId, toolId).joinToString(separator = "") { segment ->
    "${segment.length}:$segment"
}

@Composable
private fun SessionRail(
    state: HermesState,
    connection: GatewayConnectionState,
    onRefresh: () -> Unit,
    onSearchSessions: (String) -> Unit,
    onSession: (StoredSession) -> Unit,
    onBot: (BotConversation) -> Unit,
    onSetBotHidden: (String, Boolean) -> Unit,
    onDeleteSession: (StoredSession) -> Unit,
    onArchiveSession: (String, StoredSession) -> Unit,
    onPinSession: (String, StoredSession) -> Unit,
    onNewSession: () -> Unit,
    onArtifacts: () -> Unit,
    onAutomations: () -> Unit,
    onManage: () -> Unit,
    onAppSettings: () -> Unit,
    onBackends: () -> Unit,
    botModeEnabled: Boolean,
    compact: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var inboxMode by rememberSaveable(botModeEnabled) {
        mutableStateOf(if (botModeEnabled) ChatInboxMode.BOTS else ChatInboxMode.SESSIONS)
    }
    var pendingDelete by remember { mutableStateOf<StoredSession?>(null) }
    var confirmNewSession by rememberSaveable { mutableStateOf(false) }
    var showHiddenBots by rememberSaveable(state.backend?.id) { mutableStateOf(false) }
    var activityWatermarks by remember(state.backend?.id) { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var unreadProfiles by remember(state.backend?.id) { mutableStateOf<Set<String>>(emptySet()) }
    val visibleSessions = state.sessions.filter { session ->
        query.isBlank() || listOf(
            session.displayTitle,
            session.profile,
            session.model,
            session.provider,
            session.source,
        ).filterNotNull().any { it.contains(query.trim(), ignoreCase = true) }
    }.sortedWith(compareByDescending<StoredSession> { it.pinned == true }.thenByDescending { it.lastActive })
    val remoteResults = if (query.isBlank()) emptyList() else state.sessionSearchResults.filterNot { result ->
        visibleSessions.any { it.durableId == result.sessionId && it.profile == result.profile }
    }
    val sessionSections = if (query.isBlank()) {
        listOf(
            "PINNED" to visibleSessions.filter { it.pinned == true },
            "RECENT" to visibleSessions.filterNot { it.pinned == true },
        )
    } else {
        listOf("" to visibleSessions)
    }
    val allBots = botConversations(
        state.profiles,
        state.sessions,
        state.activeStoredSession,
        sourceLabel = state.backend?.label.orEmpty(),
        unreadProfiles = unreadProfiles,
    )
    val hiddenBots = allBots.filter(BotConversation::hidden)
    val visibleBots = allBots.filter { (showHiddenBots || !it.hidden) && it.matches(query) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val timeFormat = android.text.format.DateFormat.getTimeFormat(LocalContext.current)
    var timestampNowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LifecycleResumeEffect(Unit) {
        timestampNowMillis = System.currentTimeMillis()
        onPauseOrDispose {}
    }
    LaunchedEffect(Unit) {
        while (true) {
            val currentMillis = System.currentTimeMillis()
            delay(sessionTimestampRolloverDelayMillis(currentMillis))
            timestampNowMillis = System.currentTimeMillis()
        }
    }
    val startNewSession = {
        if (state.runtimeSessionId == null) onNewSession() else confirmNewSession = true
    }
    val openBot: (BotConversation) -> Unit = { bot ->
        unreadProfiles = unreadProfiles - bot.profile.name
        onBot(bot)
    }
    LaunchedEffect(allBots.map { it.profile.name to it.activityTimestamp }) {
        if (activityWatermarks.isEmpty()) {
            activityWatermarks = allBots.associate { it.profile.name to it.activityTimestamp }
        } else {
            val selectedProfile = state.activeStoredSession?.profile.normalizedProfile()
            unreadProfiles = unreadProfiles + allBots.mapNotNull { bot ->
                val previous = activityWatermarks[bot.profile.name] ?: bot.activityTimestamp
                bot.profile.name.takeIf {
                    bot.activityTimestamp > previous && bot.profile.name.normalizedProfile() != selectedProfile
                }
            }
            activityWatermarks = allBots.associate { it.profile.name to maxOf(activityWatermarks[it.profile.name] ?: 0.0, it.activityTimestamp) }
        }
    }
    LaunchedEffect(query, inboxMode) {
        onSearchSessions(if (inboxMode == ChatInboxMode.SESSIONS) query else "")
    }
    val railContent: @Composable () -> Unit = {
        Box(modifier.background(Color.Transparent)) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (compact) {
                        IconButton(onClick = { drawerScope.launch { drawerState.open() } }) {
                            Icon(Icons.Outlined.Menu, "Open navigation")
                        }
                    }
                    if (!compact) {
                        BrandGlyphSmall()
                        Spacer(Modifier.width(10.dp))
                    }
                    Column(Modifier.weight(1f).clickable(onClick = onBackends)) {
                        Text(if (compact) "CHATS" else "HERMES", style = MaterialTheme.typography.titleLarge)
                        Text(state.backend?.label.orEmpty(), style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "Refresh conversations") }
                    if (inboxMode == ChatInboxMode.BOTS && hiddenBots.isNotEmpty()) {
                        IconButton(onClick = { showHiddenBots = !showHiddenBots }) {
                            Icon(
                                if (showHiddenBots) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                if (showHiddenBots) "Hide hidden bots" else "Show hidden bots",
                            )
                        }
                    }
                    if (!compact) {
                        IconButton(onClick = startNewSession) { Icon(Icons.Outlined.Add, "New conversation") }
                    }
                }
                ConnectionLine(connection)
                if (state.sessionListLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (botModeEnabled) {
                    BotInboxSelector(inboxMode) { inboxMode = it }
                }
                if (!compact) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onArtifacts,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            Icon(Icons.Outlined.Folder, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Artifacts")
                        }
                        OutlinedButton(
                            onClick = onAutomations,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            Icon(Icons.Outlined.Schedule, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Automations")
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(
                            onClick = onManage,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            Icon(Icons.Outlined.Tune, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Manage")
                        }
                        OutlinedButton(
                            onClick = onAppSettings,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 12.dp),
                        ) {
                            Icon(Icons.Outlined.Info, null)
                            Spacer(Modifier.width(6.dp))
                            Text("App settings")
                        }
                    }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it.take(200) },
                    placeholder = { Text(if (inboxMode == ChatInboxMode.BOTS) "Search bots" else "Search conversations") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    trailingIcon = {
                        if (state.sessionSearchLoading) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(28.dp),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
                state.error?.let { ErrorBanner(it, Modifier.padding(12.dp)) }
                state.sessionListError?.let { ErrorBanner(it, Modifier.padding(horizontal = 12.dp)) }
                LazyColumn(
                    contentPadding = PaddingValues(top = 8.dp, bottom = if (compact) 88.dp else 8.dp),
                ) {
                    if (inboxMode == ChatInboxMode.BOTS) {
                        items(visibleBots, key = { "bot:${it.profile.name}" }) { bot ->
                            BotRow(
                                bot = bot,
                                nowMillis = timestampNowMillis,
                                busyProfile = state.activeProfile.takeIf { state.runtimeInfo.running },
                                onClick = { openBot(bot) },
                                onToggleHidden = {
                                    onSetBotHidden(bot.profile.name, !bot.hidden)
                                },
                            )
                        }
                        if (visibleBots.isEmpty() && !state.managementLoading) {
                            item("bots-empty") {
                                Column(
                                    Modifier.fillMaxWidth().padding(32.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                ) {
                                    Text(if (state.profiles.isEmpty()) "NO BOTS YET" else "NO MATCHES", style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        if (state.profiles.isEmpty()) "Refresh Hermes profiles or create an agent in Manage."
                                        else "Try a bot name, role or recent conversation.",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    } else {
                    sessionSections.forEach { (section, sessions) ->
                        if (section.isNotEmpty() && sessions.isNotEmpty()) {
                            item("section:$section") {
                                Text(
                                    section,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 6.dp)
                                        .semantics { heading() },
                                )
                            }
                        }
                        items(sessions, key = { "${it.profile}:${it.durableId}" }) { session ->
                            val selected = state.activeStoredSession?.let { activeSession ->
                                sameSession(activeSession, session)
                            } == true
                            val active = session.isActive || (selected && state.runtimeSessionId != null)
                            val displayedBackendId = state.backend?.id.orEmpty()
                            SessionRow(
                                session = session,
                                selected = selected,
                                compact = compact,
                                nowMillis = timestampNowMillis,
                                active = active,
                                timeFormat = timeFormat,
                                onClick = { onSession(session) },
                                onPin = if (session.pinned != null) {
                                    { onPinSession(displayedBackendId, session) }
                                } else {
                                    null
                                },
                                onArchive = { onArchiveSession(displayedBackendId, session) },
                                onDelete = if (state.activeStoredSession?.durableId != session.durableId) {
                                    { pendingDelete = session }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                    if (remoteResults.isNotEmpty()) {
                        item("section:search-results") {
                            Text(
                                "SEARCH RESULTS",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(start = 20.dp, top = 12.dp, end = 20.dp, bottom = 6.dp)
                                    .semantics { heading() },
                            )
                        }
                    }
                    items(remoteResults, key = { "search:${it.profile}:${it.sessionId}" }) { result ->
                        SearchResultRow(result) {
                            onSession(
                                StoredSession(
                                    sessionId = result.sessionId,
                                    profile = result.profile,
                                    source = result.source,
                                    model = result.model,
                                    startedAt = result.sessionStarted,
                                ),
                            )
                        }
                    }
                    if (
                        visibleSessions.isEmpty() && remoteResults.isEmpty() &&
                        !state.loading && !state.sessionListLoading && !state.sessionSearchLoading
                    ) {
                        item {
                            Column(
                                Modifier.fillMaxWidth().padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(
                                    if (state.sessions.isEmpty()) "NO CONVERSATIONS" else "NO MATCHES",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                Text(
                                    if (state.sessions.isEmpty()) "Start a conversation or connect another Hermes surface."
                                    else "Try a title, profile, model, provider or source.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    }
                }
            }
            if (compact && inboxMode == ChatInboxMode.SESSIONS) {
                FloatingActionButton(
                    onClick = startNewSession,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
                ) {
                    Icon(Icons.Outlined.Edit, "New conversation")
                }
            }
        }
    }

    if (compact) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet {
                    Text("HERMES", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
                    NavigationDrawerItem(
                        label = { Text("Chats") },
                        selected = true,
                        onClick = { drawerScope.launch { drawerState.close() } },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("Artifacts") },
                        selected = false,
                        onClick = onArtifacts,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("Automations") },
                        selected = false,
                        onClick = onAutomations,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("Manage") },
                        selected = false,
                        onClick = onManage,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                    NavigationDrawerItem(
                        label = { Text("App settings") },
                        selected = false,
                        onClick = onAppSettings,
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
            },
        ) {
            railContent()
        }
    } else {
        railContent()
    }

    pendingDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("DELETE SESSION?") },
            text = { Text("${session.displayTitle} and its stored transcript will be permanently removed from Hermes. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDeleteSession(session)
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } },
        )
    }
    if (confirmNewSession) {
        AlertDialog(
            onDismissRequest = { confirmNewSession = false },
            title = { Text("START FRESH?") },
            text = { Text("Hermes will end the current live conversation and open a clean session. Its stored transcript remains available in the session list.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmNewSession = false
                        onNewSession()
                    },
                ) { Text("Start new session") }
            },
            dismissButton = { TextButton(onClick = { confirmNewSession = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SearchResultRow(
    result: SessionSearchHit,
    onClick: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Surface(
            onClick = onClick,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(48.dp)) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        highlightedSearchSnippet(
                            result.snippet.ifBlank { "Conversation ${result.sessionId}" },
                            MaterialTheme.colorScheme.primary,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOf(result.profile, result.source, result.model).filterNotNull().filter(String::isNotBlank).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(start = 76.dp))
    }
}

private fun highlightedSearchSnippet(raw: String, highlight: Color): AnnotatedString = buildAnnotatedString {
    val text = raw.trim()
    var cursor = 0
    while (cursor < text.length) {
        val start = text.indexOf(">>>", cursor)
        if (start < 0) {
            append(text.substring(cursor))
            break
        }
        append(text.substring(cursor, start))
        val end = text.indexOf("<<<", start + 3)
        if (end < 0) {
            append(text.substring(start))
            break
        }
        withStyle(SpanStyle(color = highlight, fontWeight = FontWeight.Bold)) {
            append(text.substring(start + 3, end))
        }
        cursor = end + 3
    }
}

@Composable
private fun BrandGlyphSmall() {
    Image(
        painter = painterResource(R.drawable.hermes_badge),
        contentDescription = "Hermes Agent",
        modifier = Modifier.size(34.dp).clip(RoundedCornerShape(8.dp)),
        contentScale = ContentScale.Crop,
    )
}

@Composable
private fun ConnectionLine(connection: GatewayConnectionState) {
    val (colour, text) = when (connection) {
        GatewayConnectionState.Open -> MaterialTheme.colorScheme.tertiary to "LIVE / JSON-RPC"
        is GatewayConnectionState.Connecting -> WarningColor to "CONNECTING"
        is GatewayConnectionState.Reconnecting -> WarningColor to "RECONNECTING"
        is GatewayConnectionState.Failed -> MaterialTheme.colorScheme.error to "CONNECTION FAILED"
        is GatewayConnectionState.Closed -> MaterialTheme.colorScheme.error to "OFFLINE"
        GatewayConnectionState.Idle -> MaterialTheme.colorScheme.outline to "IDLE"
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(colour))
        Spacer(Modifier.width(7.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = colour)
    }
}

@Composable
internal fun SessionRow(
    session: StoredSession,
    selected: Boolean,
    compact: Boolean,
    nowMillis: Long,
    active: Boolean,
    timeFormat: java.text.DateFormat? = null,
    onClick: () -> Unit,
    onPin: (() -> Unit)?,
    onArchive: () -> Unit,
    onDelete: (() -> Unit)?,
) {
    val actionWidth = (56 * ((if (onPin != null) 1 else 0) + 1 + if (onDelete != null) 1 else 0)).dp
    val actionWidthPx = with(LocalDensity.current) { actionWidth.toPx() }
    val layoutDirection = LocalLayoutDirection.current
    val openOffset = if (layoutDirection == LayoutDirection.Rtl) actionWidthPx else -actionWidthPx
    val summary = sessionSummary(session)
    val timestamp = formatSessionTimestamp(session.lastActive, nowMillis, timeFormat = timeFormat)
    val sessionDescription = listOfNotNull(
        session.displayTitle,
        summary,
        timestamp.takeIf(String::isNotBlank),
        "Selected".takeIf { selected },
        "Active".takeIf { active },
        "Pinned".takeIf { session.pinned == true },
    ).joinToString(", ")
    val anchors = remember(actionWidthPx, openOffset) {
        DraggableAnchors<Boolean> {
            false at 0f
            true at openOffset
        }
    }
    val swipeState = remember(actionWidthPx, layoutDirection) { AnchoredDraggableState(false, anchors) }
    val actionScope = rememberCoroutineScope()
    val avatarSize = if (compact) 48.dp else 40.dp
    val horizontalPadding = if (compact) 16.dp else 12.dp
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)) {
        Row(
            Modifier.matchParentSize().padding(end = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            onPin?.let { pin ->
                SessionSwipeAction(
                    icon = Icons.Outlined.PushPin,
                    contentDescription = if (session.pinned == true) "Unpin ${session.displayTitle}" else "Pin ${session.displayTitle}",
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    visible = swipeState.currentValue,
                    onClick = {
                        pin()
                        actionScope.launch { swipeState.animateTo(false) }
                    },
                )
            }
            SessionSwipeAction(
                icon = Icons.Outlined.Archive,
                contentDescription = "Archive ${session.displayTitle}",
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                visible = swipeState.currentValue,
                onClick = {
                    onArchive()
                    actionScope.launch { swipeState.animateTo(false) }
                },
            )
            onDelete?.let { delete ->
                SessionSwipeAction(
                    icon = Icons.Outlined.Delete,
                    contentDescription = "Delete ${session.displayTitle}",
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    visible = swipeState.currentValue,
                    onClick = {
                        delete()
                        actionScope.launch { swipeState.animateTo(false) }
                    },
                )
            }
        }
        Row(
            Modifier.fillMaxWidth()
                .offset {
                    val offset = swipeState.offset
                    IntOffset(if (offset.isNaN()) 0 else offset.roundToInt(), 0)
                }
                .anchoredDraggable(
                    state = swipeState,
                    orientation = Orientation.Horizontal,
                    reverseDirection = false,
                )
                .background(
                    if (selected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                )
                .clickable {
                    if (swipeState.currentValue) {
                        actionScope.launch { swipeState.animateTo(false) }
                    } else {
                        onClick()
                    }
                }
                .clearAndSetSemantics {
                    role = Role.Button
                    contentDescription = sessionDescription
                    onClick {
                        if (swipeState.currentValue) {
                            actionScope.launch { swipeState.animateTo(false) }
                        } else {
                            onClick()
                        }
                        true
                    }
                    customActions = listOfNotNull(
                        onPin?.let { pin ->
                            CustomAccessibilityAction(
                                label = if (session.pinned == true) "Unpin ${session.displayTitle}" else "Pin ${session.displayTitle}",
                                action = { pin(); true },
                            )
                        },
                        CustomAccessibilityAction(
                            label = "Archive ${session.displayTitle}",
                            action = { onArchive(); true },
                        ),
                        onDelete?.let { delete ->
                            CustomAccessibilityAction(
                                label = "Delete ${session.displayTitle}",
                                action = { delete(); true },
                            )
                        },
                    )
                }
                .padding(horizontal = horizontalPadding, vertical = if (compact) 11.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = CircleShape,
                color = if (active) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(avatarSize),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        sessionAvatarLabel(session),
                        style = MaterialTheme.typography.titleMedium,
                        color = if (active) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(session.displayTitle, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (timestamp.isNotEmpty()) {
                    Text(timestamp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (session.pinned == true) {
                        Icon(Icons.Outlined.PushPin, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    }
                    if (active) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
                    }
                }
            }
        }
        }
        HorizontalDivider(Modifier.padding(start = horizontalPadding + avatarSize + 12.dp))
    }
}

internal fun sameSession(first: StoredSession, second: StoredSession): Boolean =
    first.durableId == second.durableId &&
        first.profile.normalizedProfile() == second.profile.normalizedProfile()

internal fun sessionSummary(session: StoredSession): String {
    val metadataLabel = listOf(session.model, session.provider, session.source)
        .firstNotNullOfOrNull { it?.trim()?.takeIf(String::isNotEmpty) }
    val metadata = listOfNotNull(
        session.profile?.trim()?.takeIf(String::isNotEmpty),
        metadataLabel,
    ).distinct()
    val messageCount = session.messageCount.takeIf { it > 0 }?.let { "$it message${if (it == 1) "" else "s"}" }
    return (metadata + listOfNotNull(messageCount)).joinToString(" · ").ifBlank { "Conversation" }
}

private fun sessionAvatarLabel(session: StoredSession): String =
    (session.profile ?: session.source)?.firstOrNull(Char::isLetterOrDigit)?.uppercase() ?: "H"

internal fun sessionTimestampRolloverDelayMillis(
    nowMillis: Long,
    zoneId: ZoneId = ZoneId.systemDefault(),
): Long {
    val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
    val nextDate = now.toLocalDate().plusDays(1).atStartOfDay(zoneId)
    return ChronoUnit.MILLIS.between(now, nextDate).coerceAtLeast(1L)
}

internal fun formatSessionTimestamp(
    epochSeconds: Double,
    nowMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
    timeFormat: java.text.DateFormat? = null,
): String {
    if (!epochSeconds.isFinite() || epochSeconds <= 0.0) return ""
    val value = runCatching { Instant.ofEpochMilli((epochSeconds * 1_000).toLong()).atZone(zoneId) }.getOrNull() ?: return ""
    val now = Instant.ofEpochMilli(nowMillis).atZone(zoneId)
    val daysAgo = ChronoUnit.DAYS.between(value.toLocalDate(), now.toLocalDate())
    return when {
        daysAgo == 0L -> timeFormat?.format(java.util.Date(value.toInstant().toEpochMilli()))
            ?: value.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
        daysAgo == 1L -> "Yesterday"
        daysAgo in 2L..6L -> value.format(DateTimeFormatter.ofPattern("EEE", locale))
        value.year == now.year -> value.format(
            DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"), locale),
        )
        else -> value.format(
            DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "yMMMd"), locale),
        )
    }
}

@Composable
private fun RowScope.SessionSwipeAction(
    icon: ImageVector,
    contentDescription: String,
    containerColor: Color,
    contentColor: Color,
    visible: Boolean,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(56.dp)
            .focusProperties { canFocus = visible }
            .then(if (visible) Modifier else Modifier.clearAndSetSemantics {}),
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(containerColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription, tint = contentColor, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun ChatSurface(
    state: HermesState,
    connection: GatewayConnectionState,
    profileId: String,
    onSend: (String) -> Unit,
    onSteer: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onCompleteSlash: (String) -> Unit,
    onExecuteSlash: (String) -> Unit,
    onAttach: (List<android.net.Uri>) -> Unit,
    onRetryAttachment: (String) -> Unit,
    onCancelAttachment: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onInterrupt: () -> Unit,
    onApprove: (String) -> Unit,
    onClarify: (String) -> Unit,
    onSensitiveInput: (String) -> Unit,
    modelActions: ModelActions,
    sessionActions: SessionActionCallbacks,
    queueActions: QueueActions,
    modifier: Modifier = Modifier,
    compactLayout: Boolean = true,
    adaptiveFocusState: AdaptiveFocusState,
    expandedToolIds: Set<String> = emptySet(),
    toolDisclosureKey: (TimelineItem.Tool) -> String = { it.id },
    onToolExpandedChange: ((TimelineItem.Tool, Boolean) -> Unit)? = null,
    onBack: (() -> Unit)? = null,
    onFiles: (() -> Unit)? = null,
    focusMessageId: String? = null,
) {
    val voiceViewModel: VoiceViewModel = hiltViewModel()
    val speechState by voiceViewModel.speechState.collectAsStateWithLifecycle()
    LaunchedEffect(state.backend?.id, profileId) { state.backend?.let { voiceViewModel.bind(it, profileId) } }
    DisposableEffect(voiceViewModel) {
        onDispose {
            voiceViewModel.cancelRecording()
            voiceViewModel.stopSpeaking()
        }
    }
    Column(modifier.statusBarsPadding()) {
        ChatHeader(state, onBack, onFiles, sessionActions)
        Box(Modifier.weight(1f)) {
            if (state.runtimeSessionId == null) {
                Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    BrandGlyph()
                    Spacer(Modifier.height(18.dp))
                    Text("OPEN A SESSION", style = MaterialTheme.typography.headlineMedium)
                    Text("Select an existing conversation or create a new one.", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                key(state.activeStoredSession?.id ?: state.runtimeSessionId) {
                    Timeline(
                        items = state.timeline.items,
                        speechState = speechState,
                        onSpeak = voiceViewModel::speak,
                        onStopSpeaking = voiceViewModel::stopSpeaking,
                        expandedToolIds = expandedToolIds,
                        toolDisclosureKey = toolDisclosureKey,
                        onToolExpandedChange = onToolExpandedChange,
                        focusMessageId = focusMessageId,
                    )
                }
            }
            if (state.loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
        state.error?.let { ErrorBanner(it, Modifier.padding(horizontal = 12.dp)) }
        state.compatibilityWarning?.let { CompatibilityBanner(it, Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) }
        if (state.runtimeSessionId != null) {
            ModelControls(
                state = state,
                onRefresh = modelActions.refresh,
                onSelect = modelActions.select,
                onConfirmModel = modelActions.confirm,
                onCancelModel = modelActions.cancel,
                onReasoning = modelActions.reasoning,
                onFast = modelActions.fast,
                onYolo = modelActions.yolo,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
            Composer(
                backend = requireNotNull(state.backend),
                historySessionId = requireNotNull(state.runtimeSessionId),
                userHistory = remember(state.timeline.items) { ComposerHistory.derive(state.timeline) },
                draft = state.draft,
                slashSuggestions = state.slashSuggestions,
                slashLoading = state.slashLoading,
                sending = state.sending || state.runtimeInfo.running || state.timeline.items.any {
                    it is TimelineItem.Message && it.streaming
                },
                connected = connection is GatewayConnectionState.Open,
                attachmentEnabled = state.supportsRemoteAttachments,
                attachments = state.pendingAttachments,
                queuedPrompts = state.queuedPrompts,
                queueDraining = state.queueDraining,
                queueNotice = state.queueNotice,
                onSend = onSend,
                onSteer = onSteer,
                onQueue = queueActions.enqueueDraft,
                onUpdateQueued = queueActions.update,
                onRemoveQueued = queueActions.remove,
                onSendQueuedNow = queueActions.sendNow,
                onDraftChange = onDraftChange,
                onCompleteSlash = onCompleteSlash,
                onExecuteSlash = onExecuteSlash,
                onAttach = onAttach,
                onRetryAttachment = onRetryAttachment,
                onCancelAttachment = onCancelAttachment,
                onRemoveAttachment = onRemoveAttachment,
                onInterrupt = onInterrupt,
                voiceViewModel = voiceViewModel,
                compactLayout = compactLayout,
                adaptiveFocusState = adaptiveFocusState,
            )
        }
    }

    state.timeline.approval?.let { request ->
        ApprovalDialog(request.command, request.description, request.choices, onApprove)
    }
    state.timeline.clarification?.let { request ->
        ClarificationDialog(request.question, request.choices, onClarify)
    }
    state.timeline.sensitiveInput?.let { request ->
        SensitiveInputDialog(
            requestId = request.requestId,
            kind = request.kind,
            prompt = request.prompt,
            environmentVariable = request.environmentVariable,
            onSubmit = onSensitiveInput,
        )
    }
}

@Composable
private fun ChatHeader(
    state: HermesState,
    onBack: (() -> Unit)?,
    onFiles: (() -> Unit)?,
    sessionActions: SessionActionCallbacks,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to sessions") } }
        Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                state.activeStoredSession?.displayTitle ?: state.runtimeInfo.title.ifBlank { "New session" },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
            )
            Text(
                listOf(state.activeStoredSession?.profile, state.runtimeInfo.provider, state.runtimeInfo.model)
                    .filterNotNull().filter(String::isNotBlank).joinToString(" / ").ifBlank { "Hermes Agent" },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        onFiles?.let { IconButton(onClick = it) { Icon(Icons.Outlined.Folder, "Open session files") } }
        SessionActions(
            state = state,
            onRename = sessionActions.rename,
            onBranch = sessionActions.branch,
            onRetry = sessionActions.retry,
            onUndo = sessionActions.undo,
            onCompress = sessionActions.compress,
            onReset = sessionActions.reset,
            onArchive = sessionActions.archive,
            onRefreshCheckpoints = sessionActions.refreshCheckpoints,
            onPreviewCheckpoint = sessionActions.previewCheckpoint,
            onRestoreCheckpoint = sessionActions.restoreCheckpoint,
        )
    }
    HorizontalDivider()
}

@Composable
internal fun Timeline(
    items: List<TimelineItem>,
    speechState: SpeechUiState,
    onSpeak: (String, String) -> Unit,
    onStopSpeaking: () -> Unit,
    expandedToolIds: Set<String>,
    toolDisclosureKey: (TimelineItem.Tool) -> String,
    onToolExpandedChange: ((TimelineItem.Tool, Boolean) -> Unit)?,
    focusMessageId: String?,
) {
    val listState = rememberLazyListState()
    var followLatest by rememberSaveable { mutableStateOf(true) }
    var focusConsumed by rememberSaveable(focusMessageId) { mutableStateOf(false) }
    var initialScrollObserved by remember { mutableStateOf(false) }
    var previousTotalItems by remember { mutableIntStateOf(0) }
    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index,
                listState.layoutInfo.totalItemsCount,
                listState.isScrollInProgress,
            )
        }.collect { (lastVisibleIndex, totalItems, isScrollInProgress) ->
            if (initialScrollObserved) {
                if (!(totalItems > previousTotalItems && !isScrollInProgress)) {
                    followLatest = timelineIsNearLatest(lastVisibleIndex, totalItems)
                }
            } else {
                initialScrollObserved = true
            }
            previousTotalItems = totalItems
        }
    }
    LaunchedEffect(items.size, items.lastOrNull(), focusMessageId, followLatest) {
        if (focusMessageId != null && !focusConsumed) {
            val target = items.indexOfServerMessage(focusMessageId)
            if (target >= 0) {
                listState.scrollToItem(target)
                focusConsumed = true
            }
        } else if (followLatest && items.isNotEmpty()) {
            listState.scrollToItem(items.lastIndex)
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val renderContext = TimelineRenderContext(
                speechState = speechState,
                onSpeak = onSpeak,
                onStopSpeaking = onStopSpeaking,
                expandedToolIds = expandedToolIds,
                toolDisclosureKey = toolDisclosureKey,
                onToolExpandedChange = onToolExpandedChange,
            )
            items(items, key = { it.id }) { item ->
                TimelineRendererRegistry.Render(item, renderContext)
            }
        }
        AnimatedVisibility(
            visible = !followLatest && items.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) {
            SmallFloatingActionButton(
                onClick = {
                    focusConsumed = true
                    followLatest = true
                },
            ) {
                Icon(Icons.Outlined.KeyboardArrowDown, "Jump to latest message")
            }
        }
    }
}

internal fun timelineIsNearLatest(lastVisibleIndex: Int?, totalItems: Int): Boolean =
    totalItems == 0 || lastVisibleIndex == null || lastVisibleIndex >= totalItems - 2

@Composable
internal fun MessageBlock(
    message: TimelineItem.Message,
    speechState: SpeechUiState,
    onSpeak: () -> Unit,
    onStopSpeaking: () -> Unit,
) {
    val user = message.role == MessageRole.USER
    val context = LocalContext.current
    var copied by rememberSaveable(message.id) { mutableStateOf(false) }
    var actionError by remember(message.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_500)
            copied = false
        }
    }
    val label = when (message.role) {
        MessageRole.USER -> "YOU"
        MessageRole.ASSISTANT -> "HERMES"
        MessageRole.SYSTEM -> "SYSTEM"
        MessageRole.TOOL -> "TOOL"
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(
            modifier = Modifier.fillMaxWidth(if (user) 0.88f else 1f),
            color = if (user) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            shape = RoundedCornerShape(12.dp),
            border = if (message.failed) BorderStroke(1.dp, MaterialTheme.colorScheme.error) else null,
        ) {
            Column(Modifier.padding(if (user) 14.dp else 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    if (!message.streaming && message.text.isNotBlank()) {
                        IconButton(
                            onClick = {
                                actionError = null
                                runCatching {
                                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                        ClipData.newPlainText("Hermes message", message.text),
                                    )
                                }.onSuccess {
                                    copied = true
                                }.onFailure {
                                    actionError = "Android could not copy this message"
                                }
                            },
                        ) {
                            Icon(
                                if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                if (copied) "Copied message" else "Copy message",
                                modifier = Modifier.size(19.dp),
                            )
                        }
                        IconButton(
                            onClick = {
                                actionError = null
                                runCatching {
                                    context.startActivity(Intent.createChooser(textShareIntent(message.text), "Share message"))
                                }.onFailure {
                                    actionError = "Android could not share this message"
                                }
                            },
                        ) {
                            Icon(Icons.Outlined.Share, "Share message", modifier = Modifier.size(19.dp))
                        }
                    }
                    if (!user && message.role == MessageRole.ASSISTANT && !message.streaming && message.text.isNotBlank()) {
                        val active = speechState.messageId == message.id && speechState.phase != SpeechPhase.IDLE
                        if (active && speechState.phase == SpeechPhase.LOADING) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(4.dp))
                        }
                        IconButton(onClick = if (active) onStopSpeaking else onSpeak) {
                            Icon(
                                if (active) Icons.Outlined.StopCircle else Icons.AutoMirrored.Outlined.VolumeUp,
                                if (active) "Stop reading this reply" else "Read this reply aloud",
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(5.dp))
                SelectionContainer {
                    RichText(
                        text = message.text.ifBlank { if (message.streaming) "▍" else "" },
                        markdown = message.role == MessageRole.ASSISTANT && !message.streaming,
                    )
                }
                actionError?.let { error ->
                    Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
internal fun RichText(text: String, markdown: Boolean) {
    if (markdown) {
        MarkdownReply(text)
        return
    }
    val parts = remember(text) { text.split("```") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        parts.forEachIndexed { index, part ->
            if (index % 2 == 1) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp)) {
                    Text(part.trimStart().substringAfter('\n', part.trimStart()), Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            } else if (part.isNotBlank()) {
                Text(part.trim(), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun MarkdownReply(text: String) {
    val context = LocalContext.current
    val uriHandler = remember(context) {
        object : UriHandler {
            override fun openUri(uri: String) {
                safeExternalUrl(uri)?.let { safe ->
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(safe))) }
                }
            }
        }
    }
    val body = MaterialTheme.typography.bodyLarge
    CompositionLocalProvider(LocalUriHandler provides uriHandler) {
        Markdown(
            content = text,
            modifier = Modifier.fillMaxWidth(),
            colors = markdownColor(
                text = MaterialTheme.colorScheme.onSurface,
                codeBackground = MaterialTheme.colorScheme.surfaceVariant,
                inlineCodeBackground = MaterialTheme.colorScheme.surfaceVariant,
                dividerColor = MaterialTheme.colorScheme.outlineVariant,
                tableBackground = MaterialTheme.colorScheme.surfaceVariant,
            ),
            typography = markdownTypography(
                h1 = MaterialTheme.typography.headlineMedium,
                h2 = MaterialTheme.typography.titleLarge,
                h3 = MaterialTheme.typography.titleMedium,
                h4 = body.copy(fontWeight = FontWeight.Bold),
                h5 = body.copy(fontWeight = FontWeight.Bold),
                h6 = body.copy(fontWeight = FontWeight.Bold),
                text = body,
                paragraph = body,
                ordered = body,
                bullet = body,
                list = body,
                code = MaterialTheme.typography.bodyMedium,
                inlineCode = MaterialTheme.typography.bodyMedium,
                quote = MaterialTheme.typography.bodyMedium,
                link = body.copy(
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    textDecoration = TextDecoration.Underline,
                ),
                table = MaterialTheme.typography.bodyMedium,
            ),
            components = markdownComponents(
                codeBlock = highlightedCodeBlock,
                codeFence = highlightedCodeFence,
            ),
            animations = markdownAnimations(animateTextSize = { this }),
            loading = { Text(text, modifier = it, style = body) },
            error = { Text(text, modifier = it, style = body) },
        )
    }
}

@Composable
internal fun ToolBlock(
    tool: TimelineItem.Tool,
    expanded: Boolean? = null,
    disclosureKey: String = tool.id,
    onExpandedChange: ((TimelineItem.Tool, Boolean) -> Unit)? = null,
) {
    var localExpanded by rememberSaveable(disclosureKey) { mutableStateOf(false) }
    val isExpanded = expanded ?: localExpanded
    val presentation = remember(tool.name, tool.summary, tool.state) { tool.presentation(includeTranscript = false) }
    val accessibilityDescription = remember(presentation) {
        listOfNotNull(
            "Tool usage",
            presentation.title,
            presentation.stateDescription,
            presentation.description.takeUnless { it == presentation.stateDescription },
        ).joinToString(", ")
    }
    val colour = when (tool.state) {
        ToolState.RUNNING -> WarningColor
        ToolState.COMPLETE -> MaterialTheme.colorScheme.tertiary
        ToolState.FAILED -> MaterialTheme.colorScheme.error
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, colour.copy(alpha = 0.32f)),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (isExpanded) "Hide tool transcript" else "Show tool transcript",
                    ) {
                        val nextExpanded = !isExpanded
                        if (expanded == null) localExpanded = nextExpanded
                        onExpandedChange?.invoke(tool, nextExpanded)
                    }
                    .semantics {
                        contentDescription = accessibilityDescription
                        stateDescription = if (isExpanded) "Expanded" else "Collapsed"
                    }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Terminal, null, tint = colour, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("TOOL USAGE · ${presentation.title}", style = MaterialTheme.typography.labelMedium, color = colour)
                    Text(
                        presentation.description,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    if (isExpanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null,
                )
            }
            AnimatedVisibility(isExpanded, enter = fadeIn(), exit = fadeOut()) {
                ToolTranscriptContent(
                    tool = tool,
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                    showDivider = true,
                )
            }
        }
    }
}

@Composable
internal fun ToolSupportingPane(
    tool: TimelineItem.Tool,
    onClose: () -> Unit,
) {
    val presentation = remember(tool.name, tool.summary, tool.state) { tool.presentation(includeTranscript = false) }
    Column(
        Modifier
            .width(340.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("TOOL USAGE · ${presentation.title}", style = MaterialTheme.typography.labelMedium)
                Text(presentation.description, style = MaterialTheme.typography.bodyMedium)
            }
            IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close tool transcript") }
        }
        ToolTranscriptContent(tool = tool, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun ToolTranscriptContent(
    tool: TimelineItem.Tool,
    modifier: Modifier = Modifier,
    showDivider: Boolean = false,
) {
    Column(modifier) {
        if (showDivider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        tool.context?.takeIf(String::isNotBlank)?.let { context ->
            Text(
                context,
                Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val transcript = remember(tool.detail) { tool.presentation().transcript }
        if (transcript.isNotBlank()) {
            Text(
                "TRANSCRIPT",
                Modifier.padding(top = 10.dp, bottom = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(8.dp),
            ) {
                SelectionContainer {
                    Text(
                        transcript,
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 480.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ReasoningBlock(reasoning: TimelineItem.Reasoning) {
    var expanded by rememberSaveable(reasoning.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(vertical = 4.dp)) {
        Text("REASONING ${if (expanded) "−" else "+"}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (expanded) Text(reasoning.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun StatusBlock(status: TimelineItem.Status) {
    val colour = when {
        status.kind == "compacting" || status.kind == "context_pressure" -> WarningColor
        status.kind == "goal" && status.text.startsWith("✓") -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        color = colour.copy(alpha = 0.10f),
        contentColor = colour,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, colour.copy(alpha = 0.35f)),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status.kind == "compacting") {
                Icon(Icons.Outlined.AutoAwesome, null, modifier = Modifier.padding(end = 7.dp).size(16.dp))
            }
            Text(
                "${status.kind.replace('_', ' ').uppercase()} / ${status.text}",
                style = MaterialTheme.typography.labelMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

internal enum class ComposerKeyAction { NONE, ESCAPE, HISTORY_BACK, HISTORY_FORWARD }

internal fun composerKeyAction(type: KeyEventType, key: Key, ctrlPressed: Boolean): ComposerKeyAction = when {
    type != KeyEventType.KeyDown -> ComposerKeyAction.NONE
    key == Key.Escape -> ComposerKeyAction.ESCAPE
    ctrlPressed && key == Key.DirectionUp -> ComposerKeyAction.HISTORY_BACK
    ctrlPressed && key == Key.DirectionDown -> ComposerKeyAction.HISTORY_FORWARD
    else -> ComposerKeyAction.NONE
}

@Composable
internal fun ComposerInputLayout(
    sending: Boolean,
    input: @Composable RowScope.() -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        input()
        if (!sending) actions()
    }
    if (sending) {
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            content = actions,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Composer(
    backend: com.nousresearch.hermes.data.BackendConfig,
    historySessionId: String,
    userHistory: List<String>,
    draft: String,
    slashSuggestions: List<SlashSuggestion>,
    slashLoading: Boolean,
    sending: Boolean,
    connected: Boolean,
    attachmentEnabled: Boolean,
    attachments: List<PendingAttachment>,
    queuedPrompts: List<QueuedPrompt>,
    queueDraining: Boolean,
    queueNotice: String?,
    onSend: (String) -> Unit,
    onSteer: (String) -> Unit,
    onQueue: () -> Unit,
    onUpdateQueued: (String, String) -> Unit,
    onRemoveQueued: (String) -> Unit,
    onSendQueuedNow: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onCompleteSlash: (String) -> Unit,
    onExecuteSlash: (String) -> Unit,
    onAttach: (List<android.net.Uri>) -> Unit,
    onRetryAttachment: (String) -> Unit,
    onCancelAttachment: (String) -> Unit,
    onRemoveAttachment: (String) -> Unit,
    onInterrupt: () -> Unit,
    voiceViewModel: VoiceViewModel,
    compactLayout: Boolean,
    adaptiveFocusState: AdaptiveFocusState,
) {
    var pendingDestructiveSlash by remember { mutableStateOf<String?>(null) }
    var microphoneDenied by rememberSaveable { mutableStateOf(false) }
    var capturedCameraUri by remember { mutableStateOf<String?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var historyMenuOpen by rememberSaveable(historySessionId) { mutableStateOf(false) }
    var historyCursor by rememberSaveable(historySessionId) { mutableIntStateOf(-1) }
    var historyDraftSnapshot by remember(historySessionId) { mutableStateOf("") }
    var queuedEditId by remember(historySessionId) { mutableStateOf<String?>(null) }
    var queuedEditText by remember(historySessionId) { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val softwareKeyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val voiceState by voiceViewModel.state.collectAsStateWithLifecycle()
    val speechState by voiceViewModel.speechState.collectAsStateWithLifecycle()
    val latestDraft by rememberUpdatedState(draft)
    val latestDraftChange by rememberUpdatedState(onDraftChange)
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        microphoneDenied = !granted
        if (granted) voiceViewModel.startRecording(VoiceRecordingMode.LOCKED) else voiceViewModel.permissionDenied()
    }
    LaunchedEffect(voiceState.transcript?.id) {
        voiceState.transcript?.let { transcript ->
            val combined = listOf(latestDraft.trimEnd(), transcript.text).filter(String::isNotBlank).joinToString(" ")
            latestDraftChange(combined)
            voiceViewModel.consumeTranscript(transcript.id)
        }
    }
    LaunchedEffect(voiceState.phase) {
        if (voiceState.phase != VoicePhase.IDLE) {
            focus.clearFocus(force = true)
            softwareKeyboard?.hide()
        }
    }
    fun toggleVoice() {
        when (voiceState.phase) {
            VoicePhase.IDLE -> {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    microphoneDenied = false
                    voiceViewModel.startRecording(VoiceRecordingMode.LOCKED)
                } else {
                    microphoneDenied = false
                    microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
            VoicePhase.RECORDING -> voiceViewModel.stopAndTranscribe()
            VoicePhase.TRANSCRIBING -> voiceViewModel.cancelRecording()
        }
    }
    fun resetHistoryBrowse() {
        historyCursor = -1
        historyDraftSnapshot = ""
    }
    fun browseHistory(backward: Boolean): Boolean {
        val current = ComposerBrowseState(historyCursor, historyDraftSnapshot)
        val result = if (backward) {
            ComposerHistory.backward(current, draft, userHistory)
        } else {
            ComposerHistory.forward(current, userHistory)
        } ?: return false
        historyCursor = result.state.cursor
        historyDraftSnapshot = result.state.draftSnapshot
        onDraftChange(result.text)
        return true
    }
    fun submit() {
        if (draft.isBlank() || attachments.any { !it.ready }) return
        if (draft.trimStart().startsWith('/')) {
            val slash = draft.trim()
            val normalized = slash.lowercase()
            if (
                normalized.substringBefore(' ') in setOf("/new", "/reset") ||
                normalized.startsWith("/rollback restore")
            ) {
                pendingDestructiveSlash = slash
            } else {
                onExecuteSlash(slash)
            }
        } else if (sending) onSteer(draft) else onSend(draft)
        resetHistoryBrowse()
        focus.clearFocus()
    }
    LaunchedEffect(draft) { onCompleteSlash(draft) }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        onAttach(uris.take((MAX_PENDING_ATTACHMENTS - attachments.size).coerceAtLeast(0)))
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { captured ->
        val uri = capturedCameraUri?.let(Uri::parse)
        capturedCameraUri = null
        if (captured && uri != null) {
            onAttach(listOf(uri))
        } else if (uri != null) {
            context.contentResolver.delete(uri, null, null)
        }
    }
    Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(12.dp)) {
        if (queuedPrompts.isNotEmpty() || queueNotice != null) {
            PendingMessageQueue(
                entries = queuedPrompts,
                busy = sending,
                draining = queueDraining,
                notice = queueNotice,
                onEdit = { entry ->
                    queuedEditId = entry.id
                    queuedEditText = entry.text
                },
                onRemove = onRemoveQueued,
                onSendNow = onSendQueuedNow,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (slashSuggestions.isNotEmpty() || slashLoading) {
            SlashSuggestions(
                suggestions = slashSuggestions,
                loading = slashLoading,
                onSuggestion = onDraftChange,
            )
            Spacer(Modifier.height(8.dp))
        }
        if (attachments.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                attachments.forEach { attachment ->
                    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp)) {
                        Column(Modifier.widthIn(max = 260.dp).padding(start = 10.dp, top = 6.dp, bottom = 6.dp)) {
                            Text(attachment.label, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                            Text(
                                attachmentStatusText(attachment),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (attachment.phase == AttachmentPhase.ERROR) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (attachment.phase == AttachmentPhase.READING) {
                                val total = attachment.declaredSize
                                if (total != null && total > 0) {
                                    LinearProgressIndicator(
                                        progress = { (attachment.bytesRead.toFloat() / total).coerceIn(0f, 1f) },
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                    )
                                } else {
                                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                                }
                            } else if (attachment.phase == AttachmentPhase.UPLOADING) {
                                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
                            }
                            Row {
                                when {
                                    attachment.phase == AttachmentPhase.ERROR -> {
                                        TextButton(onClick = { onRetryAttachment(attachment.id) }) { Text("Retry") }
                                        TextButton(onClick = { onRemoveAttachment(attachment.id) }) { Text("Remove") }
                                    }
                                    attachment.active -> TextButton(onClick = { onCancelAttachment(attachment.id) }) { Text("Cancel") }
                                    else -> TextButton(onClick = { onRemoveAttachment(attachment.id) }) { Text("Remove") }
                                }
                            }
                        }
                    }
                }
            }
        }
        VoiceStatus(
            state = voiceState,
            onStop = voiceViewModel::stopAndTranscribe,
            onCancel = { voiceViewModel.cancelRecording() },
            onDismissError = {
                microphoneDenied = false
                voiceViewModel.clearError()
            },
            showPermissionSettings = microphoneDenied,
            onOpenPermissionSettings = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            },
        )
        SpeechStatus(
            state = speechState,
            onPause = voiceViewModel::pauseSpeaking,
            onResume = voiceViewModel::resumeSpeaking,
            onStop = voiceViewModel::stopSpeaking,
            onOutput = voiceViewModel::showOutputSwitcher,
            onDismissError = voiceViewModel::clearSpeechError,
        )
        ComposerInputLayout(
            sending = sending,
            input = {
                if (attachmentEnabled) {
                    IconButton(
                        onClick = { documentPicker.launch(arrayOf("*/*")) },
                        enabled = connected && attachments.size < MAX_PENDING_ATTACHMENTS,
                    ) {
                        Icon(Icons.Outlined.AttachFile, "Attach files")
                    }
                    IconButton(
                        onClick = {
                            cameraError = null
                            runCatching {
                                newCameraCaptureUri(context).also { uri ->
                                    capturedCameraUri = uri.toString()
                                    camera.launch(uri)
                                }
                            }.onFailure { error ->
                                capturedCameraUri?.let(Uri::parse)?.let { context.contentResolver.delete(it, null, null) }
                                capturedCameraUri = null
                                cameraError = error.message ?: "Android could not open the camera"
                            }
                        },
                        enabled = connected && attachments.size < MAX_PENDING_ATTACHMENTS,
                    ) {
                        Icon(Icons.Outlined.PhotoCamera, "Take a photo")
                    }
                }
                Box {
                    IconButton(
                        onClick = { historyMenuOpen = true },
                        enabled = connected && userHistory.isNotEmpty(),
                        modifier = Modifier.semantics { contentDescription = "Open message history" },
                    ) { Icon(Icons.Outlined.History, null) }
                    DropdownMenu(
                        expanded = historyMenuOpen,
                        onDismissRequest = { historyMenuOpen = false },
                    ) {
                        userHistory.take(MAX_VISIBLE_COMPOSER_HISTORY).forEach { message ->
                            DropdownMenuItem(
                                text = { Text(message, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                onClick = {
                                    resetHistoryBrowse()
                                    onDraftChange(message)
                                    historyMenuOpen = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        resetHistoryBrowse()
                        onDraftChange(it)
                    },
                    placeholder = {
                        Text(
                            when {
                                !connected -> "Reconnect to send"
                                sending -> "Steer the current run"
                                else -> "Message Hermes"
                            },
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .preserveFocusAcrossAdaptiveMove(compactLayout, adaptiveFocusState)
                        .onPreviewKeyEvent { event ->
                            when (composerKeyAction(event.type, event.key, event.isCtrlPressed)) {
                                ComposerKeyAction.ESCAPE -> {
                                    historyMenuOpen = false
                                    resetHistoryBrowse()
                                    focus.clearFocus(force = true)
                                    softwareKeyboard?.hide()
                                    true
                                }
                                ComposerKeyAction.HISTORY_BACK -> browseHistory(backward = true)
                                ComposerKeyAction.HISTORY_FORWARD -> browseHistory(backward = false)
                                ComposerKeyAction.NONE -> false
                            }
                        },
                    enabled = connected,
                    maxLines = 6,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    trailingIcon = {
                        VoiceRecordButton(
                            state = voiceState,
                            connected = connected,
                            hasPermission = {
                                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                            },
                            onRequestPermission = { microphonePermission.launch(Manifest.permission.RECORD_AUDIO) },
                            onTap = ::toggleVoice,
                            onPressStart = { voiceViewModel.startRecording(VoiceRecordingMode.PRESS_TO_TALK) },
                            onLock = voiceViewModel::lockRecording,
                            onRelease = voiceViewModel::stopAndTranscribe,
                            onCancel = { voiceViewModel.cancelRecording() },
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                )
            },
            actions = {
                if (sending) {
                    IconButton(
                        onClick = onInterrupt,
                        modifier = Modifier.semantics { contentDescription = "Stop the current Hermes run" },
                    ) {
                        Icon(Icons.Outlined.StopCircle, null, tint = MaterialTheme.colorScheme.error)
                    }
                    IconButton(
                        onClick = {
                            onQueue()
                            resetHistoryBrowse()
                            focus.clearFocus()
                        },
                        enabled = connected && draft.isNotBlank() && attachments.isEmpty() &&
                            queuedPrompts.size < ComposerQueue.MAX_ENTRIES && !queueDraining,
                    ) {
                        Icon(Icons.Outlined.Schedule, "Queue for the next turn")
                    }
                    IconButton(
                        onClick = ::submit,
                        enabled = connected && draft.isNotBlank() && attachments.all { it.ready },
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, "Steer the current run")
                    }
                } else {
                    IconButton(
                        onClick = ::submit,
                        enabled = connected && draft.isNotBlank() && attachments.all { it.ready },
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.Send, "Send message")
                    }
                }
            },
        )
        if (sending && attachments.isNotEmpty()) {
            Text(
                "Pending-message queue is text-only; send or remove attachments first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        cameraError?.let { error ->
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
    queuedEditId?.let { entryId ->
        AlertDialog(
            onDismissRequest = { queuedEditId = null },
            title = { Text("EDIT PENDING MESSAGE") },
            text = {
                OutlinedTextField(
                    value = queuedEditText,
                    onValueChange = { queuedEditText = it.take(ComposerQueue.MAX_TEXT_CHARACTERS) },
                    label = { Text("Message") },
                    minLines = 3,
                    maxLines = 8,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onUpdateQueued(entryId, queuedEditText)
                        queuedEditId = null
                    },
                    enabled = queuedEditText.isNotBlank() && !queueDraining,
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { queuedEditId = null }) { Text("Cancel") } },
        )
    }
    pendingDestructiveSlash?.let { command ->
        val restoresSnapshot = command.lowercase().startsWith("/rollback restore")
        AlertDialog(
            onDismissRequest = { pendingDestructiveSlash = null },
            title = { Text(if (restoresSnapshot) "RESTORE SNAPSHOT?" else "START FRESH?") },
            text = {
                Text(
                    if (restoresSnapshot) {
                        "Hermes will replace the current workspace with the selected snapshot. Unsaved workspace changes may be lost."
                    } else {
                        "Hermes will end the current live conversation and open a clean session. Its stored transcript remains available in the session list."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDestructiveSlash = null
                        onExecuteSlash(command)
                    },
                ) { Text(if (restoresSnapshot) "Restore snapshot" else "Start new session") }
            },
            dismissButton = { TextButton(onClick = { pendingDestructiveSlash = null }) { Text("Cancel") } },
        )
    }
}

private fun attachmentStatusText(attachment: PendingAttachment): String = when (attachment.phase) {
    AttachmentPhase.VALIDATING -> "Validating"
    AttachmentPhase.READING -> buildString {
        append("Reading ${attachment.bytesRead} bytes")
        attachment.declaredSize?.let { append(" / $it") }
    }
    AttachmentPhase.UPLOADING -> "Uploading"
    AttachmentPhase.READY -> "Ready / foreground only"
    AttachmentPhase.ERROR -> attachment.error ?: "Attachment failed"
}

@Composable
private fun PendingMessageQueue(
    entries: List<QueuedPrompt>,
    busy: Boolean,
    draining: Boolean,
    notice: String?,
    onEdit: (QueuedPrompt) -> Unit,
    onRemove: (String) -> Unit,
    onSendNow: (String) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.fillMaxWidth().padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PENDING MESSAGES", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                if (draining) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(entries.size.toString(), style = MaterialTheme.typography.labelMedium)
            }
            notice?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (entries.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 180.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        ) {
                            Text(
                                entry.text,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (entry.autoDrainFailures >= ComposerQueue.MAX_AUTO_DRAIN_ATTEMPTS && !busy) {
                                IconButton(
                                    onClick = { onSendNow(entry.id) },
                                    enabled = !draining,
                                    modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                                ) { Icon(Icons.Outlined.Refresh, "Retry pending message", modifier = Modifier.size(17.dp)) }
                            }
                            IconButton(
                                onClick = { onEdit(entry) },
                                enabled = !draining,
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                            ) { Icon(Icons.Outlined.Edit, "Edit pending message", modifier = Modifier.size(17.dp)) }
                            IconButton(
                                onClick = { onRemove(entry.id) },
                                enabled = !draining,
                                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp),
                            ) { Icon(Icons.Outlined.Delete, "Remove pending message", modifier = Modifier.size(17.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VoiceRecordButton(
    state: VoiceUiState,
    connected: Boolean,
    hasPermission: () -> Boolean,
    onRequestPermission: () -> Unit,
    onTap: () -> Unit,
    onPressStart: () -> Unit,
    onLock: () -> Unit,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
) {
    val gestureThreshold = with(LocalDensity.current) { 72.dp.toPx() }
    val latestState by rememberUpdatedState(state)
    val latestTap by rememberUpdatedState(onTap)
    val latestPermission by rememberUpdatedState(hasPermission)
    val latestRequestPermission by rememberUpdatedState(onRequestPermission)
    val latestPressStart by rememberUpdatedState(onPressStart)
    val latestLock by rememberUpdatedState(onLock)
    val latestRelease by rememberUpdatedState(onRelease)
    val latestCancel by rememberUpdatedState(onCancel)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(50))
            .semantics {
                role = Role.Button
                contentDescription = when (state.phase) {
                    VoicePhase.IDLE -> "Hold to talk or tap for locked recording"
                    VoicePhase.RECORDING -> "Stop and transcribe voice recording"
                    VoicePhase.TRANSCRIBING -> "Cancel voice transcription"
                }
                onClick {
                    if (connected) latestTap()
                    connected
                }
            }
            .focusable(enabled = connected)
            .pointerInput(connected, gestureThreshold) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                down.consume()
                if (!connected) return@awaitEachGesture
                if (latestState.phase != VoicePhase.IDLE) {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            change.consume()
                            latestTap()
                            break
                        }
                        change.consume()
                    }
                    return@awaitEachGesture
                }
                if (!latestPermission()) {
                    latestRequestPermission()
                    return@awaitEachGesture
                }

                latestPressStart()
                var locked = false
                var cancelled = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val horizontal = change.position.x - down.position.x
                    val vertical = change.position.y - down.position.y
                    if (!locked && !cancelled && horizontal <= -gestureThreshold) {
                        cancelled = true
                        latestCancel()
                    } else if (!locked && !cancelled && vertical <= -gestureThreshold) {
                        locked = true
                        latestLock()
                    }
                    if (!change.pressed) {
                        if (!locked && !cancelled) {
                            if (change.uptimeMillis - down.uptimeMillis < PRESS_TO_TALK_THRESHOLD_MILLIS) {
                                latestLock()
                            } else {
                                latestRelease()
                            }
                        }
                        change.consume()
                        break
                    }
                    change.consume()
                }
            }
        },
    ) {
        Icon(
            when {
                state.phase == VoicePhase.IDLE -> Icons.Outlined.Mic
                state.phase == VoicePhase.RECORDING && state.recordingMode == VoiceRecordingMode.LOCKED -> Icons.Outlined.Lock
                else -> Icons.Outlined.StopCircle
            },
            contentDescription = null,
            tint = if (state.phase == VoicePhase.RECORDING) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun VoiceStatus(
    state: VoiceUiState,
    onStop: () -> Unit,
    onCancel: () -> Unit,
    onDismissError: () -> Unit,
    showPermissionSettings: Boolean,
    onOpenPermissionSettings: () -> Unit,
) {
    when (state.phase) {
        VoicePhase.RECORDING -> Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${if (state.recordingMode == VoiceRecordingMode.PRESS_TO_TALK) "HOLDING" else "RECORDING"} / ${state.elapsedMillis.formatVoiceTime()}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    Button(onClick = onStop) { Text("Transcribe") }
                }
                LinearProgressIndicator(progress = { state.level }, modifier = Modifier.fillMaxWidth())
                Text(
                    if (state.recordingMode == VoiceRecordingMode.PRESS_TO_TALK) {
                        "Release to transcribe, slide up to lock, or slide left to cancel."
                    } else {
                        "Locked recording. Tap Transcribe when finished; it stops automatically after two minutes."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        VoicePhase.TRANSCRIBING -> Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("HERMES IS TRANSCRIBING", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
        VoicePhase.IDLE -> state.error?.let { message ->
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(message, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    if (showPermissionSettings) TextButton(onClick = onOpenPermissionSettings) { Text("Settings") }
                    IconButton(onClick = onDismissError) { Icon(Icons.Outlined.Close, "Dismiss voice error") }
                }
            }
        }
    }
}

@Composable
private fun SpeechStatus(
    state: SpeechUiState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onOutput: () -> Unit,
    onDismissError: () -> Unit,
) {
    if (state.phase != SpeechPhase.IDLE) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Row(
                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (state.phase == SpeechPhase.LOADING) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.AutoMirrored.Outlined.VolumeUp, null, modifier = Modifier.size(20.dp))
                Text(
                    if (state.phase == SpeechPhase.LOADING) "HERMES IS PREPARING AUDIO" else "SPEAKING / ${state.outputName}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (state.phase == SpeechPhase.PLAYING) {
                    IconButton(onClick = onPause) { Icon(Icons.Outlined.Pause, "Pause spoken reply") }
                } else if (state.phase == SpeechPhase.PAUSED) {
                    IconButton(onClick = onResume) { Icon(Icons.Outlined.PlayArrow, "Resume spoken reply") }
                }
                if (state.phase != SpeechPhase.LOADING) {
                    TextButton(onClick = onOutput) { Text("Output") }
                }
                IconButton(onClick = onStop) { Icon(Icons.Outlined.StopCircle, "Stop spoken reply") }
            }
        }
    } else if (state.error != null) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        ) {
            Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(state.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismissError) { Icon(Icons.Outlined.Close, "Dismiss spoken reply error") }
            }
        }
    }
}

private fun Long.formatVoiceTime(): String {
    val totalSeconds = this / 1_000L
    return "%d:%02d".format(totalSeconds / 60L, totalSeconds % 60L)
}

private const val PRESS_TO_TALK_THRESHOLD_MILLIS = 300L

@Composable
private fun SlashSuggestions(
    suggestions: List<SlashSuggestion>,
    loading: Boolean,
    onSuggestion: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth().heightIn(max = 168.dp),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
        ) {
            if (loading && suggestions.isEmpty()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("Loading Hermes commands", style = MaterialTheme.typography.bodySmall)
                }
            }
            suggestions.forEachIndexed { index, suggestion ->
                if (index == 0 || suggestions[index - 1].group != suggestion.group) {
                    Text(
                        suggestion.group.uppercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                    )
                }
                Surface(
                    onClick = { onSuggestion(suggestion.text) },
                    shape = RoundedCornerShape(10.dp),
                    color = Color.Transparent,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                        Text(suggestion.display, style = MaterialTheme.typography.titleMedium)
                        if (suggestion.meta.isNotBlank()) {
                            Text(
                                suggestion.meta,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ApprovalDialog(command: String, description: String?, choices: List<String>, onChoice: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = { },
        icon = { Icon(Icons.Outlined.Terminal, null, tint = MaterialTheme.colorScheme.error) },
        title = { Text("COMMAND APPROVAL") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(description ?: "Hermes is waiting for permission to execute a potentially dangerous command.")
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(6.dp)) {
                    Text(command.ifBlank { "Command details were redacted by Hermes." }, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                choices.filterNot { it == "deny" }.forEach { choice ->
                    Button(onClick = { onChoice(choice) }) { Text(choice.uppercase()) }
                }
            }
        },
        dismissButton = { OutlinedButton(onClick = { onChoice("deny") }) { Text("DENY") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClarificationDialog(question: String, choices: List<String>, onAnswer: (String) -> Unit) {
    var answer by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { },
        title = { Text("HERMES NEEDS INPUT") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(question)
                if (choices.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        choices.forEach { choice -> OutlinedButton(onClick = { answer = choice }) { Text(choice) } }
                    }
                }
                OutlinedTextField(answer, { answer = it }, Modifier.fillMaxWidth(), label = { Text("Answer") })
            }
        },
        confirmButton = { Button(enabled = answer.isNotBlank(), onClick = { onAnswer(answer.trim()) }) { Text("CONTINUE") } },
    )
}

@Composable
internal fun SensitiveInputDialog(
    requestId: String,
    kind: SensitiveInputKind,
    prompt: String,
    environmentVariable: String?,
    onSubmit: (String) -> Unit,
) {
    var value by remember(requestId) { mutableStateOf("") }
    val submit: (String) -> Unit = { response ->
        value = ""
        onSubmit(response)
    }
    val sudo = kind == SensitiveInputKind.SUDO_PASSWORD
    AlertDialog(
        onDismissRequest = { },
        icon = { Icon(Icons.Outlined.Key, null, tint = WarningColor) },
        title = { Text(if (sudo) "SUDO PASSWORD REQUIRED" else "SECRET REQUIRED") },
        text = {
            Column(
                Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(prompt)
                environmentVariable?.let {
                    Text("ENVIRONMENT VARIABLE / $it", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(8_192) },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (sudo) "Password" else "Secret value") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (value.isNotEmpty()) submit(value) }),
                )
                Text(
                    "This value is sent once to the active Hermes request. Android does not save it to drafts, restored state, logs, or local preferences.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(enabled = value.isNotEmpty(), onClick = { submit(value) }) { Text("CONTINUE") }
        },
        dismissButton = {
            OutlinedButton(onClick = { submit("") }) { Text("CANCEL REQUEST") }
        },
    )
}

@Composable
private fun ErrorBanner(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(6.dp)).padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(8.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
    }
}

@Composable
private fun CompatibilityBanner(message: String, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp)).padding(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Outlined.Warning, null, tint = WarningColor)
        Spacer(Modifier.width(8.dp))
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
