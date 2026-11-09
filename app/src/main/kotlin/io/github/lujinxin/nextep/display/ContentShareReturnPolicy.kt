package io.github.lujinxin.nextep.display

/** Observe share UI completion without claiming that a network delivery has succeeded. */
internal class ContentShareReturnPolicy(private val settleMs: Long = 1_200L) {
    data class Activity(val packageName: String, val className: String)

    private var shareScreenObserved = false
    private var wechatPickerObserved = false
    private var returnActivity: Activity? = null
    private var returnSince: Long? = null

    fun reset() {
        shareScreenObserved = false
        wechatPickerObserved = false
        returnActivity = null
        returnSince = null
    }

    fun isReady(original: Activity, current: Activity, canExchange: Boolean, now: Long): Boolean {
        if (current != original) shareScreenObserved = true
        val wechat = original.packageName == WECHAT_PACKAGE && current.packageName == WECHAT_PACKAGE
        val picker = wechat && current.className in WECHAT_PICKERS
        if (picker) wechatPickerObserved = true

        // WeChat closes its share picker and opens the recipient's chat after sending.
        // Requiring the picker first avoids treating an unrelated chat as completion.
        val wechatFinished = wechat && wechatPickerObserved && current.className == WECHAT_CHAT
        val completed = wechatFinished || (shareScreenObserved && current == original && !picker)
        if (!completed || !canExchange) {
            returnActivity = null
            returnSince = null
            return false
        }
        // A resolver -> app trampoline can briefly reveal the original Activity.
        // A changed candidate always starts a fresh settling interval.
        if (returnActivity != current) {
            returnActivity = current
            returnSince = now
        }
        return now - checkNotNull(returnSince) >= settleMs
    }

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val WECHAT_CHAT = "com.tencent.mm.ui.chatting.ChattingUI"
        val WECHAT_PICKERS = setOf(
            "com.tencent.mm.ui.transmit.MsgRetransmitUI",
            "com.tencent.mm.ui.transmit.SelectConversationUI",
        )
    }
}
