package com.nousresearch.hermes.data

import com.nousresearch.hermes.protocol.CronJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BotRoutinesTest {
    @Test
    fun `routine ownership and title use the desktop tag contract`() {
        val job = CronJob(enabled = true, id = "daily", name = "[bot:Coder_2] Daily brief")
        assertEquals("coder_2", job.botRoutineOwner())
        assertEquals("Daily brief", job.botRoutineTitle())
        assertEquals("[bot:coder_2] Daily brief", botRoutineName("Coder_2", "Daily brief"))
        assertNull(CronJob(enabled = true, id = "plain", name = "Daily brief").botRoutineOwner())
    }

    @Test
    fun `structured schedules round trip and unknown forms remain advanced`() {
        listOf(
            "30m" to BotScheduleFrequency.ONCE,
            "every 1h" to BotScheduleFrequency.HOURLY,
            "0 9 * * *" to BotScheduleFrequency.DAILY,
            "30 8 * * 1-5" to BotScheduleFrequency.WEEKDAYS,
            "0 10 * * 3" to BotScheduleFrequency.WEEKLY,
            "15 7 12 * *" to BotScheduleFrequency.MONTHLY,
            "every 2d" to BotScheduleFrequency.INTERVAL,
        ).forEach { (value, frequency) ->
            val parsed = BotSchedule.parse(value)
            assertEquals(frequency, parsed.frequency)
            assertEquals(value, parsed.expression())
        }
        val mixedDaySelectors = BotSchedule.parse("0 9 1 * 1")
        assertEquals(BotScheduleFrequency.ADVANCED, mixedDaySelectors.frequency)
        assertEquals("0 9 1 * 1", mixedDaySelectors.expression())
        assertEquals("TZ=Europe/London 0 9 * * *", BotSchedule.parse("TZ=Europe/London 0 9 * * *").expression())
    }

    @Test
    fun `mentions require an unambiguous known handle and exclude self`() {
        val candidates = listOf(
            BotMention("coder", "mac", "coder-mac"),
            BotMention("coder", "cloud", "coder-cloud"),
            BotMention("reviewer", "mac", "reviewer"),
            BotMention("duplicate-a", "mac", "same"),
            BotMention("duplicate-b", "cloud", "same"),
        )
        assertEquals(
            listOf("coder@cloud", "reviewer@mac"),
            resolveBotMentions("Ask @coder-cloud and @reviewer. Ignore @unknown @same @coder-mac", candidates, "coder", "mac")
                .map { "${it.profile}@${it.backendId}" },
        )
        val token = botMentionToken("Could you ask @rev")!!
        assertEquals("rev", token.query)
        assertEquals("Could you ask @reviewer ", completeBotMention("Could you ask @rev", token, "reviewer"))
        assertNull(botMentionToken("email@example.com"))
        assertEquals("release.bot", botMentionBaseHandle(com.nousresearch.hermes.protocol.ProfileInfo(name = "reviewer", displayName = "Release.Bot")))
        assertEquals(
            listOf("reviewer"),
            resolveBotMentions("Ask @release.bot", listOf(BotMention("reviewer", "mac", "release.bot")), "coder", "mac")
                .map(BotMention::profile),
        )
    }

    @Test
    fun `private activity policy ignores initial hidden and already open events`() {
        assertFalse(shouldNotifyBotEvent(hidden = false, initialized = false, changed = true))
        assertFalse(shouldNotifyBotEvent(hidden = true, initialized = true, changed = true))
        assertFalse(shouldNotifyBotEvent(hidden = false, initialized = true, changed = true, open = true))
        assertTrue(shouldNotifyBotEvent(hidden = false, initialized = true, changed = true))
    }
}
