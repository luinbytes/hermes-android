package com.nousresearch.hermes.network

import com.nousresearch.hermes.data.BackendConfig
import com.nousresearch.hermes.data.AuthMode
import com.nousresearch.hermes.data.SessionCredentialStore
import com.nousresearch.hermes.data.buildServerConfigPatch
import com.nousresearch.hermes.protocol.ActionResponse
import com.nousresearch.hermes.protocol.ActionStatusResponse
import com.nousresearch.hermes.protocol.AnalyticsResponse
import com.nousresearch.hermes.protocol.AudioSpeakResponse
import com.nousresearch.hermes.protocol.AudioTranscriptionResponse
import com.nousresearch.hermes.protocol.BackendUpdateCheck
import com.nousresearch.hermes.protocol.BackupActionResponse
import com.nousresearch.hermes.protocol.EnvVarInfo
import com.nousresearch.hermes.protocol.FsDataUrlResponse
import com.nousresearch.hermes.protocol.FsTextPreview
import com.nousresearch.hermes.protocol.HostLogsResponse
import com.nousresearch.hermes.protocol.CronJob
import com.nousresearch.hermes.protocol.CronJobCreatePayload
import com.nousresearch.hermes.protocol.CronJobUpdates
import com.nousresearch.hermes.protocol.CronRunPage
import com.nousresearch.hermes.protocol.ActiveProfileResponse
import com.nousresearch.hermes.protocol.ProfileCreatePayload
import com.nousresearch.hermes.protocol.ProfileSetupCommandResponse
import com.nousresearch.hermes.protocol.ProfileSoulResponse
import com.nousresearch.hermes.protocol.ProfilesResponse
import com.nousresearch.hermes.protocol.ProviderValidationResult
import com.nousresearch.hermes.protocol.OAuthProvider
import com.nousresearch.hermes.protocol.OAuthProvidersResponse
import com.nousresearch.hermes.protocol.OAuthStartResponse
import com.nousresearch.hermes.protocol.OAuthPollResponse
import com.nousresearch.hermes.protocol.OAuthActionResponse
import com.nousresearch.hermes.protocol.OAuthSubmitResponse
import com.nousresearch.hermes.protocol.ModelOptionsResult
import com.nousresearch.hermes.protocol.ManagedFileReadResponse
import com.nousresearch.hermes.protocol.ManagedFilesResponse
import com.nousresearch.hermes.protocol.LearningMutationResponse
import com.nousresearch.hermes.protocol.LearningNodeDetail
import com.nousresearch.hermes.protocol.McpCatalogResponse
import com.nousresearch.hermes.protocol.McpCatalogInstallResponse
import com.nousresearch.hermes.protocol.McpOperationResponse
import com.nousresearch.hermes.protocol.McpServerTestResponse
import com.nousresearch.hermes.protocol.McpServerToggleResponse
import com.nousresearch.hermes.protocol.McpServersResponse
import com.nousresearch.hermes.protocol.MessagingPlatformTestResponse
import com.nousresearch.hermes.protocol.MessagingPlatformUpdateResponse
import com.nousresearch.hermes.protocol.MessagingPlatformsResponse
import com.nousresearch.hermes.protocol.SessionMessagePage
import com.nousresearch.hermes.protocol.SessionPage
import com.nousresearch.hermes.protocol.SessionSearchPage
import com.nousresearch.hermes.protocol.SkillInfo
import com.nousresearch.hermes.protocol.SkillHubPreview
import com.nousresearch.hermes.protocol.SkillHubScanResult
import com.nousresearch.hermes.protocol.SkillHubSearchResponse
import com.nousresearch.hermes.protocol.SkillHubSourcesResponse
import com.nousresearch.hermes.protocol.SkillToggleResult
import com.nousresearch.hermes.protocol.StatusResponse
import com.nousresearch.hermes.protocol.StarmapGraph
import com.nousresearch.hermes.protocol.ToolsetInfo
import com.nousresearch.hermes.protocol.ToolsetToggleResult
import com.nousresearch.hermes.protocol.ServerConfigMutationResponse
import com.nousresearch.hermes.protocol.ServerConfigSchemaResponse
import java.io.IOException
import java.io.OutputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class HermesRestClient(
    private val client: OkHttpClient,
    private val json: Json,
    private val credentials: SessionCredentialStore? = null,
) {
    suspend fun status(config: BackendConfig, token: String?): StatusResponse =
        get(config, token, "/api/status", StatusResponse.serializer())

    suspend fun status(config: BackendConfig, cookie: DashboardSessionCredential): StatusResponse =
        json.decodeFromJsonElement(
            StatusResponse.serializer(),
            request(config, cookie.headerValue, "/api/status", sessionCookie = cookie),
        )

    suspend fun sessions(
        config: BackendConfig,
        token: String,
        limit: Int = 50,
        offset: Int = 0,
        profile: String? = null,
    ): SessionPage = get(
        config,
        token,
        "/api/profiles/sessions?limit=$limit&offset=$offset&order=recent&profile=${profile?.takeIf(String::isNotBlank)?.let(::encodePathSegment) ?: "all"}&exclude_sources=cron",
        SessionPage.serializer(),
    )

    suspend fun sessionMessages(
        config: BackendConfig,
        token: String,
        sessionId: String,
        profile: String?,
    ): SessionMessagePage {
        val profileQuery = profile?.let { "?profile=${encodePathSegment(it)}" }.orEmpty()
        return get(
            config,
            token,
            "/api/sessions/${encodePathSegment(sessionId)}/messages$profileQuery",
            SessionMessagePage.serializer(),
        )
    }

    suspend fun searchSessions(
        config: BackendConfig,
        token: String,
        query: String,
        profile: String,
        limit: Int = 30,
    ): SessionSearchPage {
        require(query.isNotBlank()) { "Session search query is required" }
        return get(
            config,
            token,
            "/api/sessions/search?q=${encodePathSegment(query.take(200))}&limit=${limit.coerceIn(1, 100)}&profile=${encodePathSegment(profile)}",
            SessionSearchPage.serializer(),
        )
    }

    suspend fun managedFiles(
        config: BackendConfig,
        token: String,
        path: String? = null,
    ): ManagedFilesResponse {
        val query = path?.takeIf(String::isNotBlank)?.let { "?path=${encodePathSegment(it)}" }.orEmpty()
        return get(config, token, "/api/files$query", ManagedFilesResponse.serializer())
    }

    suspend fun readManagedFile(
        config: BackendConfig,
        token: String,
        path: String,
    ): ManagedFileReadResponse = get(
        config,
        token,
        "/api/files/read?path=${encodePathSegment(path)}",
        ManagedFileReadResponse.serializer(),
    )

    suspend fun readFsDataUrl(
        config: BackendConfig,
        token: String,
        path: String,
    ): FsDataUrlResponse = boundedGet(
        config = config,
        token = token,
        path = "/api/fs/read-data-url?path=${encodePathSegment(path)}",
        maximumResponseBytes = MAX_FS_DATA_URL_RESPONSE_BYTES,
        serializer = FsDataUrlResponse.serializer(),
    )

    suspend fun readFsText(
        config: BackendConfig,
        token: String,
        path: String,
    ): FsTextPreview = boundedGet(
        config = config,
        token = token,
        path = "/api/fs/read-text?path=${encodePathSegment(path)}",
        maximumResponseBytes = MAX_FS_TEXT_RESPONSE_BYTES,
        serializer = FsTextPreview.serializer(),
    )

    suspend fun transcribeAudio(
        config: BackendConfig,
        token: String,
        profile: String,
        dataUrl: String,
        mimeType: String,
    ): AudioTranscriptionResponse = json.decodeFromJsonElement(
        AudioTranscriptionResponse.serializer(),
        request(
            config,
            token,
            "/api/audio/transcribe?${encodeQueryParameter("profile", profile)}",
            method = "POST",
            body = buildJsonObject {
                put("data_url", dataUrl)
                put("mime_type", mimeType)
            },
        ),
    )

    suspend fun speakText(
        config: BackendConfig,
        token: String,
        profile: String,
        text: String,
    ): AudioSpeakResponse = json.decodeFromJsonElement(
        AudioSpeakResponse.serializer(),
        request(
            config,
            token,
            "/api/audio/speak?${encodeQueryParameter("profile", profile)}",
            method = "POST",
            body = buildJsonObject { put("text", text) },
        ),
    )

    suspend fun messagingPlatforms(
        config: BackendConfig,
        token: String,
        profile: String,
    ): MessagingPlatformsResponse = get(
        config,
        token,
        "/api/messaging/platforms?profile=${encodePathSegment(profile)}",
        MessagingPlatformsResponse.serializer(),
    )

    suspend fun updateMessagingPlatform(
        config: BackendConfig,
        token: String,
        profile: String,
        platformId: String,
        enabled: Boolean? = null,
        env: Map<String, String> = emptyMap(),
        clearEnv: List<String> = emptyList(),
    ): MessagingPlatformUpdateResponse = json.decodeFromJsonElement(
        MessagingPlatformUpdateResponse.serializer(),
        request(
            config,
            token,
            "/api/messaging/platforms/${encodePathSegment(platformId)}?profile=${encodePathSegment(profile)}",
            method = "PUT",
            body = buildJsonObject {
                enabled?.let { put("enabled", it) }
                put("env", buildJsonObject { env.forEach { (key, value) -> put(key, value) } })
                put("clear_env", buildJsonArray { clearEnv.forEach(::add) })
            },
        ),
    )

    suspend fun testMessagingPlatform(
        config: BackendConfig,
        token: String,
        profile: String,
        platformId: String,
    ): MessagingPlatformTestResponse = json.decodeFromJsonElement(
        MessagingPlatformTestResponse.serializer(),
        request(
            config,
            token,
            "/api/messaging/platforms/${encodePathSegment(platformId)}/test?profile=${encodePathSegment(profile)}",
            method = "POST",
            body = buildJsonObject { },
        ),
    )

    suspend fun restartGateway(
        config: BackendConfig,
        token: String,
        profile: String,
    ): ActionResponse = startAction(
        config,
        token,
        "/api/gateway/restart?profile=${encodePathSegment(profile)}",
    )

    suspend fun mcpServers(
        config: BackendConfig,
        token: String,
        profile: String,
    ): McpServersResponse = get(
        config,
        token,
        "/api/mcp/servers?profile=${encodePathSegment(profile)}",
        McpServersResponse.serializer(),
    )

    suspend fun mcpCatalog(
        config: BackendConfig,
        token: String,
        profile: String,
    ): McpCatalogResponse = get(
        config,
        token,
        "/api/mcp/catalog?profile=${encodePathSegment(profile)}",
        McpCatalogResponse.serializer(),
    )

    suspend fun testMcpServer(
        config: BackendConfig,
        token: String,
        profile: String,
        name: String,
    ): McpServerTestResponse = json.decodeFromJsonElement(
        McpServerTestResponse.serializer(),
        request(
            config,
            token,
            "/api/mcp/servers/${encodePathSegment(name)}/test?profile=${encodePathSegment(profile)}",
            method = "POST",
            body = buildJsonObject { },
        ),
    )

    suspend fun setMcpServerEnabled(
        config: BackendConfig,
        token: String,
        profile: String,
        name: String,
        enabled: Boolean,
    ): McpServerToggleResponse = json.decodeFromJsonElement(
        McpServerToggleResponse.serializer(),
        request(
            config,
            token,
            "/api/mcp/servers/${encodePathSegment(name)}/enabled?profile=${encodePathSegment(profile)}",
            method = "PUT",
            body = buildJsonObject { put("enabled", enabled) },
        ),
    )

    suspend fun removeMcpServer(
        config: BackendConfig,
        token: String,
        profile: String,
        name: String,
    ): McpOperationResponse = json.decodeFromJsonElement(
        McpOperationResponse.serializer(),
        request(
            config,
            token,
            "/api/mcp/servers/${encodePathSegment(name)}?profile=${encodePathSegment(profile)}",
            method = "DELETE",
        ),
    )

    suspend fun installMcpCatalogEntry(
        config: BackendConfig,
        token: String,
        profile: String,
        name: String,
        env: Map<String, String>,
    ): McpCatalogInstallResponse = json.decodeFromJsonElement(
        McpCatalogInstallResponse.serializer(),
        request(
            config,
            token,
            "/api/mcp/catalog/install?profile=${encodePathSegment(profile)}",
            method = "POST",
            body = buildJsonObject {
                put("name", name)
                put("env", buildJsonObject { env.forEach { (key, value) -> put(key, value) } })
                put("enable", true)
            },
        ),
    )

    suspend fun usageAnalytics(
        config: BackendConfig,
        token: String,
        profile: String,
        days: Int,
    ): AnalyticsResponse {
        require(days in 1..3650) { "Usage period must be between 1 and 3,650 days" }
        return get(
            config,
            token,
            "/api/analytics/usage?days=$days&profile=${encodePathSegment(profile)}",
            AnalyticsResponse.serializer(),
        )
    }

    suspend fun serverConfig(
        config: BackendConfig,
        token: String,
        profile: String,
    ): JsonObject = request(
        config,
        token,
        "/api/config?profile=${encodePathSegment(profile)}",
    ).jsonObject

    suspend fun serverConfigSchema(
        config: BackendConfig,
        token: String,
    ): ServerConfigSchemaResponse = get(
        config,
        token,
        "/api/config/schema",
        ServerConfigSchemaResponse.serializer(),
    )

    suspend fun updateServerConfig(
        config: BackendConfig,
        token: String,
        profile: String,
        key: String,
        value: JsonElement,
    ): ServerConfigMutationResponse = json.decodeFromJsonElement(
        ServerConfigMutationResponse.serializer(),
        request(
            config,
            token,
            "/api/config?profile=${encodePathSegment(profile)}",
            method = "PUT",
            body = buildJsonObject { put("config", buildServerConfigPatch(key, value)) },
        ),
    )

    suspend fun downloadManagedFile(
        config: BackendConfig,
        token: String,
        path: String,
        output: OutputStream,
        onProgress: (bytesCopied: Long, totalBytes: Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val base = TransportPolicy.validate(config).getOrThrow().toString().trimEnd('/')
        val request = Request.Builder()
            .url("$base/api/files/download?path=${encodePathSegment(path)}")
            .get()
            .header("Accept", "application/octet-stream")
            .header("User-Agent", "Hermes-Android/0.1")
            .apply {
                if (config.authMode == com.nousresearch.hermes.data.AuthMode.DASHBOARD_SESSION) {
                    header("Cookie", token)
                } else {
                    header("Authorization", "Bearer $token")
                }
            }
            .build()
        val call = client.newCall(request)
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = response.body?.string().orEmpty().take(500)
                    throw HermesHttpException(response.code, detail.ifBlank { response.message })
                }
                updateStoredSession(config, token, response.headers.values("Set-Cookie"))
                val body = response.body ?: throw IOException("Hermes returned an empty file response")
                val total = body.contentLength().takeIf { it >= 0 }
                body.byteStream().use { input ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        onProgress(copied, total)
                    }
                    output.flush()
                }
            }
        } finally {
            cancellation?.dispose()
        }
    }

    suspend fun renameSession(
        config: BackendConfig,
        token: String,
        sessionId: String,
        title: String,
        profile: String?,
    ) {
        val body = buildJsonObject {
            put("title", title)
            profile?.let { put("profile", it) }
        }
        request(
            config,
            token,
            "/api/sessions/${encodePathSegment(sessionId)}",
            method = "PATCH",
            body = body,
        )
    }

    suspend fun archiveSession(
        config: BackendConfig,
        token: String,
        sessionId: String,
        archived: Boolean,
        profile: String?,
    ) {
        val body = buildJsonObject {
            put("archived", archived)
            profile?.let { put("profile", it) }
        }
        request(
            config,
            token,
            "/api/sessions/${encodePathSegment(sessionId)}",
            method = "PATCH",
            body = body,
        )
    }

    suspend fun pinSession(
        config: BackendConfig,
        token: String,
        sessionId: String,
        pinned: Boolean,
        profile: String?,
    ) {
        val body = buildJsonObject {
            put("pinned", pinned)
            profile?.let { put("profile", it) }
        }
        request(
            config,
            token,
            "/api/sessions/${encodePathSegment(sessionId)}",
            method = "PATCH",
            body = body,
        )
    }

    suspend fun getJson(config: BackendConfig, token: String, path: String): JsonElement =
        request(config, token, path)

    suspend fun skills(config: BackendConfig, token: String): List<SkillInfo> =
        get(config, token, "/api/skills", ListSerializer(SkillInfo.serializer()))

    suspend fun toggleSkill(
        config: BackendConfig,
        token: String,
        name: String,
        enabled: Boolean,
    ): SkillToggleResult = json.decodeFromJsonElement(
        SkillToggleResult.serializer(),
        request(
            config,
            token,
            "/api/skills/toggle",
            method = "PUT",
            body = buildJsonObject {
                put("name", name)
                put("enabled", enabled)
            },
        ),
    )

    suspend fun toolsets(
        config: BackendConfig,
        token: String,
        profile: String,
    ): List<ToolsetInfo> = get(
        config,
        token,
        "/api/tools/toolsets?profile=${encodePathSegment(profile)}",
        ListSerializer(ToolsetInfo.serializer()),
    )

    suspend fun setToolsetEnabled(
        config: BackendConfig,
        token: String,
        profile: String,
        name: String,
        enabled: Boolean,
    ): ToolsetToggleResult = json.decodeFromJsonElement(
        ToolsetToggleResult.serializer(),
        request(
            config,
            token,
            "/api/tools/toolsets/${encodePathSegment(name)}?profile=${encodePathSegment(profile)}",
            method = "PUT",
            body = buildJsonObject { put("enabled", enabled) },
        ),
    )

    suspend fun cronJobs(config: BackendConfig, token: String): List<CronJob> =
        get(config, token, "/api/cron/jobs", ListSerializer(CronJob.serializer()))

    suspend fun cronRuns(
        config: BackendConfig,
        token: String,
        jobId: String,
        limit: Int = 20,
    ): CronRunPage = get(
        config,
        token,
        "/api/cron/jobs/${encodePathSegment(jobId)}/runs?limit=${limit.coerceIn(1, 100)}",
        CronRunPage.serializer(),
    )

    suspend fun setCronEnabled(
        config: BackendConfig,
        token: String,
        jobId: String,
        enabled: Boolean,
    ): CronJob = json.decodeFromJsonElement(
        CronJob.serializer(),
        request(
            config,
            token,
            "/api/cron/jobs/${encodePathSegment(jobId)}/${if (enabled) "resume" else "pause"}",
            method = "POST",
            body = buildJsonObject { },
        ),
    )

    suspend fun triggerCron(config: BackendConfig, token: String, jobId: String): CronJob =
        json.decodeFromJsonElement(
            CronJob.serializer(),
            request(
                config,
                token,
                "/api/cron/jobs/${encodePathSegment(jobId)}/trigger",
                method = "POST",
                body = buildJsonObject { },
            ),
        )

    suspend fun createCron(
        config: BackendConfig,
        token: String,
        payload: CronJobCreatePayload,
    ): CronJob = json.decodeFromJsonElement(
        CronJob.serializer(),
        request(
            config,
            token,
            "/api/cron/jobs",
            method = "POST",
            body = json.encodeToJsonElement(CronJobCreatePayload.serializer(), payload),
        ),
    )

    suspend fun updateCron(
        config: BackendConfig,
        token: String,
        jobId: String,
        updates: CronJobUpdates,
    ): CronJob = json.decodeFromJsonElement(
        CronJob.serializer(),
        request(
            config,
            token,
            "/api/cron/jobs/${encodePathSegment(jobId)}",
            method = "PUT",
            body = buildJsonObject {
                put("updates", json.encodeToJsonElement(CronJobUpdates.serializer(), updates))
            },
        ),
    )

    suspend fun deleteCron(config: BackendConfig, token: String, jobId: String) {
        request(
            config,
            token,
            "/api/cron/jobs/${encodePathSegment(jobId)}",
            method = "DELETE",
        )
    }

    suspend fun profiles(config: BackendConfig, token: String): ProfilesResponse =
        get(config, token, "/api/profiles", ProfilesResponse.serializer())

    suspend fun activeProfile(config: BackendConfig, token: String): ActiveProfileResponse =
        get(config, token, "/api/profiles/active", ActiveProfileResponse.serializer())

    suspend fun createProfile(config: BackendConfig, token: String, payload: ProfileCreatePayload) {
        request(
            config,
            token,
            "/api/profiles",
            method = "POST",
            body = json.encodeToJsonElement(ProfileCreatePayload.serializer(), payload),
        )
    }

    suspend fun renameProfile(config: BackendConfig, token: String, name: String, newName: String) {
        request(
            config,
            token,
            "/api/profiles/${encodePathSegment(name)}",
            method = "PATCH",
            body = buildJsonObject { put("new_name", newName) },
        )
    }

    suspend fun setActiveProfile(config: BackendConfig, token: String, name: String) {
        request(
            config,
            token,
            "/api/profiles/active",
            method = "POST",
            body = buildJsonObject { put("name", name) },
        )
    }

    suspend fun deleteProfile(config: BackendConfig, token: String, name: String) {
        request(
            config,
            token,
            "/api/profiles/${encodePathSegment(name)}",
            method = "DELETE",
        )
    }

    suspend fun profileSoul(config: BackendConfig, token: String, name: String): ProfileSoulResponse =
        boundedGet(
            config,
            token,
            "/api/profiles/${encodePathSegment(name)}/soul",
            MAX_PROFILE_TEXT_RESPONSE_BYTES,
            ProfileSoulResponse.serializer(),
        )

    suspend fun profileSetupCommand(
        config: BackendConfig,
        token: String,
        name: String,
    ): ProfileSetupCommandResponse = boundedGet(
        config,
        token,
        "/api/profiles/${encodePathSegment(name)}/setup-command",
        MAX_PROFILE_SETUP_RESPONSE_BYTES,
        ProfileSetupCommandResponse.serializer(),
    )

    suspend fun updateProfileSoul(config: BackendConfig, token: String, name: String, content: String) {
        require(content.length <= MAX_PROFILE_SOUL_CHARACTERS) { "SOUL.md is too large to edit on Android" }
        val result = json.decodeFromJsonElement(
            ServerConfigMutationResponse.serializer(),
            request(
                config,
                token,
                "/api/profiles/${encodePathSegment(name)}/soul",
                method = "PUT",
                body = buildJsonObject { put("content", content) },
            ),
        )
        require(result.ok) { "Hermes did not confirm the SOUL.md update" }
    }

    suspend fun updateProfileModel(
        config: BackendConfig,
        token: String,
        name: String,
        provider: String,
        model: String,
    ) {
        val cleanProvider = provider.trim()
        val cleanModel = model.trim()
        require(cleanProvider.isNotEmpty() && cleanProvider.length <= MAX_PROFILE_MODEL_CHARACTERS) {
            "A valid profile provider is required"
        }
        require(cleanModel.isNotEmpty() && cleanModel.length <= MAX_PROFILE_MODEL_CHARACTERS) {
            "A valid profile model is required"
        }
        val result = json.decodeFromJsonElement(
            ServerConfigMutationResponse.serializer(),
            request(
                config,
                token,
                "/api/profiles/${encodePathSegment(name)}/model",
                method = "PUT",
                body = buildJsonObject {
                    put("provider", cleanProvider)
                    put("model", cleanModel)
                },
            ),
        )
        require(result.ok) { "Hermes did not confirm the profile model update" }
    }

    suspend fun learningGraph(config: BackendConfig, token: String, profile: String): StarmapGraph {
        val cleanProfile = profile.trim()
        require(cleanProfile.isNotEmpty() && cleanProfile.length <= MAX_PROFILE_MODEL_CHARACTERS) {
            "A valid Hermes profile is required"
        }
        return boundedGet(
            config,
            token,
            "/api/learning/graph?profile=${encodePathSegment(cleanProfile)}",
            MAX_STARMAP_RESPONSE_BYTES,
            StarmapGraph.serializer(),
        )
    }

    suspend fun learningNode(
        config: BackendConfig,
        token: String,
        profile: String,
        id: String,
    ): LearningNodeDetail {
        val cleanProfile = profile.trim()
        val cleanId = id.trim()
        require(cleanProfile.isNotEmpty() && cleanProfile.length <= MAX_PROFILE_MODEL_CHARACTERS) {
            "A valid Hermes profile is required"
        }
        require(cleanId.isNotEmpty() && cleanId.length <= MAX_LEARNING_NODE_ID_CHARACTERS) {
            "A valid learning node is required"
        }
        return boundedGet(
            config,
            token,
            "/api/learning/node?id=${encodePathSegment(cleanId)}&profile=${encodePathSegment(cleanProfile)}",
            MAX_LEARNING_NODE_RESPONSE_BYTES,
            LearningNodeDetail.serializer(),
        )
    }

    suspend fun updateLearningNode(
        config: BackendConfig,
        token: String,
        profile: String,
        id: String,
        content: String,
    ) {
        val payload = learningNodePayload(profile, id, content)
        val result = json.decodeFromJsonElement(
            LearningMutationResponse.serializer(),
            request(config, token, "/api/learning/node", method = "PUT", body = payload),
        )
        require(result.ok) { result.message.ifBlank { "Hermes did not confirm the learning update" } }
    }

    suspend fun deleteLearningNode(config: BackendConfig, token: String, profile: String, id: String) {
        val payload = learningNodePayload(profile, id)
        val result = json.decodeFromJsonElement(
            LearningMutationResponse.serializer(),
            request(config, token, "/api/learning/node", method = "DELETE", body = payload),
        )
        require(result.ok) { result.message.ifBlank { "Hermes did not confirm the learning deletion" } }
    }

    private fun learningNodePayload(profile: String, id: String, content: String? = null): JsonObject {
        val cleanProfile = profile.trim()
        val cleanId = id.trim()
        require(cleanProfile.isNotEmpty() && cleanProfile.length <= MAX_PROFILE_MODEL_CHARACTERS) {
            "A valid Hermes profile is required"
        }
        require(cleanId.isNotEmpty() && cleanId.length <= MAX_LEARNING_NODE_ID_CHARACTERS) {
            "A valid learning node is required"
        }
        require(content == null || content.length <= MAX_LEARNING_NODE_CONTENT_CHARACTERS) {
            "Learning node content is too large to edit on Android"
        }
        return buildJsonObject {
            put("id", cleanId)
            put("profile", cleanProfile)
            content?.let { put("content", it) }
        }
    }

    suspend fun runDoctor(config: BackendConfig, token: String): ActionResponse =
        startAction(config, token, "/api/ops/doctor")

    suspend fun runSecurityAudit(config: BackendConfig, token: String): ActionResponse =
        startAction(config, token, "/api/ops/security-audit")

    suspend fun hostLogs(config: BackendConfig, token: String): HostLogsResponse = boundedGet(
        config,
        token,
        "/api/logs?file=agent&lines=200",
        MAX_HOST_LOG_RESPONSE_BYTES,
        HostLogsResponse.serializer(),
    )

    suspend fun hermesUpdateCheck(
        config: BackendConfig,
        token: String,
        force: Boolean = false,
    ): BackendUpdateCheck = boundedGet(
        config,
        token,
        "/api/hermes/update/check?force=${if (force) "true" else "false"}",
        MAX_UPDATE_CHECK_RESPONSE_BYTES,
        BackendUpdateCheck.serializer(),
    )

    suspend fun startBackup(config: BackendConfig, token: String): BackupActionResponse =
        json.decodeFromJsonElement(
            BackupActionResponse.serializer(),
            request(config, token, "/api/ops/backup", method = "POST", body = buildJsonObject { }),
        ).also { started ->
            require(started.ok && started.name == "backup" && started.pid > 0 && started.archive.isNotBlank()) {
                "Hermes did not return a usable backup receipt"
            }
        }

    suspend fun downloadBackup(
        config: BackendConfig,
        token: String,
        archive: String,
        output: OutputStream,
        onProgress: (bytesCopied: Long, totalBytes: Long?) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val cleanArchive = archive.trim()
        require(cleanArchive.isNotEmpty() && cleanArchive.length <= MAX_BACKUP_ARCHIVE_PATH_CHARACTERS) {
            "Hermes returned an invalid backup archive"
        }
        val base = TransportPolicy.validate(config).getOrThrow().toString().trimEnd('/')
        val request = Request.Builder()
            .url("$base/api/ops/backup/download?archive=${encodePathSegment(cleanArchive)}")
            .get()
            .header("Accept", "application/zip")
            .header("User-Agent", "Hermes-Android/0.1")
            .apply {
                if (config.authMode == AuthMode.DASHBOARD_SESSION) header("Cookie", token)
                else header("Authorization", "Bearer $token")
            }
            .build()
        val noRedirectClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val call = noRedirectClient.newCall(request)
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion { cause -> if (cause != null) call.cancel() }
        try {
            call.execute().use { response ->
                if (response.request.url != request.url) throw IOException("Hermes redirected an authenticated backup")
                if (!response.isSuccessful) {
                    val detail = response.body?.use { readBounded(it.byteStream(), MAX_ERROR_RESPONSE_BYTES) }.orEmpty()
                    throw HermesHttpException(response.code, detail.ifBlank { response.message })
                }
                updateStoredSession(config, token, response.headers.values("Set-Cookie"))
                val body = response.body ?: throw IOException("Hermes returned an empty backup")
                val mime = body.contentType()?.toString()?.substringBefore(';')?.lowercase()
                require(mime == "application/zip" || mime == "application/octet-stream") {
                    "Hermes returned a non-ZIP backup"
                }
                val total = body.contentLength().takeIf { it >= 0 }
                require(total == null || total <= MAX_BACKUP_DOWNLOAD_BYTES) { "Hermes backup exceeds the Android export limit" }
                body.byteStream().use { input ->
                    val buffer = ByteArray(DOWNLOAD_BUFFER_BYTES)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        copied += read
                        require(copied <= MAX_BACKUP_DOWNLOAD_BYTES) { "Hermes backup exceeds the Android export limit" }
                        output.write(buffer, 0, read)
                        onProgress(copied, total)
                    }
                    output.flush()
                }
            }
        } finally {
            cancellation?.dispose()
        }
    }

    suspend fun actionStatus(
        config: BackendConfig,
        token: String,
        name: String,
        lines: Int = 400,
        profile: String? = null,
    ): ActionStatusResponse {
        require(name in ALLOWED_ACTIONS || SKILL_ACTION.matches(name) || MCP_INSTALL_ACTION.matches(name)) {
            "Unsupported Hermes background action"
        }
        val query = buildList {
            add("lines=${lines.coerceIn(1, 2_000)}")
            profile?.let { add("profile=${encodePathSegment(it)}") }
        }.joinToString("&")
        return get(
            config,
            token,
            "/api/actions/$name/status?$query",
            ActionStatusResponse.serializer(),
        )
    }

    suspend fun globalModelOptions(
        config: BackendConfig,
        token: String,
        profile: String,
        refresh: Boolean = false,
    ): ModelOptionsResult = get(
        config,
        token,
        "/api/model/options?explicit_only=1&include_unconfigured=1&refresh=${if (refresh) 1 else 0}&profile=${encodePathSegment(profile)}",
        ModelOptionsResult.serializer(),
    )

    suspend fun envVars(config: BackendConfig, token: String, profile: String): Map<String, EnvVarInfo> = get(
        config,
        token,
        "/api/env?profile=${encodePathSegment(profile)}",
        MapSerializer(String.serializer(), EnvVarInfo.serializer()),
    )

    suspend fun oauthProviders(
        config: BackendConfig,
        token: String,
        profile: String,
    ): List<OAuthProvider> = get(
        config,
        token,
        "/api/providers/oauth?profile=${encodePathSegment(profile)}",
        OAuthProvidersResponse.serializer(),
    ).providers

    suspend fun startProviderOAuth(
        config: BackendConfig,
        token: String,
        profile: String,
        providerId: String,
    ): OAuthStartResponse = json.decodeFromJsonElement(
        OAuthStartResponse.serializer(),
        request(
            config,
            token,
            "/api/providers/oauth/${encodePathSegment(providerId)}/start?profile=${encodePathSegment(profile)}",
            method = "POST",
            body = buildJsonObject { },
        ),
    )

    suspend fun pollProviderOAuth(
        config: BackendConfig,
        token: String,
        profile: String,
        providerId: String,
        sessionId: String,
    ): OAuthPollResponse = get(
        config,
        token,
        "/api/providers/oauth/${encodePathSegment(providerId)}/poll/${encodePathSegment(sessionId)}?profile=${encodePathSegment(profile)}",
        OAuthPollResponse.serializer(),
    )

    suspend fun cancelProviderOAuth(
        config: BackendConfig,
        token: String,
        profile: String,
        sessionId: String,
    ): Boolean = json.decodeFromJsonElement(
        OAuthActionResponse.serializer(),
        request(
            config,
            token,
            "/api/providers/oauth/sessions/${encodePathSegment(sessionId)}?profile=${encodePathSegment(profile)}",
            method = "DELETE",
        ),
    ).ok

    suspend fun submitProviderOAuth(
        config: BackendConfig,
        token: String,
        profile: String,
        providerId: String,
        sessionId: String,
        code: String,
    ): OAuthSubmitResponse = json.decodeFromJsonElement(
        OAuthSubmitResponse.serializer(),
        request(
            config,
            token,
            "/api/providers/oauth/${encodePathSegment(providerId)}/submit?profile=${encodePathSegment(profile)}",
            method = "POST",
            body = buildJsonObject {
                put("session_id", sessionId)
                put("code", code)
            },
        ),
    )

    suspend fun disconnectProviderOAuth(
        config: BackendConfig,
        token: String,
        profile: String,
        providerId: String,
    ): Boolean = json.decodeFromJsonElement(
        OAuthActionResponse.serializer(),
        request(
            config,
            token,
            "/api/providers/oauth/${encodePathSegment(providerId)}?profile=${encodePathSegment(profile)}",
            method = "DELETE",
        ),
    ).ok

    suspend fun validateProviderCredential(
        config: BackendConfig,
        token: String,
        key: String,
        value: String,
        apiKey: String = "",
    ): ProviderValidationResult = json.decodeFromJsonElement(
        ProviderValidationResult.serializer(),
        request(
            config,
            token,
            "/api/providers/validate",
            method = "POST",
            body = buildJsonObject {
                put("key", key)
                put("value", value)
                put("api_key", apiKey)
            },
        ),
    )

    suspend fun setEnvVar(config: BackendConfig, token: String, profile: String, key: String, value: String) {
        request(
            config,
            token,
            "/api/env",
            method = "PUT",
            body = buildJsonObject {
                put("key", key)
                put("value", value)
                put("profile", profile)
            },
        )
    }

    suspend fun deleteEnvVar(config: BackendConfig, token: String, profile: String, key: String) {
        request(
            config,
            token,
            "/api/env",
            method = "DELETE",
            body = buildJsonObject {
                put("key", key)
                put("profile", profile)
            },
        )
    }

    suspend fun skillHubSources(config: BackendConfig, token: String, profile: String): SkillHubSourcesResponse = get(
        config,
        token,
        "/api/skills/hub/sources?profile=${encodePathSegment(profile)}",
        SkillHubSourcesResponse.serializer(),
    )

    suspend fun searchSkillHub(
        config: BackendConfig,
        token: String,
        profile: String,
        query: String,
    ): SkillHubSearchResponse = get(
        config,
        token,
        "/api/skills/hub/search?q=${encodePathSegment(query)}&source=all&limit=30&profile=${encodePathSegment(profile)}",
        SkillHubSearchResponse.serializer(),
    )

    suspend fun previewSkillHub(config: BackendConfig, token: String, profile: String, identifier: String): SkillHubPreview = get(
        config,
        token,
        "/api/skills/hub/preview?identifier=${encodePathSegment(identifier)}&profile=${encodePathSegment(profile)}",
        SkillHubPreview.serializer(),
    )

    suspend fun scanSkillHub(config: BackendConfig, token: String, profile: String, identifier: String): SkillHubScanResult = get(
        config,
        token,
        "/api/skills/hub/scan?identifier=${encodePathSegment(identifier)}&profile=${encodePathSegment(profile)}",
        SkillHubScanResult.serializer(),
    )

    suspend fun installSkillHub(config: BackendConfig, token: String, profile: String, identifier: String): ActionResponse =
        startAction(
            config,
            token,
            "/api/skills/hub/install",
            buildJsonObject { put("identifier", identifier); put("profile", profile) },
        )

    suspend fun uninstallSkillHub(config: BackendConfig, token: String, profile: String, name: String): ActionResponse =
        startAction(
            config,
            token,
            "/api/skills/hub/uninstall",
            buildJsonObject { put("name", name); put("profile", profile) },
        )

    suspend fun updateSkillsHub(config: BackendConfig, token: String, profile: String): ActionResponse =
        startAction(config, token, "/api/skills/hub/update", buildJsonObject { put("profile", profile) })

    private suspend fun startAction(
        config: BackendConfig,
        token: String,
        path: String,
        body: JsonElement = buildJsonObject { },
    ): ActionResponse =
        json.decodeFromJsonElement(
            ActionResponse.serializer(),
            request(config, token, path, method = "POST", body = body),
        )

    private suspend fun <T> get(
        config: BackendConfig,
        token: String?,
        path: String,
        serializer: DeserializationStrategy<T>,
    ): T = json.decodeFromJsonElement(serializer, request(config, token, path))

    private suspend fun <T> boundedGet(
        config: BackendConfig,
        token: String,
        path: String,
        maximumResponseBytes: Long,
        serializer: DeserializationStrategy<T>,
    ): T = withContext(Dispatchers.IO) {
        val base = TransportPolicy.validate(config).getOrThrow().toString().trimEnd('/')
        require(path.startsWith('/')) { "Hermes API paths must be absolute" }
        val request = Request.Builder()
            .url(base + path)
            .get()
            .header("Accept", "application/json")
            .header("User-Agent", "Hermes-Android/0.1")
            .apply {
                if (config.authMode == AuthMode.DASHBOARD_SESSION) {
                    header("Cookie", token)
                } else {
                    header("Authorization", "Bearer $token")
                }
            }
            .build()
        val noRedirectClient = client.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val call = noRedirectClient.newCall(request)
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion { cause ->
            if (cause != null) call.cancel()
        }
        try {
            call.execute().use { response ->
                if (response.request.url != request.url) {
                    throw IOException("Hermes redirected an authenticated file request")
                }
                val raw = response.body?.use { body ->
                    body.contentLength().takeIf { it >= 0 }?.let { length ->
                        if (length > maximumResponseBytes) {
                            throw IOException("Hermes response exceeds the Android safety limit")
                        }
                    }
                    readBounded(body.byteStream(), maximumResponseBytes)
                }.orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching {
                        json.parseToJsonElement(raw).toString().take(500)
                    }.getOrDefault(raw.take(500))
                    throw HermesHttpException(response.code, detail.ifBlank { response.message })
                }
                updateStoredSession(config, token, response.headers.values("Set-Cookie"))
                if (raw.isBlank()) throw IOException("Hermes returned an empty file response")
                json.decodeFromString(serializer, raw)
            }
        } finally {
            cancellation?.dispose()
        }
    }

    private suspend fun request(
        config: BackendConfig,
        token: String?,
        path: String,
        method: String = "GET",
        body: JsonElement? = null,
        sessionCookie: DashboardSessionCredential? = null,
    ): JsonElement = withContext(Dispatchers.IO) {
        val base = TransportPolicy.validate(config).getOrThrow().toString().trimEnd('/')
        require(path.startsWith('/')) { "Hermes API paths must be absolute" }
        val requestBody = body?.let {
            json.encodeToString(JsonElement.serializer(), it).toRequestBody(JSON_MEDIA_TYPE)
        }
        val request = Request.Builder()
            .url(base + path)
            .method(method, requestBody)
            .header("Accept", "application/json")
            .header("User-Agent", "Hermes-Android/0.1")
            .apply {
                if (!token.isNullOrBlank()) {
                    if (config.authMode == com.nousresearch.hermes.data.AuthMode.DASHBOARD_SESSION) {
                        header("Cookie", token)
                    } else {
                        header("Authorization", "Bearer $token")
                    }
                }
            }
            .build()

        client.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    json.parseToJsonElement(raw).toString().take(500)
                }.getOrDefault(raw.take(500))
                throw HermesHttpException(response.code, detail.ifBlank { response.message })
            }
            val setCookies = response.headers.values("Set-Cookie")
            if (sessionCookie != null) {
                sessionCookie.mergeSetCookieHeaders(setCookies)
            } else if (token != null) {
                updateStoredSession(config, token, setCookies)
            }
            if (raw.isBlank()) buildJsonObject { put("ok", true) } else json.parseToJsonElement(raw)
        }
    }

    suspend fun publicImageDataUrl(value: String, maximumBytes: Int = 2_000_000): String = withContext(Dispatchers.IO) {
        val url = value.toHttpUrlOrNull()
            ?: throw IOException("Hermes returned an invalid image URL")
        require(url.isHttps && url.username.isEmpty() && url.password.isEmpty()) {
            "Hermes returned an unsafe image URL"
        }
        val request = Request.Builder().url(url).header("Accept", "image/*").build()
        val noRedirectClient = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val call = noRedirectClient.newCall(request)
        val cancellation = currentCoroutineContext()[Job]?.invokeOnCompletion { cause -> if (cause != null) call.cancel() }
        try {
            call.execute().use { response ->
                if (!response.isSuccessful || response.isRedirect) throw IOException("Hermes image download failed (${response.code})")
                val body = response.body ?: throw IOException("Hermes returned an empty image")
                val mime = body.contentType()?.toString()?.substringBefore(';').orEmpty()
                require(mime in setOf("image/png", "image/jpeg", "image/webp")) { "Hermes returned an unsupported image format" }
                body.contentLength().takeIf { it >= 0 }?.let { require(it <= maximumBytes) { "Hermes image exceeds 2 MB" } }
                val bytes = readBoundedBytes(body.byteStream(), maximumBytes)
                "data:$mime;base64,${java.util.Base64.getEncoder().encodeToString(bytes)}"
            }
        } finally {
            cancellation?.dispose()
        }
    }

    private fun updateStoredSession(config: BackendConfig, sentHeader: String, setCookieHeaders: List<String>) {
        if (config.authMode != AuthMode.DASHBOARD_SESSION || setCookieHeaders.isEmpty()) return
        val current = credentials?.get(config.id) ?: return
        if (current.headerValue != sentHeader || !current.mergeSetCookieHeaders(setCookieHeaders)) return
        credentials.put(config.id, current)
    }

    private fun encodePathSegment(value: String): String =
        okhttp3.HttpUrl.Builder().scheme("https").host("placeholder.invalid").addPathSegment(value)
            .build().encodedPath.removePrefix("/")

    private fun encodeQueryParameter(name: String, value: String): String =
        okhttp3.HttpUrl.Builder().scheme("https").host("placeholder.invalid").addQueryParameter(name, value)
            .build().encodedQuery.orEmpty()

    private fun readBounded(input: java.io.InputStream, maximumBytes: Long): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maximumBytes) throw IOException("Hermes response exceeds the Android safety limit")
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun readBoundedBytes(input: java.io.InputStream, maximumBytes: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            require(output.size() + read <= maximumBytes) { "Hermes image exceeds 2 MB" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private companion object {
        val ALLOWED_ACTIONS = setOf("backup", "doctor", "security-audit", "gateway-restart")
        val SKILL_ACTION = Regex("skills-(?:install|uninstall|update)(?:-[a-z0-9-]{1,80})?")
        val MCP_INSTALL_ACTION = Regex("mcp-install-[a-z0-9-]{1,48}-[a-f0-9]{8}")
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val DOWNLOAD_BUFFER_BYTES = 64 * 1024
        const val MAX_ERROR_RESPONSE_BYTES = 8L * 1024L
        const val MAX_FS_DATA_URL_RESPONSE_BYTES = 23L * 1024L * 1024L
        const val MAX_FS_TEXT_RESPONSE_BYTES = 1L * 1024L * 1024L
        const val MAX_PROFILE_SOUL_CHARACTERS = 128 * 1024
        const val MAX_PROFILE_TEXT_RESPONSE_BYTES = 192L * 1024L
        const val MAX_PROFILE_SETUP_RESPONSE_BYTES = 16L * 1024L
        const val MAX_PROFILE_MODEL_CHARACTERS = 200
        const val MAX_LEARNING_NODE_ID_CHARACTERS = 512
        const val MAX_LEARNING_NODE_CONTENT_CHARACTERS = 256 * 1024
        const val MAX_STARMAP_RESPONSE_BYTES = 2L * 1024L * 1024L
        const val MAX_LEARNING_NODE_RESPONSE_BYTES = 384L * 1024L
        const val MAX_HOST_LOG_RESPONSE_BYTES = 512L * 1024L
        const val MAX_UPDATE_CHECK_RESPONSE_BYTES = 192L * 1024L
        const val MAX_BACKUP_ARCHIVE_PATH_CHARACTERS = 4_096
        const val MAX_BACKUP_DOWNLOAD_BYTES = 1L * 1024L * 1024L * 1024L
    }
}

class HermesHttpException(
    val statusCode: Int,
    detail: String,
) : IOException("Hermes returned HTTP $statusCode: $detail")
