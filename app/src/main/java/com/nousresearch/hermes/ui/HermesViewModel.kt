package com.nousresearch.hermes.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nousresearch.hermes.data.AuthMode
import com.nousresearch.hermes.data.BackendConfig
import com.nousresearch.hermes.data.ArtifactPreviewContent
import com.nousresearch.hermes.data.ArtifactPreviewRepository
import com.nousresearch.hermes.data.ArtifactIndexFilter
import com.nousresearch.hermes.data.DetectedArtifactIndex
import com.nousresearch.hermes.data.DetectedArtifactIndexSnapshot
import com.nousresearch.hermes.data.HermesArtifactSessionLoader
import com.nousresearch.hermes.data.DiagnosticAction
import com.nousresearch.hermes.data.HermesRepository
import com.nousresearch.hermes.data.backupReceiptError
import com.nousresearch.hermes.domain.DetectedArtifact
import com.nousresearch.hermes.network.HermesRestClient
import com.nousresearch.hermes.network.DashboardAuthProvider
import com.nousresearch.hermes.protocol.StoredSession
import com.nousresearch.hermes.platform.SharedContent
import com.nousresearch.hermes.platform.safeContentName
import com.nousresearch.hermes.security.SecureTokenStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

private const val ARTIFACT_SCOPE_KEY = "artifacts.scope"
private const val ARTIFACT_QUERY_KEY = "artifacts.query"
private const val ARTIFACT_FILTER_KEY = "artifacts.filter"
private const val HOST_BACKUP_POLL_LIMIT = 60
private const val HOST_BACKUP_POLL_INTERVAL_MILLIS = 2_000L

data class ArtifactIndexUiState(
    val backendId: String? = null,
    val profileId: String? = null,
    val snapshot: DetectedArtifactIndexSnapshot? = null,
    val loading: Boolean = false,
    val error: String? = null,
) {
    fun matches(backendId: String, profileId: String): Boolean =
        this.backendId == backendId && this.profileId == profileId
}

data class ArtifactBrowserPreferences(
    val scope: String? = null,
    val query: String = "",
    val filter: ArtifactIndexFilter = ArtifactIndexFilter.ALL,
)

data class HostBackupUiState(
    val backendId: String? = null,
    val preparing: Boolean = false,
    val saving: Boolean = false,
    val archive: String? = null,
    val suggestedName: String = "hermes-backup.zip",
    val progress: Float? = null,
    val notice: String? = null,
    val error: String? = null,
)

