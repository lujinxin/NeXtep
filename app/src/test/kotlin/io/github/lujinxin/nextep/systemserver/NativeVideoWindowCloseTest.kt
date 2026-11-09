package io.github.lujinxin.nextep.systemserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NativeVideoWindowCloseTest {
    private fun node(depth: Int, hash: String, id: String, visible: String = "VISIBLE", label: String = "") =
        " ".repeat(depth) + "android.widget.ImageView@$hash " + listOf(
            "mID" to "id/$id", "getVisibility()" to visible, "isClickable()" to "true",
            "isEnabled()" to "true", "layout:getWidth()" to "102", "layout:getHeight()" to "102",
            "text" to label,
        ).joinToString(" ") { (key, value) -> "$key=${value.length},$value" }

    @Test fun selectsNativeCloseListenerAndIgnoresFullscreenControl() {
        assertEquals("android.widget.ImageView@b", NativeVideoWindowClose.findCloseTarget(
            listOf(node(0, "a", "root"), node(1, "b", "close"), node(1, "c", "fullscreen")).joinToString("\n")))
    }

    @Test fun neverSelectsHiddenDescendantsOrAmbiguousCloseButtons() {
        assertNull(NativeVideoWindowClose.findCloseTarget(
            listOf(node(0, "a", "root", "GONE"), node(1, "b", "close")).joinToString("\n")))
        assertNull(NativeVideoWindowClose.findCloseTarget(
            listOf(node(0, "a", "root"), node(1, "b", "close"), node(1, "c", "btn_close")).joinToString("\n")))
    }

    @Test fun propertyLookingLabelCannotForgeACloseControl() {
        assertNull(NativeVideoWindowClose.findCloseTarget(node(0, "a", "fullscreen", label = "mID=8,id/close isClickable()=4,true")))
    }

    @Test fun autoHiddenPlayerChromeRetainsItsNativeCloseAction() {
        assertEquals("android.widget.ImageView@c", NativeVideoWindowClose.findCloseTarget(
            listOf(node(0, "a", "root"), node(1, "b", "controls", "GONE"), node(2, "c", "close")).joinToString("\n")))
    }

    @Test fun hongguoObfuscatedChromeUsesItsOwnCloseListener() {
        // Captured from the native FloatingWindowLayout: close at upper left,
        // fullscreen at upper right, playback in the centre; resource IDs are obfuscated.
        assertEquals("android.widget.FrameLayout@a0d58d7", NativeVideoWindowClose.findCloseTarget(hongguoHierarchy()))
    }

    @Test fun geometricControlsNeverApplyToUnrecognizedWindows() {
        assertNull(NativeVideoWindowClose.findCloseTarget(hongguoHierarchy()
            .replace("com.dragon.read.component.shortvideo.impl.floatwindow.FloatingWindowLayout", "android.widget.FrameLayout")))
    }

    @Test fun hongguoPlaybackUsesCentreButtonAndNeverTheFullscreenButton() {
        assertEquals("android.widget.FrameLayout@764ab73",
            NativeVideoWindowControls.findControls(hongguoHierarchy()).playbackToggle)
    }

    @Test fun hongguoCornersKeepDistinctNativeActionsAndSourceGeometry() {
        val controls = NativeVideoWindowControls.findControls(hongguoHierarchy())
        assertEquals("android.widget.FrameLayout@d4dcad", controls.fullscreen)
        assertEquals("android.widget.ImageView@60a85c4", controls.closeChrome?.glyph)
        assertEquals("android.widget.ImageView@acdde2", controls.fullscreenChrome?.glyph)
        assertEquals(594, controls.fullscreenChrome?.bounds?.rootWidth)
        assertEquals(474, controls.fullscreenChrome?.bounds?.left)
    }

    @Test fun replacedGlyphsDoNotLoseTheOriginalCloseAndFullscreenListeners() {
        val hiddenGlyphs = hongguoHierarchy().lineSequence().map { line ->
            if (line.contains("@60a85c4 ") || line.contains("@acdde2 ")) {
                "$line drawing:getAlpha()=3,0.0"
            } else line
        }.joinToString("\n")
        val controls = NativeVideoWindowControls.findControls(hiddenGlyphs)
        assertEquals("android.widget.FrameLayout@a0d58d7", controls.close)
        assertEquals("android.widget.FrameLayout@d4dcad", controls.fullscreen)
        assertEquals(0, controls.closeChrome?.visibility)
        assertEquals(0, controls.fullscreenChrome?.visibility)
        assertEquals(0f, controls.closeChrome?.alpha)
        assertEquals(0f, controls.fullscreenChrome?.alpha)
    }

    @Test fun nativeProfileSupportsGetterAndFieldCoordinates() {
        assertEquals("android.widget.FrameLayout@a0d58d7", NativeVideoWindowClose.findCloseTarget(
            hongguoHierarchy().replace("mLeft=", "layout:getLeft()=").replace("mTop=", "layout:getTop()=")))
    }

    @Test fun hongguoProfileRejectsIncompleteOrHiddenWindows() {
        assertNull(NativeVideoWindowControls.findControls(hongguoHierarchy()
            .replace("android.widget.FrameLayout@764ab73", "android.widget.FrameLayout@invalid")).close)
        assertNull(NativeVideoWindowControls.findControls(hongguoHierarchy()
            .replace("getVisibility()=7,VISIBLE", "getVisibility()=4,GONE")).playbackToggle)
    }

    private fun hongguoHierarchy(): String {
        fun view(depth: Int, type: String, hash: String, id: String, left: Int, top: Int,
            width: Int, height: Int, clickable: Boolean = false, visible: String = "VISIBLE") =
            " ".repeat(depth) + "$type@$hash " + listOf(
                "mID" to "id/$id", "getVisibility()" to visible, "isClickable()" to clickable.toString(),
                "isEnabled()" to "true", "mLeft" to left.toString(), "mTop" to top.toString(),
                "layout:getWidth()" to width.toString(), "layout:getHeight()" to height.toString(),
            ).joinToString(" ") { (key, value) -> "$key=${value.length},$value" }
        return listOf(
            view(0, "com.dragon.read.component.shortvideo.impl.floatwindow.FloatingWindowLayout", "bb6f49e", "root_view", 0, 0, 594, 939),
            view(1, "androidx.constraintlayout.widget.ConstraintLayout", "9a81056", "gqn", 0, 0, 594, 939, visible = "GONE"),
            view(2, "android.widget.FrameLayout", "a0d58d7", "f", 0, 0, 120, 120, clickable = true),
            view(3, "android.widget.ImageView", "60a85c4", "icon_one", 36, 36, 48, 48),
            view(2, "android.widget.FrameLayout", "d4dcad", "jh", 474, 0, 120, 120, clickable = true),
            view(3, "android.widget.ImageView", "acdde2", "icon_two", 24, 36, 60, 60),
            view(2, "android.widget.FrameLayout", "764ab73", "dy", 207, 380, 180, 180, clickable = true),
            view(3, "android.widget.ImageView", "c3a30", "gpe", 27, 27, 126, 126),
        ).joinToString("\n")
    }
}
