package io.github.lujinxin.nextep.display

import org.junit.Assert.assertEquals
import org.junit.Test

class SlotGeometryTest {
    @Test
    fun appCanvasTracksActualSlotAspectWithoutStretchingOrChangingDensity() {
        for ((width, height) in listOf(300 to 530, 300 to 531, 282 to 576, 230 to 490)) {
            val source = SlotGeometry.forViewport(1080, 480, width, height)
            assertEquals(1080, source.width)
            assertEquals(480, source.densityDpi)
            assertEquals(width.toFloat() / source.width, height.toFloat() / source.height, .0001f)
        }
    }
}
