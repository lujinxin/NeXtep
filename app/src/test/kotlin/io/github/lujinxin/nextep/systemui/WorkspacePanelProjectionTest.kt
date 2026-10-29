package io.github.lujinxin.nextep.systemui

import org.junit.Assert.*
import org.junit.Test

class WorkspacePanelProjectionTest {
    @Test fun stagedReverseLandscapeStillRequestsTheFirstLayoutFromTheUiThread() {
        val projection = WorkspacePanelProjection()
        projection.request(1762,272,-90f)
        projection.measure(1762,272)
        assertFalse(projection.request(1762,272,-90f))
        // Shell stages the angle before the main-thread configureWindow call.
        assertTrue(projection.request(1762,272,90f))
        assertTrue(projection.request(1762,272,90f))
        assertFalse(projection.canDraw(1762,272))
        projection.measure(1762,272)
        assertTrue(projection.canDraw(1762,272))
        assertFalse(projection.request(1762,272,90f))
    }

    @Test fun angleArrivingBeforeWindowResizeKeepsExistingLogicalLayout() {
        val projection = WorkspacePanelProjection()
        projection.request(1080, 592, 0f)
        val original = projection.measure(1080, 592)
        projection.request(592, 1080, -90f)
        assertEquals(original, projection.measure(1080, 592))
        assertFalse(projection.canDraw(1080, 592))
        val rotated = projection.measure(592, 1080)
        assertEquals(1080, rotated.logicalWidth)
        assertEquals(592, rotated.logicalHeight)
        assertEquals(-90f, rotated.rotation, 0f)
    }

    @Test fun intermediateDimensionsCannotReplaceTheRetainedBuffer() {
        val projection = WorkspacePanelProjection()
        projection.request(1080, 592, 0f)
        val original = projection.measure(1080, 592)
        projection.request(592, 1080, -90f)
        assertEquals(original, projection.measure(592, 592))
        assertFalse(projection.canDraw(592, 592))
        projection.measure(592, 1080)
        assertTrue(projection.canDraw(592, 1080))
    }

    @Test fun repeatedBothWayRotationKeepsSidebarLogicalDimensions() {
        val projection = WorkspacePanelProjection()
        var previous = WorkspacePanelProjection.Frame(272, 1762, 0f)
        projection.request(previous.width, previous.height, previous.rotation)
        projection.measure(previous.width, previous.height)
        repeat(20) {
            for (next in listOf(WorkspacePanelProjection.Frame(1762,272,-90f),
                WorkspacePanelProjection.Frame(1762,272,90f), WorkspacePanelProjection.Frame(272,1762,0f))) {
                projection.request(next.width,next.height,next.rotation)
                val stale = projection.measure(previous.width,previous.height)
                assertEquals(272,stale.logicalWidth)
                assertEquals(1762,stale.logicalHeight)
                val applied = projection.measure(next.width,next.height)
                assertEquals(272,applied.logicalWidth)
                assertEquals(1762,applied.logicalHeight)
                previous=next
            }
        }
    }

    @Test fun realResizeAppliesAfterTheRequestedWindowDimensionsArrive() {
        val projection = WorkspacePanelProjection()
        projection.request(1080,592,0f)
        projection.measure(1080,592)
        projection.request(1200,620,0f)
        assertEquals(1080,projection.measure(1080,592).logicalWidth)
        assertEquals(1200,projection.measure(1200,620).logicalWidth)
    }
}
