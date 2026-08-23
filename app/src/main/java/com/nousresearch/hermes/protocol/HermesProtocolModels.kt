package com.nousresearch.hermes.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
data class ImageAttachResult(
    val attached: Boolean,
    val path: String,
    val text: String? = null,
    val bytes: Long? = null,
)

@Serializable
data class PdfAttachPage(
    val path: String,
    val page: Int,
)

@Serializable
data class PdfAttachResult(
    val attached: Boolean,
    val filename: String,
    @SerialName("pages_attached") val pagesAttached: Int,
    val pages: List<PdfAttachPage>,
    val text: String? = null,
)

@Serializable
data class FileAttachResult(
    val attached: Boolean,
    val name: String,
    val path: String,
    @SerialName("ref_path") val refPath: String,
    @SerialName("ref_text") val refText: String,
    val uploaded: Boolean,
)

@Serializable
data class FsDataUrlResponse(
    @SerialName("dataUrl") val dataUrl: String,
)

@Serializable
data class FsTextPreview(
    val path: String,
    val text: String,
    @SerialName("mimeType") val mimeType: String,
    val language: String = "text",
    @SerialName("byteSize") val byteSize: Long,
    val binary: Boolean = false,
    val truncated: Boolean = false,
)

@Serializable
data class StatusResponse(
    val status: String = "unknown",
    val version: String? = null,
    @SerialName("hermes_version") val hermesVersion: String? = null,
    @SerialName("auth_required") val authRequired: Boolean = false,
    val capabilities: JsonElement? = null,
    @SerialName("capability_contract") val capabilityContract: JsonElement? = null,
)

/**
 * The versioned, server-owned part of the full-client compatibility contract.
 *
 * This is deliberately a normalized model rather than a direct copy of a
 * server JSON shape.  The parser below accepts the canonical envelope and the
 * older direct capability map, while keeping unknown fields forward
 * compatible and known malformed fields fail-closed.
 */
data class CapabilityContractDocument(
    val schemaVersion: Int,
    val contractVersion: Int,
    val authorized: Boolean,
    val resolvedProfile: String?,
    val profiles: Set<String>,
    val audience: String?,
    val scopes: Set<String>,
    val capabilities: Map<String, CapabilityDeclaration>,
    val source: CapabilityDocumentSource,
)

enum class CapabilityDocumentSource {
    CANONICAL,
    LEGACY_STATUS,
}

data class CapabilityDeclaration(
    val wireName: String,
    val advertised: Boolean,
    val methods: Map<String, CapabilityMethodContract>,
    val events: Set<String>,
    val profiles: Set<String>,
    val profileScope: CapabilityProfileScope,
    val scopes: Set<String>,
    val audience: String?,
    val minClientContract: Int?,
    val maxClientContract: Int?,
)

data class CapabilityMethodContract(
    val name: String,
    val requiredParameters: Set<String>,
    val optionalParameters: Set<String>,
    val scopes: Set<String>,
) {
    val allowedParameters: Set<String> get() = requiredParameters + optionalParameters
}

enum class CapabilityProfileScope {
    GLOBAL,
    PROFILE,
    UNKNOWN,
}

sealed interface CapabilityContractParseResult {
    data class Valid(val document: CapabilityContractDocument) : CapabilityContractParseResult

    data class Malformed(val reason: String) : CapabilityContractParseResult

    data class Unsupported(
        val compatibility: CapabilityContractCompatibility,
        val schemaVersion: Int,
    ) : CapabilityContractParseResult

    data object Missing : CapabilityContractParseResult
}

enum class CapabilityContractCompatibility {
    OLDER_SERVER,
    NEWER_SERVER,
}

/** Parses both the current envelope and the explicit legacy status adapter. */
object CapabilityContractParser {
    const val CURRENT_SCHEMA_VERSION = 1
    const val CURRENT_CLIENT_CONTRACT = 1

