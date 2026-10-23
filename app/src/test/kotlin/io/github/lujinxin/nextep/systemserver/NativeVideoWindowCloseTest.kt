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
}
