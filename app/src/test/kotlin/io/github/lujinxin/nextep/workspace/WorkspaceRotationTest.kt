package io.github.lujinxin.nextep.workspace

import org.junit.Assert.*
import org.junit.Test

class WorkspaceRotationTest {
    @Test fun repeatedRotationsKeepRecordedControlsSlotsAndCanvas() {
        for (insets in listOf(WorkspaceNavigationInsets(), WorkspaceNavigationInsets(bottom = 132))) {
            val original = WorkspaceLayoutProfile.create(1080, 2354, 480, insets)
            var current = original
            repeat(20) {
                for (rotation in listOf(1, 3, 0)) {
                    current = current.rotatedTo(rotation)
                    assertEquals(original.geometry.topHeight, current.geometry.topHeight)
                    assertEquals(original.geometry.sidebarWidth, current.geometry.sidebarWidth)
                    assertEquals(original.slots, current.slots)
                    assertEquals(original.canvas, current.canvas)
                    assertEquals(original.source, current.source)
                    assertEquals(original.geometry.contentScale, current.geometry.contentScale, 0f)
                }
                assertEquals(original, current)
            }
        }
    }

    @Test fun navigationInsetsFollowDisplayCoordinatesWithoutResampling() {
        val original = WorkspaceGeometry.forDisplay(1080, 2354, density = 3f,
            navigationInsets = WorkspaceNavigationInsets(bottom = 132))
        val left = original.rotatedTo(1)
        val right = original.rotatedTo(3)
        assertEquals(WorkspaceNavigationInsets(right = 132), left.navigationInsets)
        assertEquals(WorkspaceNavigationInsets(left = 132), right.navigationInsets)
        assertEquals(2354, left.screenWidth)
        assertEquals(1080, left.screenHeight)
        assertEquals(original, left.rotatedTo(3).rotatedTo(0))
    }

    @Test fun onlyRotationCanTakeTheNoRefreshPath() {
        val original = WorkspaceGeometry.forDisplay(1080, 2354, density = 3f)
        val rotated = original.rotatedTo(1)
        assertTrue(rotated.isRotationOf(original))
        assertFalse(original.isRotationOf(original))
        assertFalse(rotated.copy(sidebarSide = SidebarSide.LEFT).isRotationOf(original))
        assertFalse(rotated.copy(topHeight = rotated.topHeight + 1).isRotationOf(original))
        assertFalse(rotated.copy(navigationInsets = WorkspaceNavigationInsets(right = 132)).isRotationOf(original))
    }
}
