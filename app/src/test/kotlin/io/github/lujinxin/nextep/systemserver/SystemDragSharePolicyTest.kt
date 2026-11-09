package io.github.lujinxin.nextep.systemserver

import io.github.lujinxin.nextep.config.WorkspaceTopConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemDragSharePolicyTest {
    @Test fun legacySettingsDefaultToBlockingSystemDragSuggestions() {
        assertTrue(WorkspaceTopConfig().blockSystemDragShareEnabled)
    }

    @Test fun thePortalReceiverAndPanelAreBlockedInTheWorkspace() {
        assertTrue(blocks("ContentPortal-DragEventReceiver"))
        assertTrue(blocks("ContentPortalWindow"))
    }

    @Test fun switchingOffRestoresTheSameReceiver() {
        assertTrue(blocks())
        assertFalse(blocks(enabled = false))
        assertTrue(blocks(enabled = true))
    }

    @Test fun leavingTheWorkspaceRestoresTheSystemReceiver() {
        assertTrue(blocks())
        assertFalse(blocks(active = false))
    }

    @Test fun externalDisplaysKeepTheirNativeDragTargets() {
        assertFalse(blocks(display = 3))
    }

    @Test fun applicationTargetsAndOtherPortalUiRemainAvailable() {
        assertFalse(blocks(pkg = "com.android.systemui"))
        assertFalse(blocks(pkg = "com.tencent.mobileqq"))
        assertFalse(blocks(title = "ContentPortalSettings"))
        assertFalse(blocks(title = null))
        assertFalse(blocks(pkg = null))
    }

    @Test fun hookInitializationDoesNotNeedTheSystemMainLooper() {
        Class.forName(SystemDragShareHook::class.java.name, true, javaClass.classLoader)
    }

    private fun blocks(title: String? = "ContentPortal-DragEventReceiver",
        active: Boolean = true, enabled: Boolean = true, display: Int = 0,
        pkg: String? = "com.oplus.contentportal") = SystemDragSharePolicy.shouldBlock(
        active, enabled, display, pkg, title,
    )
}
