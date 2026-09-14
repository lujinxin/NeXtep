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
    val title: String = "NeXtep",
    val manualAppOrder: Boolean = false,
    val appComponents: List<String> = emptyList(),
)

object WorkspaceConfigContract {
    const val ACTION_QUERY = "io.github.lujinxin.nextep.action.QUERY_WORKSPACE_CONFIG"
    const val EXTRA_TITLE = "io.github.lujinxin.nextep.extra.TOP_TITLE"
    const val EXTRA_APPS = "io.github.lujinxin.nextep.extra.TOP_APPS"
    const val EXTRA_MANUAL_APP_ORDER = "io.github.lujinxin.nextep.extra.MANUAL_APP_ORDER"
    const val RESULT_CONFIG = 35_001
    private const val MODULE_PACKAGE = "io.github.lujinxin.nextep"
    private const val RECEIVER_CLASS = "io.github.lujinxin.nextep.config.WorkspaceConfigReceiver"

    fun queryIntent(): Intent = Intent(ACTION_QUERY).setComponent(
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
            putBoolean(WorkspaceConfigContract.EXTRA_MANUAL_APP_ORDER, settings.manualAppOrder)
            putStringArrayList(
                WorkspaceConfigContract.EXTRA_APPS,
                ArrayList(settings.appComponents),
            )
        })
    }
}

object WorkspaceConfigClient {
    fun query(context: Context): WorkspaceTopConfig {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "Workspace config must be queried off the main thread"
        }
        val latch = CountDownLatch(1)
        var result = WorkspaceTopConfig()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                if (resultCode == WorkspaceConfigContract.RESULT_CONFIG) {
                    val extras = getResultExtras(false)
                    result = WorkspaceTopConfig(
                        title = extras?.getString(WorkspaceConfigContract.EXTRA_TITLE)
                            ?.trim()
                            ?.takeIf(String::isNotEmpty)
                            ?: "NeXtep",
                        manualAppOrder = extras?.getBoolean(
                            WorkspaceConfigContract.EXTRA_MANUAL_APP_ORDER,
                            false,
                        ) ?: false,
                        appComponents = extras
                            ?.getStringArrayList(WorkspaceConfigContract.EXTRA_APPS)
                            .orEmpty(),
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
        }.getOrDefault(WorkspaceTopConfig())
    }

    private const val QUERY_TIMEOUT_MS = 1_200L
}