@HiltViewModel
class HermesViewModel @Inject constructor(
    private val repository: HermesRepository,
    private val restClient: HermesRestClient,
    private val tokenStore: SecureTokenStore,
    private val artifactPreviewRepository: ArtifactPreviewRepository,
    private val savedStateHandle: SavedStateHandle,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val state = repository.state.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), repository.state.value)
    val connectionState = repository.connectionState
    val startupReady = repository.startupReady.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    private val detectedArtifactIndex = DetectedArtifactIndex(HermesArtifactSessionLoader(restClient))
    private val mutableArtifactIndex = MutableStateFlow(ArtifactIndexUiState())
    private val mutableArtifactPreferences = MutableStateFlow(
        ArtifactBrowserPreferences(
            scope = savedStateHandle[ARTIFACT_SCOPE_KEY],
            query = savedStateHandle[ARTIFACT_QUERY_KEY] ?: "",
            filter = runCatching {
                ArtifactIndexFilter.valueOf(savedStateHandle[ARTIFACT_FILTER_KEY] ?: ArtifactIndexFilter.ALL.name)
            }.getOrDefault(ArtifactIndexFilter.ALL),
        ),
    )
    private var artifactRefreshJob: Job? = null
    private val mutableHostBackup = MutableStateFlow(HostBackupUiState())
    private var hostBackupJob: Job? = null
    private var hostBackupGeneration = 0L
    val artifactIndex = mutableArtifactIndex.asStateFlow()
    val artifactPreferences = mutableArtifactPreferences.asStateFlow()
    val hostBackup = mutableHostBackup.asStateFlow()

    fun bindHostBackupBackend(backendId: String?) {
        if (mutableHostBackup.value.backendId == null || mutableHostBackup.value.backendId == backendId) return
        hostBackupGeneration += 1
        hostBackupJob?.cancel()
        hostBackupJob = null
        mutableHostBackup.value = HostBackupUiState(backendId = backendId)
    }

    fun prepareHostBackup() {
        val backend = repository.state.value.backend ?: return
        val token = tokenStore.get(backend.id)?.headerValue ?: run {
            mutableHostBackup.value = HostBackupUiState(backendId = backend.id, error = "Reconnect Hermes before creating a backup")
            return
        }
        val generation = ++hostBackupGeneration
        hostBackupJob?.cancel()
        hostBackupJob = viewModelScope.launch {
            mutableHostBackup.value = HostBackupUiState(backendId = backend.id, preparing = true)
            try {
                val started = restClient.startBackup(backend, token)
                repeat(HOST_BACKUP_POLL_LIMIT) {
                    delay(HOST_BACKUP_POLL_INTERVAL_MILLIS)
                    val status = restClient.actionStatus(backend, token, "backup")
                    backupReceiptError(started, status)?.let { throw IOException(it) }
                    if (!status.running) {
                        if (hostBackupIsCurrent(generation, backend.id)) {
                            val rawName = started.archive.substringAfterLast('/').substringAfterLast('\\')
                            val safeName = safeContentName(rawName, "hermes-backup.zip").let {
                                if (it.endsWith(".zip", ignoreCase = true)) it else "$it.zip"
                            }
                            mutableHostBackup.value = HostBackupUiState(
                                backendId = backend.id,
                                archive = started.archive,
                                suggestedName = safeName,
                                notice = "Hermes confirmed the host backup. Choose where Android should save it.",
                            )
                        }
                        return@launch
                    }
                }
                throw IOException("Backup status timed out; the Hermes host may still be working")
            } catch (cancelled: CancellationException) {
                if (hostBackupIsCurrent(generation, backend.id)) {
                    mutableHostBackup.value = HostBackupUiState(
                        backendId = backend.id,
                        notice = "Stopped waiting. The Hermes host backup may still continue.",
                    )
                }
                throw cancelled
            } catch (error: Throwable) {
                if (hostBackupIsCurrent(generation, backend.id)) {
                    mutableHostBackup.value = HostBackupUiState(
                        backendId = backend.id,
                        error = error.message ?: "Hermes backup failed",
                    )
                }
            }
        }
    }

    fun saveHostBackup(destination: Uri) {
        val backend = repository.state.value.backend ?: return
        val current = mutableHostBackup.value
        val archive = current.archive?.takeIf { current.backendId == backend.id } ?: return
        val token = tokenStore.get(backend.id)?.headerValue ?: return
        val generation = ++hostBackupGeneration
        hostBackupJob?.cancel()
        hostBackupJob = viewModelScope.launch {
            mutableHostBackup.value = current.copy(saving = true, progress = null, notice = null, error = null)
            var complete = false
            try {
                val output = context.contentResolver.openOutputStream(destination, "w")
                    ?: throw IOException("Android could not open the selected backup destination")
                output.use {
                    restClient.downloadBackup(backend, token, archive, it) { copied, total ->
                        if (hostBackupIsCurrent(generation, backend.id)) {
                            mutableHostBackup.value = mutableHostBackup.value.copy(
                                progress = total?.takeIf { size -> size > 0 }?.let { size -> copied.toFloat() / size.toFloat() },
                            )
                        }
                    }
                }
                complete = true
                if (hostBackupIsCurrent(generation, backend.id)) {
                    mutableHostBackup.value = current.copy(saving = false, progress = null, notice = "Hermes backup saved through Android.")
                }
            } catch (cancelled: CancellationException) {
                if (hostBackupIsCurrent(generation, backend.id)) {
                    mutableHostBackup.value = current.copy(saving = false, progress = null, notice = "Backup export cancelled.")
                }
                throw cancelled
            } catch (error: Throwable) {
                if (hostBackupIsCurrent(generation, backend.id)) {
                    mutableHostBackup.value = current.copy(saving = false, progress = null, error = error.message ?: "Backup export failed")
                }
            } finally {
                if (!complete) runCatching { context.contentResolver.delete(destination, null, null) }
            }
        }
    }

    fun cancelHostBackup() {
        val current = mutableHostBackup.value
        hostBackupGeneration += 1
        hostBackupJob?.cancel()
        hostBackupJob = null
        mutableHostBackup.value = current.copy(
            preparing = false,
            saving = false,
            progress = null,
            notice = if (current.preparing) {
                "Stopped waiting. The Hermes host backup may still continue."
            } else {
                "Backup export cancelled."
            },
        )
    }

    private fun hostBackupIsCurrent(generation: Long, backendId: String): Boolean =
        hostBackupGeneration == generation && repository.state.value.backend?.id == backendId

    fun refreshArtifacts(backend: BackendConfig, profileId: String) {
        val normalizedProfile = profileId.trim()
        bindArtifactScope(backend.id, normalizedProfile)
        val current = mutableArtifactIndex.value
        if (
            normalizedProfile.isEmpty() ||
            (current.loading && current.backendId == backend.id && current.profileId == normalizedProfile)
        ) return
        artifactRefreshJob?.cancel()
        mutableArtifactIndex.value = ArtifactIndexUiState(
            backendId = backend.id,
            profileId = normalizedProfile,
            snapshot = current.snapshot?.takeIf {
                it.backendId == backend.id && it.profileId == normalizedProfile
            },
            loading = true,
        )
        artifactRefreshJob = viewModelScope.launch {
            try {
                val credential = tokenStore.get(backend.id)?.headerValue
                    ?: error("Reconnect ${backend.label} before loading detected artifacts")
                val snapshot = detectedArtifactIndex.load(backend, normalizedProfile, credential)
                if (mutableArtifactIndex.value.matches(backend.id, normalizedProfile)) {
                    mutableArtifactIndex.value = ArtifactIndexUiState(
                        backendId = backend.id,
                        profileId = normalizedProfile,
                        snapshot = snapshot,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Throwable) {
                if (mutableArtifactIndex.value.matches(backend.id, normalizedProfile)) {
                    mutableArtifactIndex.value = mutableArtifactIndex.value.copy(
                        loading = false,
                        error = failure.message ?: "Detected artifacts could not be loaded",
                    )
                }
            }
        }
    }

    suspend fun loadArtifactPreview(
        backend: BackendConfig,
        artifact: DetectedArtifact,
    ): ArtifactPreviewContent = artifactPreviewRepository.load(backend, artifact)

    fun updateArtifactQuery(query: String) {
        val bounded = query.take(200)
        mutableArtifactPreferences.value = mutableArtifactPreferences.value.copy(query = bounded)
        savedStateHandle[ARTIFACT_QUERY_KEY] = bounded
    }

    fun updateArtifactFilter(filter: ArtifactIndexFilter) {
        mutableArtifactPreferences.value = mutableArtifactPreferences.value.copy(filter = filter)
        savedStateHandle[ARTIFACT_FILTER_KEY] = filter.name
    }

    private fun bindArtifactScope(backendId: String, profileId: String) {
        val scope = "$backendId\u0000$profileId"
        if (mutableArtifactPreferences.value.scope == scope) return
        mutableArtifactPreferences.value = ArtifactBrowserPreferences(scope = scope)
        savedStateHandle[ARTIFACT_SCOPE_KEY] = scope
        savedStateHandle[ARTIFACT_QUERY_KEY] = ""
        savedStateHandle[ARTIFACT_FILTER_KEY] = ArtifactIndexFilter.ALL.name
    }

    fun connect(
        label: String,
        baseUrl: String,
        username: String,
        password: String,
        allowPrivateHttp: Boolean,
        passwordProvider: String? = null,
    ) {
        viewModelScope.launch {
            runCatching {
                repository.testAndSave(
                    dashboardConfig(label, baseUrl, allowPrivateHttp),
                    username.trim(),
                    password,
                    passwordProvider,
                )
            }
        }
    }

    suspend fun discoverDashboardPasswordProviders(
        baseUrl: String,
        allowPrivateHttp: Boolean,
    ): List<DashboardAuthProvider> = repository.discoverDashboardPasswordProviders(
        dashboardConfig("Hermes", baseUrl, allowPrivateHttp),
    )

    fun refresh() = viewModelScope.launch { repository.refreshSessions() }
    fun searchSessions(query: String) = repository.searchSessions(query)
    fun openSession(session: StoredSession) = viewModelScope.launch { repository.openSession(session) }
    fun openBotChat(profile: String, onOpened: (StoredSession?) -> Unit) = viewModelScope.launch {
        val opened = repository.openCanonicalBotChat(profile)
        onOpened(repository.state.value.activeStoredSession.takeIf { opened })
    }
    fun newSession(profile: String? = null) = viewModelScope.launch { repository.newSession(profile) }
    suspend fun newSessionFromEntry(profile: String? = null): Boolean = repository.newSession(profile)
    fun send(text: String) = viewModelScope.launch { repository.send(text) }
    fun steer(text: String) = viewModelScope.launch { repository.steer(text) }
    fun queueDraft() = viewModelScope.launch { repository.queueDraft() }
    fun updateQueuedPrompt(id: String, text: String) = viewModelScope.launch { repository.updateQueuedPrompt(id, text) }
    fun removeQueuedPrompt(id: String) = viewModelScope.launch { repository.removeQueuedPrompt(id) }
    fun sendQueuedPromptNow(id: String) = viewModelScope.launch { repository.sendQueuedPromptNow(id) }
    fun updateDraft(value: String) = repository.updateDraft(value)
    suspend fun ingestSharedContent(content: SharedContent): Boolean =
        repository.ingestSharedContent(content.text, content.uriStrings.map(Uri::parse))
    suspend fun discardSharedContent(content: SharedContent) =
        repository.discardSharedContentUris(content.uriStrings)
    fun completeSlash(text: String) = repository.completeSlash(text)
    fun executeSlash(command: String) = viewModelScope.launch { repository.executeSlash(command) }
    fun attach(uri: Uri) = viewModelScope.launch { repository.attach(uri) }
    fun retryAttachment(id: String) = viewModelScope.launch { repository.retryPendingAttachment(id) }
    fun cancelAttachment(id: String) = repository.cancelPendingAttachment(id)
    fun removeAttachment(id: String) = viewModelScope.launch { repository.removePendingAttachment(id) }
    fun refreshModels() = viewModelScope.launch { repository.refreshModelOptions(refresh = true) }
    fun selectModel(provider: String, model: String) = viewModelScope.launch { repository.selectModel(provider, model) }
    fun confirmModel() = viewModelScope.launch { repository.confirmModelSelection() }
    fun cancelModel() = repository.cancelModelSelection()
    fun setReasoning(effort: String) = viewModelScope.launch { repository.setReasoningEffort(effort) }
    fun setFast(enabled: Boolean) = viewModelScope.launch { repository.setFastMode(enabled) }
    fun setYolo(enabled: Boolean) = viewModelScope.launch { repository.setYolo(enabled) }
    fun interrupt() = viewModelScope.launch { repository.interrupt() }
    fun approve(choice: String) = viewModelScope.launch { repository.respondToApproval(choice) }
    fun clarify(answer: String) = viewModelScope.launch { repository.respondToClarification(answer) }
    fun submitSensitiveInput(value: String) = viewModelScope.launch { repository.respondToSensitiveInput(value) }
    fun archiveActive() = viewModelScope.launch { repository.archiveActive() }
    fun archiveSession(backendId: String, session: StoredSession) = viewModelScope.launch {
        repository.archiveSession(backendId, session)
    }
    fun pinSession(backendId: String, session: StoredSession) = viewModelScope.launch {
        repository.pinSession(backendId, session)
    }
    fun deleteSession(session: StoredSession) = viewModelScope.launch { repository.deleteSession(session) }
    fun renameActive(title: String) = viewModelScope.launch { repository.renameActive(title) }
    fun branchActive(name: String) = viewModelScope.launch { repository.branchActive(name) }
    fun undoLastTurn() = viewModelScope.launch { repository.undoLastTurn() }
    fun retryLastMessage() = viewModelScope.launch { repository.retryLastMessage() }
    fun resetActive() = viewModelScope.launch { repository.resetActive() }
    fun compressActive(focusTopic: String) = viewModelScope.launch { repository.compressActive(focusTopic) }
    fun refreshSkills() = viewModelScope.launch { repository.refreshSkills() }
    fun toggleSkill(name: String, enabled: Boolean) = viewModelScope.launch { repository.toggleSkill(name, enabled) }
    fun loadSkillHub(query: String) = viewModelScope.launch { repository.loadSkillHub(query) }
    fun reviewSkill(identifier: String) = viewModelScope.launch { repository.reviewSkill(identifier) }
    fun closeSkillReview() = repository.closeSkillReview()
    fun installReviewedSkill() = viewModelScope.launch { repository.installReviewedSkill() }
    fun uninstallSkill(name: String) = viewModelScope.launch { repository.uninstallSkill(name) }
    fun updateSkills() = viewModelScope.launch { repository.updateSkills() }
    fun refreshCron() = viewModelScope.launch { repository.refreshCronJobs() }
    fun refreshBotRoutines(owner: String) = viewModelScope.launch { repository.refreshBotRoutines(owner) }
    fun refreshCronRuns(jobId: String) = viewModelScope.launch { repository.refreshCronRuns(jobId) }
    fun setCronEnabled(jobId: String, enabled: Boolean) = viewModelScope.launch { repository.setCronEnabled(jobId, enabled) }
    fun triggerCron(jobId: String) = viewModelScope.launch { repository.triggerCron(jobId) }
    fun createCron(name: String, prompt: String, schedule: String, deliver: String) = viewModelScope.launch {
        repository.createCron(name, prompt, schedule, deliver)
    }
    fun createBotRoutine(owner: String, name: String, prompt: String, schedule: String, deliver: String) = viewModelScope.launch {
        repository.createBotRoutine(owner, name, prompt, schedule, deliver)
    }
    fun updateCron(jobId: String, name: String, prompt: String, schedule: String, deliver: String) = viewModelScope.launch {
        repository.updateCron(jobId, name, prompt, schedule, deliver)
    }
    fun deleteCron(jobId: String) = viewModelScope.launch { repository.deleteCron(jobId) }
    fun refreshProfiles() = viewModelScope.launch { repository.refreshProfiles() }
    fun refreshBotRoster() = viewModelScope.launch { repository.refreshProfiles(showLoading = false) }
    fun setBotHidden(profile: String, hidden: Boolean) = viewModelScope.launch {
        repository.setBotHidden(profile, hidden)
    }
    suspend fun createBotGroup(name: String, members: List<com.nousresearch.hermes.protocol.BotGroupMember>) =
        repository.createBotGroup(name, members)
    suspend fun botGroupCandidates() = repository.botGroupCandidates()
    suspend fun updateBotGroup(room: com.nousresearch.hermes.protocol.BotGroupRoom) = repository.updateBotGroup(room)
    suspend fun disbandBotGroup(roomId: String) = repository.disbandBotGroup(roomId)
    suspend fun sendBotGroupMessage(
        roomId: String,
        text: String,
        thread: String,
        attachments: List<com.nousresearch.hermes.protocol.BotGroupAttachment>,
    ) = repository.sendBotGroupMessage(roomId, text, thread, attachments)
    fun acknowledgeBotGroup(roomId: String) = repository.acknowledgeBotGroup(roomId)
    suspend fun answerBotGroupBlocking(requestId: String, answers: Map<String, List<String>>) =
        repository.answerBotGroupBlocking(requestId, answers)
    suspend fun describeBotAgent(profile: String) = repository.describeBotAgent(profile)
    suspend fun createBotAgent(
        draft: com.nousresearch.hermes.data.BotAgentDraft,
        backendId: String,
        cloneFrom: String?,
        cloneAll: Boolean,
        noSkills: Boolean,
        mirrorCredentials: Boolean,
    ) = repository.createBotAgent(draft, backendId, cloneFrom, cloneAll, noSkills, mirrorCredentials)
    suspend fun configureBotAgent(draft: com.nousresearch.hermes.data.BotAgentDraft) = repository.configureBotAgent(draft)
    suspend fun profileAvatar(profile: String) = repository.profileAvatar(profile)
    suspend fun setProfileAvatar(profile: String, dataUrl: String?) = repository.setProfileAvatar(profile, dataUrl)
    suspend fun generateProfileAvatar(profile: String, prompt: String) = repository.generateProfileAvatar(profile, prompt)
    suspend fun profilePetGallery(profile: String) = repository.profilePetGallery(profile)
    suspend fun adoptProfilePet(profile: String, slug: String) = repository.adoptProfilePet(profile, slug)
    suspend fun entryAuthoritySnapshot(includeCronJobs: Boolean) =
        repository.entryAuthoritySnapshot(includeCronJobs)
    fun renameProfile(name: String, newName: String) = viewModelScope.launch { repository.renameProfile(name, newName) }
    fun setActiveProfile(name: String) = viewModelScope.launch { repository.setActiveProfile(name) }
    fun deleteProfile(name: String) = viewModelScope.launch { repository.deleteProfile(name) }
    suspend fun profileIdentity(name: String) = repository.profileIdentity(name)
    suspend fun saveProfileSoul(name: String, content: String) = repository.saveProfileSoul(name, content)
    suspend fun saveProfileModel(name: String, provider: String, model: String) =
        repository.saveProfileModel(name, provider, model)
    fun refreshStarmap(profile: String) = viewModelScope.launch { repository.refreshStarmap(profile) }
    fun loadLearningNode(profile: String, id: String) = viewModelScope.launch { repository.loadLearningNode(profile, id) }
    fun updateLearningNode(profile: String, id: String, content: String) = viewModelScope.launch {
        repository.updateLearningNode(profile, id, content)
    }
    fun deleteLearningNode(profile: String, id: String) = viewModelScope.launch {
        repository.deleteLearningNode(profile, id)
    }
    fun closeLearningNode() = repository.closeLearningNode()
    fun runDiagnostic(action: DiagnosticAction) = viewModelScope.launch { repository.runDiagnostic(action) }
    fun refreshHostMaintenance(force: Boolean = false) = viewModelScope.launch {
        repository.refreshHostMaintenance(force)
    }
    fun refreshProviders() = viewModelScope.launch { repository.refreshProviders(refresh = true) }
    fun startProviderOAuth(providerId: String) = viewModelScope.launch { repository.startProviderOAuth(providerId) }
    fun submitProviderOAuth(code: String) = viewModelScope.launch { repository.submitProviderOAuth(code) }
    fun cancelProviderOAuth() = viewModelScope.launch { repository.cancelProviderOAuth() }
    fun disconnectProviderOAuth(providerId: String) = viewModelScope.launch { repository.disconnectProviderOAuth(providerId) }
    fun saveProviderSetting(key: String, value: String, apiKey: String) = viewModelScope.launch {
        repository.saveProviderSetting(key, value, apiKey)
    }
    fun deleteProviderSetting(key: String) = viewModelScope.launch { repository.deleteProviderSetting(key) }
    fun refreshMessaging() = viewModelScope.launch { repository.refreshMessaging() }
    fun setMessagingEnabled(platformId: String, enabled: Boolean) = viewModelScope.launch {
        repository.setMessagingEnabled(platformId, enabled)
    }
    fun saveMessagingSettings(platformId: String, values: Map<String, String>) = viewModelScope.launch {
        repository.saveMessagingSettings(platformId, values)
    }
    fun clearMessagingSetting(platformId: String, key: String) = viewModelScope.launch {
        repository.clearMessagingSetting(platformId, key)
    }
    fun testMessagingPlatform(platformId: String) = viewModelScope.launch { repository.testMessagingPlatform(platformId) }
    fun restartMessagingGateway() = viewModelScope.launch { repository.restartMessagingGateway() }
    fun refreshMcp() = viewModelScope.launch { repository.refreshMcp() }
    fun testMcpServer(name: String) = viewModelScope.launch { repository.testMcpServer(name) }
    fun setMcpServerEnabled(name: String, enabled: Boolean) = viewModelScope.launch {
        repository.setMcpServerEnabled(name, enabled)
    }
    fun removeMcpServer(name: String) = viewModelScope.launch { repository.removeMcpServer(name) }
    fun installMcpCatalogEntry(name: String, env: Map<String, String>) = viewModelScope.launch {
        repository.installMcpCatalogEntry(name, env)
    }
    fun refreshToolsets() = viewModelScope.launch { repository.refreshToolsets() }
    fun setToolsetEnabled(name: String, enabled: Boolean) = viewModelScope.launch {
        repository.setToolsetEnabled(name, enabled)
    }
    fun refreshServerConfig() = viewModelScope.launch { repository.refreshServerConfig() }
    fun updateServerConfig(key: String, value: JsonElement) = viewModelScope.launch {
        repository.updateServerConfig(key, value)
    }
    fun refreshUsage(days: Int) = viewModelScope.launch { repository.refreshUsage(days) }
    fun refreshBilling() = viewModelScope.launch { repository.refreshBilling() }
    fun chargeBillingCredits(amount: String) = viewModelScope.launch { repository.chargeBillingCredits(amount) }
    fun updateBillingAutoReload(enabled: Boolean, threshold: String, reloadTo: String) = viewModelScope.launch {
        repository.updateBillingAutoReload(enabled, threshold, reloadTo)
    }
    fun startBillingStepUp() = viewModelScope.launch { repository.startBillingStepUp() }
    fun acknowledgeUnconfirmedBillingCharge() = viewModelScope.launch {
        repository.acknowledgeUnconfirmedBillingCharge()
    }
    fun refreshCheckpoints() = viewModelScope.launch { repository.refreshCheckpoints() }
    fun previewCheckpoint(hash: String) = viewModelScope.launch { repository.previewCheckpoint(hash) }
    fun restoreCheckpoint(hash: String) = viewModelScope.launch { repository.restoreCheckpoint(hash) }
    fun refreshAgents() = viewModelScope.launch { repository.refreshAgents() }
    fun refreshSpawnTrees() = viewModelScope.launch { repository.refreshSpawnTrees() }
    fun loadSpawnTree(path: String) = viewModelScope.launch { repository.loadSpawnTree(path) }
    fun setDelegationPaused(paused: Boolean) = viewModelScope.launch { repository.setDelegationPaused(paused) }
    fun interruptSubagent(id: String) = viewModelScope.launch { repository.interruptSubagent(id) }
    fun stopBackgroundProcess(id: String) = viewModelScope.launch { repository.stopBackgroundProcess(id) }
    fun selectBackend(id: String) = viewModelScope.launch { repository.selectBackend(id) }
    fun forgetBackend(id: String) = viewModelScope.launch { repository.forgetBackend(id) }
    fun disconnect() = viewModelScope.launch { repository.disconnectAndForget() }
}

private fun dashboardConfig(label: String, baseUrl: String, allowPrivateHttp: Boolean): BackendConfig {
    val normalized = baseUrl.trim().trimEnd('/')
    return BackendConfig(
        id = normalized.sha256().take(20),
        label = label.trim().ifBlank { "Hermes" },
        baseUrl = normalized,
        authMode = AuthMode.DASHBOARD_SESSION,
        allowInsecurePrivateNetwork = allowPrivateHttp,
    )
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray())
    .joinToString("") { "%02x".format(it) }
