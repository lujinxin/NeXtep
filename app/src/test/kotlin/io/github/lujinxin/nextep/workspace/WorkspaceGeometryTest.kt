package io.github.lujinxin.nextep.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceGeometryTest {
    @Test
    fun mainViewportPreservesTheAppAspectRatioAboveThreeButtonNavigation() {
        for (side in SidebarSide.entries) {
            val geometry = WorkspaceGeometry.forDisplay(
                1080, 2354, side, 3f, WorkspaceNavigationInsets(bottom = 132),
            )
            assertEquals(
                "The safe app canvas must not be squeezed into a different aspect ratio",
                geometry.availableWidth.toFloat() / geometry.availableHeight,
                geometry.contentWidth.toFloat() / geometry.contentHeight,
                1f / geometry.contentHeight,
            )
        }
    }

    @Test
    fun uniformTransformCropsNavigationExactlyOnceInEitherOrientation() {
        val devices = listOf(1080 to 2354, 2354 to 1080, 1600 to 2560, 2560 to 1600)
        val insets = listOf(WorkspaceNavigationInsets(), WorkspaceNavigationInsets(bottom = 132),
            WorkspaceNavigationInsets(left = 132), WorkspaceNavigationInsets(right = 132),
            WorkspaceNavigationInsets(bottom = 48))
        for ((width, height) in devices) for (navigation in insets) for (side in SidebarSide.entries) {
            val g = WorkspaceGeometry.forDisplay(width, height, side, 3f, navigation)
            val left = g.availableLeft * g.contentScale + g.contentTranslationX
            val right = g.availableRight * g.contentScale + g.contentTranslationX
            val top = g.availableTop * g.contentScale + g.contentTranslationY
            val bottom = g.availableBottom * g.contentScale + g.contentTranslationY
            assertEquals(g.contentLeft.toFloat(), left, .6f)
            assertEquals(g.contentRight.toFloat(), right, .6f)
            assertEquals(g.contentTop.toFloat(), top, .6f)
            assertEquals(g.contentBottom.toFloat(), bottom, .6f)
            assertTrue(left >= g.availableLeft - .01f && right <= g.availableRight + .01f)
            assertTrue(top >= g.availableTop - .01f && bottom <= g.availableBottom + .01f)
        }
    }
}
