package com.nousresearch.hermes.data

import com.nousresearch.hermes.protocol.BOT_GROUP_META_KEY
import com.nousresearch.hermes.protocol.BotGroupEntry
import com.nousresearch.hermes.protocol.BotGroupMember
import com.nousresearch.hermes.protocol.BotGroupRoom
import com.nousresearch.hermes.protocol.BotGroupSnapshot
import com.nousresearch.hermes.protocol.ProfileInfo
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal const val BOT_GROUP_MAX_MEMBERS = 6
internal const val BOT_GROUP_MAX_ROUNDS = 3
internal const val BOT_GROUP_MAX_MESSAGES = 10
internal const val BOT_GROUP_SYNC_MESSAGES = 16
internal const val BOT_GROUP_SYNC_TEXT_CHARS = 1200

internal fun profileGroupSnapshot(profile: ProfileInfo?, json: Json): BotGroupSnapshot {
    val element = profile?.uiMeta?.get(BOT_GROUP_META_KEY) ?: return BotGroupSnapshot()
    val raw = element.jsonObject
    val version = raw["version"]?.jsonPrimitive?.intOrNull ?: 1
    if (version < 3) {
        val legacyRooms = raw["rooms"]?.jsonObject.orEmpty()
        val rooms = legacyRooms.map { (name, value) ->
            val roomId = value.jsonObject["roomId"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                ?: legacyRoomId(name)
            val normalized = buildJsonObject {
                value.jsonObject.forEach(::put)
                put("name", JsonPrimitive(name))
                put("roomId", JsonPrimitive(roomId))
            }
            "id:$roomId" to json.decodeFromJsonElement<BotGroupRoom>(normalized)
        }.toMap()
        val roomIdsByName = rooms.values.associate { it.name to it.roomId }
        val deleted = raw["deleted"]?.jsonObject.orEmpty().map { (name, value) ->
            "id:${roomIdsByName[name] ?: legacyRoomId(name)}" to
                if (version >= 2) value.jsonPrimitive.content.toLongOrNull() ?: 0 else 0
        }.toMap()
        return BotGroupSnapshot(rooms = rooms, deleted = deleted)
    }
    return json.decodeFromJsonElement(raw)
}

private fun legacyRoomId(name: String): String = UUID.nameUUIDFromBytes(
    "hermes-bots-group:$name".toByteArray(StandardCharsets.UTF_8),
).toString()

internal fun BotGroupSnapshot.visibleRooms(): List<BotGroupRoom> = rooms
    .filter { (key, room) -> (deleted[key] ?: -1) < room.revision && room.log.isNotEmpty() }
    .values
    .sortedByDescending { it.log.lastOrNull()?.at ?: 0 }

internal fun newBotGroupRoom(name: String, members: List<BotGroupMember>, now: Long): BotGroupRoom {
    val cleanName = name.trim().take(64)
    require(cleanName.isNotEmpty()) { "Group name is required" }
    val unique = members.distinctBy(::botGroupMemberKey).take(BOT_GROUP_MAX_MEMBERS)
    require(unique.size in 2..BOT_GROUP_MAX_MEMBERS) { "Pick 2–$BOT_GROUP_MAX_MEMBERS bots" }
    return BotGroupRoom(
        name = cleanName,
        roomId = UUID.randomUUID().toString(),
        revision = 1,
        members = unique,
        log = listOf(
            BotGroupEntry(
                id = UUID.randomUUID().toString(),
                text = "Group created",
                at = now,
            ),
        ),
    )
}

internal fun BotGroupSnapshot.upsert(room: BotGroupRoom, now: Long): BotGroupSnapshot {
    val key = "id:${room.roomId}"
    require((deleted[key] ?: Long.MIN_VALUE) < room.revision) { "That Bot group was disbanded" }
    return copy(
        version = 3,
        updatedAt = now,
        rooms = rooms + (key to room.copy(
            name = room.name.take(64),
            members = room.members.distinctBy(::botGroupMemberKey).take(BOT_GROUP_MAX_MEMBERS),
            log = room.log.takeLast(BOT_GROUP_SYNC_MESSAGES).map { it.copy(text = it.text.take(BOT_GROUP_SYNC_TEXT_CHARS)) },
            image = room.image?.takeIf { it.length <= 24_000 },
        )),
        deleted = deleted - key,
    )
}

internal fun BotGroupSnapshot.disband(room: BotGroupRoom, revision: Long, now: Long): BotGroupSnapshot {
    val key = "id:${room.roomId}"
    return copy(
        version = 3,
        updatedAt = now,
        rooms = rooms - key,
        deleted = (deleted + (key to revision)).entries.sortedByDescending { it.value }
            .take(64).associate { it.toPair() },
    )
}

internal fun BotGroupSnapshot.asUiMeta(json: Json): JsonObject =
    json.encodeToJsonElement(this).jsonObject

internal fun BotGroupSnapshot.boundedForGateway(json: Json): BotGroupSnapshot {
    var bounded = copy(
        rooms = rooms.entries.sortedByDescending { it.value.log.lastOrNull()?.at ?: 0 }.associate { it.toPair() },
        deleted = deleted.entries.sortedByDescending { it.value }.take(64).associate { it.toPair() },
    )
    while (groupGatewayJsonSize(json.encodeToString(BotGroupSnapshot.serializer(), bounded)) > 48_000) {
        val oldest = bounded.rooms.entries.lastOrNull() ?: break
        val room = oldest.value
        bounded = when {
            room.log.size > 1 -> bounded.copy(rooms = bounded.rooms + (oldest.key to room.copy(log = room.log.drop(1))))
            room.image != null -> bounded.copy(rooms = bounded.rooms + (oldest.key to room.copy(image = null)))
            else -> bounded.copy(rooms = bounded.rooms - oldest.key)
        }
    }
    require(groupGatewayJsonSize(json.encodeToString(BotGroupSnapshot.serializer(), bounded)) <= 48_000) {
        "Group metadata exceeds the Hermes sync limit"
    }
    return bounded
}

private fun groupGatewayJsonSize(value: String): Int = value.fold(0) { total, character ->
    total + when {
        character.code <= 0x7f -> if (character == ',' || character == ':') 2 else 1
        Character.isSurrogate(character) -> 6
        else -> 6
    }
}

internal fun botGroupMemberKey(member: BotGroupMember): String = listOf(
    member.connectionId.trim().lowercase(Locale.ROOT),
    member.name.trim().lowercase(Locale.ROOT),
).joinToString("::")

internal fun ProfileInfo.botMemberships(): List<String> {
    val element = uiMeta?.get("hermes-bots") ?: return emptyList()
    val meta = element.jsonObject
    val canonical = meta["groups"]?.jsonArray?.mapNotNull {
        it.jsonPrimitive.contentOrNull?.trim()?.takeIf(String::isNotBlank)
    }
    return (canonical ?: listOfNotNull(meta["group"]?.jsonPrimitive?.contentOrNull?.trim())).distinct()
}

internal fun groupResponders(text: String, members: List<BotGroupMember>): List<BotGroupMember> {
    val tags = Regex("@([a-z0-9][a-z0-9._-]*)", RegexOption.IGNORE_CASE)
        .findAll(text).map { it.groupValues[1].lowercase(Locale.ROOT) }.toSet()
    if (tags.isEmpty() || tags.any { it == "all" || it == "everyone" }) return members
    val uniqueNames = members.groupingBy { it.name.lowercase(Locale.ROOT) }.eachCount()
    return members.filter { member ->
        val name = member.name.lowercase(Locale.ROOT)
        val forms = buildSet {
            add(member.handle.removePrefix("@").lowercase(Locale.ROOT))
            if (uniqueNames[name] == 1) {
                add(name)
                add(name.replace(Regex("[\\s._-]+"), ""))
            }
        }
        tags.any { it in forms || it.replace(Regex("[._-]+"), "") in forms }
    }
}

internal fun mergeBotGroupSnapshots(first: BotGroupSnapshot, second: BotGroupSnapshot): BotGroupSnapshot {
    val deleted = (first.deleted.keys + second.deleted.keys).associateWith { key ->
        maxOf(first.deleted[key] ?: Long.MIN_VALUE, second.deleted[key] ?: Long.MIN_VALUE)
    }
    val rooms = (first.rooms.keys + second.rooms.keys).mapNotNull { key ->
        val left = first.rooms[key]
        val right = second.rooms[key]
        val room = when {
            left == null -> right
            right == null -> left
            left.revision != right.revision -> maxOf(left, right, compareBy(BotGroupRoom::revision))
            else -> {
                val latest = maxOf(left, right, compareBy { it.log.lastOrNull()?.at ?: 0 })
                latest.copy(
                    log = (left.log + right.log).distinctBy(BotGroupEntry::id).sortedBy(BotGroupEntry::at),
                    stranded = left.stranded + right.stranded,
                )
            }
        } ?: return@mapNotNull null
        if ((deleted[key] ?: Long.MIN_VALUE) >= room.revision) null else key to room
    }.toMap()
    return BotGroupSnapshot(
        updatedAt = maxOf(first.updatedAt, second.updatedAt),
        rooms = rooms,
        deleted = deleted,
    )
}

internal fun isBotGroupPass(text: String): Boolean =
    text.trim().isEmpty() || Regex("^\\(?\\s*pass\\s*\\)?\\.?$", RegexOption.IGNORE_CASE).matches(text.trim())
