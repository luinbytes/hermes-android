package com.nousresearch.hermes.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val BOT_GROUP_META_KEY = "hermes-bots-groups"

@Serializable
data class BotGroupMember(
    val name: String,
    val handle: String = "",
    @SerialName("connectionId") val connectionId: String = "",
    @SerialName("connectionKind") val connectionKind: String = "",
    @SerialName("connectionLabel") val connectionLabel: String = "",
    @SerialName("sourceScoped") val sourceScoped: Boolean = false,
)

@Serializable
data class BotGroupSpeaker(
    val kind: String = "user",
    val name: String = "You",
    val source: String = "",
)

@Serializable
data class BotGroupEntry(
    val id: String = "",
    val from: BotGroupSpeaker = BotGroupSpeaker(),
    val text: String = "",
    val at: Long = 0,
    val thread: String = "main",
)

@Serializable
data class BotGroupStranded(
    val before: Int,
    val thread: String = "main",
)

@Serializable
data class BotGroupRoom(
    val name: String,
    @SerialName("roomId") val roomId: String = "",
    val log: List<BotGroupEntry> = emptyList(),
    val revision: Long = 0,
    val members: List<BotGroupMember> = emptyList(),
    val image: String? = null,
    val stranded: Map<String, BotGroupStranded> = emptyMap(),
)

@Serializable
data class BotGroupSnapshot(
    val version: Int = 3,
    @SerialName("updatedAt") val updatedAt: Long = 0,
    val rooms: Map<String, BotGroupRoom> = emptyMap(),
    val deleted: Map<String, Long> = emptyMap(),
)

data class BotGroupUiState(
    val rooms: List<BotGroupRoom> = emptyList(),
    val loading: Boolean = false,
    val runningRoomId: String? = null,
    val needsYouRoomIds: Set<String> = emptySet(),
    val blockingRequests: List<BotGroupBlockingRequest> = emptyList(),
    val error: String? = null,
)

data class BotGroupQuestion(
    val id: String,
    val prompt: String,
    val choices: List<String> = emptyList(),
    val multiSelect: Boolean = false,
)

data class BotGroupBlockingRequest(
    val roomId: String,
    val member: BotGroupMember,
    val sessionId: String,
    val requestId: String,
    val kind: String,
    val prompt: String,
    val command: String = "",
    val choices: List<String> = emptyList(),
    val questions: List<BotGroupQuestion> = emptyList(),
)

data class BotGroupAttachment(
    val name: String,
    val mimeType: String,
    val base64: String,
)

data class BotGroupCandidate(
    val profile: ProfileInfo,
    val backendId: String,
    val backendLabel: String,
    val handle: String,
)

data class BotGroupCandidateResult(
    val candidates: List<BotGroupCandidate>,
    val unavailableSources: List<String> = emptyList(),
)
