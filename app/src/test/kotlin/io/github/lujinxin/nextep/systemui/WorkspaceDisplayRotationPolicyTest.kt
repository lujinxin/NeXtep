package io.github.lujinxin.nextep.systemui

import org.junit.Assert.*
import org.junit.Test
import io.github.lujinxin.nextep.systemui.WorkspaceDisplayRotationPolicy.Change
import io.github.lujinxin.nextep.systemui.WorkspaceDisplayRotationPolicy.Kind

class WorkspaceDisplayRotationPolicyTest {
    private val main = Change(Kind.MAIN, 6, 0, 0, 1)
    private val display = Change(Kind.DISPLAY, 6, 0, 0, 1)

    @Test fun activityAndTaskFragmentResolveToTheOwnedMainTaskEvenWhenItsChangeIsAbsent() {
        val parents = mapOf("player" to "fragment", "fragment" to "main", "foreign" to "other")
        assertTrue(WorkspaceDisplayRotationPolicy.isMainContent("player", parents) { it == "main" })
        assertFalse(WorkspaceDisplayRotationPolicy.isMainContent("foreign", parents) { it == "main" })
        assertFalse(WorkspaceDisplayRotationPolicy.isMainContent("missing", parents) { it == "main" })
    }

    @Test fun malformedOrUnownedParentsCannotClaimMainContent() {
        val parents = mapOf("first" to "second", "second" to "first", "self" to "self")
        assertFalse(WorkspaceDisplayRotationPolicy.isMainContent("first", parents) { it == "main" })
        assertFalse(WorkspaceDisplayRotationPolicy.isMainContent("self", parents) { it == "self" })
    }

    @Test fun fittedMainAndPhysicalDisplayFinishWithoutScreenSnapshotAnimation() {
        assertEquals(1, WorkspaceDisplayRotationPolicy.targetRotation(6, listOf(main, display)))
        assertEquals(0, WorkspaceDisplayRotationPolicy.targetRotation(6,
            listOf(main.copy(startRotation = 1, endRotation = 0), display.copy(startRotation = 1, endRotation = 0))))
    }

    @Test fun fixedOrientationMainCanStayUnchangedWhileItsDisplayRotates() {
        assertEquals(1, WorkspaceDisplayRotationPolicy.targetRotation(6,
            listOf(main.copy(endRotation = 0), display, Change(Kind.WALLPAPER, 6, 0, 0, 1))))
    }

    @Test fun capturedColorOsRotationIncludesItsVerifiedDisplayAncestors() {
        val ancestor = Change(Kind.DISPLAY_CONTAINER, 6, 0, 0, 1)
        assertEquals(1, WorkspaceDisplayRotationPolicy.targetRotation(6,
            listOf(main, ancestor, ancestor, ancestor, Change(Kind.WALLPAPER, 6, 0, 0, 1), display)))
        assertNull(WorkspaceDisplayRotationPolicy.targetRotation(6,
            listOf(main, ancestor.copy(displayId = 8), display)))
    }

    @Test fun ordinaryAppsHomeAndVirtualDisplaysDoNotTakeTheShortcut() {
        assertNull(WorkspaceDisplayRotationPolicy.targetRotation(6, listOf(main.copy(kind = Kind.OTHER), display)))
        assertNull(WorkspaceDisplayRotationPolicy.targetRotation(6, listOf(main.copy(kind = Kind.HOME), display)))
        assertNull(WorkspaceDisplayRotationPolicy.targetRotation(6, listOf(main, display.copy(displayId = 8))))
    }

    @Test fun playerFullscreenCanCarryPhysicalRotationInVisibilityTransitions() {
        for (type in listOf(1, 2, 3, 4, 6)) {
            val content = Change(Kind.MAIN_CONTENT, type, 0, -1, 1)
            assertEquals(1, WorkspaceDisplayRotationPolicy.targetRotation(type, listOf(main, content, display)))
            assertNull(WorkspaceDisplayRotationPolicy.targetRotation(type, listOf(main, content)))
        }
    }

    @Test fun ownedActivityCanRotateWithoutDuplicatingItsUnchangedTaskInTheChangeList() {
        val activity = Change(Kind.MAIN_CONTENT, 6, 0, 0, 1)
        assertEquals(1, WorkspaceDisplayRotationPolicy.targetRotation(6, listOf(activity, display)))
        assertNull(WorkspaceDisplayRotationPolicy.targetRotation(6,
            listOf(activity.copy(kind = Kind.OTHER), display)))
    }

    @Test fun immersiveDecorAndWallpaperVisibilityDoNotAnimateTheWholeWorkspace() {
        val decor = Change(Kind.DISPLAY_DECOR, 4, 0, -1, -1)
        val wallpaper = Change(Kind.WALLPAPER, 3, 0, 0, 1)
        assertEquals(1, WorkspaceDisplayRotationPolicy.targetRotation(6, listOf(main, decor, wallpaper, display)))
        assertNull(WorkspaceDisplayRotationPolicy.targetRotation(6,
            listOf(main, decor.copy(displayId = 8), wallpaper, display)))
    }

    @Test fun reverseLandscapeKeepsFixedPlayerContentInsideItsOwnedRotation() {
        val content = Change(Kind.MAIN_CONTENT, 6, 0, 1, 1)
        assertEquals(3, WorkspaceDisplayRotationPolicy.targetRotation(6,
            listOf(main.copy(startRotation = 1, endRotation = 3), content,
                display.copy(startRotation = 1, endRotation = 3))))
    }

    @Test fun unrelatedTaskVisibilityStillPreventsTheRotationShortcut() {
        for (mode in listOf(1, 2, 3, 4, 6)) {
            assertNull(WorkspaceDisplayRotationPolicy.targetRotation(6,
                listOf(main, display, Change(Kind.OTHER, mode, 0, 0, 1))))
        }
    }

    @Test fun rootTaskVisibilityAndLockTransitionsRetainNativeHandling() {
        for (type in listOf(7, 8, 11, 12, 13)) {
            assertNull(WorkspaceDisplayRotationPolicy.targetRotation(type, listOf(main, display)))
        }
        for (change in listOf(main.copy(mode = 1), main.copy(mode = 2), main.copy(kind = Kind.OTHER))) {
            assertNull(WorkspaceDisplayRotationPolicy.targetRotation(6, listOf(main, display, change)))
        }
    }

    @Test fun resizeMismatchedAndUndefinedRotationAreRejected() {
        assertNull(WorkspaceDisplayRotationPolicy.targetRotation(6, listOf(main, display.copy(samePhysicalSize = false))))
        assertNull(WorkspaceDisplayRotationPolicy.targetRotation(6, listOf(main.copy(endRotation = 3), display)))
        assertNull(WorkspaceDisplayRotationPolicy.targetRotation(6, listOf(main, display.copy(startRotation = -1))))
        assertNull(WorkspaceDisplayRotationPolicy.targetRotation(6, listOf(main, display.copy(endRotation = 0))))
    }
}
