package io.github.lujinxin.nextep.launcher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherAnimationPolicyTest {
    // Same public int metadata consumed from ColorOS RemoteAnimationTargetCompat.
    private class Target(
        @JvmField val mode: Int,
        @JvmField val activityType: Int,
        @JvmField val rotationChange: Int = 0,
    )

    @Test fun ordinaryAppReturnKeepsWorkspaceLightAnimation() {
        assertTrue(LauncherAnimationPolicy.canOverrideClose(arrayOf(Target(1, 1), Target(0, 2))))
        assertTrue(LauncherAnimationPolicy.canOverrideClose(arrayOf(Target(1, 1))))
    }

    @Test fun openingCameraDoesNotOverrideNativeAnimation() {
        assertFalse(LauncherAnimationPolicy.canOverrideClose(arrayOf(Target(0, 1), Target(1, 2))))
    }

    @Test fun cameraRotationAndRotatingReturnKeepNativeAnimation() {
        for (rotation in listOf(-3, -2, -1, 1, 2, 3)) {
            assertFalse(LauncherAnimationPolicy.canOverrideClose(arrayOf(Target(2, 1, rotation))))
            assertFalse(LauncherAnimationPolicy.canOverrideClose(
                arrayOf(Target(1, 1, rotation), Target(0, 2))))
            assertFalse(LauncherAnimationPolicy.canOverrideClose(
                arrayOf(Target(1, 1), Target(0, 2, rotation))))
        }
        assertFalse(LauncherAnimationPolicy.canOverrideClose(arrayOf(Target(2, 1))))
    }

    @Test fun mixedAndUnknownTargetsRetainTheOemDecision() {
        assertFalse(LauncherAnimationPolicy.canOverrideClose(null))
        assertFalse(LauncherAnimationPolicy.canOverrideClose(emptyArray<Target>()))
        assertFalse(LauncherAnimationPolicy.canOverrideClose(arrayOf(Target(1, 1), null)))
        assertFalse(LauncherAnimationPolicy.canOverrideClose(arrayOf(Target(1, 1), Any())))
        assertFalse(LauncherAnimationPolicy.canOverrideClose(arrayOf(Target(1, 1), Target(0, 1))))
        assertFalse(LauncherAnimationPolicy.canOverrideClose(arrayOf(Target(1, 1), Target(0, 3))))
    }

    @Test fun publicationAndDisplayMustHaveTheSameRotation() {
        assertFalse(LauncherAnimationPolicy.matchesViewport(0, 1, 1080, 2354, 1080, 2354))
        assertFalse(LauncherAnimationPolicy.matchesViewport(1, 3, 2354, 1080, 2354, 1080))
        assertFalse(LauncherAnimationPolicy.matchesViewport(0, 2, 1080, 2354, 1080, 2354))
    }

    @Test fun portraitHomeIsNotTransformedUsingTheCamerasLandscapeViewport() {
        assertFalse(LauncherAnimationPolicy.matchesViewport(1, 1, 2354, 1080, 1080, 2354))
        assertFalse(LauncherAnimationPolicy.matchesViewport(0, 0, 1080, 2354, 2354, 1080))
    }

    @Test fun matchingLayoutsWorkInAllFourRotations() {
        for (rotation in 0..3) {
            val width = if (rotation % 2 == 0) 1080 else 2354
            val height = if (rotation % 2 == 0) 2354 else 1080
            assertTrue(LauncherAnimationPolicy.matchesViewport(rotation, rotation, width, height, width, height))
        }
        assertFalse(LauncherAnimationPolicy.matchesViewport(0, 0, 1080, 2354, 0, 0))
    }
}
