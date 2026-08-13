package com.nousresearch.hermes.data

import android.net.Uri
import android.util.Log
import com.nousresearch.hermes.domain.SensitiveInputKind
import com.nousresearch.hermes.domain.ComposerQueue
import com.nousresearch.hermes.domain.QueuedPrompt
import com.nousresearch.hermes.domain.SubagentProgress
import com.nousresearch.hermes.domain.SubagentReducer
import com.nousresearch.hermes.domain.TimelineReducer
import com.nousresearch.hermes.domain.TimelineState
import com.nousresearch.hermes.domain.lastUserPrompt
import com.nousresearch.hermes.network.DashboardAuthProvider
import com.nousresearch.hermes.network.HermesRestClient
import com.nousresearch.hermes.protocol.ConfigSetResult
import com.nousresearch.hermes.protocol.AnalyticsResponse
import com.nousresearch.hermes.protocol.BackgroundProcess
import com.nousresearch.hermes.protocol.BackgroundProcessKillResponse
import com.nousresearch.hermes.protocol.BackgroundProcessListResponse
import com.nousresearch.hermes.protocol.BackendUpdateCheck
import com.nousresearch.hermes.protocol.BillingChargeResponse
import com.nousresearch.hermes.protocol.BillingChargeStatusResponse
import com.nousresearch.hermes.protocol.BillingMutationResponse
import com.nousresearch.hermes.protocol.BillingStateResponse
import com.nousresearch.hermes.protocol.BillingStepUpVerification
import com.nousresearch.hermes.protocol.CronJob
import com.nousresearch.hermes.protocol.CronJobCreatePayload
import com.nousresearch.hermes.protocol.CronJobUpdates
import com.nousresearch.hermes.protocol.ContextBreakdown
import com.nousresearch.hermes.protocol.FileAttachResult
import com.nousresearch.hermes.protocol.DelegationPauseResponse
import com.nousresearch.hermes.protocol.DelegationStatusResponse
import com.nousresearch.hermes.protocol.EnvVarInfo
import com.nousresearch.hermes.protocol.GatewayConnectionState
import com.nousresearch.hermes.protocol.HermesGatewayClient
import com.nousresearch.hermes.protocol.HermesRpcException
import com.nousresearch.hermes.protocol.ImageAttachResult
import com.nousresearch.hermes.protocol.ModelOptionsResult
import com.nousresearch.hermes.protocol.OAuthProvider
import com.nousresearch.hermes.protocol.McpCatalogEntry
import com.nousresearch.hermes.protocol.McpReloadResponse
import com.nousresearch.hermes.protocol.McpServerSummary
import com.nousresearch.hermes.protocol.McpServerTestResponse
import com.nousresearch.hermes.protocol.MessagingPlatformInfo
import com.nousresearch.hermes.protocol.MessagingPlatformTestResponse
import com.nousresearch.hermes.protocol.PdfAttachResult
import com.nousresearch.hermes.protocol.ProfileCreatePayload
import com.nousresearch.hermes.protocol.ProfileInfo
import com.nousresearch.hermes.protocol.ProtocolMessage
import com.nousresearch.hermes.protocol.PromptSubmitResult
import com.nousresearch.hermes.protocol.RollbackCheckpoint
import com.nousresearch.hermes.protocol.RollbackDiffResult
import com.nousresearch.hermes.protocol.RollbackListResult
import com.nousresearch.hermes.protocol.RollbackRestoreResult
import com.nousresearch.hermes.protocol.SessionCreateResult
import com.nousresearch.hermes.protocol.SessionBranchResult
import com.nousresearch.hermes.protocol.SessionCompressResult
import com.nousresearch.hermes.protocol.SessionDeleteResult
import com.nousresearch.hermes.protocol.SessionHistoryResult
import com.nousresearch.hermes.protocol.SessionMessagePage
import com.nousresearch.hermes.protocol.SessionResumeResult
import com.nousresearch.hermes.protocol.SessionRuntimeInfo
import com.nousresearch.hermes.protocol.SessionSearchHit
import com.nousresearch.hermes.protocol.SessionSteerResult
import com.nousresearch.hermes.protocol.SessionTitleResult
import com.nousresearch.hermes.protocol.SessionUndoResult
import com.nousresearch.hermes.protocol.SkillInfo
import com.nousresearch.hermes.protocol.SkillHubPreview
import com.nousresearch.hermes.protocol.SkillHubResult
import com.nousresearch.hermes.protocol.SkillHubScanResult
import com.nousresearch.hermes.protocol.SlashCommandCatalog
import com.nousresearch.hermes.protocol.SlashCommandResult
import com.nousresearch.hermes.protocol.SlashCompletionResult
import com.nousresearch.hermes.protocol.StatusResponse
import com.nousresearch.hermes.protocol.StarmapGraph
import com.nousresearch.hermes.protocol.LearningNodeDetail
import com.nousresearch.hermes.protocol.StoredSession
import com.nousresearch.hermes.protocol.SubscriptionStateResponse
import com.nousresearch.hermes.protocol.SubagentInterruptResponse
import com.nousresearch.hermes.protocol.SpawnTreeListEntry
import com.nousresearch.hermes.protocol.SpawnTreeListResponse
import com.nousresearch.hermes.protocol.SpawnTreeSnapshot
import com.nousresearch.hermes.protocol.ToolsetInfo
import com.nousresearch.hermes.platform.mergeSharedText
import com.nousresearch.hermes.security.DiagnosticRedactor
import java.util.UUID
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class HermesState(
    val backend: BackendConfig? = null,
    val savedBackends: List<BackendConfig> = emptyList(),
    val status: StatusResponse? = null,
    val sessions: List<StoredSession> = emptyList(),
    val sessionSearchResults: List<SessionSearchHit> = emptyList(),
    val sessionSearchLoading: Boolean = false,
    val sessionListLoading: Boolean = false,
    val sessionListError: String? = null,
    val sessionSearchQuery: String = "",
    val activeStoredSession: StoredSession? = null,
    val runtimeSessionId: String? = null,
    val restoration: SessionRestorationState = SessionRestorationState(),
    val timeline: TimelineState = TimelineState(),
    val loading: Boolean = false,
    val sending: Boolean = false,
    val pendingAttachments: List<PendingAttachment> = emptyList(),
    val queuedPrompts: List<QueuedPrompt> = emptyList(),
    val queueDraining: Boolean = false,
    val queueNotice: String? = null,
    val queueStorageHealthy: Boolean = true,
    val draft: String = "",
    val slashSuggestions: List<SlashSuggestion> = emptyList(),
    val slashLoading: Boolean = false,
    val slashQuery: String = "",
    val runtimeInfo: SessionRuntimeInfo = SessionRuntimeInfo(),
    val modelOptions: ModelOptionsResult? = null,
    val modelsLoading: Boolean = false,
    val pendingModelConfirmation: PendingModelConfirmation? = null,
    val skills: List<SkillInfo> = emptyList(),
    val skillHubResults: List<SkillHubResult> = emptyList(),
    val skillHubReview: SkillHubReview? = null,
    val skillHubLoading: Boolean = false,
    val skillAction: DiagnosticRunState? = null,
    val toolsets: List<ToolsetInfo> = emptyList(),
    val toolsetsLoading: Boolean = false,
    val toolsetNotice: String? = null,
    val toolsetError: String? = null,
    val serverConfigProfile: String? = null,
    val serverConfig: ServerConfigSnapshot = ServerConfigSnapshot(),
    val serverConfigLoading: Boolean = false,
    val serverConfigNotice: String? = null,
    val serverConfigError: String? = null,
    val cronJobs: List<CronJob> = emptyList(),
    val cronRuns: Map<String, List<StoredSession>> = emptyMap(),
    val profiles: List<ProfileInfo> = emptyList(),
    val activeProfile: String = "default",
    val currentProfile: String = "default",
    val managementLoading: Boolean = false,
    val starmapProfile: String? = null,
    val starmap: StarmapGraph? = null,
    val starmapLoading: Boolean = false,
    val starmapNodeId: String? = null,
    val starmapNode: LearningNodeDetail? = null,
    val starmapNotice: String? = null,
    val starmapError: String? = null,
    val diagnostics: Map<DiagnosticAction, DiagnosticRunState> = emptyMap(),
    val hostUpdate: BackendUpdateCheck? = null,
    val hostLogs: List<String> = emptyList(),
    val hostMaintenanceLoading: Boolean = false,
    val hostMaintenanceError: String? = null,
    val providerOptions: ModelOptionsResult? = null,
    val providerEnv: Map<String, EnvVarInfo> = emptyMap(),
    val oauthProviders: List<OAuthProvider> = emptyList(),
    val providerAccountsSupported: Boolean = oauthProviders.isNotEmpty(),
    val providerOAuthSession: ProviderOAuthSession? = null,
    val providersLoading: Boolean = false,
    val providerNotice: String? = null,
    val messagingPlatforms: List<MessagingPlatformInfo> = emptyList(),
    val messagingLoading: Boolean = false,
    val messagingNotice: String? = null,
    val messagingTests: Map<String, MessagingPlatformTestResponse> = emptyMap(),
    val gatewayRestarting: Boolean = false,
    val mcpServers: List<McpServerSummary> = emptyList(),
    val mcpCatalog: List<McpCatalogEntry> = emptyList(),
    val mcpTests: Map<String, McpServerTestResponse> = emptyMap(),
    val mcpLoading: Boolean = false,
    val mcpNotice: String? = null,
    val mcpError: String? = null,
    val usageAnalytics: AnalyticsResponse? = null,
    val contextBreakdown: ContextBreakdown? = null,
    val usageDays: Int = 30,
    val usageLoading: Boolean = false,
    val usageError: String? = null,
    val billingState: BillingStateResponse? = null,
    val subscriptionState: SubscriptionStateResponse? = null,
    val billingSupported: Boolean = true,
    val billingLoading: Boolean = false,
    val billingBusy: Boolean = false,
    val billingNotice: String? = null,
    val billingError: String? = null,
    val billingRecovery: BillingRecovery = BillingRecovery.NONE,
    val billingPortalUrl: String? = null,
    val billingRetryIntent: BillingRetryIntent? = null,
    val billingChargeUnconfirmed: Boolean = false,
    val billingStepUpVerification: BillingStepUpVerification? = null,
    val backendTransitionInProgress: Boolean = false,
    val checkpointsEnabled: Boolean? = null,
    val checkpoints: List<RollbackCheckpoint> = emptyList(),
    val checkpointPreview: CheckpointPreview? = null,
    val checkpointsLoading: Boolean = false,
    val checkpointNotice: String? = null,
    val checkpointError: String? = null,
    val delegationStatus: DelegationStatusResponse? = null,
    val activeSubagents: List<SubagentProgress> = emptyList(),
    val subagentsBySession: Map<String, List<SubagentProgress>> = emptyMap(),
    val backgroundProcesses: List<BackgroundProcess> = emptyList(),
    val agentsLoading: Boolean = false,
    val agentsNotice: String? = null,
    val agentsError: String? = null,
    val spawnTreeArchives: List<SpawnTreeListEntry> = emptyList(),
    val spawnTreeReplay: SpawnTreeReplay? = null,
    val spawnTreesLoading: Boolean = false,
    val spawnTreesError: String? = null,
    val reconnectRequiredBackendId: String? = null,
    val error: String? = null,
) {
    val compatibilityWarning: String?
        get() = when {
            runtimeSessionId == null -> null
            runtimeInfo.desktopContract == null ->
                "This Hermes server does not report a desktop contract version. Version-gated controls are hidden."
            runtimeInfo.desktopContract < MINIMUM_DESKTOP_CONTRACT ->
                "This session reports desktop contract v${runtimeInfo.desktopContract}; Android expects v$MINIMUM_DESKTOP_CONTRACT. Update Hermes for full controls."
            else -> null
        }

    val supportsRemoteAttachments: Boolean
        get() = runtimeSessionId != null && (runtimeInfo.desktopContract ?: 0) >= ATTACHMENT_DESKTOP_CONTRACT

    val supportsSessionYolo: Boolean
        get() = runtimeSessionId != null && (runtimeInfo.desktopContract ?: 0) >= MINIMUM_DESKTOP_CONTRACT

    private companion object {
        const val ATTACHMENT_DESKTOP_CONTRACT = 2
        const val MINIMUM_DESKTOP_CONTRACT = 3
    }
}

data class SpawnTreeReplay(
    val archive: SpawnTreeListEntry,
    val subagents: List<SubagentProgress>,
)

data class ProfileIdentityDraft(
    val soul: String,
    val setupCommand: String,
    val provider: String,
    val model: String,
)

data class EntryAuthoritySnapshot(
    val profileIds: Set<String>,
    val cronJobIds: Set<String>,
)

data class ProviderOAuthSession(
    val providerId: String,
    val providerName: String,
    val flow: String,
    val sessionId: String,
    val profile: String = "default",
    val browserUrl: String,
    val userCode: String? = null,
    val expiresAtEpochMillis: Long,
    val pollIntervalSeconds: Long,
)

private data class ProviderRefreshSnapshot(
    val activeProfile: String,
    val currentProfile: String,
    val options: ModelOptionsResult,
    val env: Map<String, EnvVarInfo>,
    val oauthProviders: List<OAuthProvider>,
    val providerAccountsSupported: Boolean,
)

enum class DiagnosticAction(val wireName: String) {
    DOCTOR("doctor"),
    SECURITY_AUDIT("security-audit"),
}

data class DiagnosticRunState(
    val running: Boolean = false,
    val pid: Long? = null,
    val exitCode: Int? = null,
    val lines: List<String> = emptyList(),
    val error: String? = null,
    val timedOut: Boolean = false,
)

data class SkillHubReview(
    val preview: SkillHubPreview,
    val scan: SkillHubScanResult,
)

data class ModelSelection(val provider: String, val model: String) {
    fun rpcValue(): String {
        require(provider.isSafeModelToken() && model.isSafeModelToken()) {
            "Hermes returned a model identifier that cannot be switched safely"
        }
        return "$model --provider $provider --session"
    }
}

data class PendingModelConfirmation(
    val selection: ModelSelection,
    val message: String,
)

data class CheckpointPreview(
    val hash: String,
    val stat: String,
    val diff: String,
    val fingerprint: String,
)

sealed interface BillingRetryIntent {
    data object Refresh : BillingRetryIntent
    data class Charge(val amountUsd: String) : BillingRetryIntent
    data class AutoReload(val enabled: Boolean, val thresholdUsd: String, val reloadToUsd: String) : BillingRetryIntent
    data object StepUp : BillingRetryIntent
}

data class SlashSuggestion(
    val text: String,
    val display: String,
    val meta: String,
    val group: String,
)

