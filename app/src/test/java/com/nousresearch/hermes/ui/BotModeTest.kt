package com.nousresearch.hermes.ui

import com.nousresearch.hermes.protocol.ProfileInfo
import com.nousresearch.hermes.protocol.StoredSession
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotModeTest {
    @Test
    fun `bot conversations are source profiles ordered by recent activity`() {
        val bots = botConversations(
            profiles = listOf(
                ProfileInfo(name = "default", isDefault = true, displayName = "Hermes", description = "General assistant"),
                ProfileInfo(name = "coder", displayName = "Code Fox", description = "Builds things"),
                ProfileInfo(name = "quiet", displayName = "Quiet"),
            ),
            sessions = listOf(
                StoredSession(sessionId = "older", profile = "default", title = "Old", lastActive = 10.0),
                StoredSession(sessionId = "newer", profile = "default", title = "Plan launch", lastActive = 30.0),
                StoredSession(sessionId = "code", profile = "coder", title = "Fix CI", lastActive = 20.0, isActive = true),
            ),
            selectedSession = StoredSession(sessionId = "newer", profile = "default"),
        )

        assertEquals(listOf("default", "coder", "quiet"), bots.map { it.profile.name })
        assertEquals("Plan launch", bots[0].preview)
        assertTrue(bots[0].selected)
        assertFalse(bots[0].isActive(nowMillis = 200_000L, busyProfile = null))
        assertTrue(bots[1].isActive(nowMillis = 200_000L, busyProfile = null))
        assertEquals("No conversations yet — say hi", bots[2].preview)
    }

    @Test
    fun `bot state follows profile identity rich activity and source`() {
        val selected = StoredSession(sessionId = "older", profile = "coder")
        val bot = botConversations(
            profiles = listOf(
                ProfileInfo(
                    name = "coder",
                    displayName = "Code Fox",
                    description = "Builds things",
                    canonicalSession = com.nousresearch.hermes.protocol.BotSessionSummary(
                        id = "canonical",
                        preview = "The build is green",
                        lastActive = 190.0,
                    ),
                    uiMeta = kotlinx.serialization.json.buildJsonObject {
                        put("hermes-bots", kotlinx.serialization.json.buildJsonObject { put("hidden", true) })
                    },
                ),
            ),
            sessions = listOf(selected),
            selectedSession = selected,
            sourceLabel = "Mac mini",
            unreadProfiles = setOf("coder"),
        ).single()

        assertEquals("Code Fox · Mac mini", bot.identity)
        assertEquals("The build is green", bot.preview)
        assertTrue(bot.selected)
        assertTrue(bot.hidden)
        assertTrue(bot.unread)
        assertTrue(bot.isActive(nowMillis = 200_000L, busyProfile = null))
    }

    @Test
    fun `bot search uses stable identity role and preview`() {
        val bot = BotConversation(
            profile = ProfileInfo(name = "research", displayName = "Scout", description = "Finds primary sources"),
            latestSession = StoredSession(sessionId = "research-chat", title = "Battery paper"),
        )

        assertTrue(bot.matches("scout"))
        assertTrue(bot.matches("primary"))
        assertTrue(bot.matches("battery"))
        assertFalse(bot.matches("accounting"))
    }
}
