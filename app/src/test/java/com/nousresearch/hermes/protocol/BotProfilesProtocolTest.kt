package com.nousresearch.hermes.protocol

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotProfilesProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `decodes exact canonical session lookup and compression tip`() {
        val result = json.decodeFromString<BotSessionPage>(
            checkNotNull(javaClass.getResource("/fixtures/canonical-session-list-261a4ef.json")).readText(),
        ).sessions.single()

        assertEquals("bot-chat-root", result.id)
        assertEquals("bot-chat-tip", result.resolvedId)
        assertEquals("Bot Chat", result.rootTitle)
    }

    @Test
    fun `decodes current rich Bot Mode roster`() {
        val result = json.decodeFromString<ProfilesResponse>(
            checkNotNull(javaClass.getResource("/fixtures/bot-profiles-261a4ef.json")).readText(),
        )
        val profile = result.profiles.single()

        assertTrue(result.botModeProtocol)
        assertEquals("bot-chat-tip", profile.canonicalSession?.resolvedId)
        assertEquals("Message from coder: Build is green", profile.canonicalSession?.preview)
        assertEquals("kanban", profile.workerSession?.source)
        assertEquals(3L, profile.uiMetaRevisions["hermes-bots"])
    }

    @Test
    fun `decodes complete profile editor contract`() {
        val result = json.decodeFromString<ProfileDescription>(
            checkNotNull(javaClass.getResource("/fixtures/profile-description-261a4ef.json")).readText(),
        )

        assertEquals("coder", result.name)
        assertEquals("hermes-4", result.model.default)
        assertFalse(result.skills.last().enabled)
        assertEquals(4, result.toolsets.single().toolCount)
        assertEquals("http", result.mcpServers.single().transport)
    }

    @Test
    fun `older profile responses keep safe Bot Mode defaults`() {
        val result = json.decodeFromString<ProfilesResponse>(
            """{"profiles":[{"name":"default","is_default":true}]}""",
        )
        val profile = result.profiles.single()

        assertFalse(result.botModeProtocol)
        assertFalse(profile.hasAvatar)
        assertNull(profile.canonicalSession)
        assertNull(profile.uiMeta)
        assertTrue(profile.uiMetaRevisions.isEmpty())
    }

    @Test
    fun `pet gallery keeps installed and curated avatar sources`() {
        val result = json.decodeFromString<PetGallery>(
            """{"enabled":true,"active":"fox","pets":[{"slug":"fox","displayName":"Fox","installed":true,"curated":true}]}""",
        )

        assertTrue(result.enabled)
        assertTrue(result.pets.single().installed)
        assertEquals("Fox", result.pets.single().displayName)
    }
}
