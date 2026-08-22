package com.nousresearch.hermes.data

import com.nousresearch.hermes.protocol.BotGroupEntry
import com.nousresearch.hermes.protocol.BotGroupMember
import com.nousresearch.hermes.protocol.BotGroupSpeaker
import com.nousresearch.hermes.protocol.BotGroupSnapshot
import com.nousresearch.hermes.protocol.ProfileInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BotGroupsTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val bots = listOf(
        BotGroupMember("coder", handle = "coder-mac", connectionId = "mac"),
        BotGroupMember("coder", handle = "coder-cloud", connectionId = "cloud"),
        BotGroupMember("reviewer", handle = "reviewer", connectionId = "mac"),
    )

    @Test
    fun `room identity survives rename and recreate gets a fresh identity`() {
        val first = newBotGroupRoom("Launch", bots.take(2), 1)
        val renamed = first.copy(name = "Ship room", revision = first.revision + 1)
        val disbanded = BotGroupSnapshot().upsert(renamed, 2).disband(renamed, 3, 3)
        val recreated = newBotGroupRoom("Ship room", bots.take(2), 4)

        assertEquals(first.roomId, renamed.roomId)
        assertNotEquals(first.roomId, recreated.roomId)
        assertTrue(disbanded.visibleRooms().isEmpty())
    }

    @Test
    fun `projection bounds members messages text and tombstones`() {
        val room = newBotGroupRoom("Launch", bots + bots, 1).copy(
            log = (1..30).map {
                BotGroupEntry("$it", BotGroupSpeaker("member", "coder"), "x".repeat(1400), it.toLong())
            },
        )
        val snapshot = BotGroupSnapshot().upsert(room, 2)

        val projected = snapshot.visibleRooms().single()
        assertEquals(BOT_GROUP_MAX_MEMBERS.coerceAtMost(bots.size), projected.members.size)
        assertEquals(BOT_GROUP_SYNC_MESSAGES, projected.log.size)
        assertEquals(BOT_GROUP_SYNC_TEXT_CHARS, projected.log.first().text.length)
    }

    @Test
    fun `mentions keep same named source handles unambiguous`() {
        assertEquals(listOf("cloud"), groupResponders("@coder-cloud please check", bots).map { it.connectionId })
        assertTrue(groupResponders("@coder please check", bots).isEmpty())
        assertEquals(3, groupResponders("@everyone check", bots).size)
        assertTrue(isBotGroupPass("(pass)."))
        assertFalse(isBotGroupPass("I found it"))
    }

    @Test
    fun `legacy snapshots receive stable editable identities and gateway projection stays bounded`() {
        val legacy = ProfileInfo(
            name = "default",
            uiMeta = buildJsonObject {
                put("hermes-bots-groups", buildJsonObject {
                    put("version", 2)
                    put("rooms", buildJsonObject {
                        put("Launch", json.encodeToJsonElement(
                            newBotGroupRoom("ignored", bots.take(2), 1).copy(name = "Launch"),
                        ))
                    })
                })
            },
        )
        val lifted = profileGroupSnapshot(legacy, json)
        val huge = (1..20).fold(lifted) { snapshot, index ->
            snapshot.upsert(
                newBotGroupRoom("Room $index", bots.take(2), index.toLong()).copy(
                    log = (1..16).map { message ->
                        BotGroupEntry("$index-$message", text = "é".repeat(1200), at = (index * 100 + message).toLong())
                    },
                ),
                index.toLong(),
            )
        }.boundedForGateway(json)

        assertEquals("Launch", lifted.visibleRooms().single().name)
        assertTrue(lifted.visibleRooms().single().roomId.isNotBlank())
        assertEquals(lifted.visibleRooms().single().roomId, profileGroupSnapshot(legacy, json).visibleRooms().single().roomId)
        assertTrue(json.encodeToString(BotGroupSnapshot.serializer(), huge).isNotEmpty())
        assertTrue(huge.rooms.size < 20)
    }

    @Test
    fun `legacy snapshots preserve an existing room identity`() {
        val room = newBotGroupRoom("Launch", bots.take(2), 1).copy(roomId = "legacy-room")
        val legacy = ProfileInfo(
            name = "default",
            uiMeta = buildJsonObject {
                put("hermes-bots-groups", buildJsonObject {
                    put("version", 2)
                    put("rooms", buildJsonObject { put("Launch", json.encodeToJsonElement(room)) })
                })
            },
        )

        assertEquals("legacy-room", profileGroupSnapshot(legacy, json).visibleRooms().single().roomId)
    }

    @Test
    fun `snapshot merge keeps newer rooms and tombstones`() {
        val room = newBotGroupRoom("Launch", bots.take(2), 1)
        val first = BotGroupSnapshot().upsert(room, 1)
        val renamed = room.copy(name = "Release", revision = room.revision + 1)
        val concurrent = room.copy(log = room.log + BotGroupEntry("remote", text = "Remote reply", at = 2))

        assertEquals("Release", mergeBotGroupSnapshots(first, BotGroupSnapshot().upsert(renamed, 2)).visibleRooms().single().name)
        assertEquals(2, mergeBotGroupSnapshots(first, BotGroupSnapshot().upsert(concurrent, 2)).visibleRooms().single().log.size)
        assertTrue(mergeBotGroupSnapshots(first, first.disband(room, 2, 3)).visibleRooms().isEmpty())
        assertTrue(runCatching { first.disband(room, 2, 3).upsert(room.copy(revision = 2), 4) }.isFailure)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `malformed group metadata is not hidden as an empty room list`() {
        profileGroupSnapshot(
            ProfileInfo(name = "default", uiMeta = buildJsonObject { put("hermes-bots-groups", "broken") }),
            json,
        )
    }

    @Test
    fun `pinned Desktop v3 room keeps immutable and source qualified identity`() {
        val profile = json.decodeFromString<com.nousresearch.hermes.protocol.ProfilesResponse>(
            checkNotNull(javaClass.getResource("/fixtures/bot-groups-0287df.json")).readText(),
        ).profiles.single()
        val snapshot = profileGroupSnapshot(profile, json)
        val room = snapshot.visibleRooms().single()

        assertEquals("room-launch", room.roomId)
        assertEquals(listOf("coder-mac-mini", "coder-cloud"), room.members.map { it.handle })
        assertEquals("release", room.log.single().thread)
        assertEquals(9L, profile.uiMetaRevisions["hermes-bots-groups"])
    }
}
