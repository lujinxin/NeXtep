package io.github.lujinxin.nextep.display

import io.github.lujinxin.nextep.config.WorkspaceTopConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentShareReturnPolicyTest {
    private val wechat = activity("com.tencent.mm", ".ui.LauncherUI")
    private val picker = activity("com.tencent.mm", ".ui.transmit.MsgRetransmitUI")
    private val chat = activity("com.tencent.mm", ".ui.chatting.ChattingUI")
    private val policy = ContentShareReturnPolicy()

    @Test fun existingSettingsKeepAutomaticReturnEnabled() {
        assertTrue(WorkspaceTopConfig().returnAfterSharingEnabled)
    }

    @Test fun wechatReturnsAfterThePickerTransitionsToChatWithoutABackPress() {
        assertFalse(observe(picker, 0))
        assertFalse(observe(chat, 1_000))
        assertTrue(observe(chat, 2_200))
    }

    @Test fun theAlternateWechatPickerUsesTheSameCompletionTransition() {
        assertFalse(observe(activity("com.tencent.mm", ".ui.transmit.SelectConversationUI"), 0))
        assertFalse(observe(chat, 500))
        assertTrue(observe(chat, 1_700))
    }

    @Test fun recipientSelectionAndConfirmationNeverReturnWhileThePickerIsOpen() {
        assertFalse(observe(picker, 0))
        assertFalse(observe(picker, 20_000))
    }

    @Test fun anUnrelatedWechatChatDoesNotImplyAShareHasFinished() {
        assertFalse(observe(chat, 0))
        assertFalse(observe(chat, 20_000))
    }

    @Test fun resolverTrampolinesDoNotPrematurelyHideTheRecipientPicker() {
        assertFalse(observe(activity("android", "com.android.internal.app.ResolverActivity"), 0))
        assertFalse(observe(wechat, 200))
        assertFalse(observe(picker, 600))
        assertFalse(observe(picker, 5_000))
    }

    @Test fun qqAndOtherReceiversStillReturnWhenTheirOriginalActivityResumes() {
        val original = activity("com.tencent.mobileqq", ".activity.SplashActivity")
        val share = activity("com.tencent.mobileqq", ".activity.QPublicTransFragmentActivity")
        assertFalse(policy.isReady(original, share, true, 0))
        assertFalse(policy.isReady(original, original, true, 1_000))
        assertTrue(policy.isReady(original, original, true, 2_200))
    }

    @Test fun aNewReturnCandidateStartsItsOwnSettlingInterval() {
        assertFalse(observe(picker, 0))
        assertFalse(observe(wechat, 500))
        assertFalse(observe(chat, 1_500))
        assertFalse(observe(chat, 1_700))
        assertTrue(observe(chat, 2_700))
    }

    @Test fun resetPreventsOneShareFromCompletingTheNext() {
        assertFalse(observe(picker, 0))
        policy.reset()
        assertFalse(observe(chat, 1_000))
        assertFalse(observe(chat, 10_000))
    }

    @Test fun anOngoingWindowTransitionMustSettleBeforeReturning() {
        assertFalse(observe(picker, 0))
        assertFalse(policy.isReady(wechat, chat, false, 1_000))
        assertFalse(observe(chat, 3_000))
        assertTrue(observe(chat, 4_200))
    }

    private fun observe(current: ContentShareReturnPolicy.Activity, now: Long) =
        policy.isReady(wechat, current, true, now)

    private fun activity(pkg: String, name: String) =
        ContentShareReturnPolicy.Activity(pkg, if (name.startsWith('.')) pkg + name else name)
}
