package com.nousresearch.hermes.ui

import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.nousresearch.hermes.protocol.StoredSession
import com.nousresearch.hermes.ui.theme.HermesTheme
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SessionInboxLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun conversationRowFitsCompactWidthAndExposesItsState() {
        assertConversationRowFits(width = 360, compact = true, click = true)
    }

    @Test
    fun conversationRowFitsExpandedRailAtLargeText() {
        assertConversationRowFits(width = 330, compact = false, click = false)
    }

    @Test
    fun summaryUsesAvailableSessionMetadataWithoutInventingMessageContent() {
        assertEquals(
            "default · hermes-4 · 7 messages",
            sessionSummary(
                StoredSession(
                    profile = "default",
                    source = "android",
                    model = "hermes-4",
                    messageCount = 7,
                ),
            ),
        )
        assertEquals("telegram · 1 message", sessionSummary(StoredSession(source = "telegram", messageCount = 1)))
        assertEquals("openai", sessionSummary(StoredSession(model = " ", provider = "openai", source = "android")))
        assertEquals("telegram", sessionSummary(StoredSession(model = "", provider = " ", source = "telegram")))
        assertEquals("Conversation", sessionSummary(StoredSession()))
    }

    @Test
    fun timestampUsesMessagingStyleCalendarLabels() {
        val zone = ZoneId.of("UTC")
        val locale = Locale.US
        val now = Instant.parse("2026-08-12T15:30:00Z").toEpochMilli()

        assertEquals("9:15 AM", formatSessionTimestamp(epoch("2026-08-12T09:15:00Z"), now, zone, locale))
        assertEquals("Yesterday", formatSessionTimestamp(epoch("2026-08-11T09:15:00Z"), now, zone, locale))
        assertEquals("Fri", formatSessionTimestamp(epoch("2026-08-07T09:15:00Z"), now, zone, locale))
        assertEquals("Jul 2", formatSessionTimestamp(epoch("2026-07-02T09:15:00Z"), now, zone, locale))
        assertEquals("Dec 31, 2025", formatSessionTimestamp(epoch("2025-12-31T09:15:00Z"), now, zone, locale))
        assertEquals("", formatSessionTimestamp(0.0, now, zone, locale))
    }

    @Test
    fun timestampClockWaitsUntilTheNextLocalDate() {
        val now = Instant.parse("2026-08-12T23:59:30Z").toEpochMilli()

        assertEquals(30_000L, sessionTimestampRolloverDelayMillis(now, ZoneId.of("UTC")))
    }

    private fun assertConversationRowFits(width: Int, compact: Boolean, click: Boolean) {
        var clicks = 0
        compose.setContent {
            HermesTheme {
                CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1.3f)) {
                    Surface(Modifier.width(width.dp).testTag("inbox-root")) {
                        SessionRow(
                            session = StoredSession(
                                title = "Release planning",
                                profile = "default",
                                model = "hermes-4",
                                pinned = true,
                                messageCount = 12,
                            ),
                            selected = true,
                            compact = compact,
                            nowMillis = 0L,
                            onClick = { clicks += 1 },
                            onPin = {},
                            onArchive = {},
                            onDelete = {},
                        )
                    }
                }
            }
        }

        val row = compose.onNode(
            hasContentDescription("Release planning", substring = true) and
                hasContentDescription("default · hermes-4 · 12 messages", substring = true) and
                hasContentDescription("Active", substring = true) and
                hasContentDescription("Pinned", substring = true),
        )
        row.assertExists()
        if (click) row.performClick()

        val rootWidth = compose.onNodeWithTag("inbox-root").fetchSemanticsNode().boundsInRoot.width
        val rowBounds = row.fetchSemanticsNode().boundsInRoot
        assertTrue("Conversation row is clipped", rowBounds.left >= 0f && rowBounds.right <= rootWidth)
        assertEquals(if (click) 1 else 0, clicks)
    }

    private fun epoch(value: String): Double = Instant.parse(value).epochSecond.toDouble()
}