    fun parse(element: JsonElement?): CapabilityContractParseResult {
        if (element == null || element is JsonNull) return CapabilityContractParseResult.Missing
        if (element !is JsonObject) return CapabilityContractParseResult.Malformed("Capability document must be an object")

        return runCatching {
            val hasEnvelope = element.containsKey("capabilities") ||
                element.containsKey("schema_version") ||
                element.containsKey("contract_version")
            val source = if (hasEnvelope) CapabilityDocumentSource.CANONICAL else CapabilityDocumentSource.LEGACY_STATUS
            val schemaVersion = element.int("schema_version") ?: CURRENT_SCHEMA_VERSION
            if (schemaVersion < CURRENT_SCHEMA_VERSION) {
                return@runCatching CapabilityContractParseResult.Unsupported(
                    CapabilityContractCompatibility.OLDER_SERVER,
                    schemaVersion,
                )
            }
            if (schemaVersion > CURRENT_SCHEMA_VERSION) {
                return@runCatching CapabilityContractParseResult.Unsupported(
                    CapabilityContractCompatibility.NEWER_SERVER,
                    schemaVersion,
                )
            }

            val capabilityElement = if (hasEnvelope) {
                element["capabilities"] ?: JsonObject(emptyMap())
            } else {
                element
            }
            if (capabilityElement !is JsonObject) {
                return@runCatching CapabilityContractParseResult.Malformed("capabilities must be an object")
            }
            val capabilities = capabilityElement.mapNotNull { (wireName, raw) ->
                val known = HermesBackendCapability.fromWireName(wireName)
                    ?: return@mapNotNull null
                known.wireName to parseDeclaration(known.wireName, raw)
            }.toMap()

            CapabilityContractParseResult.Valid(
                CapabilityContractDocument(
                    schemaVersion = schemaVersion,
                    contractVersion = element.int("contract_version") ?: CURRENT_CLIENT_CONTRACT,
                    authorized = element.boolean("authorized") ?: true,
                    resolvedProfile = element.string("resolved_profile")
                        ?: element.string("profile"),
                    profiles = element.stringSet("profiles"),
                    audience = element.string("audience"),
                    scopes = element.stringSet("scopes"),
                    capabilities = capabilities,
                    source = source,
                ),
            )
        }.getOrElse { error ->
            CapabilityContractParseResult.Malformed(
                error.message?.take(240).orEmpty().ifBlank { "Capability document is malformed" },
            )
        }
    }

    private fun parseDeclaration(wireName: String, raw: JsonElement): CapabilityDeclaration {
        if (raw is JsonPrimitive && raw.isString.not()) {
            return CapabilityDeclaration(
                wireName = wireName,
                advertised = raw.booleanValueOrNull()
                    ?: throw IllegalArgumentException("$wireName advertised value must be boolean"),
                methods = emptyMap(),
                events = emptySet(),
                profiles = emptySet(),
                profileScope = CapabilityProfileScope.GLOBAL,
                scopes = emptySet(),
                audience = null,
                minClientContract = null,
                maxClientContract = null,
            )
        }
        val declaration = raw as? JsonObject
            ?: throw IllegalArgumentException("$wireName declaration must be an object or boolean")
        val profileScope = when {
            declaration.boolean("profile_scoped") == true -> CapabilityProfileScope.PROFILE
            declaration.string("profile_scope") == null -> CapabilityProfileScope.GLOBAL
            declaration.string("profile_scope")?.lowercase() in setOf("profile", "profiles") -> CapabilityProfileScope.PROFILE
            declaration.string("profile_scope")?.lowercase() == "global" -> CapabilityProfileScope.GLOBAL
            else -> CapabilityProfileScope.UNKNOWN
        }
        return CapabilityDeclaration(
            wireName = wireName,
            advertised = declaration.boolean("advertised") ?: declaration.boolean("enabled") ?: true,
            methods = parseMethods(declaration["methods"] ?: declaration["required_methods"]),
            events = declaration.stringSet("events"),
            profiles = declaration.stringSet("profiles"),
            profileScope = profileScope,
            scopes = declaration.stringSet("scopes"),
            audience = declaration.string("audience"),
            minClientContract = declaration.int("min_client") ?: declaration.int("min_client_contract"),
            maxClientContract = declaration.int("max_client") ?: declaration.int("max_client_contract"),
        )
    }

    private fun parseMethods(element: JsonElement?): Map<String, CapabilityMethodContract> {
        if (element == null) return emptyMap()
        val methods = element as? JsonObject ?: throw IllegalArgumentException("methods must be an object")
        return methods.map { (name, raw) ->
            val method = when (raw) {
                is JsonArray -> CapabilityMethodContract(
                    name = name,
                    requiredParameters = raw.stringSet(),
                    optionalParameters = emptySet(),
                    scopes = emptySet(),
                )
                is JsonObject -> CapabilityMethodContract(
                    name = name,
                    requiredParameters = raw.stringSet("parameters")
                        .ifEmpty { raw.stringSet("required_parameters") },
                    optionalParameters = raw.stringSet("optional_parameters"),
                    scopes = raw.stringSet("scopes"),
                )
                else -> throw IllegalArgumentException("method $name must be an object or array")
            }
            name to method
        }.toMap()
    }
}

fun StatusResponse.capabilityContract(): CapabilityContractParseResult =
    CapabilityContractParser.parse(capabilityContract ?: capabilities)

private fun JsonObject.string(name: String): String? {
    val value = this[name] ?: return null
    val primitive = value as? JsonPrimitive
        ?: throw IllegalArgumentException("$name must be a string")
    if (!primitive.isString) throw IllegalArgumentException("$name must be a string")
    return primitive.content.takeIf(String::isNotBlank)
}

