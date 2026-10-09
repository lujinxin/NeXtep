package io.github.lujinxin.nextep.systemui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.lujinxin.nextep.systemui.WorkspaceDisplayExchangePolicy.Change
import io.github.lujinxin.nextep.systemui.WorkspaceDisplayExchangePolicy.Kind

class WorkspaceDisplayExchangePolicyTest {
    @Test fun capturedColorOsToFrontMoveUsesNativeImmediateFinish() {
        assertTrue(WorkspaceDisplayExchangePolicy.isExchangeMode(3))
        assertTrue(WorkspaceDisplayExchangePolicy.shouldFinishImmediately(3,
            listOf(Change(Kind.EXCHANGE), Change(Kind.STATIONARY_SLOT_DISPLAY))))
    }

    @Test fun exchangeModesKeepLockAndDisplayLifecycleChangesOut() {
        for (mode in listOf(1, 2, 3, 4, 6)) {
            assertTrue(WorkspaceDisplayExchangePolicy.isExchangeMode(mode))
        }
        for (mode in listOf(0, 5, 7, 8, 9, 10, 11, 12, 13)) {
            assertFalse(WorkspaceDisplayExchangePolicy.isExchangeMode(mode))
        }
    }

    @Test fun slotDisplayAloneDoesNotSuppressOrdinaryTransitions() {
        assertFalse(WorkspaceDisplayExchangePolicy.shouldFinishImmediately(3,
            listOf(Change(Kind.STATIONARY_SLOT_DISPLAY)), hasRecentSlotMove = true))
        assertFalse(WorkspaceDisplayExchangePolicy.shouldFinishImmediately(1,
            listOf(Change(Kind.HOME), Change(Kind.STATIONARY_SLOT_DISPLAY)), hasRecentSlotMove = true))
        assertFalse(WorkspaceDisplayExchangePolicy.shouldFinishImmediately(3,
            listOf(Change(Kind.EXCHANGE), Change(Kind.STATIONARY_SLOT_DISPLAY, rotates = true))))
    }

    @Test fun exchangeWithHomeAndWallpaperUsesNativeImmediateFinish() {
        assertTrue(WorkspaceDisplayExchangePolicy.shouldFinishImmediately(6,
            listOf(Change(Kind.EXCHANGE), Change(Kind.HOME), Change(Kind.WALLPAPER), Change(Kind.STATIONARY_DISPLAY))))
    }

    @Test fun ordinaryHomeLaunchRetainsItsAnimation() {
        assertFalse(WorkspaceDisplayExchangePolicy.shouldFinishImmediately(3,
            listOf(Change(Kind.HOME), Change(Kind.WALLPAPER))))
    }

    @Test fun parkingHomeOnlyDisplayTransitionUsesNativeImmediateFinish() {
        // Captured ColorOS OPEN transition after moving Settings to a slot:
        // HOME CHANGE + stationary physical display CHANGE, without the moved task.
        assertTrue(WorkspaceDisplayExchangePolicy.shouldFinishImmediately(1,
            listOf(Change(Kind.HOME), Change(Kind.STATIONARY_DISPLAY)),
            hasRecentSlotMove = true))
    }

    @Test fun ordinaryHomeDisplayTransitionRetainsItsAnimation() {
        assertFalse(WorkspaceDisplayExchangePolicy.shouldFinishImmediately(1,
            listOf(Change(Kind.HOME), Change(Kind.STATIONARY_DISPLAY))))
        assertFalse(WorkspaceDisplayExchangePolicy.shouldFinishImmediately(1,
            listOf(Change(Kind.HOME), Change(Kind.WALLPAPER)),
            hasRecentSlotMove = true))
    }

    @Test fun recentParkingDoesNotSkipMixedAppsOrRotation() {
        assertFalse(WorkspaceDisplayExchangePolicy.shouldFinishImmediately(1,
            listOf(Change(Kind.HOME), Change(Kind.STATIONARY_DISPLAY), Change(Kind.OTHER)),
            hasRecentSlotMove = true))
        assertFalse(WorkspaceDisplayExchangePolicy.shouldFinishImmediately(1,
            listOf(Change(Kind.HOME), Change(Kind.STATIONARY_DISPLAY, rotates = true)),
            hasRecentSlotMove = true))
    }

    @Test fun mixedAppOrNativeFloatingTransitionRetainsItsAnimation() {
        assertFalse(WorkspaceDisplayExchangePolicy.shouldFinishImmediately(6,
            listOf(Change(Kind.EXCHANGE), Change(Kind.OTHER))))
    }

    @Test fun physicalRotationNeverUsesTheExchangeShortcut() {
        assertFalse(WorkspaceDisplayExchangePolicy.shouldFinishImmediately(6,
            listOf(Change(Kind.EXCHANGE), Change(Kind.STATIONARY_DISPLAY, rotates = true))))
    }

    @Test fun sleepWakeAndKeyguardTransitionsRetainTheirLifecycle() {
        for (type in listOf(5, 7, 8, 9, 10, 11, 12, 13)) {
            assertFalse(WorkspaceDisplayExchangePolicy.shouldFinishImmediately(type, listOf(Change(Kind.EXCHANGE))))
        }
    }
}
