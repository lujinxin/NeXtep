package io.github.lujinxin.nextep.config

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import io.github.lujinxin.nextep.logging.NeXtepLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

data class WorkspaceTopConfig(
    // Missing mode falls back to legacy custom text or the default icon.
    val title: String = "",
    val contentMode: String = TopContentMode.ICON,
    val showSeconds: Boolean = false,
    val frostStrength: Int = 50,
    val manualAppOrder: Boolean = false,
    val appComponents: List<String> = emptyList(),
    val textScroll: Boolean = false,
    val textFontFamily: String = TopTextStyle.DEFAULT_FAMILY,
    val textSizeSp: Int = TopTextStyle.DEFAULT_SIZE_SP,
    val textBold: Boolean = false,
    val statusBarGestureEnabled: Boolean = true,
    val autoMinimizeMainOnTopAppSwitch: Boolean = false,
)

object WorkspaceConfigContract {
    const val ACTION_CHANGED = "io.github.lujinxin.nextep.action.WORKSPACE_CONFIG_CHANGED"
    const val ACTION_QUERY = "io.github.lujinxin.nextep.action.QUERY_WORKSPACE_CONFIG"
    const val EXTRA_TITLE = "io.github.lujinxin.nextep.extra.TOP_TITLE"
    const val EXTRA_CONTENT_MODE = "io.github.lujinxin.nextep.extra.TOP_CONTENT_MODE"
    const val EXTRA_SHOW_SECONDS = "io.github.lujinxin.nextep.extra.TOP_SHOW_SECONDS"
    const val EXTRA_FROST_STRENGTH = "io.github.lujinxin.nextep.extra.FROST_STRENGTH"
    const val EXTRA_APPS = "io.github.lujinxin.nextep.extra.TOP_APPS"
    const val EXTRA_MANUAL_APP_ORDER = "io.github.lujinxin.nextep.extra.MANUAL_APP_ORDER"
    const val EXTRA_TEXT_SCROLL = "io.github.lujinxin.nextep.extra.TOP_TEXT_SCROLL"
    const val EXTRA_TEXT_FONT = "io.github.lujinxin.nextep.extra.TOP_TEXT_FONT"
    const val EXTRA_TEXT_SIZE = "io.github.lujinxin.nextep.extra.TOP_TEXT_SIZE"
    const val EXTRA_TEXT_BOLD = "io.github.lujinxin.nextep.extra.TOP_TEXT_BOLD"
    const val EXTRA_STATUS_BAR_GESTURE_ENABLED = "io.github.lujinxin.nextep.extra.STATUS_BAR_GESTURE_ENABLED"
    const val EXTRA_AUTO_MINIMIZE_MAIN = "io.github.lujinxin.nextep.extra.AUTO_MINIMIZE_MAIN_ON_TOP_APP_SWITCH"
    const val RESULT_CONFIG = 35_001
    private const val MODULE_PACKAGE = "io.github.lujinxin.nextep"
    private const val RECEIVER_CLASS = "io.github.lujinxin.nextep.config.WorkspaceConfigReceiver"

    fun queryIntent(): Intent = Intent(ACTION_QUERY)
        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND or Intent.FLAG_INCLUDE_STOPPED_PACKAGES).setComponent(
        ComponentName(MODULE_PACKAGE, RECEIVER_CLASS),
    )
}

class WorkspaceConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!isOrderedBroadcast || intent.action != WorkspaceConfigContract.ACTION_QUERY) return
        val settings = SettingsRepository(context).topBarSettings()
        resultCode = WorkspaceConfigContract.RESULT_CONFIG
        setResultExtras(Bundle().apply {
            putString(WorkspaceConfigContract.EXTRA_TITLE, settings.title)
            putString(WorkspaceConfigContract.EXTRA_CONTENT_MODE, settings.contentMode)
            putBoolean(WorkspaceConfigContract.EXTRA_SHOW_SECONDS, settings.showSeconds)
            putInt(WorkspaceConfigContract.EXTRA_FROST_STRENGTH, settings.frostStrength)
            putBoolean(WorkspaceConfigContract.EXTRA_MANUAL_APP_ORDER, settings.manualAppOrder)
            putBoolean(WorkspaceConfigContract.EXTRA_TEXT_SCROLL, settings.textScroll)
            putString(WorkspaceConfigContract.EXTRA_TEXT_FONT, settings.textFontFamily)
            putInt(WorkspaceConfigContract.EXTRA_TEXT_SIZE, settings.textSizeSp)
            putBoolean(WorkspaceConfigContract.EXTRA_TEXT_BOLD, settings.textBold)
            putBoolean(WorkspaceConfigContract.EXTRA_STATUS_BAR_GESTURE_ENABLED, settings.statusBarGestureEnabled)
            putBoolean(WorkspaceConfigContract.EXTRA_AUTO_MINIMIZE_MAIN, settings.autoMinimizeMainOnTopAppSwitch)
            putStringArrayList(
                WorkspaceConfigContract.EXTRA_APPS,
                ArrayList(settings.appComponents),
            )
        })
    }
}

object WorkspaceConfigClient {
    fun query(context: Context): WorkspaceTopConfig = queryOrNull(context) ?: WorkspaceTopConfig()

    fun queryOrNull(context: Context): WorkspaceTopConfig? {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Workspace config must be queried off the main thread"
        }
        val latch = CountDownLatch(1)
        var result: WorkspaceTopConfig? = null
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (resultCode == WorkspaceConfigContract.RESULT_CONFIG) {
                    val extras = getResultExtras(false)
                    result = WorkspaceTopConfig(
                        title = extras?.getString(WorkspaceConfigContract.EXTRA_TITLE)
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                            ?: "",
                        contentMode = TopContentMode.resolve(
                            extras?.getString(WorkspaceConfigContract.EXTRA_CONTENT_MODE),
                            extras?.getString(WorkspaceConfigContract.EXTRA_TITLE).orEmpty(),
                        ),
                        showSeconds = extras?.getBoolean(WorkspaceConfigContract.EXTRA_SHOW_SECONDS, false) ?: false,
                        frostStrength = (extras?.getInt(WorkspaceConfigContract.EXTRA_FROST_STRENGTH, 50) ?: 50).coerceIn(0, 100),
                        manualAppOrder = extras?.getBoolean(
                            WorkspaceConfigContract.EXTRA_MANUAL_APP_ORDER,
                            false,
                        ) ?: false,
                        appComponents = extras
                            ?.getStringArrayList(WorkspaceConfigContract.EXTRA_APPS)
                            .orEmpty(),
                        textScroll = extras?.getBoolean(WorkspaceConfigContract.EXTRA_TEXT_SCROLL, false) ?: false,
                        textFontFamily = TopTextStyle.resolveFamily(extras?.getString(WorkspaceConfigContract.EXTRA_TEXT_FONT)),
                        textSizeSp = (extras?.getInt(WorkspaceConfigContract.EXTRA_TEXT_SIZE, TopTextStyle.DEFAULT_SIZE_SP)
                            ?: TopTextStyle.DEFAULT_SIZE_SP).coerceIn(TopTextStyle.MIN_SIZE_SP, TopTextStyle.MAX_SIZE_SP),
                        textBold = extras?.getBoolean(WorkspaceConfigContract.EXTRA_TEXT_BOLD, false) ?: false,
                        statusBarGestureEnabled = extras?.getBoolean(
                            WorkspaceConfigContract.EXTRA_STATUS_BAR_GESTURE_ENABLED, true,
                        ) ?: true,
                        autoMinimizeMainOnTopAppSwitch = extras?.getBoolean(
                            WorkspaceConfigContract.EXTRA_AUTO_MINIMIZE_MAIN, false,
                        ) ?: false,
                    )
                }
                latch.countDown()
            }
        }
        return runCatching {
            context.sendOrderedBroadcast(
                WorkspaceConfigContract.queryIntent(),
                null,
                receiver,
                Handler(Looper.getMainLooper()),
                0,
                null,
                null,
            )
            latch.await(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            result
        }.onFailure { error ->
            NeXtepLog.warn("workspace_config", "Unable to query module settings", error)
        }.getOrNull()
    }

    // A cold module process may enumerate OEM fonts before replying after boot.
    private const val QUERY_TIMEOUT_MS = 4_000L
}
