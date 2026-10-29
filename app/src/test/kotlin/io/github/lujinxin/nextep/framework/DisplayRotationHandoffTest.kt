package io.github.lujinxin.nextep.framework

import org.junit.Assert.*
import org.junit.Test

class DisplayRotationHandoffTest {
    @Test fun viewportPollingCannotReplaceTheRetainedFrameBeforeDraw() {
        val handoff = DisplayRotationHandoff()
        val ticket = handoff.begin(1, setOf(7), 100)
        assertTrue(handoff.holds(7, 101))
        assertTrue(handoff.holds(7, 400))
        assertFalse(handoff.holds(8, 400))
        assertEquals(1, handoff.ticket(ticket.generation)?.rotation)
        handoff.complete(ticket.generation)
        assertFalse(handoff.holds(7, 401))
    }

    @Test fun rapidOppositeRotationRejectsTheOldDrawCallback() {
        val handoff = DisplayRotationHandoff()
        val first = handoff.begin(1, setOf(7), 100)
        val second = handoff.begin(0, setOf(7), 120)
        assertNull(handoff.ticket(first.generation))
        handoff.complete(first.generation)
        assertEquals(second, handoff.ticket(second.generation))
        assertTrue(handoff.holds(7, 130))
    }

    @Test fun missingDrawCannotFreezeTaskPollingIndefinitely() {
        val handoff = DisplayRotationHandoff()
        handoff.begin(1, setOf(7), 100)
        assertTrue(handoff.holds(7, 1599))
        assertFalse(handoff.holds(7, 1600))
    }
}