@Singleton
class HermesRepository @Inject constructor(
    private val backendRegistry: BackendRegistry,
    private val tokenStore: SessionCredentialStore,
    private val restClient: HermesRestClient,
    private val gateway: HermesGatewayClient,
    private val dashboardConnector: DashboardBackendConnector,
    private val json: Json,
    private val attachmentReader: AttachmentReader,
    private val draftStore: DraftStore,
    private val composerQueueStore: ComposerQueueStore,
    private val privacyPreferences: PrivacyPreferences,
    private val billingPendingChargeStore: BillingPendingChargeStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(HermesState())
    private val mutableStartupReady = MutableStateFlow(false)
    private var reconnectJob: Job? = null
    private var draftSaveJob: Job? = null
    private var sessionSearchJob: Job? = null
    private val sessionSearchLock = Any()
    private val sessionSearchGeneration = AtomicLong()
    private val sessionListGeneration = AtomicLong()
    private val sessionListRefreshGeneration = AtomicLong()
    private val visibleSessionListRefreshGeneration = AtomicLong()
    private val silentSessionListRefreshGeneration = AtomicLong()
    private val pendingSilentSessionListRefresh = AtomicBoolean()
    private val sessionListRefreshPriorityMutex = Mutex()
    private val backendCredentialGeneration = AtomicLong()
    private val sessionListMutationMutex = Mutex()
    private var slashCompletionJob: Job? = null
    private var queueDrainJob: Job? = null
    private var providerOAuthPollJob: Job? = null
    private var billingIdempotencyKey: String? = null
    private var billingIdempotencyAmount: String? = null
    private var billingIdempotencyBackendId: String? = null
    private var billingSettlementDeadlineEpochMillis: Long? = null
    private var billingStepUpRunId: String? = null
    private var billingStepUpSessionId: String? = null
    private val queueMutex = Mutex()
    private val billingAccountMutex = Mutex()
    private val sessionTargetMutex = Mutex()
    private val attachmentSessionMutex = Mutex()
    private val attachmentJobs = ConcurrentHashMap<String, Job>()
    private var attachmentCreatedSessionScope: AttachmentScope? = null
    private var extensionSlashCommands: Set<String>? = null
    private var cachedSlashCatalog: List<SlashSuggestion>? = null
    private var intentionalDisconnect = false
    private var gatewayBackendId: String? = null
    private val openSessionGeneration = AtomicLong()
    private val archivedSessionTargets = mutableMapOf<SessionTarget, Long>()
    private var pendingOpenSession: SessionTarget? = null
    private var pendingOpenSessionGeneration: Long? = null
    val state = mutableState.asStateFlow()
    val startupReady = mutableStartupReady.asStateFlow()
    val connectionState = gateway.connectionState

    init {
        scope.launch {
            combine(backendRegistry.backends, backendRegistry.activeBackendId) { backends, activeId ->
                backends to backends.firstOrNull { it.id == activeId }
            }.collectLatest { (backends, backend) ->
                billingAccountMutex.withLock {
                    if (backend == null) {
                        mutableStartupReady.value = true
                        if (mutableState.value.backend != null) {
                            flushDraft()
                            intentionalDisconnect = true
                            reconnectJob?.cancel()
                            gateway.disconnect()
                            gatewayBackendId = null
                            mutableState.value = HermesState(savedBackends = backends)
                        } else {
                            mutableState.value = mutableState.value.copy(savedBackends = backends)
                        }
                    } else if (
                        mutableState.value.backend == backend &&
                        !mutableState.value.backendTransitionInProgress
                    ) {
                        mutableStartupReady.value = true
                        mutableState.value = mutableState.value.copy(savedBackends = backends)
                    } else {
                        mutableStartupReady.value = false
                        mutableState.value = mutableState.value.copy(savedBackends = backends)
                        connect(backend)
                    }
                }
            }
        }
        scope.launch {
            gateway.events.collect { event ->
                var current = mutableState.value
                val eventSessionId = event.sessionId?.takeIf(String::isNotBlank)
                val runtimeId = current.runtimeSessionId
                val acceptsEvent = shouldAcceptRuntimeEvent(current.restoration.status, runtimeId, event.sessionId)
                if (
                    event.type == "billing.step_up.verification" &&
                    event.payload != null &&
                    billingStepUpRunId != null &&
                    eventSessionId == billingStepUpSessionId &&
                    acceptsEvent
                ) {
                    runCatching {
                        json.decodeFromJsonElement(BillingStepUpVerification.serializer(), event.payload)
                    }.onSuccess { verification ->
                        current = current.copy(
                            billingStepUpVerification = verification,
                            billingNotice = "Finish verification in the browser, then return to Hermes.",
                            billingError = null,
                        )
                        mutableState.value = current
                    }
                }
                if (
                    event.type in SubagentReducer.eventTypes &&
                    shouldAcceptSubagentEvent(current.restoration.status, runtimeId, event.sessionId)
                ) {
                    val sessionKey = event.sessionId?.takeIf(String::isNotBlank) ?: "unscoped"
                    val sessions = LinkedHashMap(current.subagentsBySession)
                    sessions[sessionKey] = SubagentReducer.reduce(sessions[sessionKey].orEmpty(), event)
                    while (sessions.size > MAX_AGENT_EVENT_SESSIONS) sessions.remove(sessions.keys.first())
                    current = current.copy(subagentsBySession = sessions)
                    mutableState.value = current
                }
                if (acceptsEvent) {
                    val runtimeInfo = when {
                        event.type == "session.info" && event.payload != null -> runCatching {
                            json.decodeFromJsonElement(SessionRuntimeInfo.serializer(), event.payload)
                        }.getOrDefault(current.runtimeInfo)
                        event.type == "message.start" -> current.runtimeInfo.copy(running = true)
                        event.type == "message.complete" -> current.runtimeInfo.copy(running = false)
                        else -> current.runtimeInfo
                    }
                    val activeStoredSession = if (
                        runtimeInfo.storedSessionId.isNotBlank() &&
                        current.activeStoredSession?.durableId.isNullOrBlank()
                    ) {
                        (current.activeStoredSession ?: StoredSession()).copy(
                            sessionId = runtimeInfo.storedSessionId,
                            profile = current.activeStoredSession?.profile ?: current.activeProfile,
                            title = runtimeInfo.title.ifBlank { current.activeStoredSession?.title.orEmpty() },
                        )
                    } else {
                        current.activeStoredSession
                    }
                    mutableState.value = current.copy(
                        runtimeInfo = runtimeInfo,
                        activeStoredSession = activeStoredSession,
                        restoration = if (
                            activeStoredSession?.durableId?.isNotBlank() == true &&
                            current.restoration.status == SessionRestorationStatus.REHYDRATING &&
                            current.backend != null
                        ) {
                            SessionRestorationState(
                                status = SessionRestorationStatus.READY,
                                target = sessionTarget(current.backend.id, activeStoredSession),
                                session = activeStoredSession,
                            )
                        } else {
                            current.restoration
                        },
                        timeline = TimelineReducer.reduce(current.timeline, event),
                    )
                    if (
                        activeStoredSession?.durableId?.isNotBlank() == true &&
                        activeStoredSession.durableId != current.activeStoredSession?.durableId &&
                        current.backend != null
                    ) {
                        val backendId = current.backend.id
                        scope.launch {
                            persistActiveSessionTargetIfCurrent(sessionTarget(backendId, activeStoredSession))
                        }
                    }
                    if (event.type == "message.complete") {
                        scope.launch { refreshSessions(showLoading = false) }
                    }
                    if (event.type == "message.complete" || (event.type == "session.info" && !runtimeInfo.running)) {
                        scheduleQueueDrain()
                    }
                }
            }
        }
        scope.launch {
            gateway.connectionState.collect { connection ->
                if (
                    !intentionalDisconnect &&
                    mutableState.value.backend != null &&
                    (connection is GatewayConnectionState.Closed || connection is GatewayConnectionState.Failed)
                ) {
                    scheduleReconnect()
                }
            }
        }
    }

    suspend fun testAndSave(
        config: BackendConfig,
        username: String,
        password: String,
        passwordProvider: String? = null,
    ): StatusResponse {
        return billingAccountMutex.withLock {
            requireBackendTransitionSafe(config.id)
            mutableState.value = mutableState.value.copy(
                loading = true,
                error = null,
                backendTransitionInProgress = true,
            )
            try {
                val status = dashboardConnector.loginValidateAndSave(config, username, password, passwordProvider)
                val saved = config.copy(lastHermesVersion = status.hermesVersion ?: status.version)
                connect(saved)
                status
            } catch (error: Throwable) {
                mutableState.value = mutableState.value.copy(backendTransitionInProgress = false)
                fail(error)
                throw error
            }
        }
    }

    suspend fun discoverDashboardPasswordProviders(config: BackendConfig): List<DashboardAuthProvider> =
        dashboardConnector.discoverPasswordProviders(config)

    suspend fun refreshSessions(showLoading: Boolean = true) {
        val requestCredentialGeneration = backendCredentialGeneration.get()
        val requestBackendId = mutableState.value.backend?.id
        val credentials = runCatching { activeCredentials(allowRecovery = true) }.getOrElse { error ->
            if (error is CancellationException) throw error
            if (
                backendCredentialGeneration.get() == requestCredentialGeneration &&
                mutableState.value.backend?.id == requestBackendId &&
                (showLoading || error.isSessionAuthenticationFailure())
            ) {
                failSessionListRefresh(error)
            }
            return
        }
        val (backend, token) = credentials
        if (
            backendCredentialGeneration.get() != requestCredentialGeneration ||
            mutableState.value.backend?.id != backend.id
        ) return
        val requestGeneration = sessionListGeneration.get()
        val refreshGeneration = sessionListRefreshPriorityMutex.withLock {
            if (
                !showLoading &&
                (visibleSessionListRefreshGeneration.get() != 0L || silentSessionListRefreshGeneration.get() != 0L)
            ) {
                pendingSilentSessionListRefresh.set(true)
                null
            } else {
                sessionListRefreshGeneration.incrementAndGet().also {
                    if (showLoading) {
                        visibleSessionListRefreshGeneration.set(it)
                        silentSessionListRefreshGeneration.set(0L)
                    }
                    else {
                        silentSessionListRefreshGeneration.set(it)
                        pendingSilentSessionListRefresh.set(false)
                    }
                }
            }
        } ?: return
        val credentialGeneration = requestCredentialGeneration
        mutableState.update { current ->
            if (
                showLoading &&
                current.backend?.id == backend.id &&
                backendCredentialGeneration.get() == credentialGeneration
            ) {
                current.copy(sessionListLoading = true, sessionListError = null)
            } else {
                current
            }
        }
        try {
            runCatching { restClient.sessions(backend, token).sessions }
                .onSuccess { sessions ->
                sessionTargetMutex.withLock {
                    var published = false
                    mutableState.update { current ->
                        published = false
                        val ownsLoading = current.backend?.id == backend.id &&
                            sessionListRefreshGeneration.get() == refreshGeneration &&
                            backendCredentialGeneration.get() == credentialGeneration
                        if (!ownsLoading) {
                            current
                        } else if (sessionListGeneration.get() != requestGeneration) {
                            pendingSilentSessionListRefresh.set(true)
                            if (showLoading) current.copy(sessionListLoading = false) else current
                        } else {
                            published = true
                            current.copy(
                                sessions = sessions,
                                sessionListLoading = if (showLoading) false else current.sessionListLoading,
                                sessionListError = null,
                            )
                        }
                    }
                    if (published) {
                        sessions.forEach {
                            if (it.durableId.isNotBlank()) {
                                archivedSessionTargets.remove(sessionTarget(backend.id, it))
                            }
                        }
                    }
                }
            }
            .onFailure { error ->
                if (error is CancellationException || !currentCoroutineContext().isActive) {
                    throw CancellationException("Session refresh cancelled").also { it.initCause(error) }
                }
                var currentRequest = false
                mutableState.update { current ->
                    val ownsLoading = current.backend?.id == backend.id &&
                        sessionListRefreshGeneration.get() == refreshGeneration &&
                        backendCredentialGeneration.get() == credentialGeneration
                    if (!ownsLoading) {
                        current
                    } else {
                        currentRequest = sessionListGeneration.get() == requestGeneration
                        if (showLoading) current.copy(sessionListLoading = false) else current
                    }
                }
                if (
                    error.isSessionAuthenticationFailure() &&
                    mutableState.value.backend?.id == backend.id &&
                    sessionListRefreshGeneration.get() == refreshGeneration &&
                    backendCredentialGeneration.get() == credentialGeneration
                ) {
                    failSessionListRefresh(error)
                } else if (
                    currentRequest &&
                    mutableState.value.backend?.id == backend.id &&
                    sessionListRefreshGeneration.get() == refreshGeneration &&
                    backendCredentialGeneration.get() == credentialGeneration &&
                    sessionListGeneration.get() == requestGeneration
                ) {
                    if (showLoading) failSessionListRefresh(error)
                }
            }
        } finally {
            if (showLoading && visibleSessionListRefreshGeneration.compareAndSet(refreshGeneration, 0L)) {
                mutableState.update { current ->
                    if (
                        current.backend?.id == backend.id &&
                        current.sessionListLoading &&
                        backendCredentialGeneration.get() == credentialGeneration &&
                        sessionListRefreshGeneration.get() == refreshGeneration
                    ) {
                        current.copy(sessionListLoading = false)
                    } else {
                        current
                    }
                }
                if (pendingSilentSessionListRefresh.getAndSet(false)) {
                    scope.launch { refreshSessions(showLoading = false) }
                }
            } else if (!showLoading && silentSessionListRefreshGeneration.compareAndSet(refreshGeneration, 0L)) {
                if (pendingSilentSessionListRefresh.getAndSet(false)) {
                    scope.launch { refreshSessions(showLoading = false) }
                }
            }
        }
    }

    fun searchSessions(query: String) {
        val cleaned = query.trim().take(200)
        synchronized(sessionSearchLock) {
            val requestGeneration = sessionSearchGeneration.incrementAndGet()
            val requestListGeneration = sessionListGeneration.get()
            val requestBackendId = mutableState.value.backend?.id
            val requestCredentialGeneration = backendCredentialGeneration.get()
            sessionSearchJob?.cancel()
            if (cleaned.isBlank()) {
                mutableState.value = mutableState.value.copy(
                    sessionSearchResults = emptyList(),
                    sessionSearchLoading = false,
                    sessionSearchQuery = "",
                )
                return@synchronized
            }
            mutableState.value = mutableState.value.copy(
                sessionSearchLoading = true,
                sessionSearchQuery = cleaned,
            )
            sessionSearchJob = scope.launch {
                delay(SESSION_SEARCH_DEBOUNCE_MILLIS)
                runCatching {
                    val (backend, token) = activeCredentials(allowRecovery = true)
                    val profiles = restClient.profiles(backend, token).profiles
                        .map(ProfileInfo::name)
                        .ifEmpty { listOf("default") }
                    profiles.flatMap { profile ->
                        restClient.searchSessions(backend, token, cleaned, profile).results.map {
                            it.copy(profile = profile)
                        }
                    }.distinctBy { "${it.profile}:${it.sessionId}" }.take(MAX_SESSION_SEARCH_RESULTS).let {
                        backend.id to it
                    }
                }.onSuccess { (backendId, results) ->
                    synchronized(sessionSearchLock) {
                        mutableState.update { current ->
                            if (
                                sessionSearchGeneration.get() == requestGeneration &&
                                sessionListGeneration.get() == requestListGeneration &&
                                backendCredentialGeneration.get() == requestCredentialGeneration &&
                                backendId == requestBackendId &&
                                current.backend?.id == backendId &&
                                current.sessionSearchQuery == cleaned
                            ) {
                                current.copy(
                                    sessionSearchResults = results,
                                    sessionSearchLoading = false,
                                    error = null,
                                )
                            } else {
                                current
                            }
                        }
                    }
                }.onFailure { error ->
                    if (error !is CancellationException) {
                        synchronized(sessionSearchLock) {
                            var currentRequest = false
                            mutableState.update { current ->
                                if (
                                    sessionSearchGeneration.get() == requestGeneration &&
                                    sessionListGeneration.get() == requestListGeneration &&
                                    current.backend?.id == requestBackendId &&
                                    current.sessionSearchQuery == cleaned
                                ) {
                                    currentRequest = true
                                    current.copy(sessionSearchLoading = false)
                                } else {
                                    current
                                }
                            }
                            if (
                                currentRequest &&
                                sessionSearchGeneration.get() == requestGeneration &&
                                sessionListGeneration.get() == requestListGeneration &&
                                backendCredentialGeneration.get() == requestCredentialGeneration &&
                                mutableState.value.backend?.id == requestBackendId &&
                                mutableState.value.sessionSearchQuery == cleaned
                            ) {
                                fail(error)
                            }
                        }
                    }
                }
            }
        }
    }

    suspend fun openSession(session: StoredSession) {
        val requestGeneration = sessionTargetMutex.withLock {
            val target = mutableState.value.backend?.id?.let { backendId ->
                if (session.durableId.isBlank()) null else sessionTarget(backendId, session)
            }
            if (target != null && archivedSessionTargets[target] == backendCredentialGeneration.get()) {
                return@withLock null
            }
            invalidatePendingAttachments()
            val generation = openSessionGeneration.incrementAndGet()
            pendingOpenSession = target
            pendingOpenSessionGeneration = generation
            generation
        } ?: return
        val credentials = try {
            activeCredentials(allowRecovery = true, allowRehydrating = true)
        } catch (error: Throwable) {
            sessionTargetMutex.withLock {
                if (pendingOpenSessionGeneration == requestGeneration) {
                    pendingOpenSession = null
                    pendingOpenSessionGeneration = null
                }
            }
            if (openSessionGeneration.get() == requestGeneration) fail(error)
            return
        }
        val (backend, token) = credentials
        flushDraft()
        var previousTimeline = TimelineState()
        var previousRuntimeSessionId: String? = null
        var selected = false
        sessionTargetMutex.withLock {
            mutableState.update { live ->
                if (openSessionGeneration.get() != requestGeneration || live.backend?.id != backend.id) {
                    if (pendingOpenSessionGeneration == requestGeneration) {
                        pendingOpenSession = null
                        pendingOpenSessionGeneration = null
                    }
                    live
                } else {
                    pendingOpenSession = null
                    pendingOpenSessionGeneration = null
                    selected = true
                    val reopeningCurrent = live.activeStoredSession?.let {
                        it.durableId == session.durableId && it.profile == session.profile
                    } == true
                    previousTimeline = if (reopeningCurrent) live.timeline else TimelineState()
                    previousRuntimeSessionId = live.runtimeSessionId
                    live.copy(
                        activeStoredSession = session,
                        runtimeSessionId = null,
                        runtimeInfo = SessionRuntimeInfo(),
                        restoration = SessionRestorationState(
                            status = SessionRestorationStatus.REHYDRATING,
                            target = sessionTarget(backend.id, session),
                        ),
                        timeline = previousTimeline,
                        pendingAttachments = emptyList(),
                        loading = true,
                        checkpointsEnabled = null,
                        checkpoints = emptyList(),
                        checkpointPreview = null,
                        checkpointsLoading = false,
                        checkpointNotice = null,
                        checkpointError = null,
                        error = null,
                    )
                }
            }
        }
        if (!selected) return
        if (!persistSessionTargetIfCurrent(requestGeneration, sessionTarget(backend.id, session))) return

        var resumedResult: SessionResumeResult? = null
        runCatching {
            val resumed = gateway.request(
                "session.resume",
                buildJsonObject {
                    put("session_id", session.durableId)
                    put("cols", 96)
                    put("source", "android")
                    session.profile?.let { put("profile", it) }
                },
            )
            json.decodeFromJsonElement(SessionResumeResult.serializer(), resumed)
        }.onSuccess { resumed ->
            val activeSession = resumedStoredSession(session, resumed)
            var applied = false
            mutableState.update { live ->
                val currentRequest = openSessionGeneration.get() == requestGeneration &&
                    live.backend?.id == backend.id &&
                    live.activeStoredSession?.durableId == session.durableId &&
                    live.activeStoredSession?.profile == session.profile
                if (!currentRequest) {
                    live
                } else {
                    applied = true
                    val liveTimeline = live.timeline.takeIf { live.runtimeSessionId == previousRuntimeSessionId }
                        ?: previousTimeline
                    val running = resumed.running || resumed.info.running
                    live.copy(
                        activeStoredSession = activeSession,
                        runtimeSessionId = resumed.runtimeSessionId,
                        timeline = TimelineReducer.reconcileResume(
                            messages = resumed.messages,
                            runtimeSessionId = resumed.runtimeSessionId,
                            inflight = resumed.inflight,
                            queued = resumed.queued,
                            running = running,
                            previousRuntimeSessionId = live.runtimeSessionId,
                            previous = liveTimeline,
                        ),
                        runtimeInfo = resumed.info.copy(
                            running = running,
                            storedSessionId = activeSession.durableId,
                        ),
                        restoration = SessionRestorationState(
                            status = SessionRestorationStatus.READY,
                            target = sessionTarget(backend.id, activeSession),
                            session = activeSession,
                        ),
                        loading = false,
                        error = null,
                    )
                }
            }
            if (applied) resumedResult = resumed
        }.onFailure { error ->
            val currentRequest = mutableState.value.let { live ->
                openSessionGeneration.get() == requestGeneration &&
                    live.backend?.id == backend.id &&
                    live.activeStoredSession?.durableId == session.durableId &&
                    live.activeStoredSession?.profile == session.profile
            }
            if (currentRequest) {
                if (error.isMissingSessionFailure()) {
                    if (!clearSessionTargetIfCurrent(requestGeneration, backend.id)) return@onFailure
                    mutableState.update { live ->
                        val currentRequest = openSessionGeneration.get() == requestGeneration &&
                            live.backend?.id == backend.id &&
                            live.activeStoredSession?.durableId == session.durableId &&
                            live.activeStoredSession?.profile == session.profile
                        if (!currentRequest) live else live.copy(
                            activeStoredSession = null,
                            runtimeSessionId = null,
                            runtimeInfo = SessionRuntimeInfo(),
                            timeline = TimelineState(),
                            pendingAttachments = emptyList(),
                            restoration = SessionRestorationState(
                                status = SessionRestorationStatus.SESSION_UNAVAILABLE,
                                target = sessionTarget(backend.id, session),
                                explanation = "That Hermes session could not be found. Choose another session to continue.",
                            ),
                            loading = false,
                            error = "That Hermes session could not be found. Choose another session to continue.",
                        )
                    }
                } else {
                    fail(error)
                }
            }
        }

        val resumed = resumedResult ?: return
        val activeSession = resumedStoredSession(session, resumed)
        if (!persistSessionTargetIfCurrent(requestGeneration, sessionTarget(backend.id, activeSession))) return
        val committedTimeline = mutableState.value.timeline
        runCatching {
            restClient.sessionMessages(backend, token, activeSession.durableId, activeSession.profile)
        }.onSuccess { prefetch ->
            mutableState.update { live ->
                val stillCurrent = openSessionGeneration.get() == requestGeneration &&
                    live.backend?.id == backend.id &&
                    live.activeStoredSession?.durableId == activeSession.durableId &&
                    live.activeStoredSession?.profile == activeSession.profile &&
                    live.runtimeSessionId == resumed.runtimeSessionId &&
                    live.timeline == committedTimeline
                if (!stillCurrent) {
                    live
                } else {
                    val prefetchSupersedes = prefetchSupersedesResume(prefetch, resumed)
                    val running = (resumed.running || resumed.info.running) && !prefetchSupersedes
                    live.copy(
                        timeline = TimelineReducer.reconcileResume(
                            messages = selectResumeMessages(prefetch, resumed),
                            runtimeSessionId = resumed.runtimeSessionId,
                            inflight = resumed.inflight.takeUnless { prefetchSupersedes },
                            queued = resumed.queued.takeUnless { prefetchSupersedes },
                            running = running,
                            previousRuntimeSessionId = resumed.runtimeSessionId,
                            previous = committedTimeline,
                        ),
                        runtimeInfo = live.runtimeInfo.copy(running = running),
                    )
                }
            }
        }.onFailure { error ->
            mutableState.update { live ->
                if (
                    openSessionGeneration.get() == requestGeneration &&
                    live.backend?.id == backend.id &&
                    live.runtimeSessionId == resumed.runtimeSessionId &&
                    live.error == null
                ) {
                    live.copy(
                        error = "Session opened, but full history could not be refreshed: ${DiagnosticRedactor.redact(error.message.orEmpty())}",
                    )
                } else {
                    live
                }
            }
        }
        if (isCurrentSessionRequest(requestGeneration, backend.id, activeSession, resumed.runtimeSessionId)) {
            loadComposerState()
            refreshModelOptions()
        }
    }

    suspend fun newSession(profile: String? = null, preservePendingAttachments: Boolean = false): Boolean {
        if (!preservePendingAttachments) invalidatePendingAttachments()
        val requestGeneration = openSessionGeneration.incrementAndGet()
        val backend = try {
            activeCredentials(allowRecovery = true).first
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            if (openSessionGeneration.get() == requestGeneration) fail(error)
            return false
        }
        flushDraft()
        setLoading(true)
        val created = try {
            gateway.request(
                "session.create",
                buildJsonObject {
                    put("cols", 96)
                    put("source", "android")
                    profile?.let { put("profile", it) }
                },
            ).let { json.decodeFromJsonElement(SessionCreateResult.serializer(), it) }
        } catch (cancelled: CancellationException) {
            if (openSessionGeneration.get() == requestGeneration) setLoading(false)
            throw cancelled
        } catch (error: Throwable) {
            if (openSessionGeneration.get() == requestGeneration) fail(error)
            return false
        }
        val current = mutableState.value
        if (
            openSessionGeneration.get() != requestGeneration ||
            current.backend?.id != backend.id
        ) {
            try {
                gateway.request(
                    "session.close",
                    buildJsonObject { put("session_id", created.runtimeSessionId) },
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(
                    "HermesRepository",
                    "Could not close a stale created session: " +
                        DiagnosticRedactor.redact(error.message.orEmpty()).ifBlank { "unknown error" },
                )
            }
            return false
        }
        val activeProfile = profile ?: current.activeProfile
        val durableId = created.durableSessionId.orEmpty().ifBlank { created.info.storedSessionId }
        val activeSession = durableId.takeIf(String::isNotBlank)?.let {
            StoredSession(sessionId = it, profile = activeProfile, source = "android")
        }
        mutableState.value = current.copy(
            activeStoredSession = activeSession,
            runtimeSessionId = created.runtimeSessionId,
            timeline = TimelineReducer.hydrate(created.messages),
            runtimeInfo = created.info.copy(storedSessionId = durableId),
            activeProfile = activeProfile,
            restoration = SessionRestorationState(
                status = SessionRestorationStatus.READY,
                target = activeSession?.let { sessionTarget(current.backend.id, it) },
                session = activeSession,
            ),
            pendingAttachments = if (preservePendingAttachments) current.pendingAttachments else emptyList(),
            checkpointsEnabled = null,
            checkpoints = emptyList(),
            checkpointPreview = null,
            checkpointsLoading = false,
            checkpointNotice = null,
            checkpointError = null,
            loading = false,
            error = null,
        )
        activeSession?.let { persistSessionTargetIfCurrent(requestGeneration, sessionTarget(backend.id, it)) }
        loadComposerState()
        refreshModelOptions()
        return true
    }

    fun updateDraft(value: String) {
        val bounded = value.take(DraftStore.MAX_DRAFT_CHARACTERS)
        mutableState.value = mutableState.value.copy(draft = bounded)
        val context = currentDraftContext() ?: return
        draftSaveJob?.cancel()
        draftSaveJob = scope.launch {
            delay(DRAFT_SAVE_DEBOUNCE_MILLIS)
            draftStore.put(context, bounded)
        }
    }

    suspend fun ingestSharedContent(text: String, uris: List<Uri>): Boolean {
        val allUris = uris
            .distinctBy(Uri::toString)
            .take(MAX_SHARED_ATTACHMENTS)
        val importUris = allUris
            .filterNot { uri -> mutableState.value.pendingAttachments.any { it.sourceUri == uri.toString() } }
            .take((MAX_SHARED_ATTACHMENTS - mutableState.value.pendingAttachments.size).coerceAtLeast(0))
        val requestedSources = allUris.mapTo(mutableSetOf(), Uri::toString)
        mutableState.value.pendingAttachments
            .filter { it.sourceUri in requestedSources && it.phase == AttachmentPhase.ERROR }
            .map(PendingAttachment::id)
            .forEach { retryPendingAttachment(it) }
        val stagedIds = importUris.mapNotNull { uri ->
            if (!uri.scheme.equals("content", ignoreCase = true)) {
                fail(IllegalArgumentException("Shared attachments must use content URIs"))
                null
            } else {
                stageAttachment(uri)
            }
        }
        stagedIds.map { id -> scope.launch { runAttachment(id) } }.joinAll()
        var accepted = mutableState.value.pendingAttachments.any {
            it.sourceUri in requestedSources && it.phase == AttachmentPhase.READY
        }
        if (text.isNotEmpty()) {
            if (mutableState.value.runtimeSessionId == null) {
                require(newSession(preservePendingAttachments = true)) {
                    "Hermes could not open a session for shared content"
                }
            }
            updateDraft(mergeSharedText(mutableState.value.draft, text, DraftStore.MAX_DRAFT_CHARACTERS))
            accepted = true
        }
        return accepted
    }

    suspend fun discardSharedContentUris(uriStrings: List<String>) {
        val sources = uriStrings.toSet()
        mutableState.value.pendingAttachments
            .filter { it.sourceUri in sources }
            .forEach { removePendingAttachment(it.id) }
        uriStrings.map(Uri::parse).forEach(attachmentReader::release)
    }

    suspend fun queueDraft() {
        runCatching {
            val current = mutableState.value
            require(current.queueStorageHealthy) { QUEUE_RECOVERY_MESSAGE }
            require(isRunBusy(current)) { "Pending messages can only be queued while Hermes is running" }
            require(current.pendingAttachments.isEmpty()) {
                "Pending-message queue is text-only. Send or remove attachments first."
            }
            val context = requireNotNull(currentComposerQueueContext()) {
                "Open a Hermes session before queueing a message"
            }
            val entry = QueuedPrompt(
                id = "queued:${UUID.randomUUID()}",
                text = current.draft,
                queuedAtEpochMillis = System.currentTimeMillis(),
            )
            queueMutex.withLock {
                val next = ComposerQueue.enqueue(mutableState.value.queuedPrompts, entry)
                composerQueueStore.put(context, next)
                mutableState.value = mutableState.value.copy(
                    queuedPrompts = next,
                    queueNotice = null,
                )
            }
            clearCurrentDraft()
        }.onFailure(::fail)
    }

    suspend fun updateQueuedPrompt(id: String, text: String) {
        runCatching {
            require(mutableState.value.queueStorageHealthy) { QUEUE_RECOVERY_MESSAGE }
            val context = requireNotNull(currentComposerQueueContext()) { "Queued message session is unavailable" }
            queueMutex.withLock {
                val next = ComposerQueue.updateText(mutableState.value.queuedPrompts, id, text)
                composerQueueStore.put(context, next)
                mutableState.value = mutableState.value.copy(queuedPrompts = next, queueNotice = null)
            }
            scheduleQueueDrain()
        }.onFailure(::fail)
    }

    suspend fun removeQueuedPrompt(id: String) {
        runCatching {
            require(mutableState.value.queueStorageHealthy) { QUEUE_RECOVERY_MESSAGE }
            val context = requireNotNull(currentComposerQueueContext()) { "Queued message session is unavailable" }
            queueMutex.withLock {
                val current = mutableState.value.queuedPrompts
                require(current.any { it.id == id }) { "Queued message was not found" }
                val next = ComposerQueue.remove(current, id)
                composerQueueStore.put(context, next)
                mutableState.value = mutableState.value.copy(queuedPrompts = next, queueNotice = null)
            }
        }.onFailure(::fail)
    }

    suspend fun sendQueuedPromptNow(id: String) {
        runCatching {
            require(mutableState.value.queueStorageHealthy) { QUEUE_RECOVERY_MESSAGE }
            require(!isRunBusy(mutableState.value)) { "Stop the current run before sending a queued message now" }
            val context = requireNotNull(currentComposerQueueContext()) { "Queued message session is unavailable" }
            queueMutex.withLock {
                val current = mutableState.value.queuedPrompts
                require(current.any { it.id == id }) { "Queued message was not found" }
                val next = ComposerQueue.resetFailures(ComposerQueue.promote(current, id), id)
                composerQueueStore.put(context, next)
                mutableState.value = mutableState.value.copy(queuedPrompts = next, queueNotice = null)
            }
            scheduleQueueDrain()
        }.onFailure(::fail)
    }

    fun completeSlash(text: String) {
        val cleaned = text.take(MAX_SLASH_TEXT_CHARACTERS)
        slashCompletionJob?.cancel()
        if (!cleaned.startsWith('/')) {
            mutableState.value = mutableState.value.copy(
                slashSuggestions = emptyList(),
                slashLoading = false,
                slashQuery = "",
            )
            return
        }
        mutableState.value = mutableState.value.copy(slashLoading = true, slashQuery = cleaned)
        slashCompletionJob = scope.launch {
            delay(SLASH_COMPLETION_DEBOUNCE_MILLIS)
            runCatching {
                activeCredentials()
                val catalogueSuggestions = loadSlashCatalog()
                if (cleaned == "/") {
                    catalogueSuggestions
                } else {
                    val completed = gateway.request(
                        "complete.slash",
                        buildJsonObject { put("text", cleaned) },
                    ).let { json.decodeFromJsonElement(SlashCompletionResult.serializer(), it) }
                    mobileCompletionSuggestions(cleaned, completed, extensionSlashCommands.orEmpty())
                }
            }.onSuccess { suggestions ->
                if (mutableState.value.slashQuery == cleaned) {
                    mutableState.value = mutableState.value.copy(
                        slashSuggestions = suggestions.take(MAX_SLASH_SUGGESTIONS),
                        slashLoading = false,
                    )
                }
            }.onFailure { error ->
                if (error !is CancellationException && mutableState.value.slashQuery == cleaned) {
                    mutableState.value = mutableState.value.copy(
                        slashSuggestions = emptyList(),
                        slashLoading = false,
                    )
                }
            }
        }
    }

    suspend fun executeSlash(rawCommand: String) {
        try {
            executeSlashUnchecked(rawCommand)
        } catch (cancelled: CancellationException) {
            mutableState.value = mutableState.value.copy(billingLoading = false)
            throw cancelled
        } catch (error: Throwable) {
            fail(error)
        }
    }

    private suspend fun executeSlashUnchecked(rawCommand: String) {
        val command = rawCommand.trim().take(MAX_SLASH_TEXT_CHARACTERS)
        require(command.startsWith('/') && command.length > 1) { "Enter a Hermes slash command" }
        val body = command.dropWhile { it == '/' }
        val name = body.substringBefore(' ').lowercase()
        val argument = body.substringAfter(' ', "").trim()
        activeCredentials()
        loadSlashCatalog()
        if (!isMobileSlashCommand("/$name", extensionSlashCommands.orEmpty())) {
            error("/$name is not available in the Android app")
        }
        when (name) {
            "new", "reset" -> {
                clearCurrentDraft()
                newSession(mutableState.value.activeStoredSession?.profile)
            }
            "retry" -> {
                clearCurrentDraft()
                retryLastMessage()
            }
            "undo" -> {
                clearCurrentDraft()
                undoLastTurn()
            }
            "compress" -> {
                clearCurrentDraft()
                compressActive(argument)
            }
            "branch" -> {
                clearCurrentDraft()
                branchActive(argument)
            }
            "title" -> {
                require(argument.isNotBlank()) { "Usage: /title <name>" }
                clearCurrentDraft()
                renameActive(argument)
            }
            else -> executeRemoteSlash(command, name, argument)
        }
    }

    suspend fun refreshModelOptions(refresh: Boolean = false) {
        val requestContext = currentSessionContentContext() ?: return
        val sessionId = requestContext.runtimeSessionId
        mutableState.value = mutableState.value.copy(modelsLoading = true, error = null)
        runCatching {
            gateway.request(
                "model.options",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("explicit_only", true)
                    if (refresh) put("refresh", true)
                },
            )
        }.mapCatching { json.decodeFromJsonElement(ModelOptionsResult.serializer(), it) }
            .onSuccess { options ->
                if (currentSessionContentContext() == requestContext) {
                    mutableState.value = mutableState.value.copy(
                        modelOptions = options,
                        modelsLoading = false,
                        runtimeInfo = mutableState.value.runtimeInfo.copy(
                            model = options.model ?: mutableState.value.runtimeInfo.model,
                            provider = options.provider ?: mutableState.value.runtimeInfo.provider,
                        ),
                    )
                }
            }
            .onFailure { error ->
                val stillCurrent = currentSessionContentContext() == requestContext
                if (stillCurrent) {
                    mutableState.value = mutableState.value.copy(modelsLoading = false)
                }
                if (error is CancellationException) throw error
                if (stillCurrent) {
                    fail(error)
                }
            }
    }

    suspend fun selectModel(provider: String, model: String, confirmExpensive: Boolean = false) {
        val sessionId = mutableState.value.runtimeSessionId ?: return
        val selection = ModelSelection(provider, model)
        mutableState.value = mutableState.value.copy(modelsLoading = true, error = null)
        val result = runCatching {
            val response = gateway.request(
                "config.set",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("key", "model")
                    put("value", selection.rpcValue())
                    if (confirmExpensive) put("confirm_expensive_model", true)
                },
            )
            json.decodeFromJsonElement(ConfigSetResult.serializer(), response)
        }.getOrElse { error ->
            mutableState.value = mutableState.value.copy(modelsLoading = false)
            fail(error)
            return
        }
        if (result.confirmRequired) {
            mutableState.value = mutableState.value.copy(
                modelsLoading = false,
                pendingModelConfirmation = PendingModelConfirmation(
                    selection,
                    result.confirmMessage.ifBlank { "Hermes requires confirmation before using this model." },
                ),
            )
        } else {
            mutableState.value = mutableState.value.copy(
                modelsLoading = false,
                pendingModelConfirmation = null,
                runtimeInfo = mutableState.value.runtimeInfo.copy(model = model, provider = provider),
            )
            applyModelPreset(provider, model)
        }
    }

    suspend fun confirmModelSelection() {
        val pending = mutableState.value.pendingModelConfirmation ?: return
        selectModel(pending.selection.provider, pending.selection.model, confirmExpensive = true)
    }

    fun cancelModelSelection() {
        mutableState.value = mutableState.value.copy(pendingModelConfirmation = null)
    }

    suspend fun setReasoningEffort(effort: String) {
        setSessionConfig("reasoning", effort)
    }

    suspend fun setFastMode(enabled: Boolean) {
        setSessionConfig("fast", if (enabled) "fast" else "normal")
    }

    suspend fun setYolo(enabled: Boolean) {
        setSessionConfig("yolo", if (enabled) "on" else "off", "session")
    }

    private suspend fun applyModelPreset(provider: String, model: String) {
        val capabilities = mutableState.value.modelOptions?.providers
            ?.firstOrNull { it.slug == provider }
            ?.capabilities
            ?.get(model)
            ?: return
        val preset = runCatching { privacyPreferences.modelPreset(provider, model) }.getOrElse {
            mutableState.value = mutableState.value.copy(
                error = "Model changed, but its saved reasoning and fast preset could not be loaded.",
            )
            return
        }
        for ((key, value) in preset.sessionConfigChanges(capabilities)) {
            if (!setSessionConfig(key, value, rememberModelPreset = false)) return
        }
    }

    private suspend fun setSessionConfig(
        key: String,
        value: String,
        scope: String? = null,
        rememberModelPreset: Boolean = key == "reasoning" || key == "fast",
    ): Boolean {
        val sessionId = mutableState.value.runtimeSessionId ?: return false
        val result = runCatching {
            val response = gateway.request(
                "config.set",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("key", key)
                    put("value", value)
                    scope?.let { put("scope", it) }
                },
            )
            json.decodeFromJsonElement(ConfigSetResult.serializer(), response)
        }.getOrElse {
            fail(it)
            return false
        }
        val current = mutableState.value.runtimeInfo
        val next = when (key) {
            "reasoning" -> current.copy(reasoningEffort = result.value)
            "fast" -> current.copy(fast = result.value == "fast", serviceTier = if (result.value == "fast") "priority" else "")
            "yolo" -> current.copy(yolo = result.value == "1")
            else -> current
        }
        mutableState.value = mutableState.value.copy(runtimeInfo = next, error = null)
        if (rememberModelPreset && current.provider.isNotBlank() && current.model.isNotBlank()) {
            runCatching {
                when (key) {
                    "reasoning" -> privacyPreferences.setModelReasoningPreset(current.provider, current.model, value)
                    "fast" -> privacyPreferences.setModelFastPreset(current.provider, current.model, value == "fast")
                }
            }.onFailure {
                mutableState.value = mutableState.value.copy(
                    error = "Hermes updated the session, but Android could not remember this model preset.",
                )
            }
        }
        return true
    }

    suspend fun send(text: String) {
        val submittedDraft = text
        val draftContextBeforeSend = currentDraftContext()
        val cleaned = text.trim()
        require(cleaned.isNotEmpty())
        require(mutableState.value.pendingAttachments.readyToSend(currentAttachmentScope())) {
            "Wait for attachments to finish or remove failed attachments before sending."
        }
        val sessionId = mutableState.value.runtimeSessionId ?: run {
            newSession()
            requireNotNull(mutableState.value.runtimeSessionId)
        }
        val attachmentRefs = mutableState.value.pendingAttachments.mapNotNull { it.refText }
        val submittedText = buildString {
            append(cleaned)
            if (attachmentRefs.isNotEmpty()) append("\n\n").append(attachmentRefs.joinToString("\n"))
        }
        val optimisticId = "local:${UUID.randomUUID()}"
        mutableState.value = mutableState.value.copy(
            timeline = TimelineReducer.appendUserMessage(mutableState.value.timeline, optimisticId, cleaned),
            sending = true,
            error = null,
        )
        runCatching {
            gateway.request(
                "prompt.submit",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("text", submittedText)
                },
            )
        }.onSuccess {
            mutableState.value = mutableState.value.copy(sending = false, pendingAttachments = emptyList())
            if (mutableState.value.draft == submittedDraft) {
                clearDraft(listOfNotNull(draftContextBeforeSend, currentDraftContext()).distinct())
            }
        }.onFailure(::fail)
    }

    suspend fun steer(text: String) {
        val submittedDraft = text
        val draftContextBeforeSteer = currentDraftContext()
        val cleaned = text.trim()
        require(cleaned.isNotEmpty())
        val attachments = mutableState.value.pendingAttachments
        require(attachments.readyToSend(currentAttachmentScope())) {
            "Wait for attachments to finish or remove failed attachments before steering."
        }
        val attachmentRefs = attachments.mapNotNull(PendingAttachment::refText)
        val submittedText = buildString {
            append(cleaned)
            if (attachmentRefs.isNotEmpty()) append("\n\n").append(attachmentRefs.joinToString("\n"))
        }
        val sessionId = mutableState.value.runtimeSessionId ?: return
        runCatching {
            val response = gateway.request(
                "session.steer",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("text", submittedText)
                },
            )
            json.decodeFromJsonElement(SessionSteerResult.serializer(), response).also {
                require(it.status == "queued") { "Hermes rejected the steering message" }
            }
        }.onSuccess {
            mutableState.value = mutableState.value.copy(error = null, pendingAttachments = emptyList())
            if (mutableState.value.draft == submittedDraft) {
                clearDraft(listOfNotNull(draftContextBeforeSteer, currentDraftContext()).distinct())
            }
        }.onFailure(::fail)
    }

    suspend fun renameActive(title: String) {
        val cleaned = title.trim()
        require(cleaned.isNotEmpty() && cleaned.length <= 200) { "Session titles must be 1–200 characters" }
        val sessionId = mutableState.value.runtimeSessionId ?: return
        runCatching {
            val response = gateway.request(
                "session.title",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("title", cleaned)
                },
            )
            json.decodeFromJsonElement(SessionTitleResult.serializer(), response)
        }.onSuccess { result ->
            val active = mutableState.value.activeStoredSession
            val durableId = result.sessionKey ?: active?.durableId
            mutableState.value = mutableState.value.copy(
                activeStoredSession = (active ?: StoredSession()).copy(title = result.title),
                runtimeInfo = mutableState.value.runtimeInfo.copy(title = result.title),
                sessions = mutableState.value.sessions.map {
                    if (it.durableId == durableId) it.copy(title = result.title) else it
                },
                error = null,
            )
        }.onFailure(::fail)
    }

    suspend fun branchActive(name: String = "") {
        invalidatePendingAttachments()
        val sessionId = mutableState.value.runtimeSessionId ?: return
        val profile = mutableState.value.activeStoredSession?.profile
        val requestGeneration = openSessionGeneration.incrementAndGet()
        flushDraft()
        var opened = false
        var targetToPersist: SessionTarget? = null
        runCatching {
            val response = gateway.request(
                "session.branch",
                buildJsonObject {
                    put("session_id", sessionId)
                    name.trim().takeIf(String::isNotBlank)?.let { put("name", it.take(200)) }
                },
            )
            json.decodeFromJsonElement(SessionBranchResult.serializer(), response)
        }.onSuccess { branch ->
            val durableId = requireNotNull(branch.durableSessionId?.takeIf(String::isNotBlank)) {
                "Hermes created the branch without returning its durable session id"
            }
            mutableState.update { current ->
                val backend = current.backend
                if (
                    openSessionGeneration.get() != requestGeneration ||
                    backend == null ||
                    current.runtimeSessionId != sessionId
                ) {
                    current
                } else {
                    val activeSession = StoredSession(
                        sessionId = durableId,
                        title = branch.title,
                        profile = profile,
                        source = "android",
                    )
                    targetToPersist = sessionTarget(backend.id, activeSession)
                    opened = true
                    current.copy(
                        runtimeSessionId = branch.runtimeSessionId,
                        activeStoredSession = activeSession,
                        timeline = TimelineReducer.hydrate(branch.messages),
                        restoration = SessionRestorationState(
                            status = SessionRestorationStatus.READY,
                            target = targetToPersist,
                            session = activeSession,
                        ),
                        runtimeInfo = branch.info.copy(
                            title = branch.title,
                            storedSessionId = durableId,
                            running = false,
                        ),
                        pendingAttachments = emptyList(),
                        checkpointsEnabled = null,
                        checkpoints = emptyList(),
                        checkpointPreview = null,
                        checkpointsLoading = false,
                        checkpointNotice = null,
                        checkpointError = null,
                        loading = false,
                        error = null,
                    )
                }
            }
        }.onFailure(::fail)
        if (opened) {
            targetToPersist?.let { persistSessionTargetIfCurrent(requestGeneration, it) }
            loadComposerState()
            refreshModelOptions()
        }
    }

    suspend fun undoLastTurn() {
        val sessionId = mutableState.value.runtimeSessionId ?: return
        runCatching {
            val undo = gateway.request("session.undo", buildJsonObject { put("session_id", sessionId) })
            json.decodeFromJsonElement(SessionUndoResult.serializer(), undo)
        }.onSuccess { undo ->
            mutableState.value = mutableState.value.copy(
                timeline = if (undo.removed > 0) {
                    TimelineReducer.removeLastExchange(mutableState.value.timeline)
                } else {
                    mutableState.value.timeline
                },
                error = if (undo.removed == 0) "Hermes had no completed turn to undo." else null,
            )
        }.onFailure(::fail)
    }

    suspend fun retryLastMessage() {
        val sessionId = mutableState.value.runtimeSessionId ?: return
        require(!mutableState.value.runtimeInfo.running && !mutableState.value.sending) {
            "Interrupt the current Hermes run before retrying"
        }
        setLoading(true)
        var retryText: String? = null
        runCatching {
            val before = gateway.request("session.history", buildJsonObject { put("session_id", sessionId) })
            val historyBefore = json.decodeFromJsonElement(SessionHistoryResult.serializer(), before)
            retryText = requireNotNull(lastUserPrompt(historyBefore.messages)) { "Hermes has no user message to retry" }

            val undo = gateway.request("session.undo", buildJsonObject { put("session_id", sessionId) })
            val removed = json.decodeFromJsonElement(SessionUndoResult.serializer(), undo)
            require(removed.removed > 0) { "Hermes had no completed turn to retry" }

            val currentTimeline = mutableState.value.timeline
            val localBase = TimelineReducer.removeLastExchange(currentTimeline)
            val hydratedBase = TimelineReducer.removeLastExchange(TimelineReducer.hydrate(historyBefore.messages))
            mutableState.value = mutableState.value.copy(
                timeline = TimelineReducer.appendUserMessage(
                    if (localBase.items.size < currentTimeline.items.size) localBase else hydratedBase,
                    "local:${UUID.randomUUID()}",
                    requireNotNull(retryText),
                ),
                loading = false,
                sending = true,
                error = null,
            )
            gateway.request(
                "prompt.submit",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("text", requireNotNull(retryText))
                },
            )
        }.onSuccess {
            mutableState.value = mutableState.value.copy(loading = false, sending = false, error = null)
        }.onFailure { error ->
            retryText?.let(::updateDraft)
            fail(error)
        }
    }

    suspend fun compressActive(focusTopic: String = "") {
        val sessionId = mutableState.value.runtimeSessionId ?: return
        setLoading(true)
        runCatching {
            val response = gateway.request(
                "session.compress",
                buildJsonObject {
                    put("session_id", sessionId)
                    focusTopic.trim().takeIf(String::isNotBlank)?.let { put("focus_topic", it.take(500)) }
                },
            )
            json.decodeFromJsonElement(SessionCompressResult.serializer(), response)
        }.onSuccess { compressed ->
            mutableState.value = mutableState.value.copy(
                timeline = if (compressed.messages.isEmpty()) {
                    mutableState.value.timeline
                } else {
                    TimelineReducer.hydrate(compressed.messages)
                },
                runtimeInfo = compressed.info ?: mutableState.value.runtimeInfo,
                loading = false,
                error = if (compressed.status == "aborted") "Hermes left the context unchanged because compression was not useful." else null,
            )
        }.onFailure(::fail)
    }

    suspend fun refreshSkills() {
        val (backend, token) = activeCredentials()
        mutableState.value = mutableState.value.copy(managementLoading = true, error = null)
        runCatching { restClient.skills(backend, token) }
            .onSuccess { skills ->
                mutableState.value = mutableState.value.copy(
                    skills = skills.sortedWith(compareByDescending<SkillInfo> { it.usage ?: 0 }.thenBy { it.name }),
                    managementLoading = false,
                )
            }
            .onFailure(::fail)
    }

    suspend fun toggleSkill(name: String, enabled: Boolean) {
        val (backend, token) = activeCredentials()
        runCatching { restClient.toggleSkill(backend, token, name, enabled) }
            .onSuccess { changed ->
                mutableState.value = mutableState.value.copy(
                    skills = mutableState.value.skills.map {
                        if (it.name == changed.name) it.copy(enabled = changed.enabled) else it
                    },
                    error = null,
                )
            }
            .onFailure(::fail)
    }

    suspend fun refreshToolsets() {
        val (backend, token) = activeCredentials()
        mutableState.value = mutableState.value.copy(
            toolsetsLoading = true,
            toolsetNotice = null,
            toolsetError = null,
        )
        runCatching {
            val active = restClient.activeProfile(backend, token)
            active to restClient.toolsets(backend, token, active.active)
        }.onSuccess { (active, rows) ->
            val safeRows = rows
                .filter { it.name.isNotBlank() && it.name.length <= MAX_TOOLSET_NAME_CHARACTERS }
                .distinctBy(ToolsetInfo::name)
                .sortedWith(compareByDescending<ToolsetInfo> { it.enabled }.thenBy { it.label })
            mutableState.value = mutableState.value.copy(
                activeProfile = active.active,
                currentProfile = active.current,
                toolsets = safeRows,
                toolsetsLoading = false,
                toolsetError = null,
            )
        }.onFailure(::failToolsets)
    }

    suspend fun setToolsetEnabled(name: String, enabled: Boolean) {
        val toolset = mutableState.value.toolsets.firstOrNull { it.name == name }
            ?: error("Hermes did not advertise this toolset")
        val (backend, token) = activeCredentials()
        val profile = mutableState.value.activeProfile
        mutableState.value = mutableState.value.copy(
            toolsetsLoading = true,
            toolsetNotice = null,
            toolsetError = null,
        )
        runCatching {
            restClient.setToolsetEnabled(backend, token, profile, toolset.name, enabled).also { changed ->
                require(
                    changed.ok && changed.name == toolset.name &&
                        changed.platform == toolset.platform && changed.enabled == enabled,
                ) { "Hermes returned an inconsistent toolset state" }
            }
        }.onSuccess { changed ->
            if (mutableState.value.activeProfile != profile) {
                mutableState.value = mutableState.value.copy(toolsetsLoading = false)
                return@onSuccess
            }
            mutableState.value = mutableState.value.copy(
                toolsets = mutableState.value.toolsets.map { row ->
                    if (row.name == changed.name) row.copy(enabled = changed.enabled, available = changed.enabled) else row
                },
                toolsetsLoading = false,
                toolsetNotice = "${toolset.label} ${if (enabled) "enabled" else "disabled"} for ${toolset.platformLabel}. New sessions will use this configuration.",
            )
        }.onFailure(::failToolsets)
    }

    suspend fun refreshServerConfig() {
        val (backend, token) = activeCredentials()
        mutableState.value = mutableState.value.copy(
            serverConfigLoading = true,
            serverConfigNotice = null,
            serverConfigError = null,
        )
        runCatching {
            val active = restClient.activeProfile(backend, token)
            val schema = restClient.serverConfigSchema(backend, token)
            val config = restClient.serverConfig(backend, token, active.active)
            Triple(active, parseServerConfig(schema, config), active.active)
        }.onSuccess { (active, snapshot, profile) ->
            mutableState.value = mutableState.value.copy(
                activeProfile = active.active,
                currentProfile = active.current,
                serverConfigProfile = profile,
                serverConfig = snapshot,
                serverConfigLoading = false,
                serverConfigError = null,
            )
        }.onFailure(::failServerConfig)
    }

    suspend fun updateServerConfig(key: String, value: kotlinx.serialization.json.JsonElement) {
        val observed = mutableState.value
        val field = observed.serverConfig.fields.firstOrNull { it.key == key }
            ?: error("Hermes did not advertise this configuration field")
        val profile = observed.serverConfigProfile
            ?.takeIf { it == observed.activeProfile }
            ?: error("Refresh server settings after switching profiles")
        val safeValue = validateServerConfigValue(field, value)
        val (backend, token) = activeCredentials()
        mutableState.value = mutableState.value.copy(
            serverConfigLoading = true,
            serverConfigNotice = null,
            serverConfigError = null,
        )
        runCatching {
            restClient.updateServerConfig(backend, token, profile, field.key, safeValue).also { result ->
                require(result.ok) { "Hermes did not confirm the configuration change" }
            }
        }.onSuccess {
            if (mutableState.value.activeProfile != profile || mutableState.value.serverConfigProfile != profile) {
                mutableState.value = mutableState.value.copy(serverConfigLoading = false)
                return@onSuccess
            }
            mutableState.value = mutableState.value.copy(
                serverConfig = mutableState.value.serverConfig.copy(
                    fields = mutableState.value.serverConfig.fields.map { current ->
                        if (current.key == field.key) current.copy(value = safeValue) else current
                    },
                ),
                serverConfigLoading = false,
                serverConfigNotice = "${field.key} saved for $profile. Running sessions may retain their prior value.",
                serverConfigError = null,
            )
        }.onFailure(::failServerConfig)
    }

    suspend fun loadSkillHub(query: String = "") {
        val (backend, token) = activeCredentials()
        val profile = mutableState.value.activeProfile
        mutableState.value = mutableState.value.copy(skillHubLoading = true, skillHubReview = null, error = null)
        runCatching {
            if (query.isBlank()) restClient.skillHubSources(backend, token, profile).featured
            else restClient.searchSkillHub(backend, token, profile, query.trim()).results
        }.onSuccess { results ->
            mutableState.value = mutableState.value.copy(skillHubResults = results, skillHubLoading = false)
        }.onFailure(::fail)
    }

    suspend fun reviewSkill(identifier: String) {
        val (backend, token) = activeCredentials()
        val profile = mutableState.value.activeProfile
        mutableState.value = mutableState.value.copy(skillHubLoading = true, skillHubReview = null, error = null)
        runCatching {
            SkillHubReview(
                preview = restClient.previewSkillHub(backend, token, profile, identifier),
                scan = restClient.scanSkillHub(backend, token, profile, identifier),
            )
        }.onSuccess { review ->
            require(review.preview.identifier == review.scan.identifier) { "Hermes returned mismatched skill review data" }
            mutableState.value = mutableState.value.copy(skillHubReview = review, skillHubLoading = false)
        }.onFailure(::fail)
    }

    fun closeSkillReview() {
        mutableState.value = mutableState.value.copy(skillHubReview = null)
    }

    suspend fun installReviewedSkill() {
        try {
            val review = mutableState.value.skillHubReview ?: error("Review and scan a skill before installing it")
            require(review.scan.policy != "block") { review.scan.policyReason ?: "Hermes blocked this skill" }
            val (backend, token) = activeCredentials()
            val started = restClient.installSkillHub(backend, token, mutableState.value.activeProfile, review.preview.identifier)
            mutableState.value = mutableState.value.copy(skillHubReview = null)
            pollSkillAction(started.name, started.pid)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            fail(error)
        }
    }

    suspend fun uninstallSkill(name: String) {
        runCatching {
            val (backend, token) = activeCredentials()
            restClient.uninstallSkillHub(backend, token, mutableState.value.activeProfile, name)
        }.onSuccess { pollSkillAction(it.name, it.pid) }.onFailure(::fail)
    }

    suspend fun updateSkills() {
        runCatching {
            val (backend, token) = activeCredentials()
            restClient.updateSkillsHub(backend, token, mutableState.value.activeProfile)
        }.onSuccess { pollSkillAction(it.name, it.pid) }.onFailure(::fail)
    }

    private suspend fun pollSkillAction(name: String, pid: Long) {
        val (backend, token) = activeCredentials()
        mutableState.value = mutableState.value.copy(skillAction = DiagnosticRunState(running = true, pid = pid), error = null)
        try {
            repeat(DIAGNOSTIC_POLL_LIMIT) {
                val status = restClient.actionStatus(backend, token, name)
                mutableState.value = mutableState.value.copy(
                    skillAction = DiagnosticRunState(
                        running = status.running,
                        pid = status.pid ?: pid,
                        exitCode = status.exitCode,
                        lines = DiagnosticRedactor.redactLines(status.lines),
                    ),
                )
                if (!status.running) {
                    refreshSkills()
                    return
                }
                delay(DIAGNOSTIC_POLL_INTERVAL_MILLIS)
            }
            mutableState.value = mutableState.value.copy(
                skillAction = mutableState.value.skillAction?.copy(
                    running = false,
                    timedOut = true,
                    error = "Status polling stopped after two minutes. The server action may still be running.",
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = mutableState.value.copy(
                skillAction = mutableState.value.skillAction?.copy(
                    running = false,
                    error = DiagnosticRedactor.redact(error.message.orEmpty()).ifBlank { "Skill action failed" },
                ),
            )
        }
    }

    suspend fun refreshCronJobs() {
        val (backend, token) = activeCredentials()
        mutableState.value = mutableState.value.copy(managementLoading = true, error = null)
        runCatching { restClient.cronJobs(backend, token) }
            .onSuccess { jobs ->
                mutableState.value = mutableState.value.copy(cronJobs = jobs, managementLoading = false)
            }
            .onFailure(::fail)
    }

    suspend fun refreshCronRuns(jobId: String) {
        val (backend, token) = activeCredentials()
        mutableState.value = mutableState.value.copy(managementLoading = true, error = null)
        runCatching { restClient.cronRuns(backend, token, jobId).runs }
            .onSuccess { runs ->
                mutableState.value = mutableState.value.copy(
                    cronRuns = mutableState.value.cronRuns + (jobId to runs),
                    managementLoading = false,
                    error = null,
                )
            }
            .onFailure(::fail)
    }

    suspend fun setCronEnabled(jobId: String, enabled: Boolean) {
        val (backend, token) = activeCredentials()
        runCatching { restClient.setCronEnabled(backend, token, jobId, enabled) }
            .onSuccess(::replaceCronJob)
            .onFailure(::fail)
    }

    suspend fun triggerCron(jobId: String) {
        val (backend, token) = activeCredentials()
        runCatching { restClient.triggerCron(backend, token, jobId) }
            .onSuccess(::replaceCronJob)
            .onFailure(::fail)
    }

    suspend fun createCron(name: String, prompt: String, schedule: String, deliver: String) {
        val cleanPrompt = prompt.trim()
        val cleanSchedule = schedule.trim()
        require(cleanPrompt.isNotEmpty() && cleanSchedule.isNotEmpty()) { "Cron prompt and schedule are required" }
        val (backend, token) = activeCredentials()
        runCatching {
            restClient.createCron(
                backend,
                token,
                CronJobCreatePayload(
                    name = name.trim().takeIf(String::isNotEmpty),
                    prompt = cleanPrompt,
                    schedule = cleanSchedule,
                    deliver = deliver.trim().takeIf(String::isNotEmpty),
                ),
            )
        }.onSuccess(::replaceCronJob).onFailure(::fail)
    }

    suspend fun updateCron(jobId: String, name: String, prompt: String, schedule: String, deliver: String) {
        val cleanPrompt = prompt.trim()
        val cleanSchedule = schedule.trim()
        require(cleanPrompt.isNotEmpty() && cleanSchedule.isNotEmpty()) { "Cron prompt and schedule are required" }
        val (backend, token) = activeCredentials()
        runCatching {
            restClient.updateCron(
                backend,
                token,
                jobId,
                CronJobUpdates(
                    name = name.trim(),
                    prompt = cleanPrompt,
                    schedule = cleanSchedule,
                    deliver = deliver.trim(),
                ),
            )
        }.onSuccess(::replaceCronJob).onFailure(::fail)
    }

    suspend fun deleteCron(jobId: String) {
        val (backend, token) = activeCredentials()
        runCatching { restClient.deleteCron(backend, token, jobId) }
            .onSuccess {
                mutableState.value = mutableState.value.copy(
                    cronJobs = mutableState.value.cronJobs.filterNot { it.id == jobId },
                    cronRuns = mutableState.value.cronRuns - jobId,
                    error = null,
                )
            }
            .onFailure(::fail)
    }

    suspend fun refreshProfiles() {
        val (backend, token) = activeCredentials()
        mutableState.value = mutableState.value.copy(managementLoading = true, error = null)
        val result = runCatching {
            restClient.profiles(backend, token) to restClient.activeProfile(backend, token)
        }
        result.onSuccess { (profiles, active) ->
            mutableState.value = mutableState.value.copy(
                profiles = profiles.profiles.sortedWith(compareByDescending<ProfileInfo> { it.isDefault }.thenBy { it.name }),
                activeProfile = active.active,
                currentProfile = active.current,
                managementLoading = false,
                error = null,
            )
        }.onFailure(::fail)
    }

    suspend fun entryAuthoritySnapshot(includeCronJobs: Boolean): EntryAuthoritySnapshot? = try {
        val (backend, token) = activeCredentials()
        val profiles = restClient.profiles(backend, token)
        val active = restClient.activeProfile(backend, token)
        EntryAuthoritySnapshot(
            profileIds = buildSet {
                profiles.profiles.mapTo(this) { it.name }
                add(active.active)
                add(active.current)
            },
            cronJobIds = if (includeCronJobs) {
                restClient.cronJobs(backend, token).mapTo(mutableSetOf()) { it.id }
            } else {
                emptySet()
            },
        )
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        fail(error)
        null
    }

    suspend fun createProfile(name: String, cloneFrom: String, cloneAll: Boolean, noSkills: Boolean) {
        val cleanName = name.trim()
        require(cleanName.isNotEmpty()) { "Profile name is required" }
        val (backend, token) = activeCredentials()
        val result = runCatching {
            restClient.createProfile(
                backend,
                token,
                ProfileCreatePayload(
                    name = cleanName,
                    cloneFrom = cloneFrom.trim().takeIf(String::isNotEmpty),
                    cloneAll = cloneAll,
                    noSkills = noSkills,
                ),
            )
        }
        if (result.isSuccess) {
            refreshProfiles()
            refreshSessions()
        } else {
            fail(requireNotNull(result.exceptionOrNull()))
        }
    }

    suspend fun renameProfile(name: String, newName: String) {
        val cleanName = newName.trim()
        require(cleanName.isNotEmpty()) { "New profile name is required" }
        val (backend, token) = activeCredentials()
        val result = runCatching { restClient.renameProfile(backend, token, name, cleanName) }
        if (result.isSuccess) {
            refreshProfiles()
            refreshSessions()
        } else {
            fail(requireNotNull(result.exceptionOrNull()))
        }
    }

    suspend fun setActiveProfile(name: String) {
        val (backend, token) = activeCredentials()
        val hasActiveSession = mutableState.value.activeStoredSession != null
        val attachmentProfile = mutableState.value.activeProfile
        val result = runCatching { restClient.setActiveProfile(backend, token, name) }
        if (result.isSuccess) {
            if (!hasActiveSession && attachmentProfile != name.trim()) invalidatePendingAttachments()
            providerOAuthPollJob?.cancel()
            providerOAuthPollJob = null
            mutableState.value = mutableState.value.copy(
                providerOptions = null,
                providerEnv = emptyMap(),
                oauthProviders = emptyList(),
                providerAccountsSupported = false,
                providerOAuthSession = null,
                providerNotice = null,
                serverConfigProfile = null,
                serverConfig = ServerConfigSnapshot(),
                serverConfigNotice = null,
                serverConfigError = null,
            )
            refreshProfiles()
        } else {
            fail(requireNotNull(result.exceptionOrNull()))
        }
    }

    suspend fun deleteProfile(name: String) {
        val profile = mutableState.value.profiles.firstOrNull { it.name == name } ?: return
        require(!profile.isDefault && name != mutableState.value.currentProfile) {
            "The default or currently running Hermes profile cannot be deleted"
        }
        val (backend, token) = activeCredentials()
        val result = runCatching { restClient.deleteProfile(backend, token, name) }
        if (result.isSuccess) {
            refreshProfiles()
            refreshSessions()
        } else {
            fail(requireNotNull(result.exceptionOrNull()))
        }
    }

    suspend fun profileIdentity(name: String): ProfileIdentityDraft {
        val profile = mutableState.value.profiles.firstOrNull { it.name == name }
            ?: throw IllegalArgumentException("Unknown Hermes profile")
        val (backend, token) = activeCredentials()
        val soul = restClient.profileSoul(backend, token, name)
        val setup = restClient.profileSetupCommand(backend, token, name)
        return ProfileIdentityDraft(
            soul = soul.content,
            setupCommand = setup.command.take(4_096),
            provider = profile.provider.orEmpty(),
            model = profile.model.orEmpty(),
        )
    }

    suspend fun saveProfileSoul(name: String, content: String) {
        require(mutableState.value.profiles.any { it.name == name }) { "Unknown Hermes profile" }
        val (backend, token) = activeCredentials()
        restClient.updateProfileSoul(backend, token, name, content)
    }

    suspend fun saveProfileModel(name: String, provider: String, model: String) {
        require(mutableState.value.profiles.any { it.name == name }) { "Unknown Hermes profile" }
        val (backend, token) = activeCredentials()
        restClient.updateProfileModel(backend, token, name, provider, model)
        refreshProfiles()
    }

    suspend fun refreshStarmap(profile: String) {
        val selectedProfile = profile.normalizedProfile()
        val (backend, token) = activeCredentials()
        mutableState.value = mutableState.value.copy(
            starmapProfile = selectedProfile,
            starmapLoading = true,
            starmapNotice = null,
            starmapError = null,
        )
        try {
            val graph = restClient.learningGraph(backend, token, selectedProfile)
            if (mutableState.value.backend?.id == backend.id && mutableState.value.starmapProfile == selectedProfile) {
                mutableState.value = mutableState.value.copy(starmap = graph, starmapLoading = false)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = mutableState.value.copy(
                starmapLoading = false,
                starmapError = error.message ?: "Hermes could not load the learning graph",
            )
        }
    }

    suspend fun loadLearningNode(profile: String, id: String) {
        val selectedProfile = profile.normalizedProfile()
        val (backend, token) = activeCredentials()
        mutableState.value = mutableState.value.copy(
            starmapProfile = selectedProfile,
            starmapNodeId = id,
            starmapNode = null,
            starmapLoading = true,
            starmapNotice = null,
            starmapError = null,
        )
        try {
            val detail = restClient.learningNode(backend, token, selectedProfile, id)
            if (
                mutableState.value.backend?.id == backend.id &&
                mutableState.value.starmapProfile == selectedProfile &&
                mutableState.value.starmapNodeId == id
            ) {
                mutableState.value = mutableState.value.copy(starmapNode = detail, starmapLoading = false)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = mutableState.value.copy(
                starmapLoading = false,
                starmapError = error.message ?: "Hermes could not load this learning node",
            )
        }
    }

    suspend fun updateLearningNode(profile: String, id: String, content: String) {
        val selectedProfile = profile.normalizedProfile()
        val (backend, token) = activeCredentials()
        mutableState.value = mutableState.value.copy(starmapLoading = true, starmapNotice = null, starmapError = null)
        try {
            restClient.updateLearningNode(backend, token, selectedProfile, id, content)
            mutableState.value = mutableState.value.copy(
                starmapNode = mutableState.value.starmapNode?.copy(content = content),
                starmapLoading = false,
            )
            refreshStarmap(selectedProfile)
            mutableState.value = mutableState.value.copy(starmapNotice = "Learning node updated")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = mutableState.value.copy(
                starmapLoading = false,
                starmapError = error.message ?: "Hermes could not update this learning node",
            )
        }
    }

    suspend fun deleteLearningNode(profile: String, id: String) {
        val selectedProfile = profile.normalizedProfile()
        val (backend, token) = activeCredentials()
        mutableState.value = mutableState.value.copy(starmapLoading = true, starmapNotice = null, starmapError = null)
        try {
            restClient.deleteLearningNode(backend, token, selectedProfile, id)
            mutableState.value = mutableState.value.copy(
                starmapNodeId = null,
                starmapNode = null,
                starmapLoading = false,
            )
            refreshStarmap(selectedProfile)
            mutableState.value = mutableState.value.copy(starmapNotice = "Learning node removed")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = mutableState.value.copy(
                starmapLoading = false,
                starmapError = error.message ?: "Hermes could not remove this learning node",
            )
        }
    }

    fun closeLearningNode() {
        mutableState.value = mutableState.value.copy(starmapNodeId = null, starmapNode = null, starmapError = null)
    }

    suspend fun runDiagnostic(action: DiagnosticAction) {
        val (backend, token) = activeCredentials()
        updateDiagnostic(action, DiagnosticRunState(running = true))
        try {
            val started = when (action) {
                DiagnosticAction.DOCTOR -> restClient.runDoctor(backend, token)
                DiagnosticAction.SECURITY_AUDIT -> restClient.runSecurityAudit(backend, token)
            }
            require(started.ok && started.name == action.wireName) {
                "Hermes did not start the requested diagnostic action"
            }
            updateDiagnostic(action, DiagnosticRunState(running = true, pid = started.pid))
            repeat(DIAGNOSTIC_POLL_LIMIT) {
                val status = restClient.actionStatus(backend, token, action.wireName)
                updateDiagnostic(
                    action,
                    DiagnosticRunState(
                        running = status.running,
                        pid = status.pid ?: started.pid,
                        exitCode = status.exitCode,
                        lines = DiagnosticRedactor.redactLines(status.lines),
                    ),
                )
                if (!status.running) return
                delay(DIAGNOSTIC_POLL_INTERVAL_MILLIS)
            }
            val current = mutableState.value.diagnostics[action] ?: DiagnosticRunState()
            updateDiagnostic(
                action,
                current.copy(
                    running = false,
                    timedOut = true,
                    error = "Status polling stopped after two minutes. The server action may still be running.",
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            val current = mutableState.value.diagnostics[action] ?: DiagnosticRunState()
            updateDiagnostic(
                action,
                current.copy(
                    running = false,
                    error = DiagnosticRedactor.redact(error.message.orEmpty()).ifBlank { "Diagnostic action failed" },
                ),
            )
        }
    }

    suspend fun refreshHostMaintenance(force: Boolean = false) {
        val (backend, token) = activeCredentials()
        mutableState.value = mutableState.value.copy(hostMaintenanceLoading = true, hostMaintenanceError = null)
        val errors = mutableListOf<String>()
        val update = try {
            restClient.hermesUpdateCheck(backend, token, force)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            errors += error.message.orEmpty()
            null
        }
        val logs = try {
            restClient.hostLogs(backend, token).lines
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            errors += error.message.orEmpty()
            null
        }
        if (mutableState.value.backend?.id == backend.id) {
            mutableState.value = mutableState.value.copy(
                hostUpdate = update ?: mutableState.value.hostUpdate,
                hostLogs = logs?.let(DiagnosticRedactor::redactLines)?.takeLast(200) ?: mutableState.value.hostLogs,
                hostMaintenanceLoading = false,
                hostMaintenanceError = DiagnosticRedactor.redact(errors.filter(String::isNotBlank).joinToString(" / "))
                    .ifBlank { null },
            )
        }
    }

    suspend fun refreshProviders(refresh: Boolean = false) {
        val (backend, token) = activeCredentials()
        mutableState.value = mutableState.value.copy(providersLoading = true, error = null)
        runCatching {
            val active = restClient.activeProfile(backend, token)
            val options = restClient.globalModelOptions(backend, token, active.active, refresh)
            val oauthProviders = try {
                restClient.oauthProviders(backend, token, active.active)
            } catch (error: com.nousresearch.hermes.network.HermesHttpException) {
                if (error.statusCode == 404) null else throw error
            }
            ProviderRefreshSnapshot(
                activeProfile = active.active,
                currentProfile = active.current,
                options = options,
                env = restClient.envVars(backend, token, active.active),
                oauthProviders = oauthProviders.orEmpty(),
                providerAccountsSupported = oauthProviders != null,
            )
        }.onSuccess { snapshot ->
            mutableState.value = mutableState.value.copy(
                activeProfile = snapshot.activeProfile,
                currentProfile = snapshot.currentProfile,
                providerOptions = snapshot.options,
                providerEnv = snapshot.env.filterValues { !it.channelManaged && (it.category == "provider" || it.provider.isNotBlank()) },
                oauthProviders = snapshot.oauthProviders,
                providerAccountsSupported = snapshot.providerAccountsSupported,
                providersLoading = false,
                error = null,
            )
        }.onFailure(::fail)
    }

    suspend fun startProviderOAuth(providerId: String) {
        val provider = mutableState.value.oauthProviders.firstOrNull { it.id == providerId }
            ?: error("Hermes did not advertise this provider account")
        if (provider.status.loggedIn || provider.flow !in setOf("pkce", "device_code")) {
            mutableState.value = mutableState.value.copy(
                error = if (provider.status.loggedIn) {
                    "This provider account is already connected."
                } else {
                    "Hermes advertised a provider sign-in flow this Android version does not support."
                },
            )
            return
        }
        providerOAuthPollJob?.cancelAndJoin()
        providerOAuthPollJob = null
        mutableState.value = mutableState.value.copy(
            providersLoading = true,
            providerOAuthSession = null,
            providerNotice = null,
            error = null,
        )
        val (backend, token) = activeCredentials()
        val profile = mutableState.value.activeProfile
        val started = runCatching { restClient.startProviderOAuth(backend, token, profile, provider.id) }
            .getOrElse { error -> fail(error); return }
        val session = runCatching {
            require(started.flow == provider.flow) { "Hermes returned a different provider login flow" }
            require(started.sessionId.isNotBlank() && started.sessionId.length <= MAX_OAUTH_SESSION_ID_CHARACTERS) {
                "Hermes returned an invalid provider login session"
            }
            val browserUrl = when (started.flow) {
                "device_code" -> started.verificationUrl
                "pkce" -> started.authUrl
                else -> null
            }.orEmpty()
            require(browserUrl.isNotBlank()) { "Hermes did not return a provider sign-in URL" }
            if (started.flow == "device_code") require(!started.userCode.isNullOrBlank()) {
                "Hermes did not return a provider authorization code"
            }
            val expiresInSeconds = started.expiresIn.coerceIn(1L, MAX_OAUTH_SESSION_SECONDS)
            ProviderOAuthSession(
                providerId = provider.id,
                providerName = provider.name,
                flow = started.flow,
                sessionId = started.sessionId,
                profile = profile,
                browserUrl = browserUrl,
                userCode = started.userCode,
                expiresAtEpochMillis = System.currentTimeMillis() + expiresInSeconds * 1_000L,
                pollIntervalSeconds = (started.pollInterval ?: DEFAULT_OAUTH_POLL_SECONDS)
                    .coerceIn(MIN_OAUTH_POLL_SECONDS, MAX_OAUTH_POLL_SECONDS),
            )
        }.getOrElse { error -> fail(error); return }
        mutableState.value = mutableState.value.copy(
            providerOAuthSession = session,
            providersLoading = false,
            error = null,
        )
        if (session.flow == "device_code") {
            providerOAuthPollJob = scope.launch {
                pollProviderOAuthSession(backend, token, session)
            }
        }
    }

    suspend fun submitProviderOAuth(code: String) {
        val session = mutableState.value.providerOAuthSession ?: return
        require(session.flow == "pkce") { "This provider flow does not accept an authorization code" }
        require(session.profile == mutableState.value.activeProfile) { "The active Hermes profile changed during sign-in" }
        val clean = code.trim()
        require(clean.isNotEmpty() && clean.length <= MAX_OAUTH_CODE_CHARACTERS) {
            "Authorization code must be between 1 and $MAX_OAUTH_CODE_CHARACTERS characters"
        }
        mutableState.value = mutableState.value.copy(providersLoading = true, error = null)
        val (backend, token) = activeCredentials()
        val response = runCatching {
            restClient.submitProviderOAuth(backend, token, session.profile, session.providerId, session.sessionId, clean)
        }.getOrElse { error -> fail(error); return }
        if (response.ok && response.status == "approved") {
            mutableState.value = mutableState.value.copy(
                providerOAuthSession = null,
                providersLoading = false,
                providerNotice = "${session.providerName} connected to Hermes.",
                error = null,
            )
            refreshProviders(refresh = true)
        } else {
            mutableState.value = mutableState.value.copy(
                providersLoading = false,
                error = response.message?.takeIf(String::isNotBlank) ?: "Hermes could not complete provider sign-in.",
            )
        }
    }

    suspend fun cancelProviderOAuth() {
        val session = mutableState.value.providerOAuthSession ?: return
        providerOAuthPollJob?.cancelAndJoin()
        providerOAuthPollJob = null
        mutableState.value = mutableState.value.copy(providersLoading = true, error = null)
        val (backend, token) = activeCredentials()
        val result = runCatching { restClient.cancelProviderOAuth(backend, token, session.profile, session.sessionId) }
        if (result.getOrDefault(false)) {
            mutableState.value = mutableState.value.copy(
                providerOAuthSession = null,
                providersLoading = false,
                providerNotice = "Provider sign-in cancelled.",
                error = null,
            )
            return
        }
        val failure = result.exceptionOrNull()
        if (failure != null) fail(failure) else {
            mutableState.value = mutableState.value.copy(
                providersLoading = false,
                error = "Hermes did not cancel this provider sign-in session.",
            )
        }
        if (session.flow == "device_code" && mutableState.value.backend?.id == backend.id) {
            providerOAuthPollJob = scope.launch { pollProviderOAuthSession(backend, token, session) }
        }
    }

    suspend fun disconnectProviderOAuth(providerId: String) {
        val provider = mutableState.value.oauthProviders.firstOrNull { it.id == providerId }
            ?: error("Hermes did not advertise this provider account")
        require(provider.status.loggedIn && provider.disconnectable) {
            provider.disconnectHint ?: "Hermes did not advertise automatic disconnect for this provider"
        }
        mutableState.value = mutableState.value.copy(providersLoading = true, providerNotice = null, error = null)
        val (backend, token) = activeCredentials()
        runCatching {
            check(restClient.disconnectProviderOAuth(backend, token, mutableState.value.activeProfile, provider.id)) {
                "Hermes did not disconnect this provider"
            }
        }.onSuccess {
            mutableState.value = mutableState.value.copy(providerNotice = "${provider.name} disconnected from Hermes.")
            refreshProviders(refresh = true)
        }.onFailure(::fail)
    }

    private suspend fun pollProviderOAuthSession(
        backend: BackendConfig,
        token: String,
        session: ProviderOAuthSession,
    ) {
        while (System.currentTimeMillis() < session.expiresAtEpochMillis) {
            delay(session.pollIntervalSeconds * 1_000L)
            if (mutableState.value.providerOAuthSession != session || mutableState.value.backend?.id != backend.id) return
            val result = try {
                restClient.pollProviderOAuth(backend, token, session.profile, session.providerId, session.sessionId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                mutableState.value = mutableState.value.copy(providerOAuthSession = null)
                fail(error)
                return
            }
            if (result.sessionId != session.sessionId) {
                mutableState.value = mutableState.value.copy(
                    providerOAuthSession = null,
                    error = "Hermes returned a different provider login session.",
                )
                return
            }
            when (result.status) {
                "pending" -> Unit
                "approved" -> {
                    mutableState.value = mutableState.value.copy(
                        providerOAuthSession = null,
                        providersLoading = false,
                        providerNotice = "${session.providerName} connected to Hermes.",
                        error = null,
                    )
                    refreshProviders(refresh = true)
                    return
                }
                "denied", "expired", "error" -> {
                    mutableState.value = mutableState.value.copy(
                        providerOAuthSession = null,
                        providersLoading = false,
                        error = result.errorMessage?.takeIf(String::isNotBlank)
                            ?: "Provider sign-in ${result.status}.",
                    )
                    return
                }
                else -> {
                    mutableState.value = mutableState.value.copy(
                        providerOAuthSession = null,
                        providersLoading = false,
                        error = "Hermes returned an unsupported provider sign-in status.",
                    )
                    return
                }
            }
        }
        if (mutableState.value.providerOAuthSession == session) {
            mutableState.value = mutableState.value.copy(
                providerOAuthSession = null,
                providersLoading = false,
                error = "Provider sign-in expired.",
            )
        }
    }

    suspend fun saveProviderSetting(key: String, value: String, apiKey: String = "") {
        val info = mutableState.value.providerEnv[key] ?: error("Hermes did not advertise this provider setting")
        require(!info.channelManaged && (info.category == "provider" || info.provider.isNotBlank())) {
            "This setting is not managed by the provider surface"
        }
        val clean = value.trim()
        require(clean.isNotEmpty() && clean.length <= 32_768) { "Provider value must be between 1 and 32,768 characters" }
        mutableState.value = mutableState.value.copy(providersLoading = true, providerNotice = null, error = null)
        val (backend, token) = activeCredentials()
        val validation = runCatching { restClient.validateProviderCredential(backend, token, key, clean, apiKey) }
            .getOrElse { error -> fail(error); return }
        if (!validation.ok) {
            mutableState.value = mutableState.value.copy(
                providersLoading = false,
                error = validation.message.ifBlank {
                    if (validation.reachable) "Hermes rejected this provider value." else "Hermes could not validate this provider value."
                },
            )
            return
        }
        runCatching { restClient.setEnvVar(backend, token, mutableState.value.activeProfile, key, clean) }
            .onSuccess {
                mutableState.value = mutableState.value.copy(
                    providerNotice = validation.message.ifBlank {
                        if (validation.reachable) "Provider credential validated and saved on Hermes." else "Provider setting saved; this provider has no live validation probe."
                    },
                )
                refreshProviders(refresh = true)
            }
            .onFailure(::fail)
    }

    suspend fun deleteProviderSetting(key: String) {
        val info = mutableState.value.providerEnv[key] ?: return
        require(!info.channelManaged && (info.category == "provider" || info.provider.isNotBlank())) {
            "This setting is not managed by the provider surface"
        }
        val (backend, token) = activeCredentials()
        mutableState.value = mutableState.value.copy(providersLoading = true, providerNotice = null, error = null)
        runCatching { restClient.deleteEnvVar(backend, token, mutableState.value.activeProfile, key) }
            .onSuccess {
                mutableState.value = mutableState.value.copy(providerNotice = "Provider setting removed from Hermes.")
                refreshProviders(refresh = true)
            }
            .onFailure(::fail)
    }

    suspend fun refreshMessaging() {
        val (backend, token) = activeCredentials()
        mutableState.value = mutableState.value.copy(messagingLoading = true, error = null)
        runCatching {
            val active = restClient.activeProfile(backend, token)
            active to restClient.messagingPlatforms(backend, token, active.active)
        }.onSuccess { (active, response) ->
            mutableState.value = mutableState.value.copy(
                activeProfile = active.active,
                currentProfile = active.current,
                messagingPlatforms = response.platforms,
                messagingTests = emptyMap(),
                messagingLoading = false,
                error = null,
            )
        }.onFailure(::fail)
    }

    suspend fun setMessagingEnabled(platformId: String, enabled: Boolean) {
        val platform = advertisedMessagingPlatform(platformId)
        val (backend, token) = activeCredentials()
        mutableState.value = mutableState.value.copy(messagingLoading = true, messagingNotice = null, error = null)
        runCatching {
            restClient.updateMessagingPlatform(
                backend,
                token,
                mutableState.value.activeProfile,
                platform.id,
                enabled = enabled,
            )
        }.onSuccess {
            mutableState.value = mutableState.value.copy(
                messagingNotice = "${platform.name} ${if (enabled) "enabled" else "disabled"}. Restart the Hermes messaging gateway to apply the change.",
            )
            refreshMessaging()
        }.onFailure(::fail)
    }

    suspend fun saveMessagingSettings(platformId: String, values: Map<String, String>) {
        val platform = advertisedMessagingPlatform(platformId)
        val allowed = platform.envVars.mapTo(mutableSetOf()) { it.key }
        val cleaned = values.mapValues { it.value.trim() }.filterValues(String::isNotEmpty)
        require(cleaned.isNotEmpty()) { "Enter at least one messaging setting" }
        require(cleaned.keys.all { it in allowed }) { "Hermes did not advertise one of these messaging settings" }
        require(cleaned.values.all { it.length <= MAX_MESSAGING_VALUE_CHARACTERS }) { "Messaging settings must not exceed 32,768 characters" }
        val (backend, token) = activeCredentials()
        mutableState.value = mutableState.value.copy(messagingLoading = true, messagingNotice = null, error = null)
        runCatching {
            restClient.updateMessagingPlatform(
                backend,
                token,
                mutableState.value.activeProfile,
                platform.id,
                env = cleaned,
            )
        }.onSuccess {
            mutableState.value = mutableState.value.copy(
                messagingNotice = "${platform.name} setup saved on Hermes. Restart the messaging gateway to reconnect it.",
            )
            refreshMessaging()
        }.onFailure(::fail)
    }

    suspend fun clearMessagingSetting(platformId: String, key: String) {
        val platform = advertisedMessagingPlatform(platformId)
        require(platform.envVars.any { it.key == key }) { "Hermes did not advertise this messaging setting" }
        val (backend, token) = activeCredentials()
        mutableState.value = mutableState.value.copy(messagingLoading = true, messagingNotice = null, error = null)
        runCatching {
            restClient.updateMessagingPlatform(
                backend,
                token,
                mutableState.value.activeProfile,
                platform.id,
                clearEnv = listOf(key),
            )
        }.onSuccess {
            mutableState.value = mutableState.value.copy(messagingNotice = "$key removed from ${platform.name} on Hermes.")
            refreshMessaging()
        }.onFailure(::fail)
    }

    suspend fun testMessagingPlatform(platformId: String) {
        val platform = advertisedMessagingPlatform(platformId)
        val (backend, token) = activeCredentials()
        mutableState.value = mutableState.value.copy(messagingLoading = true, error = null)
        runCatching {
            restClient.testMessagingPlatform(backend, token, mutableState.value.activeProfile, platform.id)
        }.onSuccess { result ->
            mutableState.value = mutableState.value.copy(
                messagingTests = mutableState.value.messagingTests + (platform.id to result),
                messagingLoading = false,
                error = null,
            )
        }.onFailure(::fail)
    }

    suspend fun restartMessagingGateway() {
        val (backend, token) = activeCredentials()
        val profile = mutableState.value.activeProfile
        mutableState.value = mutableState.value.copy(gatewayRestarting = true, messagingNotice = null, error = null)
        try {
            val started = restClient.restartGateway(backend, token, profile)
            require(started.ok && started.name == "gateway-restart") { "Hermes did not start the messaging gateway restart" }
            repeat(GATEWAY_RESTART_POLL_LIMIT) {
                delay(GATEWAY_RESTART_POLL_INTERVAL_MILLIS)
                val status = restClient.actionStatus(backend, token, started.name, lines = 100, profile = profile)
                if (!status.running) {
                    require(status.exitCode == null || status.exitCode == 0) { "Hermes messaging gateway restart failed" }
                    mutableState.value = mutableState.value.copy(
                        gatewayRestarting = false,
                        messagingNotice = "Hermes messaging gateway restarted for $profile.",
                    )
                    refreshMessaging()
                    return
                }
            }
            mutableState.value = mutableState.value.copy(
                gatewayRestarting = false,
                error = "Gateway restart is still running after the Android polling window. Refresh messaging status before retrying.",
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            mutableState.value = mutableState.value.copy(gatewayRestarting = false)
            fail(error)
        }
    }

    suspend fun refreshMcp() {
        if (mutableState.value.mcpLoading) return
        val (backend, token) = activeCredentials()
        mutableState.value = mutableState.value.copy(mcpLoading = true, mcpError = null)
        runCatching {
            val active = restClient.activeProfile(backend, token)
            val servers = restClient.mcpServers(backend, token, active.active)
            val catalog = restClient.mcpCatalog(backend, token, active.active)
            Triple(active, servers, catalog)
        }.onSuccess { (active, servers, catalog) ->
            val safeServers = servers.servers
                .filter { it.name.isNotBlank() && it.name.length <= MAX_MCP_NAME_CHARACTERS }
                .distinctBy { it.name }
            val safeCatalog = catalog.entries
                .filter { it.name.isNotBlank() && it.name.length <= MAX_MCP_NAME_CHARACTERS }
                .distinctBy { it.name }
            mutableState.value = mutableState.value.copy(
                activeProfile = active.active,
                currentProfile = active.current,
                mcpServers = safeServers,
                mcpCatalog = safeCatalog,
                mcpTests = mutableState.value.mcpTests.filterKeys { name ->
                    safeServers.any { it.name == name }
                },
                mcpLoading = false,
                mcpError = catalog.diagnostics.takeIf { it.isNotEmpty() }?.joinToString("\n") {
                    "${it.name}: ${it.message}"
                }?.let(DiagnosticRedactor::redact),
                error = null,
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            failMcp(error)
        }
    }

    suspend fun testMcpServer(name: String) {
        val server = advertisedMcpServer(name)
        val (backend, token) = activeCredentials()
        mutableState.value = mutableState.value.copy(mcpLoading = true, mcpError = null)
        runCatching {
            restClient.testMcpServer(backend, token, mutableState.value.activeProfile, server.name)
        }.onSuccess { result ->
            val safeResult = result.copy(error = result.error?.let(DiagnosticRedactor::redact))
            mutableState.value = mutableState.value.copy(
                mcpTests = mutableState.value.mcpTests + (server.name to safeResult),
                mcpLoading = false,
                mcpError = safeResult.error,
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            failMcp(error)
        }
    }

    suspend fun setMcpServerEnabled(name: String, enabled: Boolean) {
        val server = advertisedMcpServer(name)
        val (backend, token) = activeCredentials()
        val profile = mutableState.value.activeProfile
        mutableState.value = mutableState.value.copy(
            mcpLoading = true,
            mcpNotice = null,
            mcpError = null,
        )
        val saved = runCatching {
            restClient.setMcpServerEnabled(backend, token, profile, server.name, enabled).also {
                require(it.ok && it.name == server.name && it.enabled == enabled) {
                    "Hermes returned an inconsistent MCP server state"
                }
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            failMcp(error)
            return
        }
        if (mutableState.value.activeProfile != profile) {
            mutableState.value = mutableState.value.copy(mcpLoading = false)
            return
        }
        mutableState.value = mutableState.value.copy(
            mcpServers = mutableState.value.mcpServers.map {
                if (it.name == saved.name) it.copy(enabled = saved.enabled) else it
            },
            mcpTests = mutableState.value.mcpTests - saved.name,
            mcpLoading = false,
            mcpNotice = "${saved.name} ${if (saved.enabled) "enabled" else "disabled"} in Hermes configuration.",
        )
        reloadMcpAfterSaved(
            profile,
            "${saved.name} ${if (saved.enabled) "enabled" else "disabled"}",
        )
    }

    suspend fun removeMcpServer(name: String) {
        val server = runCatching { advertisedMcpServer(name) }.getOrElse {
            failMcp(it)
            return
        }
        val (backend, token) = activeCredentials()
        val profile = mutableState.value.activeProfile
        mutableState.value = mutableState.value.copy(mcpLoading = true, mcpNotice = null, mcpError = null)
        val removed = runCatching {
            restClient.removeMcpServer(backend, token, profile, server.name).also {
                require(it.ok) { "Hermes did not confirm MCP server removal" }
            }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            failMcp(error)
            return
        }
        if (!removed.ok || mutableState.value.activeProfile != profile) {
            mutableState.value = mutableState.value.copy(mcpLoading = false)
            return
        }
        mutableState.value = mutableState.value.copy(
            mcpServers = mutableState.value.mcpServers.filterNot { it.name == server.name },
            mcpTests = mutableState.value.mcpTests - server.name,
            mcpLoading = false,
            mcpNotice = "${server.name} removed from Hermes configuration.",
        )
        reloadMcpAfterSaved(profile, "${server.name} removed")
    }

    suspend fun installMcpCatalogEntry(name: String, env: Map<String, String>) {
        val (entry, cleanedEnv) = runCatching {
            advertisedMcpCatalogEntry(name).let { it to validateMcpInstall(it, env) }
        }.getOrElse {
            failMcp(it)
            return
        }
        val (backend, token) = activeCredentials()
        val profile = mutableState.value.activeProfile
        mutableState.value = mutableState.value.copy(mcpLoading = true, mcpNotice = null, mcpError = null)
        runCatching {
            val installed = restClient.installMcpCatalogEntry(backend, token, profile, entry.name, cleanedEnv)
            require(installed.ok && installed.name == entry.name) { "Hermes did not confirm the catalog install" }
            if (installed.background) {
                val action = requireNotNull(installed.action) { "Hermes omitted the MCP install action identity" }
                var completed = false
                var polls = 0
                while (polls < MCP_INSTALL_POLL_LIMIT) {
                    polls += 1
                    val status = restClient.actionStatus(backend, token, action, profile = profile)
                    if (!status.running) {
                        require(status.exitCode == 0) {
                            "Hermes MCP install process exited ${status.exitCode ?: "without a status"}"
                        }
                        completed = true
                        break
                    }
                    delay(MCP_INSTALL_POLL_INTERVAL_MILLIS)
                }
                require(completed) { "Hermes MCP install is still running after the Android polling window" }
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            failMcp(error)
            return
        }
        if (mutableState.value.activeProfile != profile) {
            mutableState.value = mutableState.value.copy(mcpLoading = false)
            return
        }
        mutableState.value = mutableState.value.copy(
            mcpLoading = false,
            mcpNotice = "${entry.name} installed in Hermes configuration.",
        )
        refreshMcp()
        reloadMcpAfterSaved(profile, "${entry.name} installed")
    }

    private suspend fun reloadMcpAfterSaved(profile: String, mutation: String) {

        val runtimeProfile = mutableState.value.activeStoredSession?.profile
            ?: mutableState.value.activeProfile.takeIf { mutableState.value.runtimeSessionId != null }
        val runtimeSessionId = mutableState.value.runtimeSessionId.takeIf { runtimeProfile == profile }
        if (runtimeSessionId == null && mutableState.value.currentProfile != profile) {
            mutableState.value = mutableState.value.copy(
                mcpNotice = "$mutation; Hermes will apply it when profile $profile next starts.",
            )
            return
        }

        runCatching {
            val response = gateway.request(
                "reload.mcp",
                buildJsonObject {
                    put("confirm", true)
                    runtimeSessionId?.let { put("session_id", it) }
                },
            ).let { json.decodeFromJsonElement(McpReloadResponse.serializer(), it) }
            require(response.status == "reloaded") {
                response.message ?: "Hermes did not confirm the MCP runtime reload"
            }
        }.onSuccess {
            mutableState.value = mutableState.value.copy(
                mcpNotice = "$mutation; the live MCP runtime was reloaded.",
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            mutableState.value = mutableState.value.copy(
                mcpError = DiagnosticRedactor.redact(
                    "The setting was saved, but the live MCP reload failed: ${error.message ?: "unknown gateway error"}",
                ),
            )
        }
    }

    suspend fun refreshBilling() = billingAccountMutex.withLock {
        refreshBillingLocked()
    }

    private suspend fun refreshBillingLocked() {
        if (mutableState.value.billingLoading) return
        mutableState.value = mutableState.value.copy(
            billingLoading = true,
            billingNotice = null,
            billingError = null,
            billingRecovery = BillingRecovery.NONE,
            billingRetryIntent = null,
        )
        val backendId: String
        try {
            backendId = activeCredentials().first.id
        } catch (cancelled: CancellationException) {
            mutableState.value = mutableState.value.copy(billingLoading = false)
            throw cancelled
        } catch (error: Throwable) {
            failBilling(error)
            return
        }
        val billing = runCatching {
            gateway.request("billing.state", buildJsonObject {})
                .let { json.decodeFromJsonElement(BillingStateResponse.serializer(), it) }
        }.getOrElse { error ->
            if (mutableState.value.backend?.id != backendId) return
            if (error is CancellationException) {
                mutableState.value = mutableState.value.copy(billingLoading = false)
                throw error
            }
            if (error is HermesRpcException && error.rpcCode == -32601) {
                mutableState.value = mutableState.value.copy(
                    billingSupported = false,
                    billingLoading = false,
                    billingError = "Billing requires a newer Hermes gateway.",
                )
            } else {
                failBilling(error)
            }
            return
        }
        var subscriptionUnavailable = false
        val subscription = try {
            gateway.request("subscription.state", buildJsonObject {})
                .let { json.decodeFromJsonElement(SubscriptionStateResponse.serializer(), it) }
        } catch (cancelled: CancellationException) {
            mutableState.value = mutableState.value.copy(billingLoading = false)
            throw cancelled
        } catch (_: Throwable) {
            subscriptionUnavailable = true
            null
        }
        if (mutableState.value.backend?.id != backendId) return
        if (!billing.ok) {
            mutableState.value = mutableState.value.copy(
                billingState = billing,
                subscriptionState = null,
                billingSupported = true,
                billingLoading = false,
                billingPortalUrl = billing.portalUrl,
            )
            applyBillingRefusal(billing.error, billing.message, billing.portalUrl)
            return
        }
        mutableState.value = mutableState.value.copy(
            billingState = billing,
            subscriptionState = subscription?.takeIf { it.ok },
            billingSupported = true,
            billingLoading = false,
            billingNotice = if (subscriptionUnavailable || subscription?.ok == false) {
                "Subscription details are unavailable. Portal management remains available."
            } else null,
            billingError = null,
            billingPortalUrl = billing.portalUrl ?: subscription?.portalUrl,
        )
    }

    suspend fun chargeBillingCredits(rawAmount: String) = billingAccountMutex.withLock {
        try {
            chargeBillingCreditsLocked(rawAmount)
        } catch (cancelled: CancellationException) {
            val pending = billingIdempotencyKey != null || mutableState.value.billingChargeUnconfirmed
            mutableState.value = mutableState.value.copy(
                billingBusy = false,
                billingChargeUnconfirmed = pending,
                billingNotice = null,
                billingError = if (pending) {
                    "Charge outcome is unconfirmed after interruption. Check your balance before retrying."
                } else null,
            )
            throw cancelled
        } catch (error: Throwable) {
            failBilling(error, billingIdempotencyAmount?.let(BillingRetryIntent::Charge))
        }
    }

    private suspend fun chargeBillingCreditsLocked(rawAmount: String) {
        if (mutableState.value.billingBusy) return
        if (
            mutableState.value.billingChargeUnconfirmed &&
            (billingIdempotencyAmount != rawAmount.trim() || billingIdempotencyKey == null)
        ) return
        mutableState.value = mutableState.value.copy(
            billingBusy = true,
            billingNotice = null,
            billingError = null,
            billingRecovery = BillingRecovery.NONE,
        )
        val backendId: String
        val billing: BillingStateResponse
        val amount: String
        try {
            backendId = activeCredentials().first.id
            billing = requireNotNull(mutableState.value.billingState) { "Load billing before buying credits" }
            require(billing.loggedIn && billing.isAdmin && billing.canCharge && billing.cliBillingEnabled && billing.card != null) {
                "This account cannot buy credits from Hermes Android"
            }
            amount = validateBillingAmount(rawAmount, billing.minUsd, billing.maxUsd)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failBilling(error)
            return
        }
        val reusePendingIntent = billingIdempotencyBackendId == backendId && billingIdempotencyAmount == amount
        val key = if (reusePendingIntent) {
            billingIdempotencyKey ?: UUID.randomUUID().toString()
        } else {
            UUID.randomUUID().toString()
        }
        billingIdempotencyAmount = amount
        billingIdempotencyKey = key
        billingIdempotencyBackendId = backendId
        val settlementDeadline = billingSettlementDeadlineEpochMillis
            ?.takeIf { reusePendingIntent }
            ?: (System.currentTimeMillis() + BILLING_SETTLEMENT_CAP_MILLIS)
        billingSettlementDeadlineEpochMillis = settlementDeadline
        try {
            billingPendingChargeStore.put(
                PendingBillingCharge(
                    backendId = backendId,
                    amountUsd = amount,
                    idempotencyKey = key,
                    settlementDeadlineEpochMillis = settlementDeadline,
                    portalUrl = billing.portalUrl,
                ),
            )
        } catch (cancelled: CancellationException) {
            clearBillingIdempotency()
            throw cancelled
        } catch (error: Throwable) {
            clearBillingIdempotency()
            failBilling(error)
            return
        }
        mutableState.value = mutableState.value.copy(billingChargeUnconfirmed = false)
        mutableState.value = mutableState.value.copy(
            billingBusy = true,
            billingNotice = "Processing credit purchase and checking settlement…",
            billingError = null,
            billingRecovery = BillingRecovery.NONE,
            billingPortalUrl = billing.portalUrl,
            billingRetryIntent = BillingRetryIntent.Charge(amount),
        )
        val accepted = runCatching {
            gateway.request(
                "billing.charge",
                billingChargeParams(amount, key),
            ).let { json.decodeFromJsonElement(BillingChargeResponse.serializer(), it) }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            mutableState.value = mutableState.value.copy(billingChargeUnconfirmed = true)
            failBilling(error, BillingRetryIntent.Charge(amount))
            return
        }
        if (!accepted.ok) {
            billingPendingChargeStore.remove(backendId)
            applyBillingRefusal(
                code = accepted.error,
                message = accepted.message,
                portalUrl = accepted.portalUrl ?: billing.portalUrl,
                retryIntent = BillingRetryIntent.Charge(amount),
                actor = accepted.actor,
                payload = accepted.payload,
            )
            return
        }
        clearBillingIdempotency()
        mutableState.value = mutableState.value.copy(
            billingRetryIntent = null,
            billingChargeUnconfirmed = true,
        )
        val chargeId = accepted.chargeId
        if (chargeId.isNullOrBlank()) {
            mutableState.value = mutableState.value.copy(
                billingBusy = false,
                billingNotice = null,
                billingError = "Hermes accepted the charge but did not return a charge id. Check your balance before another purchase.",
            )
            return
        }
        val portalUrl = accepted.portalUrl ?: billing.portalUrl
        billingPendingChargeStore.put(
            PendingBillingCharge(
                backendId = backendId,
                amountUsd = amount,
                idempotencyKey = key,
                settlementDeadlineEpochMillis = settlementDeadline,
                chargeId = chargeId,
                portalUrl = portalUrl,
            ),
        )
        pollBillingCharge(backendId, chargeId, portalUrl, settlementDeadline)
    }

    private suspend fun pollBillingCharge(
        backendId: String,
        chargeId: String,
        portalUrl: String?,
        settlementDeadlineEpochMillis: Long,
    ) {
        while (System.currentTimeMillis() < settlementDeadlineEpochMillis) {
            val status = runCatching {
                gateway.request(
                    "billing.charge_status",
                    billingChargeStatusParams(chargeId),
                ).let { json.decodeFromJsonElement(BillingChargeStatusResponse.serializer(), it) }
            }.getOrElse { error ->
                if (error is CancellationException) throw error
                mutableState.value = mutableState.value.copy(
                    billingBusy = false,
                    billingNotice = null,
                    billingError = "Charge outcome is unconfirmed. Check your balance or portal before retrying.",
                    billingPortalUrl = portalUrl,
                )
                return
            }
            if (!status.ok) {
                val policy = BillingPolicy.forCode(status.error)
                if (policy.ambiguousMidPoll) {
                    mutableState.value = mutableState.value.copy(
                        billingBusy = false,
                        billingChargeUnconfirmed = true,
                        billingNotice = null,
                        billingError = "${billingRefusalMessage(status.error, status.message, payload = status.payload)} Charge outcome is unconfirmed, so check your balance before retrying.",
                        billingRecovery = policy.recovery,
                        billingPortalUrl = status.portalUrl ?: portalUrl,
                    )
                    return
                }
                if (policy.recovery == BillingRecovery.RETRY) {
                    val retrySeconds = (status.retryAfter ?: 5L).coerceIn(0L, BILLING_MAX_RETRY_SECONDS)
                    delay(retrySeconds * 1_000L)
                    continue
                }
                mutableState.value = mutableState.value.copy(billingChargeUnconfirmed = false)
                billingPendingChargeStore.remove(backendId)
                applyBillingRefusal(
                    status.error,
                    status.message,
                    status.portalUrl ?: portalUrl,
                    payload = status.payload,
                )
                return
            }
            when (status.status) {
                "settled" -> {
                    val notice = status.amountUsd?.let { "$$it added. Balance is refreshing." } ?: "Credits added. Balance is refreshing."
                    refreshBillingLocked()
                    mutableState.value = mutableState.value.copy(
                        billingBusy = false,
                        billingChargeUnconfirmed = false,
                        billingNotice = notice,
                    )
                    clearBillingIdempotency()
                    billingPendingChargeStore.remove(backendId)
                    return
                }
                "failed" -> {
                    mutableState.value = mutableState.value.copy(
                        billingBusy = false,
                        billingChargeUnconfirmed = false,
                        billingNotice = null,
                        billingError = billingChargeFailureMessage(status.reason),
                        billingPortalUrl = status.portalUrl ?: portalUrl,
                    )
                    clearBillingIdempotency()
                    billingPendingChargeStore.remove(backendId)
                    return
                }
            }
            delay(BILLING_SETTLEMENT_POLL_MILLIS)
        }
        mutableState.value = mutableState.value.copy(
            billingBusy = false,
            billingNotice = null,
            billingError = "Charge may still settle. Check the portal before retrying.",
            billingPortalUrl = portalUrl,
        )
    }

    suspend fun updateBillingAutoReload(enabled: Boolean, rawThreshold: String, rawReloadTo: String) =
        billingAccountMutex.withLock {
            try {
                updateBillingAutoReloadLocked(enabled, rawThreshold, rawReloadTo)
            } catch (cancelled: CancellationException) {
                mutableState.value = mutableState.value.copy(billingBusy = false)
                throw cancelled
            }
        }

    private suspend fun updateBillingAutoReloadLocked(enabled: Boolean, rawThreshold: String, rawReloadTo: String) {
        if (mutableState.value.billingBusy) return
        mutableState.value = mutableState.value.copy(
            billingBusy = true,
            billingNotice = null,
            billingError = null,
            billingRecovery = BillingRecovery.NONE,
            billingRetryIntent = null,
        )
        val threshold: String
        val reloadTo: String
        try {
            activeCredentials()
            val billing = requireNotNull(mutableState.value.billingState) { "Load billing before changing auto-refill" }
            require(billing.autoReload?.enabled == true && billing.autoReload.card.kind == "canonical") {
                "Manage this auto-refill configuration in the Nous portal"
            }
            threshold = validateBillingAmount(rawThreshold, billing.minUsd, billing.maxUsd)
            reloadTo = validateBillingAmount(rawReloadTo, billing.minUsd, billing.maxUsd)
            require(BigDecimal(reloadTo) > BigDecimal(threshold)) { "Reload-to amount must be greater than the threshold" }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failBilling(error)
            return
        }
        val result = runCatching {
            gateway.request(
                "billing.auto_reload",
                billingAutoReloadParams(enabled, threshold, reloadTo),
            ).let { json.decodeFromJsonElement(BillingMutationResponse.serializer(), it) }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            failBilling(error, BillingRetryIntent.AutoReload(enabled, threshold, reloadTo))
            return
        }
        if (!result.ok) {
            applyBillingRefusal(
                result.error,
                result.message,
                result.portalUrl,
                retryIntent = BillingRetryIntent.AutoReload(enabled, threshold, reloadTo),
            )
            return
        }
        refreshBillingLocked()
        mutableState.value = mutableState.value.copy(
            billingBusy = false,
            billingNotice = if (enabled) "Auto-refill updated." else "Auto-refill turned off.",
        )
    }

    suspend fun startBillingStepUp() = billingAccountMutex.withLock {
        startBillingStepUpLocked()
    }

    private suspend fun startBillingStepUpLocked() {
        if (billingStepUpRunId != null) return
        billingStepUpRunId = UUID.randomUUID().toString()
        billingStepUpSessionId = mutableState.value.runtimeSessionId?.takeIf(String::isNotBlank)
        mutableState.value = mutableState.value.copy(
            billingBusy = true,
            billingStepUpVerification = null,
            billingNotice = "Waiting for a verification link…",
            billingError = null,
            billingRetryIntent = null,
        )
        try {
            activeCredentials()
        } catch (cancelled: CancellationException) {
            billingStepUpRunId = null
            billingStepUpSessionId = null
            mutableState.value = mutableState.value.copy(billingBusy = false)
            throw cancelled
        } catch (error: Throwable) {
            billingStepUpRunId = null
            billingStepUpSessionId = null
            failBilling(error, BillingRetryIntent.StepUp)
            return
        }
        val result = runCatching {
            gateway.request(
                "billing.step_up",
                billingStepUpParams(billingStepUpSessionId),
            ).let { json.decodeFromJsonElement(BillingMutationResponse.serializer(), it) }
        }.getOrElse { error ->
            billingStepUpRunId = null
            billingStepUpSessionId = null
            if (error is CancellationException) {
                mutableState.value = mutableState.value.copy(billingBusy = false)
                throw error
            }
            failBilling(error, BillingRetryIntent.StepUp)
            return
        }
        billingStepUpRunId = null
        billingStepUpSessionId = null
        if (!result.ok || result.granted != true) {
            mutableState.value = mutableState.value.copy(billingStepUpVerification = null)
            applyBillingRefusal(
                result.error,
                result.message ?: "Verification finished without granting billing access.",
                result.portalUrl,
                retryIntent = BillingRetryIntent.StepUp,
            )
            return
        }
        refreshBillingLocked()
        mutableState.value = mutableState.value.copy(
            billingBusy = false,
            billingStepUpVerification = null,
            billingNotice = "Billing management access verified.",
        )
    }

    suspend fun acknowledgeUnconfirmedBillingCharge() = billingAccountMutex.withLock {
        if (!mutableState.value.billingChargeUnconfirmed) return
        mutableState.value.backend?.id?.let { billingPendingChargeStore.remove(it) }
        clearBillingIdempotency()
        mutableState.value = mutableState.value.copy(
            billingChargeUnconfirmed = false,
            billingError = null,
            billingRecovery = BillingRecovery.NONE,
            billingNotice = "Unconfirmed charge warning cleared after balance review.",
        )
    }

    private fun applyBillingRefusal(
        code: String?,
        message: String?,
        portalUrl: String?,
        retryIntent: BillingRetryIntent? = null,
        actor: String? = null,
        payload: com.nousresearch.hermes.protocol.BillingErrorPayload? = null,
    ) {
        val policy = BillingPolicy.forCode(code)
        if (!policy.reuseIdempotencyKey) {
            clearBillingIdempotency()
        }
        mutableState.value = mutableState.value.copy(
            billingBusy = false,
            billingNotice = null,
            billingError = billingRefusalMessage(code, message, actor, payload),
            billingRecovery = policy.recovery,
            billingPortalUrl = portalUrl ?: mutableState.value.billingState?.portalUrl,
            billingRetryIntent = retryIntent.takeIf { policy.recovery == BillingRecovery.RETRY },
        )
    }

    private fun failBilling(
        error: Throwable,
        retryIntent: BillingRetryIntent? = BillingRetryIntent.Refresh,
    ) {
        mutableState.value = mutableState.value.copy(
            billingLoading = false,
            billingBusy = false,
            billingNotice = null,
            billingError = DiagnosticRedactor.redact(error.message ?: "Billing request failed"),
            billingRecovery = BillingRecovery.RETRY,
            billingRetryIntent = retryIntent,
        )
    }

    private fun clearBillingIdempotency() {
        billingIdempotencyKey = null
        billingIdempotencyAmount = null
        billingIdempotencyBackendId = null
        billingSettlementDeadlineEpochMillis = null
    }

    suspend fun refreshUsage(days: Int = mutableState.value.usageDays) {
        require(days in USAGE_PERIODS) { "Unsupported usage period" }
        val (backend, token) = activeCredentials()
        mutableState.value = mutableState.value.copy(
            usageDays = days,
            usageLoading = true,
            usageError = null,
        )
        runCatching {
            val active = restClient.activeProfile(backend, token)
            val analytics = restClient.usageAnalytics(backend, token, active.active, days)
            val context = mutableState.value.runtimeSessionId?.let { sessionId ->
                try {
                    Result.success(
                        gateway.request(
                            "session.context_breakdown",
                            buildJsonObject { put("session_id", sessionId) },
                        ).let { json.decodeFromJsonElement(ContextBreakdown.serializer(), it) },
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Result.failure(error)
                }
            }
            Triple(active, analytics, context)
        }.onSuccess { (active, analytics, context) ->
            val safeContext = context?.getOrNull()?.let { breakdown ->
                breakdown.copy(
                    categories = breakdown.categories
                        .filter { it.id.isNotBlank() && it.id.length <= MAX_USAGE_LABEL_CHARACTERS && it.tokens >= 0 }
                        .distinctBy { it.id }
                        .take(MAX_CONTEXT_CATEGORIES),
                )
            }
            mutableState.value = mutableState.value.copy(
                activeProfile = active.active,
                currentProfile = active.current,
                usageAnalytics = analytics,
                contextBreakdown = safeContext,
                usageLoading = false,
                usageError = context?.exceptionOrNull()?.message?.let {
                    "Profile analytics loaded, but the live context breakdown is unavailable: ${DiagnosticRedactor.redact(it)}"
                },
                error = null,
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            failUsage(error)
        }
    }

    suspend fun refreshCheckpoints() {
        if (mutableState.value.checkpointsLoading) return
        val sessionId = mutableState.value.runtimeSessionId ?: return
        activeCredentials()
        mutableState.value = mutableState.value.copy(
            checkpointsLoading = true,
            checkpointNotice = null,
            checkpointError = null,
        )
        runCatching { loadCheckpointList(sessionId) }
            .onSuccess { result ->
                if (mutableState.value.runtimeSessionId != sessionId) return@onSuccess
                val safeCheckpoints = result.checkpoints
                    .filter { CheckpointSafety.isValidIdentity(it.hash) }
                    .distinctBy(RollbackCheckpoint::hash)
                    .take(MAX_CHECKPOINTS)
                    .map {
                        it.copy(
                            timestamp = it.timestamp.take(MAX_CHECKPOINT_LABEL_CHARACTERS),
                            message = it.message.take(MAX_CHECKPOINT_LABEL_CHARACTERS),
                        )
                    }
                mutableState.value = mutableState.value.copy(
                    checkpointsEnabled = result.enabled,
                    checkpoints = safeCheckpoints,
                    checkpointPreview = null,
                    checkpointsLoading = false,
                    checkpointError = null,
                    error = null,
                )
            }
            .onFailure { error ->
                if (error is CancellationException) throw error
                failCheckpoints(error)
            }
    }

    suspend fun previewCheckpoint(hash: String) {
        val sessionId = mutableState.value.runtimeSessionId ?: return
        val checkpoint = runCatching {
            CheckpointSafety.requireAdvertised(mutableState.value.checkpoints, hash)
        }.getOrElse { error ->
            failCheckpoints(error)
            return
        }
        mutableState.value = mutableState.value.copy(
            checkpointsLoading = true,
            checkpointPreview = null,
            checkpointNotice = null,
            checkpointError = null,
        )
        runCatching {
            gateway.request(
                "rollback.diff",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("hash", checkpoint.hash)
                },
            ).let { json.decodeFromJsonElement(RollbackDiffResult.serializer(), it) }
        }.onSuccess { result ->
            if (mutableState.value.runtimeSessionId != sessionId) return@onSuccess
            mutableState.value = mutableState.value.copy(
                checkpointPreview = CheckpointSafety.boundedPreview(checkpoint.hash, result),
                checkpointsLoading = false,
                checkpointError = null,
                error = null,
            )
        }.onFailure { error ->
            if (error is CancellationException) throw error
            failCheckpoints(error)
        }
    }

    suspend fun restoreCheckpoint(hash: String) {
        val sessionId = mutableState.value.runtimeSessionId ?: return
        val checkpoint = runCatching {
            CheckpointSafety.requireRestorable(
                checkpoints = mutableState.value.checkpoints,
                requestedHash = hash,
                previewedHash = mutableState.value.checkpointPreview?.hash,
                running = mutableState.value.runtimeInfo.running || mutableState.value.sending,
            )
        }.getOrElse { error ->
            failCheckpoints(error)
            return
        }
        val preview = mutableState.value.checkpointPreview ?: return
        mutableState.value = mutableState.value.copy(
            checkpointsLoading = true,
            checkpointNotice = null,
            checkpointError = null,
        )
        val restored = runCatching {
            val latestDiff = gateway.request(
                "rollback.diff",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("hash", checkpoint.hash)
                },
            ).let { json.decodeFromJsonElement(RollbackDiffResult.serializer(), it) }
            CheckpointSafety.requireUnchangedPreview(preview, latestDiff)
            gateway.request(
                "rollback.restore",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("hash", checkpoint.hash)
                },
            ).let { json.decodeFromJsonElement(RollbackRestoreResult.serializer(), it) }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            if (error is IllegalStateException && error.message?.contains("changed after the preview") == true) {
                mutableState.value = mutableState.value.copy(checkpointPreview = null)
            }
            failCheckpoints(error)
            return
        }
        if (mutableState.value.runtimeSessionId != sessionId) return
        if (!restored.success) {
            failCheckpoints(IllegalStateException(restored.error ?: "Hermes did not restore the checkpoint"))
            return
        }

        val history = runCatching {
            gateway.request("session.history", buildJsonObject { put("session_id", sessionId) })
                .let { json.decodeFromJsonElement(SessionHistoryResult.serializer(), it) }
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            mutableState.value = mutableState.value.copy(
                timeline = TimelineState(),
                checkpointsLoading = false,
                checkpointPreview = null,
                checkpointError = "Hermes restored the workspace, but Android could not reload the changed session history: ${DiagnosticRedactor.redact(error.message ?: "unknown gateway error")}. Reopen this session before continuing.",
            )
            return
        }
        val refreshed = runCatching { loadCheckpointList(sessionId) }.getOrNull()
        val safeRefreshed = refreshed?.checkpoints.orEmpty()
            .filter { CheckpointSafety.isValidIdentity(it.hash) }
            .distinctBy(RollbackCheckpoint::hash)
            .take(MAX_CHECKPOINTS)
        mutableState.value = mutableState.value.copy(
            timeline = TimelineReducer.hydrate(history.messages),
            checkpointsEnabled = refreshed?.enabled ?: mutableState.value.checkpointsEnabled,
            checkpoints = if (refreshed == null) mutableState.value.checkpoints else safeRefreshed,
            checkpointPreview = null,
            checkpointsLoading = false,
            checkpointNotice = "Restored checkpoint ${restored.restoredTo ?: checkpoint.hash.take(8)} and reloaded the session history (${restored.historyRemoved} messages removed).",
            checkpointError = null,
            error = null,
        )
    }

    private suspend fun loadCheckpointList(sessionId: String): RollbackListResult =
        gateway.request("rollback.list", buildJsonObject { put("session_id", sessionId) })
            .let { json.decodeFromJsonElement(RollbackListResult.serializer(), it) }

    suspend fun refreshAgents() {
        if (mutableState.value.agentsLoading) return
        mutableState.value = mutableState.value.copy(agentsLoading = true, agentsError = null)
        runCatching {
            activeCredentials()
            val status = gateway.request("delegation.status", buildJsonObject {})
                .let { json.decodeFromJsonElement(DelegationStatusResponse.serializer(), it) }
            val runtimeId = mutableState.value.runtimeSessionId
            val processes = if (runtimeId == null) {
                emptyList()
            } else {
                gateway.request("process.list", buildJsonObject { put("session_id", runtimeId) })
                    .let { json.decodeFromJsonElement(BackgroundProcessListResponse.serializer(), it).processes }
            }
            status to processes
        }.onSuccess { (status, processes) ->
            val observed = mutableState.value.subagentsBySession.values.flatten().associateBy(SubagentProgress::id)
            mutableState.value = mutableState.value.copy(
                delegationStatus = status,
                activeSubagents = status.active.filter { it.id.isNotBlank() }.map { active ->
                    SubagentReducer.fromActive(active, observed[active.id])
                },
                backgroundProcesses = processes.filter { it.id.isNotBlank() },
                agentsLoading = false,
                agentsError = null,
            )
        }.onFailure(::failAgents)
    }

    suspend fun refreshSpawnTrees() {
        if (mutableState.value.spawnTreesLoading) return
        mutableState.value = mutableState.value.copy(spawnTreesLoading = true, spawnTreesError = null)
        runCatching {
            activeCredentials()
            gateway.request(
                "spawn_tree.list",
                buildJsonObject {
                    put("limit", MAX_SPAWN_TREE_ARCHIVES)
                    put("cross_session", true)
                },
            ).let { json.decodeFromJsonElement(SpawnTreeListResponse.serializer(), it) }
        }.onSuccess { result ->
            val safe = result.entries
                .filter {
                    it.path.isNotBlank() && it.path.length <= MAX_SPAWN_TREE_PATH_CHARACTERS &&
                        it.count in 1..MAX_AGENTS_PER_SPAWN_TREE
                }
                .map {
                    it.copy(
                        sessionId = it.sessionId?.take(MAX_SPAWN_TREE_LABEL_CHARACTERS),
                        label = it.label.take(MAX_SPAWN_TREE_LABEL_CHARACTERS),
                    )
                }
                .distinctBy(SpawnTreeListEntry::path)
                .sortedByDescending { it.finishedAt ?: 0.0 }
                .take(MAX_SPAWN_TREE_ARCHIVES)
            mutableState.value = mutableState.value.copy(
                spawnTreeArchives = safe,
                spawnTreeReplay = mutableState.value.spawnTreeReplay?.takeIf { replay ->
                    safe.any { it.path == replay.archive.path }
                },
                spawnTreesLoading = false,
                spawnTreesError = null,
            )
        }.onFailure(::failSpawnTrees)
    }

    suspend fun loadSpawnTree(path: String) {
        val archive = mutableState.value.spawnTreeArchives.firstOrNull { it.path == path }
            ?: run {
                failSpawnTrees(IllegalArgumentException("Hermes did not advertise this spawn-tree archive"))
                return
            }
        mutableState.value = mutableState.value.copy(
            spawnTreesLoading = true,
            spawnTreesError = null,
        )
        runCatching {
            activeCredentials()
            val snapshot = gateway.request("spawn_tree.load", buildJsonObject { put("path", archive.path) })
                .let { json.decodeFromJsonElement(SpawnTreeSnapshot.serializer(), it) }
            require(snapshot.sessionId == null || archive.sessionId == null || snapshot.sessionId == archive.sessionId) {
                "Hermes returned a spawn tree for a different session"
            }
            val finishedAt = ((snapshot.finishedAt ?: archive.finishedAt ?: 0.0) * 1_000)
                .toLong().takeIf { it > 0 } ?: System.currentTimeMillis()
            val items = snapshot.subagents.take(MAX_AGENTS_PER_SPAWN_TREE).mapIndexed { index, raw ->
                SubagentReducer.fromSnapshot(raw, "archive:${archive.finishedAt?.toLong() ?: 0}:$index", finishedAt)
            }.distinctBy(SubagentProgress::id)
            require(items.isNotEmpty()) { "Hermes returned an empty spawn-tree archive" }
            SpawnTreeReplay(archive, items)
        }.onSuccess { replay ->
            mutableState.value = mutableState.value.copy(
                spawnTreeReplay = replay,
                spawnTreesLoading = false,
                spawnTreesError = null,
            )
        }.onFailure(::failSpawnTrees)
    }

    suspend fun setDelegationPaused(paused: Boolean) {
        mutableState.value = mutableState.value.copy(agentsLoading = true, agentsNotice = null, agentsError = null)
        runCatching {
            activeCredentials()
            gateway.request(
                "delegation.pause",
                buildJsonObject { put("paused", paused) },
            ).let { json.decodeFromJsonElement(DelegationPauseResponse.serializer(), it) }
                .also { require(it.paused == paused) { "Hermes returned a different delegation pause state" } }
        }.onSuccess { result ->
            mutableState.value = mutableState.value.copy(
                delegationStatus = mutableState.value.delegationStatus?.copy(paused = result.paused),
                agentsLoading = false,
                agentsNotice = if (result.paused) {
                    "New subagent spawns are paused. Running subagents continue until they finish or are interrupted."
                } else {
                    "New subagent spawns are enabled."
                },
                agentsError = null,
            )
        }.onFailure(::failAgents)
    }

    suspend fun interruptSubagent(id: String) {
        mutableState.value = mutableState.value.copy(agentsLoading = true, agentsNotice = null, agentsError = null)
        runCatching {
            require(mutableState.value.activeSubagents.any { it.id == id }) { "This subagent is no longer active" }
            activeCredentials()
            gateway.request(
                "subagent.interrupt",
                buildJsonObject { put("subagent_id", id) },
            ).let { json.decodeFromJsonElement(SubagentInterruptResponse.serializer(), it) }
                .also { require(it.found && it.subagentId == id) { "Hermes could not find the requested subagent" } }
        }.onSuccess { result ->
            mutableState.value = mutableState.value.copy(
                agentsLoading = false,
                agentsNotice = "Interrupt requested for subagent $id.",
                agentsError = null,
            )
            refreshAgents()
        }.onFailure(::failAgents)
    }

    suspend fun stopBackgroundProcess(id: String) {
        mutableState.value = mutableState.value.copy(agentsLoading = true, agentsNotice = null, agentsError = null)
        runCatching {
            val process = mutableState.value.backgroundProcesses.firstOrNull { it.id == id }
                ?: error("This background process is no longer reported by Hermes")
            require(process.status == "running") { "Only a running background process can be stopped" }
            val runtimeId = mutableState.value.runtimeSessionId ?: error("Open the process owner session first")
            activeCredentials()
            gateway.request(
                "process.kill",
                buildJsonObject {
                    put("session_id", runtimeId)
                    put("process_id", id)
                },
            ).let { json.decodeFromJsonElement(BackgroundProcessKillResponse.serializer(), it) }
                .also { result ->
                    require(result.status == "killed" || result.status == "already_exited") {
                        result.error ?: "Hermes did not confirm the process stopped"
                    }
                }
        }.onSuccess { result ->
            mutableState.value = mutableState.value.copy(
                agentsNotice = "Background process $id stopped.",
                agentsError = null,
            )
            refreshAgents()
        }.onFailure(::failAgents)
    }

    private fun advertisedMessagingPlatform(platformId: String): MessagingPlatformInfo =
        mutableState.value.messagingPlatforms.firstOrNull { it.id == platformId }
            ?: error("Hermes did not advertise this messaging platform")

    private fun advertisedMcpServer(name: String): McpServerSummary =
        mutableState.value.mcpServers.firstOrNull { it.name == name }
            ?: error("Hermes did not advertise this MCP server")

    private fun advertisedMcpCatalogEntry(name: String): McpCatalogEntry =
        mutableState.value.mcpCatalog.firstOrNull { it.name == name }
            ?: error("Hermes did not advertise this MCP catalog entry")

    private fun updateDiagnostic(action: DiagnosticAction, run: DiagnosticRunState) {
        mutableState.value = mutableState.value.copy(
            diagnostics = mutableState.value.diagnostics + (action to run),
        )
    }

    private fun replaceCronJob(job: CronJob) {
        val existing = mutableState.value.cronJobs
        mutableState.value = mutableState.value.copy(
            cronJobs = if (existing.any { it.id == job.id }) {
                existing.map { if (it.id == job.id) job else it }
            } else {
                existing + job
            },
            managementLoading = false,
            error = null,
        )
    }

    suspend fun attach(uri: Uri) {
        runAttachment(stageAttachment(uri))
    }

    private fun stageAttachment(uri: Uri): String {
        require(uri.scheme.equals("content", ignoreCase = true)) { "Attachments must use content URIs" }
        require(mutableState.value.pendingAttachments.size < MAX_SHARED_ATTACHMENTS) {
            "Hermes Android supports up to $MAX_SHARED_ATTACHMENTS pending attachments."
        }
        val id = UUID.randomUUID().toString()
        val pending = PendingAttachment(
            id = id,
            label = "Attachment",
            sourceUri = uri.toString(),
            scope = currentAttachmentScope(),
        )
        mutableState.update { it.copy(pendingAttachments = it.pendingAttachments + pending, error = null) }
        return id
    }

    suspend fun retryPendingAttachment(id: String) {
        val attachment = mutableState.value.pendingAttachments.firstOrNull { it.id == id } ?: return
        require(attachment.phase == AttachmentPhase.ERROR) { "Only failed attachments can be retried" }
        mutableState.update { state ->
            state.copy(pendingAttachments = state.pendingAttachments.map {
                if (it.id == id) it.copy(
                    phase = AttachmentPhase.VALIDATING,
                    error = null,
                    bytesRead = 0,
                    scope = currentAttachmentScope(),
                ) else it
            })
        }
        runAttachment(id)
    }

    fun cancelPendingAttachment(id: String) {
        attachmentJobs[id]?.cancel(CancellationException("Attachment cancelled"))
    }

    private suspend fun runAttachment(id: String) {
        val job = currentCoroutineContext()[Job] ?: error("Attachment work requires a coroutine")
        val sourceUri = mutableState.value.pendingAttachments.firstOrNull { it.id == id }?.sourceUri
        attachmentJobs[id] = job
        try {
            attachOrThrow(id)
        } catch (cancelled: CancellationException) {
            updatePendingAttachment(id) { it.copy(phase = AttachmentPhase.ERROR, error = "Attachment cancelled") }
            throw cancelled
        } catch (error: Throwable) {
            updatePendingAttachment(id) {
                it.copy(phase = AttachmentPhase.ERROR, error = error.message ?: "Attachment failed")
            }
        } finally {
            attachmentJobs.remove(id, job)
            if (mutableState.value.pendingAttachments.none { it.id == id }) {
                sourceUri?.let(Uri::parse)?.let(attachmentReader::release)
            }
        }
    }

    private suspend fun attachOrThrow(id: String) {
        val pending = mutableState.value.pendingAttachments.firstOrNull { it.id == id } ?: return
        val startingGeneration = openSessionGeneration.get()
        val uri = Uri.parse(requireNotNull(pending.sourceUri))
        val metadata = attachmentReader.inspect(uri)
        updatePendingAttachment(id) {
            it.copy(
                label = metadata.displayName,
                mimeType = metadata.mimeType,
                declaredSize = metadata.declaredSize,
                phase = AttachmentPhase.READING,
            )
        }
        val payload = attachmentReader.read(uri, metadata = metadata, releaseAfterRead = false) { bytesRead, _ ->
            updatePendingAttachment(id) { it.copy(bytesRead = bytesRead) }
        }
        val scope = attachmentSessionMutex.withLock {
            val currentScope = currentAttachmentScope()
            when {
                pending.scope.runtimeSessionId != null -> {
                    require(openSessionGeneration.get() == startingGeneration && currentScope == pending.scope) {
                        "The active Hermes session changed while the attachment was being read"
                    }
                    currentScope
                }
                currentScope.runtimeSessionId == null -> {
                    require(openSessionGeneration.get() == startingGeneration && currentScope == pending.scope) {
                        "The active Hermes session changed while the attachment was being read"
                    }
                    require(newSession(preservePendingAttachments = true)) {
                        "Hermes could not open a session for this attachment"
                    }
                    currentAttachmentScope().also { attachmentCreatedSessionScope = it }
                }
                currentScope == attachmentCreatedSessionScope -> currentScope
                else -> error("The active Hermes session changed while the attachment was being read")
            }
        }
        val sessionId = requireNotNull(scope.runtimeSessionId)
        val currentPending = mutableState.value.pendingAttachments.firstOrNull { it.id == id }
            ?: error("The attachment was removed before upload")
        val uploading = currentPending.copy(
            label = payload.displayName,
            mimeType = payload.mimeType,
            byteCount = payload.byteCount,
            bytesRead = payload.byteCount,
            declaredSize = metadata.declaredSize,
            phase = AttachmentPhase.UPLOADING,
            error = null,
            scope = scope,
        )
        updatePendingAttachment(id) { uploading }
        val attached = createAttachment(sessionId, uploading, payload)
        if (!attached.matches(currentAttachmentScope()) || mutableState.value.pendingAttachments.none { it.id == id }) {
            detachRemoteAttachment(sessionId, attached)
            attachmentReader.release(uri)
            error("The active Hermes session changed while the attachment was uploading")
        }
        updatePendingAttachment(id) { attached.copy(phase = AttachmentPhase.READY) }
        attachmentReader.release(uri)
    }

    private fun currentAttachmentScope(): AttachmentScope {
        val current = mutableState.value
        return AttachmentScope(
            backendId = requireNotNull(current.backend?.id) { "Connect to Hermes before attaching a document" },
            profile = current.activeStoredSession?.profile ?: current.activeProfile,
            runtimeSessionId = current.runtimeSessionId,
        )
    }

    private fun updatePendingAttachment(id: String, transform: (PendingAttachment) -> PendingAttachment) {
        mutableState.update { state ->
            state.copy(pendingAttachments = state.pendingAttachments.map { if (it.id == id) transform(it) else it })
        }
    }

    private fun invalidatePendingAttachments() {
        attachmentJobs.values.toList().forEach { it.cancel(CancellationException("Attachment scope changed")) }
        mutableState.value.pendingAttachments.mapNotNull(PendingAttachment::sourceUri)
            .map(Uri::parse)
            .forEach(attachmentReader::release)
        attachmentCreatedSessionScope = null
        mutableState.update { it.copy(pendingAttachments = emptyList()) }
    }

    private suspend fun createAttachment(
        sessionId: String,
        pending: PendingAttachment,
        payload: AttachmentPayload,
    ): PendingAttachment = when {
        payload.mimeType.startsWith("image/") -> {
            val result = gateway.request(
                "image.attach_bytes",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("content_base64", payload.base64)
                    put("filename", payload.displayName)
                },
            )
            val attached = json.decodeFromJsonElement(ImageAttachResult.serializer(), result)
            require(attached.attached && attached.path.isNotBlank()) { "Hermes did not attach the image" }
            pending.copy(
                queuedImagePaths = listOf(attached.path),
            )
        }
        payload.mimeType == "application/pdf" -> {
            val result = gateway.request(
                "pdf.attach",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("content_base64", payload.base64)
                    put("filename", payload.displayName)
                },
            )
            val attached = json.decodeFromJsonElement(PdfAttachResult.serializer(), result)
            require(
                attached.attached && attached.pages.isNotEmpty() &&
                    attached.pages.size == attached.pagesAttached && attached.pages.all { it.path.isNotBlank() },
            ) { "Hermes did not attach the PDF pages" }
            pending.copy(
                queuedImagePaths = attached.pages.map { it.path },
            )
        }
        else -> {
            val result = gateway.request(
                "file.attach",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("name", payload.displayName)
                    put("path", payload.displayName)
                    put("data_url", "data:${payload.mimeType};base64,${payload.base64}")
                },
            )
            val attached = json.decodeFromJsonElement(FileAttachResult.serializer(), result)
            require(attached.attached && attached.uploaded && attached.refText.isNotBlank()) {
                "Hermes did not attach the file"
            }
            pending.copy(
                refText = attached.refText,
            )
        }
    }

    private suspend fun detachRemoteAttachment(sessionId: String, attachment: PendingAttachment) {
        attachment.queuedImagePaths.forEach { path ->
            runCatching {
                gateway.request(
                    "image.detach",
                    buildJsonObject {
                        put("session_id", sessionId)
                        put("path", path)
                    },
                )
            }
        }
    }

    suspend fun removePendingAttachment(id: String) {
        mutableState.value.pendingAttachments.firstOrNull { it.id == id } ?: return
        attachmentJobs[id]?.cancelAndJoin()
        val attachment = mutableState.value.pendingAttachments.firstOrNull { it.id == id } ?: return
        val sessionId = attachment.scope.runtimeSessionId
        if (sessionId != null) detachRemoteAttachment(sessionId, attachment)
        attachment.sourceUri?.let(Uri::parse)?.let(attachmentReader::release)
        mutableState.value = mutableState.value.copy(
            pendingAttachments = mutableState.value.pendingAttachments.filterNot { it.id == id },
        )
    }

    suspend fun interrupt() {
        val sessionId = mutableState.value.runtimeSessionId ?: return
        gateway.request("session.interrupt", buildJsonObject { put("session_id", sessionId) })
    }

    suspend fun respondToApproval(choice: String) {
        val request = mutableState.value.timeline.approval ?: return
        gateway.request(
            "approval.respond",
            buildJsonObject {
                put("session_id", request.sessionId)
                put("choice", choice)
            },
        )
        mutableState.value = mutableState.value.copy(
            timeline = TimelineReducer.clearApproval(mutableState.value.timeline),
        )
    }

    suspend fun respondToClarification(answer: String) {
        val request = mutableState.value.timeline.clarification ?: return
        gateway.request(
            "clarify.respond",
            buildJsonObject {
                put("request_id", request.requestId)
                put("answer", answer)
            },
        )
        mutableState.value = mutableState.value.copy(
            timeline = TimelineReducer.clearClarification(mutableState.value.timeline),
        )
    }

    suspend fun respondToSensitiveInput(value: String) {
        val request = mutableState.value.timeline.sensitiveInput ?: return
        val method: String
        val key: String
        when (request.kind) {
            SensitiveInputKind.SUDO_PASSWORD -> {
                method = "sudo.respond"
                key = "password"
            }
            SensitiveInputKind.SECRET -> {
                method = "secret.respond"
                key = "value"
            }
        }
        gateway.request(
            method,
            buildJsonObject {
                put("request_id", request.requestId)
                put(key, value)
            },
        )
        mutableState.value = mutableState.value.copy(
            timeline = TimelineReducer.clearSensitiveInput(mutableState.value.timeline),
        )
    }

    suspend fun archiveActive(
        expectedBackendId: String? = null,
        expectedSession: StoredSession? = null,
    ) {
        val current = mutableState.value
        val session = current.activeStoredSession ?: return
        if (
            expectedBackendId != null && expectedSession != null &&
            (
                current.backend?.id != expectedBackendId ||
                    session.durableId != expectedSession.durableId ||
                    session.profile.normalizedProfile() != expectedSession.profile.normalizedProfile()
                )
        ) {
            archiveSessionMutation(expectedBackendId, expectedSession, allowActiveDelegation = false)
            return
        }
        val requestBackendId = current.backend?.id
        val credentialGeneration = backendCredentialGeneration.get()
        flushDraft()
        val credentials = runCatching { activeCredentials() }.getOrElse { error ->
            if (error is CancellationException) throw error
            requestBackendId?.let { reportCurrentBackendMutationFailure(error, it, credentialGeneration) }
            return
        }
        val (backend, token) = credentials
        if (backend.id != requestBackendId || !isCurrentBackendMutation(backend.id, credentialGeneration)) return
        val activeStillCurrent = mutableState.value.let { live ->
            live.backend?.id == requestBackendId && live.activeStoredSession?.let { active ->
                active.durableId == session.durableId &&
                    active.profile.normalizedProfile() == session.profile.normalizedProfile()
            } == true
        }
        if (!activeStillCurrent) {
            val fallbackBackendId = expectedBackendId ?: requestBackendId ?: return
            archiveSessionMutation(
                fallbackBackendId,
                expectedSession ?: session,
                allowActiveDelegation = false,
            )
            return
        }
        invalidatePendingAttachments()
        val requestGeneration = openSessionGeneration.incrementAndGet()
        try {
            restClient.archiveSession(backend, token, session.durableId, true, session.profile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            reportCurrentBackendMutationFailure(error, backend.id, credentialGeneration)
            mutableState.update { live ->
                if (
                    live.backend?.id == backend.id &&
                    backendCredentialGeneration.get() == credentialGeneration &&
                    openSessionGeneration.get() == requestGeneration
                ) {
                    live.copy(loading = false)
                } else {
                    live
                }
            }
            return
        }
        if (!isCurrentBackendMutation(backend.id, credentialGeneration)) return
        markSessionListMutation(backend.id, credentialGeneration)
        invalidateSessionSearch(backend.id, session, credentialGeneration)
        if (!clearArchivedActiveSession(requestGeneration, backend.id, session, credentialGeneration)) {
            val reopened = mutableState.value.let { current ->
                current.backend?.id == backend.id && current.activeStoredSession?.let { active ->
                    active.durableId == session.durableId &&
                        active.profile.normalizedProfile() == session.profile.normalizedProfile()
                } == true
            }
            if (reopened) {
                invalidatePendingAttachments()
                flushDraft()
                if (clearArchivedActiveSession(openSessionGeneration.get(), backend.id, session, credentialGeneration)) {
                    return
                }
            }
            removeArchivedSessionFromState(backend.id, session, credentialGeneration)
        }
    }

    suspend fun archiveSession(requestBackendId: String, session: StoredSession) {
        archiveSessionMutation(requestBackendId, session, allowActiveDelegation = true)
    }

    private suspend fun archiveSessionMutation(
        requestBackendId: String,
        session: StoredSession,
        allowActiveDelegation: Boolean,
    ) {
        require(session.durableId.isNotBlank()) { "Hermes session id is missing" }
        if (mutableState.value.backend?.id != requestBackendId) return
        if (allowActiveDelegation && mutableState.value.activeStoredSession?.let { active ->
                active.durableId == session.durableId &&
                    active.profile.normalizedProfile() == session.profile.normalizedProfile()
            } == true
        ) {
            archiveActive(requestBackendId, session)
            return
        }
        val credentialGeneration = backendCredentialGeneration.get()
        val (backend, token) = runCatching { activeCredentials() }.getOrElse { error ->
            if (error is CancellationException) throw error
            reportCurrentBackendMutationFailure(error, requestBackendId, credentialGeneration)
            return
        }
        if (backend.id != requestBackendId || !isCurrentBackendMutation(backend.id, credentialGeneration)) return
        try {
            restClient.archiveSession(backend, token, session.durableId, true, session.profile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            reportCurrentBackendMutationFailure(error, backend.id, credentialGeneration)
            return
        }
        if (!isCurrentBackendMutation(backend.id, credentialGeneration)) return
        markSessionListMutation(backend.id, credentialGeneration)
        invalidateSessionSearch(backend.id, session, credentialGeneration)
        if (
            cleanupArchivedSessionState(
                backend.id,
                session,
                credentialGeneration,
            )
        ) return
    }

    private suspend fun cleanupArchivedSessionState(
        backendId: String,
        session: StoredSession,
        credentialGeneration: Long,
    ): Boolean {
        var clearedActive = false
        sessionTargetMutex.withLock {
            if (!isCurrentBackendMutation(backendId, credentialGeneration)) return@withLock
            val current = mutableState.value
            val target = sessionTarget(backendId, session)
            archivedSessionTargets[target] = credentialGeneration
            val activeMatches = current.activeStoredSession?.let { active ->
                current.backend?.id == backendId &&
                    active.durableId == session.durableId &&
                    active.profile.normalizedProfile() == session.profile.normalizedProfile()
            } == true
            val pendingMatches =
                pendingOpenSessionGeneration == openSessionGeneration.get() &&
                    pendingOpenSession == target
            val requestGeneration = if (activeMatches || pendingMatches) {
                openSessionGeneration.incrementAndGet()
            } else {
                null
            }
            if (pendingMatches) {
                pendingOpenSession = null
                pendingOpenSessionGeneration = null
            }
            if (activeMatches && requestGeneration != null) {
                invalidatePendingAttachments()
                flushDraft()
                clearedActive = clearArchivedActiveSessionLocked(
                    requestGeneration,
                    backendId,
                    session,
                    credentialGeneration,
                )
            }
            if (!clearedActive) {
                removeArchivedSessionFromState(backendId, session, credentialGeneration)
            }
        }
        if (!clearedActive) return false
        loadComposerState()
        refreshSessions()
        return true
    }

    private suspend fun clearArchivedActiveSession(
        requestGeneration: Long,
        backendId: String,
        session: StoredSession,
        credentialGeneration: Long,
    ): Boolean {
        val cleared = sessionTargetMutex.withLock {
            if (!isCurrentBackendMutation(backendId, credentialGeneration)) return@withLock false
            val target = sessionTarget(backendId, session)
            archivedSessionTargets[target] = credentialGeneration
            val pendingMatches =
                pendingOpenSessionGeneration == openSessionGeneration.get() &&
                    pendingOpenSession == target
            val effectiveRequestGeneration = if (pendingMatches) {
                openSessionGeneration.incrementAndGet().also {
                    pendingOpenSession = null
                    pendingOpenSessionGeneration = null
                }
            } else {
                requestGeneration
            }
            clearArchivedActiveSessionLocked(
                effectiveRequestGeneration,
                backendId,
                session,
                credentialGeneration,
            )
        }
        if (!cleared) return false
        loadComposerState()
        refreshSessions()
        return true
    }

    private suspend fun clearArchivedActiveSessionLocked(
        requestGeneration: Long,
        backendId: String,
        session: StoredSession,
        credentialGeneration: Long,
    ): Boolean {
        val current = mutableState.value
        if (
            openSessionGeneration.get() != requestGeneration ||
            backendCredentialGeneration.get() != credentialGeneration ||
            current.backend?.id != backendId ||
            current.activeStoredSession?.durableId != session.durableId ||
            current.activeStoredSession?.profile.normalizedProfile() != session.profile.normalizedProfile()
        ) {
            return false
        }
        backendRegistry.clearSessionTarget(backendId)
        while (true) {
            val live = mutableState.value
            if (
                openSessionGeneration.get() != requestGeneration ||
                backendCredentialGeneration.get() != credentialGeneration ||
                live.backend?.id != backendId ||
                live.activeStoredSession?.durableId != session.durableId ||
                live.activeStoredSession?.profile.normalizedProfile() != session.profile.normalizedProfile()
            ) {
                return false
            }
            val next = live.copy(
                activeStoredSession = null,
                runtimeSessionId = null,
                runtimeInfo = SessionRuntimeInfo(),
                timeline = TimelineState(),
                pendingAttachments = emptyList(),
                restoration = SessionRestorationState(status = SessionRestorationStatus.READY),
            )
            if (mutableState.compareAndSet(live, next)) return true
        }
    }

    private suspend fun markSessionListMutation(backendId: String, credentialGeneration: Long) {
        sessionListMutationMutex.withLock {
            if (
                mutableState.value.backend?.id == backendId &&
                backendCredentialGeneration.get() == credentialGeneration
            ) {
                sessionListGeneration.incrementAndGet()
            }
        }
    }

    private fun removeArchivedSessionFromState(
        backendId: String,
        session: StoredSession,
        credentialGeneration: Long,
    ) {
        mutableState.update { current ->
            if (
                current.backend?.id != backendId ||
                backendCredentialGeneration.get() != credentialGeneration
            ) {
                current
            } else {
                current.copy(
                    sessions = current.sessions.filterNot {
                        it.durableId == session.durableId &&
                            it.profile.normalizedProfile() == session.profile.normalizedProfile()
                    },
                    sessionSearchResults = current.sessionSearchResults.filterNot {
                        it.sessionId == session.durableId &&
                            it.profile.normalizedProfile() == session.profile.normalizedProfile()
                    },
                    error = null,
                )
            }
        }
    }

    private fun isCurrentBackendMutation(
        backendId: String,
        credentialGeneration: Long,
    ): Boolean = mutableState.value.backend?.id == backendId &&
        backendCredentialGeneration.get() == credentialGeneration

    private fun reportCurrentBackendMutationFailure(
        error: Throwable,
        backendId: String,
        credentialGeneration: Long,
    ) {
        if (!isCurrentBackendMutation(backendId, credentialGeneration)) return
        if (
            error is ReconnectRequiredException ||
            (error is com.nousresearch.hermes.network.HermesHttpException && error.statusCode in setOf(401, 403))
        ) {
            fail(error)
            return
        }
        val message = DiagnosticRedactor.redact(error.message.orEmpty())
            .ifBlank { "Hermes could not update the session." }
        mutableState.update { current ->
            if (
                current.backend?.id == backendId &&
                backendCredentialGeneration.get() == credentialGeneration
            ) {
                current.copy(error = message)
            } else {
                current
            }
        }
    }

    private fun invalidateSessionSearch(
        backendId: String,
        session: StoredSession,
        credentialGeneration: Long,
    ) {
        var query = ""
        var invalidated = false
        var invalidationGeneration = 0L
        synchronized(sessionSearchLock) {
            if (
                mutableState.value.backend?.id != backendId ||
                backendCredentialGeneration.get() != credentialGeneration
            ) return@synchronized
            invalidationGeneration = sessionSearchGeneration.incrementAndGet()
            sessionSearchJob?.cancel()
            sessionSearchJob = null
            mutableState.update { current ->
                if (
                    current.backend?.id != backendId ||
                    backendCredentialGeneration.get() != credentialGeneration
                ) {
                    current
                } else {
                    invalidated = true
                    query = current.sessionSearchQuery
                    current.copy(
                        sessionSearchResults = current.sessionSearchResults.filterNot {
                            it.sessionId == session.durableId &&
                                it.profile.normalizedProfile() == session.profile.normalizedProfile()
                        },
                        sessionSearchLoading = false,
                    )
                }
            }
            if (
                invalidated &&
                query.isNotBlank() &&
                sessionSearchGeneration.get() == invalidationGeneration &&
                mutableState.value.backend?.id == backendId &&
                backendCredentialGeneration.get() == credentialGeneration &&
                mutableState.value.sessionSearchQuery == query
            ) {
                searchSessions(query)
            }
        }
    }

    suspend fun pinSession(requestBackendId: String, session: StoredSession) {
        require(session.durableId.isNotBlank()) { "Hermes session id is missing" }
        val pinned = !requireNotNull(session.pinned) { "Hermes did not advertise session pinning" }
        if (mutableState.value.backend?.id != requestBackendId) return
        val credentialGeneration = backendCredentialGeneration.get()
        val (backend, token) = runCatching { activeCredentials() }.getOrElse { error ->
            if (error is CancellationException) throw error
            reportCurrentBackendMutationFailure(error, requestBackendId, credentialGeneration)
            return
        }
        if (backend.id != requestBackendId || !isCurrentBackendMutation(backend.id, credentialGeneration)) return
        try {
            restClient.pinSession(backend, token, session.durableId, pinned, session.profile)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            reportCurrentBackendMutationFailure(error, backend.id, credentialGeneration)
            return
        }
        if (isCurrentBackendMutation(backend.id, credentialGeneration)) {
            markSessionListMutation(backend.id, credentialGeneration)
            invalidateSessionSearch(backend.id, session, credentialGeneration)
            mutableState.update { current ->
                if (
                    current.backend?.id != backend.id ||
                    backendCredentialGeneration.get() != credentialGeneration
                ) {
                    current
                } else {
                    current.copy(
                        sessions = current.sessions.map {
                            if (
                                it.durableId == session.durableId &&
                                it.profile.normalizedProfile() == session.profile.normalizedProfile()
                            ) {
                                it.copy(pinned = pinned)
                            } else {
                                it
                            }
                        },
                        activeStoredSession = current.activeStoredSession?.let { active ->
                            if (
                                active.durableId == session.durableId &&
                                active.profile.normalizedProfile() == session.profile.normalizedProfile()
                            ) {
                                active.copy(pinned = pinned)
                            } else {
                                active
                            }
                        },
                        error = null,
                    )
                }
            }
        }
    }

    suspend fun deleteSession(session: StoredSession) {
        require(session.durableId.isNotBlank()) { "Hermes session id is missing" }
        require(
            mutableState.value.activeStoredSession?.durableId != session.durableId,
        ) { "Close an active session before deleting it" }
        val requestBackendId = mutableState.value.backend?.id
        val credentialGeneration = backendCredentialGeneration.get()
        val cleanupProfile = session.profile.normalizedProfile()
        runCatching {
            val response = gateway.request(
                "session.delete",
                buildJsonObject { put("session_id", session.durableId) },
            )
            json.decodeFromJsonElement(SessionDeleteResult.serializer(), response).also {
                require(it.deleted == session.durableId) { "Hermes deleted a different session than requested" }
            }
        }.onSuccess {
            if (requestBackendId != null) {
                markSessionListMutation(requestBackendId, credentialGeneration)
                draftStore.remove(DraftContext(requestBackendId, cleanupProfile, session.durableId))
                composerQueueStore.remove(ComposerQueueContext(requestBackendId, cleanupProfile, session.durableId))
            }
            mutableState.update { current ->
                if (
                    current.backend?.id != requestBackendId ||
                    backendCredentialGeneration.get() != credentialGeneration
                ) {
                    current
                } else {
                    current.copy(
                        sessions = current.sessions.filterNot {
                            it.durableId == session.durableId &&
                                it.profile.normalizedProfile() == session.profile.normalizedProfile()
                        },
                        error = null,
                    )
                }
            }
        }.onFailure { error ->
            if (error is CancellationException) throw error
            requestBackendId?.let { reportCurrentBackendMutationFailure(error, it, credentialGeneration) }
        }
    }

    suspend fun disconnectAndForget() {
        billingAccountMutex.withLock {
            if (!billingBackendTransitionSafe()) return
            val backend = mutableState.value.backend ?: return
            mutableState.value = mutableState.value.copy(backendTransitionInProgress = true)
            try {
                providerOAuthPollJob?.cancelAndJoin()
                providerOAuthPollJob = null
                draftSaveJob?.cancelAndJoin()
                draftSaveJob = null
                draftStore.removeBackend(backend.id)
                composerQueueStore.removeBackend(backend.id)
                billingPendingChargeStore.remove(backend.id)
                mutableState.value = mutableState.value.copy(
                    backend = null,
                    draft = "",
                    queuedPrompts = emptyList(),
                    queueDraining = false,
                    queueNotice = null,
                )
                intentionalDisconnect = true
                reconnectJob?.cancel()
                gateway.disconnect()
                gatewayBackendId = null
                tokenStore.remove(backend.id)
                backendRegistry.remove(backend.id)
            } finally {
                mutableState.value = mutableState.value.copy(backendTransitionInProgress = false)
            }
        }
    }

    suspend fun selectBackend(id: String) {
        billingAccountMutex.withLock {
            if (mutableState.value.backend?.id == id) return
            if (!billingBackendTransitionSafe()) return
            val backend = requireNotNull(mutableState.value.savedBackends.firstOrNull { it.id == id }) {
                "Saved Hermes backend was not found"
            }
            mutableState.value = mutableState.value.copy(backendTransitionInProgress = true)
            try {
                flushDraft()
                backendRegistry.select(id)
                connect(backend)
            } catch (error: Throwable) {
                mutableState.value = mutableState.value.copy(backendTransitionInProgress = false)
                throw error
            }
        }
    }

    suspend fun forgetBackend(id: String) {
        billingAccountMutex.withLock {
            val active = mutableState.value.backend?.id == id
            val pendingReconnect = mutableState.value.reconnectRequiredBackendId == id &&
                mutableState.value.billingChargeUnconfirmed
            if ((active || pendingReconnect) && !billingBackendTransitionSafe()) return
            if (active) mutableState.value = mutableState.value.copy(backendTransitionInProgress = true)
            try {
                if (active) {
                    draftSaveJob?.cancelAndJoin()
                    draftSaveJob = null
                    mutableState.value = mutableState.value.copy(
                        backend = null,
                        draft = "",
                        queuedPrompts = emptyList(),
                        queueDraining = false,
                        queueNotice = null,
                    )
                }
                draftStore.removeBackend(id)
                composerQueueStore.removeBackend(id)
                billingPendingChargeStore.remove(id)
                tokenStore.remove(id)
                backendRegistry.remove(id)
            } finally {
                if (active) mutableState.value = mutableState.value.copy(backendTransitionInProgress = false)
            }
        }
    }

    private fun billingBackendTransitionSafe(): Boolean {
        val state = mutableState.value
        val message = when {
            state.backendTransitionInProgress -> "Wait for the active backend change to finish"
            state.billingLoading || state.billingBusy -> "Wait for the active billing request to finish"
            state.billingChargeUnconfirmed -> "Review the unconfirmed charge balance before switching backends"
            else -> return true
        }
        mutableState.value = state.copy(error = message)
        return false
    }

    private fun requireBackendTransitionSafe(reconnectingBackendId: String? = null) {
        val state = mutableState.value
        val resumingPendingBackend = state.billingChargeUnconfirmed &&
            state.reconnectRequiredBackendId == reconnectingBackendId &&
            !state.billingLoading &&
            !state.billingBusy
        check(resumingPendingBackend || billingBackendTransitionSafe()) {
            mutableState.value.error ?: "Finish the active billing review before changing backends"
        }
    }

    private suspend fun connect(backend: BackendConfig) {
        sessionListMutationMutex.withLock { backendCredentialGeneration.incrementAndGet() }
        sessionListRefreshPriorityMutex.withLock {
            visibleSessionListRefreshGeneration.set(0L)
            silentSessionListRefreshGeneration.set(0L)
            pendingSilentSessionListRefresh.set(false)
        }
        sessionTargetMutex.withLock { archivedSessionTargets.clear() }
        invalidatePendingAttachments()
        providerOAuthPollJob?.cancelAndJoin()
        providerOAuthPollJob = null
        if (mutableState.value.backend?.id != backend.id) flushDraft()
        cachedSlashCatalog = null
        extensionSlashCommands = null
        clearBillingIdempotency()
        intentionalDisconnect = false
        if (backend.authMode != AuthMode.DASHBOARD_SESSION) {
            mutableState.value = HermesState(
                savedBackends = mutableState.value.savedBackends,
                reconnectRequiredBackendId = backend.id,
                error = "This legacy token-only backend must reconnect with its dashboard username and password.",
            )
            restorePendingBillingCharge(backend.id)
            mutableStartupReady.value = true
            return
        }
        val cookie = tokenStore.get(backend.id)
        if (cookie == null) {
            mutableState.value = HermesState(
                savedBackends = mutableState.value.savedBackends,
                reconnectRequiredBackendId = backend.id,
                error = "Saved dashboard session is unavailable. Reconnect this backend.",
            )
            restorePendingBillingCharge(backend.id)
            mutableStartupReady.value = true
            return
        }
        val target = backendRegistry.sessionTarget(backend.id)
        mutableState.value = HermesState(
            backend = backend,
            savedBackends = mutableState.value.savedBackends,
            loading = true,
            restoration = SessionRestorationState(
                status = SessionRestorationStatus.AUTHENTICATING,
                target = target,
            ),
            reconnectRequiredBackendId = null,
            backendTransitionInProgress = true,
        )
        restorePendingBillingCharge(backend.id)
        runCatching {
            val status = dashboardConnector.validateSaved(backend, cookie)
            gatewayBackendId = backend.id
            val sessions = restClient.sessions(backend, cookie.headerValue).sessions
            status to sessions
        }.onSuccess { (status, sessions) ->
            val restoration = resolveSessionTarget(
                target = target,
                availableBackendIds = setOf(backend.id),
                authenticatedBackendId = backend.id,
                sessions = sessions,
            )
            if (restoration.status == SessionRestorationStatus.SESSION_UNAVAILABLE ||
                restoration.status == SessionRestorationStatus.PROFILE_MISMATCH
            ) {
                sessionTargetMutex.withLock { backendRegistry.clearSessionTarget(backend.id) }
            }
            mutableState.value = mutableState.value.copy(
                status = status,
                sessions = sessions,
                activeStoredSession = restoration.session,
                loading = restoration.session != null,
                restoration = if (restoration.session != null) {
                    restoration.copy(status = SessionRestorationStatus.REHYDRATING)
                } else {
                    restoration
                },
                error = restoration.explanation,
                backendTransitionInProgress = false,
            )
            if (restoration.session != null) {
                openSession(restoration.session)
            } else {
                loadComposerState()
            }
            mutableStartupReady.value = true
        }.onFailure { error ->
            mutableState.value = mutableState.value.copy(backendTransitionInProgress = false)
            fail(error)
            mutableStartupReady.value = true
        }
    }

    private suspend fun restorePendingBillingCharge(backendId: String) {
        val pending = try {
            billingPendingChargeStore.get(backendId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Throwable) {
            mutableState.value = mutableState.value.copy(
                billingChargeUnconfirmed = true,
                billingError = "A saved purchase review could not be restored. Check your Nous balance before buying again.",
            )
            return
        } ?: return
        val resumable = !pending.chargeId.isNullOrBlank()
        if (!resumable) {
            billingIdempotencyKey = pending.idempotencyKey
            billingIdempotencyAmount = pending.amountUsd
            billingIdempotencyBackendId = backendId
            billingSettlementDeadlineEpochMillis = pending.settlementDeadlineEpochMillis
        }
        mutableState.value = mutableState.value.copy(
            billingBusy = resumable,
            billingChargeUnconfirmed = true,
            billingPortalUrl = pending.portalUrl,
            billingNotice = if (resumable) "Resuming the previous credit purchase check…" else null,
            billingError = if (resumable) null else {
                "A credit purchase was interrupted before Hermes returned a charge id. Check your balance before retrying."
            },
        )
        val chargeId = pending.chargeId ?: return
        scope.launch {
            billingAccountMutex.withLock {
                if (
                    mutableState.value.backend?.id != backendId ||
                    !mutableState.value.billingChargeUnconfirmed
                ) return@withLock
                try {
                    pollBillingCharge(
                        backendId,
                        chargeId,
                        pending.portalUrl,
                        pending.settlementDeadlineEpochMillis,
                    )
                } catch (cancelled: CancellationException) {
                    mutableState.value = mutableState.value.copy(
                        billingBusy = false,
                        billingChargeUnconfirmed = true,
                        billingNotice = null,
                        billingError = "Charge outcome remains unconfirmed. Check your balance before retrying.",
                    )
                    throw cancelled
                }
            }
        }
    }

    private fun currentDraftContext(): DraftContext? {
        val current = mutableState.value
        val backendId = current.backend?.id ?: return null
        val storedId = current.activeStoredSession?.durableId?.takeIf(String::isNotBlank)
            ?: current.runtimeInfo.storedSessionId.takeIf(String::isNotBlank)
        val profile = current.activeStoredSession?.profile ?: current.activeProfile
        return DraftContext(backendId, profile, storedId)
    }

    private fun currentComposerQueueContext(state: HermesState = mutableState.value): ComposerQueueContext? {
        val backendId = state.backend?.id ?: return null
        val sessionId = state.activeStoredSession?.durableId?.takeIf(String::isNotBlank)
            ?: state.runtimeInfo.storedSessionId.takeIf(String::isNotBlank)
            ?: state.runtimeSessionId?.takeIf(String::isNotBlank)
            ?: return null
        val profile = state.activeStoredSession?.profile ?: state.activeProfile
        return ComposerQueueContext(backendId, profile, sessionId)
    }

    private suspend fun loadComposerState() {
        loadDraft()
        queueDrainJob?.cancelAndJoin()
        queueDrainJob = null
        val context = currentComposerQueueContext()
        val loaded = context?.let {
            try {
                Result.success(composerQueueStore.get(it))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Result.failure(error)
            }
        }
        val queue = loaded?.getOrNull().orEmpty()
        if (currentComposerQueueContext() != context) return
        mutableState.value = mutableState.value.copy(
            queuedPrompts = queue,
            queueDraining = false,
            queueNotice = loaded?.exceptionOrNull()?.let { QUEUE_RECOVERY_MESSAGE },
            queueStorageHealthy = loaded?.isSuccess != false,
        )
        scheduleQueueDrain()
    }

    private fun scheduleQueueDrain() {
        if (queueDrainJob?.isActive == true) return
        if (gateway.connectionState.value !is GatewayConnectionState.Open) return
        if (!ComposerQueue.shouldAutoDrain(isRunBusy(mutableState.value), mutableState.value.queuedPrompts)) return
        queueDrainJob = scope.launch { drainQueuedPrompt() }
    }

    private suspend fun drainQueuedPrompt() {
        while (true) {
            val stateBefore = mutableState.value
            if (gateway.connectionState.value !is GatewayConnectionState.Open) return
            if (!ComposerQueue.shouldAutoDrain(isRunBusy(stateBefore), stateBefore.queuedPrompts)) return
            val context = currentComposerQueueContext(stateBefore) ?: return
            val sessionId = stateBefore.runtimeSessionId ?: return
            val entry = stateBefore.queuedPrompts.first()
            mutableState.value = stateBefore.copy(queueDraining = true, queueNotice = null)

            val submitted = runCatching {
                val response = gateway.request(
                    "prompt.submit",
                    buildJsonObject {
                        put("session_id", sessionId)
                        put("text", entry.text)
                    },
                )
                json.decodeFromJsonElement(PromptSubmitResult.serializer(), response).also { result ->
                    require(result.status in ACCEPTED_PROMPT_STATUSES) {
                        "Hermes rejected the queued message"
                    }
                }
            }

            if (submitted.isSuccess) {
                queueMutex.withLock {
                    val liveContext = currentComposerQueueContext()
                    if (liveContext == context) {
                        val current = mutableState.value
                        val next = ComposerQueue.remove(current.queuedPrompts, entry.id)
                        composerQueueStore.put(context, next)
                        mutableState.value = current.copy(
                            queuedPrompts = next,
                            queueDraining = false,
                            queueNotice = null,
                            timeline = TimelineReducer.insertAcceptedUserMessage(
                                current.timeline,
                                "local:${entry.id}",
                                entry.text,
                            ),
                            error = null,
                        )
                    } else {
                        composerQueueStore.put(context, ComposerQueue.remove(stateBefore.queuedPrompts, entry.id))
                    }
                }
                return
            }

            val failedQueue = queueMutex.withLock {
                if (currentComposerQueueContext() != context) return
                val current = mutableState.value
                val next = ComposerQueue.markAutoDrainFailure(current.queuedPrompts, entry.id)
                composerQueueStore.put(context, next)
                mutableState.value = current.copy(
                    queuedPrompts = next,
                    queueDraining = false,
                    queueNotice = if (next.firstOrNull()?.autoDrainFailures == ComposerQueue.MAX_AUTO_DRAIN_ATTEMPTS) {
                        "Hermes did not accept the pending message after ${ComposerQueue.MAX_AUTO_DRAIN_ATTEMPTS} attempts. Review it and send again."
                    } else {
                        "Hermes did not accept the pending message; retrying shortly."
                    },
                )
                next
            }
            if (!ComposerQueue.shouldAutoDrain(isRunBusy(mutableState.value), failedQueue)) return
            delay(QUEUE_DRAIN_RETRY_MILLIS)
        }
    }

    private fun isRunBusy(state: HermesState): Boolean =
        state.sending || state.runtimeInfo.running || state.timeline.items.any {
            it is com.nousresearch.hermes.domain.TimelineItem.Message && it.streaming
        }

    private suspend fun flushDraft() {
        draftSaveJob?.cancelAndJoin()
        draftSaveJob = null
        val context = currentDraftContext() ?: return
        draftStore.put(context, mutableState.value.draft)
    }

    private suspend fun loadDraft() {
        draftSaveJob?.cancelAndJoin()
        draftSaveJob = null
        val context = currentDraftContext()
        val draft = context?.let { draftStore.get(it) }.orEmpty()
        if (currentDraftContext() == context) {
            mutableState.value = mutableState.value.copy(draft = draft)
        }
    }

    private suspend fun clearDraft(contexts: List<DraftContext>) {
        draftSaveJob?.cancelAndJoin()
        draftSaveJob = null
        contexts.forEach { draftStore.remove(it) }
        mutableState.value = mutableState.value.copy(draft = "")
    }

    private suspend fun clearCurrentDraft() {
        slashCompletionJob?.cancelAndJoin()
        slashCompletionJob = null
        val context = currentDraftContext()
        if (context == null) {
            mutableState.value = mutableState.value.copy(draft = "")
        } else {
            clearDraft(listOf(context))
        }
        mutableState.value = mutableState.value.copy(
            slashSuggestions = emptyList(),
            slashLoading = false,
            slashQuery = "",
        )
    }

    private suspend fun loadSlashCatalog(): List<SlashSuggestion> {
        cachedSlashCatalog?.let { return it }
        return gateway.request("commands.catalog", buildJsonObject {})
            .let { json.decodeFromJsonElement(SlashCommandCatalog.serializer(), it) }
            .let(::mobileCatalogSuggestions)
            .also { suggestions ->
                cachedSlashCatalog = suggestions
                extensionSlashCommands = suggestions
                    .filter { it.group == "Skills" || it.group == "User commands" }
                    .map { it.text.substringBefore(' ').lowercase() }
                    .toSet()
            }
    }

    private suspend fun executeRemoteSlash(
        command: String,
        name: String,
        argument: String,
        depth: Int = 0,
    ) {
        require(depth < MAX_SLASH_ALIAS_DEPTH) { "Hermes slash alias depth exceeded" }
        val sessionId = mutableState.value.runtimeSessionId ?: error("Open a Hermes session before running /$name")
        val raw = try {
            gateway.request(
                "slash.exec",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("command", command.dropWhile { it == '/' })
                },
            )
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            gateway.request(
                "command.dispatch",
                buildJsonObject {
                    put("session_id", sessionId)
                    put("name", name)
                    put("arg", argument)
                },
            )
        }
        val result = json.decodeFromJsonElement(SlashCommandResult.serializer(), raw)
        result.notice?.takeIf(String::isNotBlank)?.let(::appendSlashOutput)
        when (result.type) {
            "alias" -> {
                val target = result.target?.trim().orEmpty()
                require(target.isNotEmpty()) { "/$name returned an empty alias" }
                val aliased = "/${target.removePrefix("/")}${argument.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()}"
                executeRemoteSlash(aliased, target.removePrefix("/").substringBefore(' ').lowercase(), argument, depth + 1)
            }
            "send", "skill" -> {
                require(!mutableState.value.runtimeInfo.running && !mutableState.value.sending) {
                    "Interrupt the current Hermes run before sending this command"
                }
                val message = result.message?.trim().orEmpty()
                require(message.isNotEmpty()) { "/$name returned an empty prompt" }
                if (result.type == "skill") appendSlashOutput("Loading skill: ${result.name ?: name}")
                mutableState.value = mutableState.value.copy(
                    draft = message,
                    slashSuggestions = emptyList(),
                    slashQuery = "",
                )
                send(message)
            }
            "prefill" -> {
                val message = result.message.orEmpty().take(DraftStore.MAX_DRAFT_CHARACTERS)
                mutableState.value = mutableState.value.copy(slashSuggestions = emptyList(), slashQuery = "")
                updateDraft(message)
            }
            "exec", "plugin" -> {
                clearCurrentDraft()
                appendSlashOutput(result.output?.takeIf(String::isNotBlank) ?: "(no output)")
            }
            null -> {
                clearCurrentDraft()
                val output = result.output?.takeIf(String::isNotBlank) ?: "/$name: no output"
                appendSlashOutput(result.warning?.takeIf(String::isNotBlank)?.let { "Warning: $it\n$output" } ?: output)
            }
            else -> error("Hermes returned unsupported slash result type ${result.type}")
        }
    }

    private fun appendSlashOutput(text: String) {
        mutableState.value = mutableState.value.copy(
            timeline = TimelineReducer.appendSystemMessage(
                mutableState.value.timeline,
                "slash:${UUID.randomUUID()}",
                text.take(MAX_SLASH_OUTPUT_CHARACTERS),
            ),
            error = null,
        )
    }

    private suspend fun activeCredentials(
        allowRecovery: Boolean = false,
        allowRehydrating: Boolean = false,
    ): Pair<BackendConfig, String> {
        val current = mutableState.value
        val backend = current.backend ?: error("No Hermes backend is selected")
        val restorationAllowed =
            (allowRecovery && current.restoration.status.allowsRecoveryRequest()) ||
                (allowRehydrating && current.restoration.status == SessionRestorationStatus.REHYDRATING)
        if (!current.restoration.mutationsEnabled && !restorationAllowed) {
            throw IllegalStateException(
                current.restoration.explanation ?: "Wait for Hermes to finish restoring the active session",
            )
        }
        if (backend.authMode != AuthMode.DASHBOARD_SESSION) throw ReconnectRequiredException(
            "Legacy backend credentials cannot be used; reconnect is required.",
        )
        val cookie = tokenStore.get(backend.id) ?: throw ReconnectRequiredException("Dashboard session is unavailable; reconnect is required.")
        if (gateway.connectionState.value !is GatewayConnectionState.Open || gatewayBackendId != backend.id) {
            gateway.connect(backend, cookie)
            gatewayBackendId = backend.id
        }
        return backend to cookie.headerValue
    }

    private suspend fun persistSessionTargetIfCurrent(
        requestGeneration: Long,
        target: SessionTarget,
    ): Boolean = sessionTargetMutex.withLock {
        if (openSessionGeneration.get() != requestGeneration || mutableState.value.backend?.id != target.backendId) {
            return@withLock false
        }
        backendRegistry.saveSessionTarget(target)
        openSessionGeneration.get() == requestGeneration && mutableState.value.backend?.id == target.backendId
    }

    private suspend fun persistActiveSessionTargetIfCurrent(target: SessionTarget): Boolean = sessionTargetMutex.withLock {
        val current = mutableState.value
        if (
            current.backend?.id != target.backendId ||
            current.activeStoredSession?.durableId != target.sessionId ||
            current.activeStoredSession?.profile.normalizedProfile() != target.profile
        ) {
            return@withLock false
        }
        backendRegistry.saveSessionTarget(target)
        true
    }

    private suspend fun clearSessionTargetIfCurrent(
        requestGeneration: Long,
        backendId: String,
    ): Boolean = sessionTargetMutex.withLock {
        if (openSessionGeneration.get() != requestGeneration || mutableState.value.backend?.id != backendId) {
            return@withLock false
        }
        backendRegistry.clearSessionTarget(backendId)
        true
    }

    private fun isCurrentSessionRequest(
        requestGeneration: Long,
        backendId: String,
        session: StoredSession,
        runtimeSessionId: String,
    ): Boolean = mutableState.value.let { live ->
        openSessionGeneration.get() == requestGeneration &&
            live.backend?.id == backendId &&
            live.activeStoredSession?.durableId == session.durableId &&
            live.activeStoredSession?.profile == session.profile &&
            live.runtimeSessionId == runtimeSessionId
    }

    private data class SessionContentContext(
        val backendId: String,
        val profile: String?,
        val durableSessionId: String,
        val runtimeSessionId: String,
    )

    private fun currentSessionContentContext(): SessionContentContext? {
        val current = mutableState.value
        val backendId = current.backend?.id ?: return null
        val runtimeSessionId = current.runtimeSessionId?.takeIf(String::isNotBlank) ?: return null
        val durableSessionId = current.activeStoredSession?.durableId?.takeIf(String::isNotBlank)
            ?: current.runtimeInfo.storedSessionId.takeIf(String::isNotBlank)
            ?: return null
        return SessionContentContext(
            backendId = backendId,
            profile = current.activeStoredSession?.profile ?: current.activeProfile,
            durableSessionId = durableSessionId,
            runtimeSessionId = runtimeSessionId,
        )
    }

    private fun setLoading(value: Boolean) {
        mutableState.value = mutableState.value.copy(loading = value)
    }

    private fun failSessionListRefresh(error: Throwable) {
        if (error.isSessionAuthenticationFailure()) {
            fail(error)
        } else {
            mutableState.update {
                it.copy(
                    sessionListLoading = false,
                    sessionListError = error.message ?: "Could not refresh conversations",
                )
            }
        }
    }

    private fun Throwable.isSessionAuthenticationFailure(): Boolean =
        this is ReconnectRequiredException ||
            (this is com.nousresearch.hermes.network.HermesHttpException && statusCode in setOf(401, 403))

    private fun fail(error: Throwable) {
        val reconnect = error is ReconnectRequiredException || (error is com.nousresearch.hermes.network.HermesHttpException && error.statusCode in setOf(401, 403))
        val reconnectBackendId = mutableState.value.backend?.id
        if (reconnect) {
            invalidatePendingAttachments()
            reconnectBackendId?.let(tokenStore::remove)
            billingStepUpRunId = null
            billingStepUpSessionId = null
            gatewayBackendId = null
            mutableState.value = mutableState.value.copy(
                loading = false,
                sessionListLoading = false,
                sessionListError = null,
                sending = false,
                activeStoredSession = null,
                runtimeSessionId = null,
                runtimeInfo = SessionRuntimeInfo(),
                timeline = TimelineState(),
                pendingAttachments = emptyList(),
                queuedPrompts = emptyList(),
                queueDraining = false,
                queueNotice = null,
                restoration = SessionRestorationState(
                    status = SessionRestorationStatus.AUTHENTICATION_REQUIRED,
                    target = mutableState.value.restoration.target,
                    explanation = "Reconnect to this Hermes backend before continuing.",
                ),
                error = "Dashboard session expired or was rejected. Reconnect with your username and password.",
            )
            scope.launch {
                billingAccountMutex.withLock {
                    if (mutableState.value.backend?.id != reconnectBackendId) return@withLock
                    intentionalDisconnect = true
                    reconnectJob?.cancel()
                    gateway.disconnect()
                    mutableState.value = mutableState.value.copy(
                        backend = null,
                        status = null,
                        sessions = emptyList(),
                        sessionListLoading = false,
                        sessionListError = null,
                        reconnectRequiredBackendId = reconnectBackendId,
                    )
                }
            }
            return
        }
        mutableState.value = mutableState.value.copy(
            backend = mutableState.value.backend,
            loading = false,
            sending = false,
            modelsLoading = false,
            managementLoading = false,
            providersLoading = false,
            messagingLoading = false,
            gatewayRestarting = false,
            mcpLoading = false,
            usageLoading = false,
            checkpointsLoading = false,
            agentsLoading = false,
            spawnTreesLoading = false,
            skillHubLoading = false,
            toolsetsLoading = false,
            sessionSearchLoading = false,
            restoration = mutableState.value.restoration.let { restoration ->
                if (restoration.status == SessionRestorationStatus.AUTHENTICATING ||
                    restoration.status == SessionRestorationStatus.REHYDRATING
                ) {
                    restoration.copy(
                        status = SessionRestorationStatus.RECOVERY_REQUIRED,
                        explanation = error.message ?: "Hermes could not finish restoring this session.",
                    )
                } else {
                    restoration
                }
            },
            reconnectRequiredBackendId = mutableState.value.reconnectRequiredBackendId,
            error = error.message ?: error::class.simpleName ?: "Hermes operation failed",
        )
    }

    private fun failAgents(error: Throwable) {
        val reconnect = error is ReconnectRequiredException ||
            (error is com.nousresearch.hermes.network.HermesHttpException && error.statusCode in setOf(401, 403))
        if (reconnect) {
            fail(error)
            return
        }
        mutableState.value = mutableState.value.copy(
            agentsLoading = false,
            agentsError = error.message ?: error::class.simpleName ?: "Hermes orchestration request failed",
        )
    }

    private fun failSpawnTrees(error: Throwable) {
        val reconnect = error is ReconnectRequiredException ||
            (error is com.nousresearch.hermes.network.HermesHttpException && error.statusCode in setOf(401, 403))
        if (reconnect) {
            fail(error)
            return
        }
        mutableState.value = mutableState.value.copy(
            spawnTreesLoading = false,
            spawnTreesError = DiagnosticRedactor.redact(
                error.message ?: error::class.simpleName ?: "Hermes spawn-tree request failed",
            ),
        )
    }

    private fun failMcp(error: Throwable) {
        val reconnect = error is ReconnectRequiredException ||
            (error is com.nousresearch.hermes.network.HermesHttpException && error.statusCode in setOf(401, 403))
        if (reconnect) {
            fail(error)
            return
        }
        mutableState.value = mutableState.value.copy(
            mcpLoading = false,
            mcpError = DiagnosticRedactor.redact(
                error.message ?: error::class.simpleName ?: "Hermes MCP request failed",
            ),
        )
    }

    private fun failToolsets(error: Throwable) {
        val reconnect = error is ReconnectRequiredException ||
            (error is com.nousresearch.hermes.network.HermesHttpException && error.statusCode in setOf(401, 403))
        if (reconnect) {
            fail(error)
            return
        }
        mutableState.value = mutableState.value.copy(
            toolsetsLoading = false,
            toolsetError = DiagnosticRedactor.redact(
                error.message ?: error::class.simpleName ?: "Hermes toolset request failed",
            ),
        )
    }

    private fun failServerConfig(error: Throwable) {
        val reconnect = error is ReconnectRequiredException ||
            (error is com.nousresearch.hermes.network.HermesHttpException && error.statusCode in setOf(401, 403))
        if (reconnect) {
            fail(error)
            return
        }
        mutableState.value = mutableState.value.copy(
            serverConfigLoading = false,
            serverConfigError = DiagnosticRedactor.redact(
                error.message ?: error::class.simpleName ?: "Hermes configuration request failed",
            ),
        )
    }

    private fun failUsage(error: Throwable) {
        val reconnect = error is ReconnectRequiredException ||
            (error is com.nousresearch.hermes.network.HermesHttpException && error.statusCode in setOf(401, 403))
        if (reconnect) {
            fail(error)
            return
        }
        mutableState.value = mutableState.value.copy(
            usageLoading = false,
            usageError = DiagnosticRedactor.redact(
                error.message ?: error::class.simpleName ?: "Hermes usage request failed",
            ),
        )
    }

    private fun failCheckpoints(error: Throwable) {
        mutableState.value = mutableState.value.copy(
            checkpointsLoading = false,
            checkpointError = DiagnosticRedactor.redact(
                error.message ?: error::class.simpleName ?: "Hermes checkpoint request failed",
            ),
        )
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val backend = mutableState.value.backend ?: return@launch
            val cookie = tokenStore.get(backend.id) ?: return@launch
            val delays = listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)
            for ((index, retryDelay) in delays.withIndex()) {
                if (intentionalDisconnect || mutableState.value.backend?.id != backend.id) return@launch
                delay(retryDelay)
                val connected = billingAccountMutex.withLock {
                    if (intentionalDisconnect || mutableState.value.backend?.id != backend.id) return@launch
                    runCatching { gateway.connect(backend, cookie) }.isSuccess
                }
                if (connected) {
                    mutableState.value = mutableState.value.copy(error = null)
                    val active = mutableState.value.activeStoredSession
                    if (active != null) runCatching { openSession(active) }
                    return@launch
                }
                mutableState.value = mutableState.value.copy(
                    error = "Hermes reconnect attempt ${index + 1} failed; retrying in ${delays.getOrElse(index + 1) { retryDelay } / 1_000}s.",
                )
            }
            mutableState.value = mutableState.value.copy(
                error = "Hermes remains unreachable after bounded retries. Check the backend, TLS and network, then retry.",
            )
        }
    }
}

internal fun selectResumeMessages(
    prefetch: SessionMessagePage,
    resumed: SessionResumeResult,
): List<ProtocolMessage> {
    val resumedDurableId = resumed.durableSessionId?.takeIf(String::isNotBlank)
        ?: resumed.resumed?.takeIf(String::isNotBlank)
    val prefetchMatches = resumedDurableId == null || prefetch.sessionId == resumedDurableId
    val hasLiveProjection = resumed.running || resumed.inflight != null || resumed.queued != null
    return when {
        prefetchSupersedesResume(prefetch, resumed) -> prefetch.messages
        !prefetchMatches || hasLiveProjection -> resumed.messages
        resumed.messages.size > prefetch.messages.size -> resumed.messages
        else -> prefetch.messages
    }
}

internal fun prefetchSupersedesResume(
    prefetch: SessionMessagePage,
    resumed: SessionResumeResult,
): Boolean {
    val resumedDurableId = resumed.durableSessionId?.takeIf(String::isNotBlank)
        ?: resumed.resumed?.takeIf(String::isNotBlank)
    return (resumedDurableId == null || prefetch.sessionId == resumedDurableId) &&
        prefetch.messages.size > resumed.messages.size
}

internal fun resumedStoredSession(
    requested: StoredSession,
    resumed: SessionResumeResult,
): StoredSession {
    val durableId = resumed.durableSessionId?.takeIf(String::isNotBlank)
        ?: resumed.resumed?.takeIf(String::isNotBlank)
        ?: requested.durableId
    return requested.copy(sessionId = durableId)
}

private fun sessionTarget(backendId: String, session: StoredSession): SessionTarget =
    SessionTarget(
        backendId = backendId,
        profile = session.profile.normalizedProfile(),
        sessionId = session.durableId,
    )

private fun Throwable.isMissingSessionFailure(): Boolean {
    val message = generateSequence(this) { it.cause }
        .joinToString(" ") { it.message.orEmpty() }
        .lowercase()
    return "session not found" in message ||
        "unknown session" in message ||
        "session does not exist" in message ||
        "session unavailable" in message
}

private const val DIAGNOSTIC_POLL_INTERVAL_MILLIS = 1_000L
private const val DIAGNOSTIC_POLL_LIMIT = 120
private const val DRAFT_SAVE_DEBOUNCE_MILLIS = 300L
private const val MAX_SHARED_ATTACHMENTS = 5
private const val QUEUE_DRAIN_RETRY_MILLIS = 750L
private const val SESSION_SEARCH_DEBOUNCE_MILLIS = 300L
private const val MAX_SESSION_SEARCH_RESULTS = 100
private const val SLASH_COMPLETION_DEBOUNCE_MILLIS = 60L
private const val MAX_SLASH_TEXT_CHARACTERS = 2_000
private const val MAX_SLASH_OUTPUT_CHARACTERS = 20_000
private const val MAX_SLASH_SUGGESTIONS = 12
private const val MAX_SLASH_ALIAS_DEPTH = 5
private const val MAX_MESSAGING_VALUE_CHARACTERS = 32_768
private const val MAX_OAUTH_CODE_CHARACTERS = 8_192
private const val MAX_OAUTH_SESSION_ID_CHARACTERS = 512
private const val MAX_OAUTH_SESSION_SECONDS = 3_600L
private const val DEFAULT_OAUTH_POLL_SECONDS = 2L
private const val MIN_OAUTH_POLL_SECONDS = 1L
private const val MAX_OAUTH_POLL_SECONDS = 30L
private const val MAX_MCP_NAME_CHARACTERS = 200
private const val MAX_MCP_ENV_VALUE_CHARACTERS = 32_768
private const val MAX_TOOLSET_NAME_CHARACTERS = 200
private const val MCP_INSTALL_POLL_INTERVAL_MILLIS = 1_000L
private const val MCP_INSTALL_POLL_LIMIT = 240
private const val MAX_USAGE_LABEL_CHARACTERS = 200
private const val MAX_CONTEXT_CATEGORIES = 32
private const val MAX_CHECKPOINTS = 100
private const val MAX_CHECKPOINT_LABEL_CHARACTERS = 300
private const val MAX_AGENT_EVENT_SESSIONS = 32
private const val MAX_SPAWN_TREE_ARCHIVES = 30
private const val MAX_AGENTS_PER_SPAWN_TREE = 100
private const val MAX_SPAWN_TREE_PATH_CHARACTERS = 4_096
private const val MAX_SPAWN_TREE_LABEL_CHARACTERS = 200
private const val GATEWAY_RESTART_POLL_INTERVAL_MILLIS = 1_200L
private const val GATEWAY_RESTART_POLL_LIMIT = 18
private const val BILLING_SETTLEMENT_POLL_MILLIS = 2_000L
private const val BILLING_SETTLEMENT_CAP_MILLIS = 5 * 60 * 1_000L
private const val BILLING_MAX_RETRY_SECONDS = 30L
private val USAGE_PERIODS = setOf(7, 30, 90)
private val ACCEPTED_PROMPT_STATUSES = setOf("streaming", "queued", "steered")
private const val QUEUE_RECOVERY_MESSAGE =
    "Pending messages could not be loaded. They remain stored on this device; do not queue another message until recovery is available."

internal fun validateMcpInstall(entry: McpCatalogEntry, env: Map<String, String>): Map<String, String> {
    require(!entry.installed) { "This MCP catalog entry is already installed" }
    require(entry.authType != "oauth") {
        "This MCP requires a server-host OAuth flow that Android cannot complete remotely"
    }
    val requirements = entry.requiredEnv.associateBy { it.name }
    require(env.keys.all { it in requirements }) { "The MCP install contained an unadvertised environment value" }
    val cleaned = env.mapValues { (_, value) -> value.trim().take(MAX_MCP_ENV_VALUE_CHARACTERS) }
        .filterValues(String::isNotBlank)
    require(entry.requiredEnv.filter { it.required }.all { it.name in cleaned }) {
        "Complete every required MCP credential before installing"
    }
    return cleaned
}

private val MOBILE_SLASH_COMMANDS = setOf(
    "/agents", "/background", "/bg", "/btw", "/branch", "/compress", "/debug",
    "/goal", "/new", "/personality", "/q", "/queue", "/reset", "/retry", "/rollback",
    "/save", "/status", "/steer", "/stop", "/title", "/tools", "/undo", "/usage", "/version",
)

internal fun mobileCatalogSuggestions(catalog: SlashCommandCatalog): List<SlashSuggestion> {
    val categorized = catalog.categories.flatMap { category ->
        category.pairs.mapNotNull { pair ->
            val command = pair.getOrNull(0)?.lowercase() ?: return@mapNotNull null
            val userCommand = category.name == "User commands"
            if (!userCommand && command !in MOBILE_SLASH_COMMANDS) return@mapNotNull null
            SlashSuggestion(
                text = pair[0],
                display = pair[0],
                meta = pair.getOrNull(1).orEmpty(),
                group = if (userCommand) "User commands" else category.name.ifBlank { "Commands" },
            )
        }
    }
    val categorizedNames = catalog.categories.flatMap { it.pairs }.mapNotNull { it.firstOrNull()?.lowercase() }.toSet()
    val skills = catalog.pairs.mapNotNull { pair ->
        val command = pair.getOrNull(0) ?: return@mapNotNull null
        if (command.lowercase() in categorizedNames) return@mapNotNull null
        SlashSuggestion(command, command, pair.getOrNull(1).orEmpty(), "Skills")
    }
    return (categorized + skills).distinctBy { it.text.lowercase() }
}

internal fun mobileCompletionSuggestions(
    query: String,
    completion: SlashCompletionResult,
    extensionCommands: Set<String>,
): List<SlashSuggestion> {
    val argumentCompletion = completion.replaceFrom > 1
    val prefix = if (argumentCompletion) query.take(completion.replaceFrom.coerceIn(0, query.length)) else ""
    return completion.items.mapNotNull { item ->
        val fullText = if (argumentCompletion) prefix + item.text else item.text.let { if (it.startsWith('/')) it else "/$it" }
        if (!argumentCompletion && !isMobileSlashCommand(fullText, extensionCommands)) return@mapNotNull null
        SlashSuggestion(
            text = fullText,
            display = item.display.ifBlank { item.text },
            meta = item.meta,
            group = if (argumentCompletion) "Options" else if (fullText.substringBefore(' ').lowercase() in extensionCommands) "Skills" else "Commands",
        )
    }.distinctBy { it.text.lowercase() }
}

private fun isMobileSlashCommand(command: String, extensionCommands: Set<String>): Boolean {
    val base = command.substringBefore(' ').lowercase()
    return base in MOBILE_SLASH_COMMANDS || base in extensionCommands
}

private fun String.isSafeModelToken(): Boolean =
    isNotBlank() && length <= 512 && !startsWith('-') && none { it.isWhitespace() || it.isISOControl() }

internal fun validateBillingAmount(raw: String, minimum: String? = null, maximum: String? = null): String {
    val clean = raw.trim()
    require(clean.matches(Regex("\\d+(?:\\.\\d{1,2})?"))) { "Enter a dollar amount with at most 2 decimal places" }
    val amount = BigDecimal(clean)
    require(amount > BigDecimal.ZERO) { "Amount must be greater than $0" }
    minimum?.let { require(amount >= BigDecimal(it)) { "Minimum is $$it" } }
    maximum?.let { require(amount <= BigDecimal(it)) { "Maximum is $$it" } }
    return amount.stripTrailingZeros().toPlainString()
}

internal fun billingChargeParams(amountUsd: String, idempotencyKey: String) = buildJsonObject {
    put("amount_usd", amountUsd)
    put("idempotency_key", idempotencyKey)
}

internal fun billingChargeStatusParams(chargeId: String) = buildJsonObject {
    put("charge_id", chargeId)
}

internal fun billingAutoReloadParams(enabled: Boolean, thresholdUsd: String, reloadToUsd: String) = buildJsonObject {
    put("enabled", enabled)
    put("threshold", thresholdUsd)
    put("top_up_amount", reloadToUsd)
}

internal fun billingStepUpParams(sessionId: String?) = buildJsonObject {
    sessionId?.let { put("session_id", it) }
}

internal fun billingRefusalMessage(
    code: String?,
    serverMessage: String?,
    actor: String? = null,
    payload: com.nousresearch.hermes.protocol.BillingErrorPayload? = null,
): String = when (code) {
    "consent_required" -> "Confirm this card for terminal charges in the portal."
    "insufficient_scope" -> "Terminal billing needs approval. Verify this device, then retry."
    "remote_spending_revoked" -> if (actor == "admin") {
        "An admin turned off terminal billing for this device. Reconnect it from the gateway settings."
    } else {
        "Terminal billing was turned off for this device. Reconnect it from the gateway settings."
    }
    "session_revoked" -> "Your Nous session was logged out. Sign in again from the gateway settings."
    "cli_billing_disabled", "remote_spending_disabled" -> "Terminal billing is off for this account. An admin must enable it in the portal."
    "role_required" -> "Adding funds needs an organisation admin or owner."
    "no_payment_method" -> "No saved card is available for terminal charges. Add one in the portal."
    "monthly_cap_exceeded" -> payload?.remainingUsd?.let {
        "The monthly spend cap has been reached with $$it headroom left."
    } ?: "The monthly spend cap has been reached."
    "rate_limited", "temporarily_unavailable" -> "Too many charges are being processed right now. Try again shortly."
    "stripe_unavailable" -> "Stripe is having trouble. Try again shortly."
    else -> serverMessage?.takeIf(String::isNotBlank) ?: "Billing request failed."
}

internal fun billingChargeFailureMessage(reason: String?): String = when (reason) {
    "authentication_required", "subscription_payment_intent_requires_action" ->
        "Your bank requires verification. Complete it in the portal."
    "payment_method_expired" -> "Your card has expired. Update it in the portal."
    "card_declined" -> "Your card was declined. Try another card in the portal."
    else -> "The charge did not go through (${reason ?: "processing_error"})."
}
