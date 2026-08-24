package com.nousresearch.hermes.ui

import com.nousresearch.hermes.protocol.StarmapNode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class StarmapPolicyTest {
    private val node = StarmapNode(
        id = "skill:review",
        label = "Review changes",
        kind = "skill",
        category = "workflow",
        useCount = 4,
        state = "active",
        createdBy = "agent",
        pinned = true,
    )

    @Test
    fun `starmap search matches visible bounded metadata`() {
        assertTrue(starmapMatches(node, ""))
        assertTrue(starmapMatches(node, "review"))
        assertTrue(starmapMatches(node, "WORKFLOW"))
        assertTrue(starmapMatches(node, "agent"))
        assertFalse(starmapMatches(node, "memory"))
    }

    @Test
    fun `starmap layout is deterministic and places newer memories farther out`() {
        val old = node.copy(id = "memory:old", kind = "memory", timestamp = 1L)
        val new = node.copy(id = "memory:new", kind = "memory", timestamp = 2L)
        val layout = starmapLayout(listOf(old, new))

        assertEquals(layout, starmapLayout(listOf(old, new)))
        assertTrue(hypot(layout[1].x, layout[1].y) > hypot(layout[0].x, layout[0].y))
    }
}
