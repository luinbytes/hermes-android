package com.nousresearch.hermes.data

import com.nousresearch.hermes.protocol.ProfileInfo
import com.nousresearch.hermes.protocol.BotSessionSummary
import com.nousresearch.hermes.protocol.StoredSession
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotModeContractTest {
    @Test
    fun `canonical state recognizes registry root tip and newly created title only`() {
        assertTrue(
            HermesState(
                activeStoredSession = StoredSession(sessionId = "tip", profile = "coder"),
                profiles = listOf(
                    ProfileInfo(
                        name = "coder",
                        canonicalSession = BotSessionSummary(id = "root", resolvedId = "tip", rootTitle = BOT_CHAT_TITLE),
                    ),
                ),
            ).isActiveCanonicalBotChat(),
        )
        assertTrue(
            HermesState(
                activeStoredSession = StoredSession(sessionId = "new", profile = "coder", title = BOT_CHAT_TITLE),
            ).isActiveCanonicalBotChat(),
        )
        assertFalse(
            HermesState(
                activeStoredSession = StoredSession(sessionId = "scratch", profile = "coder", title = "Scratch"),
            ).isActiveCanonicalBotChat(),
        )
    }

    @Test
    fun `hide mutation preserves Bot metadata and fences its revision`() {
        val profile = ProfileInfo(
            name = "coder",
            uiMeta = buildJsonObject {
                put("hermes-bots", buildJsonObject {
                    put("title", "Code Fox")
                    put("hidden", false)
                })
            },
            uiMetaRevisions = mapOf("hermes-bots" to 7L),
        )

        val params = botHiddenConfigureParams(profile, hidden = true)
        val botMeta = params["ui_meta"]!!.jsonObject["hermes-bots"]!!.jsonObject

        assertEquals("coder", params["name"]!!.jsonPrimitive.content)
        assertEquals("Code Fox", botMeta["title"]!!.jsonPrimitive.content)
        assertTrue(botMeta["hidden"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(
            7L,
            params["ui_meta_expected_revisions"]!!.jsonObject["hermes-bots"]!!.jsonPrimitive.content.toLong(),
        )
        assertFalse(profile.uiMeta!!["hermes-bots"]!!.jsonObject["hidden"]!!.jsonPrimitive.content.toBoolean())
    }
}