private fun JsonObject.int(name: String): Int? {
    val value = this[name] ?: return null
    val primitive = value as? JsonPrimitive
        ?: throw IllegalArgumentException("$name must be an integer")
    if (primitive.isString) throw IllegalArgumentException("$name must be an integer")
    return primitive.content.toIntOrNull()
        ?: throw IllegalArgumentException("$name must be an integer")
}

private fun JsonObject.boolean(name: String): Boolean? {
    val value = this[name] ?: return null
    val primitive = value as? JsonPrimitive
        ?: throw IllegalArgumentException("$name must be a boolean")
    return primitive.booleanValueOrNull()
        ?: throw IllegalArgumentException("$name must be a boolean")
}

private fun JsonObject.stringSet(name: String): Set<String> = this[name]?.stringSet().orEmpty()

private fun JsonElement.stringSet(): Set<String> = when (this) {
    is kotlinx.serialization.json.JsonArray -> map { entry ->
        (entry as? JsonPrimitive)?.takeIf { it.isString }?.content
            ?: throw IllegalArgumentException("Expected a string array")
    }.filter(String::isNotBlank).toSet()
    is JsonPrimitive -> if (isString) setOf(content) else throw IllegalArgumentException("Expected strings")
    else -> throw IllegalArgumentException("Expected a string array")
}

