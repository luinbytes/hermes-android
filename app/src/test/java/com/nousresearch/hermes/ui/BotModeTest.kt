package com.nousresearch.hermes.ui

import com.nousresearch.hermes.data.HermesState
import com.nousresearch.hermes.data.SessionRestorationState
import com.nousresearch.hermes.data.SessionRestorationStatus
import com.nousresearch.hermes.protocol.GatewayConnectionState
import com.nousresearch.hermes.protocol.BotGroupCandidate
import com.nousresearch.hermes.protocol.BotSessionSummary
import com.nousresearch.hermes.protocol.ProfileInfo
import com.nousresearch.hermes.protocol.StoredSession
import com.nousresearch.hermes.platform.HermesNotificationKind
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotModeTest {
    @Test
    fun `background completion notifies even when its conversation remains selected`() {
        val posted = mutableListOf<HermesNotificationKind>()
        val coordinator = BotActivityNotificationCoordinator { _, kind, _ ->
            posted += kind
            true
        }
        val backend = com.nousresearch.hermes.data.BackendConfig(
            "mac", "Mac", "https://hermes.test", com.nousresearch.hermes.data.AuthMode.DASHBOARD_SESSION,
        )
        val selected = StoredSession(sessionId = "coder-chat", profile = "coder")
        val initial = HermesState(
            backend = backend,
            profiles = listOf(ProfileInfo(name = "coder", canonicalSession = BotSessionSummary("coder-chat", lastActive = 1.0))),
            activeStoredSession = selected,
        )

        coordinator.update(initial, appForeground = true)
        coordinator.update(
            initial.copy(
                profiles = listOf(ProfileInfo(name = "coder", canonicalSession = BotSessionSummary("coder-chat", lastActive = 2.0))),
            ),
            appForeground = false,
        )

        assertEquals(listOf(HermesNotificationKind.COMPLETION), posted)
    }

    @Test
    fun `bot polling waits for a restored open workspace`() {
        val ready = HermesState(
            restoration = SessionRestorationState(status = SessionRestorationStatus.READY),
            botModeProtocolSupported = true,
        )

        assertFalse(botModeRuntimeReady(false, ready, GatewayConnectionState.Open))
        assertFalse(botModeRuntimeReady(true, HermesState(), GatewayConnectionState.Open))
        assertFalse(botModeRuntimeReady(true, ready, GatewayConnectionState.Connecting(1)))
        assertFalse(botModeRuntimeReady(true, ready.copy(backendTransitionInProgress = true), GatewayConnectionState.Open))
        assertFalse(botModeRuntimeReady(true, ready.copy(botModeProtocolSupported = false), GatewayConnectionState.Open))
        assertTrue(botModeRuntimeReady(true, ready, GatewayConnectionState.Open))
    }

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
    fun `notification roster keeps same named bots from every backend`() {
        val bots = botNotificationConversations(
            HermesState(
                backend = com.nousresearch.hermes.data.BackendConfig(
                    id = "local",
                    label = "Local",
                    baseUrl = "https://local.test",
                    authMode = com.nousresearch.hermes.data.AuthMode.DASHBOARD_SESSION,
                ),
                botCandidates = listOf(
                    BotGroupCandidate(ProfileInfo(name = "coder"), "local", "Local", "coder-local"),
                    BotGroupCandidate(ProfileInfo(name = "coder"), "cloud", "Cloud", "coder-cloud"),
                ),
            ),
        )

        assertEquals(setOf("local:coder", "cloud:coder"), bots.map { it.sourceKey }.toSet())
    }

    @Test
    fun `agent targets exclude remote legacy authentication`() {
        val active = com.nousresearch.hermes.data.BackendConfig(
            "active", "Active", "https://active.test", com.nousresearch.hermes.data.AuthMode.TOKEN,
        )
        val dashboard = com.nousresearch.hermes.data.BackendConfig(
            "dashboard", "Dashboard", "https://dashboard.test", com.nousresearch.hermes.data.AuthMode.DASHBOARD_SESSION,
        )
        val legacy = com.nousresearch.hermes.data.BackendConfig(
            "legacy", "Legacy", "https://legacy.test", com.nousresearch.hermes.data.AuthMode.TOKEN,
        )

        assertEquals(listOf(active, dashboard), botAgentTargetBackends(listOf(active, dashboard, legacy), active.id))
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

    @Test
    fun `bot resumes its most recent local session instead of an older canonical chat`() {
        val recent = StoredSession(sessionId = "recent", profile = "coder", title = "Latest work", lastActive = 30.0)
        val bot = botConversations(
            profiles = listOf(
                ProfileInfo(
                    name = "coder",
                    canonicalSession = com.nousresearch.hermes.protocol.BotSessionSummary(
                        id = "canonical",
                        title = "Bot Chat",
                        lastActive = 10.0,
                    ),
                ),
            ),
            sessions = listOf(recent),
            selectedSession = null,
        ).single()

        assertEquals("recent", bot.latestSession?.durableId)
        assertEquals(recent, bot.localResumeSession(activeBackendId = "local"))
        assertEquals(null, bot.copy(backendId = "remote").localResumeSession(activeBackendId = "local"))
    }
}
