package io.github.lujinxin.nextep.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlAreaVideoGeometryTest {
    @Test fun portraitHostKeepsItsDisplayAxes() {
        val viewport = viewport(0f)
        assertEquals(1080, viewport.width)
        assertEquals(592, viewport.height)
        assertEquals(1f, viewport.inverseScaleX, .0001f)
        assertEquals(1f, viewport.inverseScaleY, .0001f)
    }

    @Test fun eitherLandscapeDirectionProducesAnUprightTallCanvasWithoutUnequalVideoScaling() {
        for (rotation in listOf(-90f, 90f)) {
            val viewport = viewport(rotation)
            assertEquals(592, viewport.width)
            assertEquals(1080, viewport.height)
            // Compose the known SurfaceView bounding-box scale with our wrapper.
            assertEquals(1f, (592f / 1080f) * viewport.inverseScaleX, .0001f)
            assertEquals(1f, (1080f / 592f) * viewport.inverseScaleY, .0001f)
            assertEquals(ControlAreaVideoGeometry.Clip(0, 0, 592, 1080), viewport.clip)
        }
    }

    @Test fun swipingClipsTheSamePhysicalPartOfEitherLandscapePanel() {
        val clip = ControlAreaVideoGeometry.Clip(270, 0, 1080, 592)
        assertEquals(ControlAreaVideoGeometry.Clip(0, 270, 592, 1080),
            ControlAreaVideoGeometry.viewport(1080, 592, 90f, clip).clip)
        assertEquals(ControlAreaVideoGeometry.Clip(0, 0, 592, 810),
            ControlAreaVideoGeometry.viewport(1080, 592, -90f, clip).clip)
    }

    @Test fun anOffscreenPageCannotExposeItsNativeVideo() {
        val empty = ControlAreaVideoGeometry.Clip(1080, 0, 1080, 592)
        for (rotation in listOf(0f, -90f, 90f)) {
            assertTrue(ControlAreaVideoGeometry.viewport(1080, 592, rotation, empty).clip.isEmpty)
            assertFalse(viewport(rotation).clip.isEmpty)
        }
    }

    @Test fun aPortraitPictureCanFillTheTallAreaWithoutCropping() {
        val placement = ControlAreaVideoGeometry.place(500, 1000, 500, 1000, fill = false)
        assertEquals(1f, placement.scale, .0001f)
        assertEquals(0f, placement.left, .0001f)
        assertEquals(0f, placement.top, .0001f)
    }

    @Test fun aLandscapePictureInATallAreaHasEqualTopAndBottomBars() {
        val placement = ControlAreaVideoGeometry.place(2000, 1000, 500, 1000, fill = false)
        assertEquals(.25f, placement.scale, .0001f)
        assertEquals(0f, placement.left, .0001f)
        assertEquals(375f, placement.top, .0001f)
        assertEquals(375f, 1000 - (placement.top + 1000 * placement.scale), .0001f)
    }

    @Test fun aPortraitPictureInAWideAreaHasEqualSideBars() {
        val placement = ControlAreaVideoGeometry.place(1000, 2000, 1000, 500, fill = false)
        assertEquals(.25f, placement.scale, .0001f)
        assertEquals(375f, placement.left, .0001f)
        assertEquals(0f, placement.top, .0001f)
    }

    @Test fun fillingIsAnExplicitCenteredCropAndUsesUniformScale() {
        val placement = ControlAreaVideoGeometry.place(2000, 1000, 500, 1000, fill = true)
        assertEquals(1f, placement.scale, .0001f)
        assertEquals(-750f, placement.left, .0001f)
        assertEquals(0f, placement.top, .0001f)
    }

    private fun viewport(rotation: Float) = ControlAreaVideoGeometry.viewport(1080, 592, rotation,
        ControlAreaVideoGeometry.Clip(0, 0, 1080, 592))
}