private fun JsonPrimitive.booleanValueOrNull(): Boolean? = when {
    isString -> when (content.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
    else -> content.toBooleanStrictOrNull()
}

@Serializable
data class SessionPage(
    val sessions: List<StoredSession> = emptyList(),
    val total: Int = sessions.size,
    val limit: Int = sessions.size,
    val offset: Int = 0,
)

@Serializable
data class SessionSearchPage(
    val results: List<SessionSearchHit> = emptyList(),
)

@Serializable
data class SessionSearchHit(
    @SerialName("session_id") val sessionId: String,
    val snippet: String = "",
    val role: String? = null,
    val source: String? = null,
    val model: String? = null,
    @SerialName("session_started") val sessionStarted: Double = 0.0,
    val profile: String? = null,
)

@Serializable
data class StoredSession(
    @SerialName("session_id") val sessionId: String = "",
    val id: String? = null,
    val title: String? = null,
    @SerialName("root_title") val rootTitle: String? = null,
    val profile: String? = null,
    val source: String? = null,
    val model: String? = null,
    val provider: String? = null,
    val archived: Boolean = false,
    val pinned: Boolean? = null,
    @SerialName("is_active") val isActive: Boolean = false,
    @SerialName("message_count") val messageCount: Int = 0,
    @SerialName("started_at") val startedAt: Double = 0.0,
    @SerialName("last_active") val lastActive: Double = startedAt,
) {
    val durableId: String get() = sessionId.ifBlank { id.orEmpty() }
    val displayTitle: String get() = title?.takeIf(String::isNotBlank) ?: "Untitled session"
}

@Serializable
data class SessionMessagePage(
    @SerialName("session_id") val sessionId: String,
    val messages: List<ProtocolMessage> = emptyList(),
)

@Serializable
data class ProtocolMessage(
    @Serializable(with = NullableFlexibleStringSerializer::class)
    val id: String? = null,
    val role: String,
    val content: JsonElement? = null,
    val text: String? = null,
    val timestamp: Double? = null,
    @SerialName("tool_calls") val toolCalls: JsonElement? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    @SerialName("tool_name") val toolName: String? = null,
)

@Serializable
data class SessionCreateResult(
    @SerialName("session_id") val runtimeSessionId: String,
    @SerialName("stored_session_id") val durableSessionId: String? = null,
    val messages: List<ProtocolMessage> = emptyList(),
    val status: String = "idle",
    val running: Boolean = false,
    val info: SessionRuntimeInfo = SessionRuntimeInfo(),
)

@Serializable
data class SessionResumeResult(
    @SerialName("session_id") val runtimeSessionId: String,
    @SerialName("session_key") val durableSessionId: String? = null,
    val resumed: String? = null,
    val messages: List<ProtocolMessage> = emptyList(),
    val status: String = "idle",
    val running: Boolean = false,
    val inflight: SessionInflightProjection? = null,
    val queued: SessionQueuedProjection? = null,
    val info: SessionRuntimeInfo = SessionRuntimeInfo(),
    @SerialName("pending_clarify") val pendingClarify: PendingClarify? = null,
    @SerialName("pending_approval") val pendingApproval: PendingApproval? = null,
)

@Serializable
data class PendingClarifyQuestion(
    val qid: String? = null,
    val id: String? = null,
    val question: String = "",
    val choices: List<String> = emptyList(),
    @SerialName("multi_select") val multiSelect: Boolean = false,
)

@Serializable
data class PendingClarify(
    @SerialName("request_id") val requestId: String = "",
    val question: String = "",
    val choices: List<String> = emptyList(),
    @SerialName("multi_select") val multiSelect: Boolean = false,
    val questions: List<PendingClarifyQuestion> = emptyList(),
)

@Serializable
data class PendingApproval(
    @SerialName("request_id") val requestId: String = "",
    val command: String = "",
    val description: String = "",
    val choices: List<String> = emptyList(),
)

@Serializable
data class SessionInflightProjection(
    val user: String = "",
    val assistant: String = "",
    val streaming: Boolean = false,
)

@Serializable
data class SessionQueuedProjection(
    val user: String = "",
)

@Serializable
data class ModelOptionsResult(
    val model: String? = null,
    val provider: String? = null,
    val providers: List<ModelProvider> = emptyList(),
)

@Serializable
data class ModelProvider(
    val slug: String,
    val name: String,
    @SerialName("is_current") val isCurrent: Boolean = false,
    val models: List<String> = emptyList(),
    @SerialName("total_models") val totalModels: Int = models.size,
    val warning: String? = null,
    val authenticated: Boolean = true,
    @SerialName("auth_type") val authType: String? = null,
    @SerialName("key_env") val keyEnvironment: String? = null,
    @SerialName("is_user_defined") val isUserDefined: Boolean = false,
    val capabilities: Map<String, ModelCapabilities> = emptyMap(),
)

@Serializable
data class ModelCapabilities(
    val fast: Boolean = false,
    val reasoning: Boolean = false,
)

@Serializable
data class SessionRuntimeInfo(
    val cwd: String = "",
    val model: String = "",
    val provider: String = "",
    @SerialName("reasoning_effort") val reasoningEffort: String = "",
    @SerialName("service_tier") val serviceTier: String = "",
    val fast: Boolean = false,
    val yolo: Boolean = false,
    @SerialName("approval_mode") val approvalMode: String = "manual",
    val running: Boolean = false,
    val title: String = "",
    @SerialName("stored_session_id") val storedSessionId: String = "",
    @SerialName("desktop_contract") val desktopContract: Int? = null,
    val usage: JsonElement? = null,
)

@Serializable
data class ConfigSetResult(
    val key: String,
    val value: String,
    val warning: String? = null,
    @SerialName("confirm_required") val confirmRequired: Boolean = false,
    @SerialName("confirm_message") val confirmMessage: String = "",
    val scope: String? = null,
)

@Serializable
data class SessionTitleResult(
    val title: String,
    @SerialName("session_key") val sessionKey: String? = null,
)

@Serializable
data class SessionBranchResult(
    @SerialName("session_id") val runtimeSessionId: String,
    @SerialName("stored_session_id") val durableSessionId: String? = null,
    val title: String,
    val parent: String,
    val messages: List<ProtocolMessage> = emptyList(),
    val info: SessionRuntimeInfo = SessionRuntimeInfo(),
)

@Serializable
data class SessionUndoResult(
    val removed: Int,
)

@Serializable
data class SessionDeleteResult(
    val deleted: String,
)

@Serializable
data class SessionCloseResult(
    val closed: Boolean,
)

@Serializable
data class SlashCommandCategory(
    val name: String = "",
    val pairs: List<List<String>> = emptyList(),
)

@Serializable
data class SlashCommandCatalog(
    val pairs: List<List<String>> = emptyList(),
    val categories: List<SlashCommandCategory> = emptyList(),
    @SerialName("skill_count") val skillCount: Int = 0,
    val warning: String = "",
)

@Serializable
data class SlashCompletionItem(
    val text: String,
    val display: String = text,
    val meta: String = "",
)

@Serializable
data class SlashCompletionResult(
    val items: List<SlashCompletionItem> = emptyList(),
    @SerialName("replace_from") val replaceFrom: Int = 1,
)

@Serializable
data class SlashCommandResult(
    val type: String? = null,
    val output: String? = null,
    val warning: String? = null,
    val target: String? = null,
    val message: String? = null,
    val notice: String? = null,
    val name: String? = null,
)

@Serializable
data class SessionHistoryResult(
    val count: Int,
    val messages: List<ProtocolMessage> = emptyList(),
)

@Serializable
data class SessionCompressResult(
    val status: String,
    val removed: Int = 0,
    @SerialName("before_messages") val beforeMessages: Int = 0,
    @SerialName("after_messages") val afterMessages: Int = 0,
    val messages: List<ProtocolMessage> = emptyList(),
    val info: SessionRuntimeInfo? = null,
)

@Serializable
data class SessionSteerResult(
    val status: String,
    val text: String,
)

@Serializable
data class PromptSubmitResult(
    val status: String,
)
