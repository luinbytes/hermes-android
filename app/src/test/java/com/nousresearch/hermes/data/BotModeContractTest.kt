package com.nousresearch.hermes.data

import com.nousresearch.hermes.protocol.ProfileInfo
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
