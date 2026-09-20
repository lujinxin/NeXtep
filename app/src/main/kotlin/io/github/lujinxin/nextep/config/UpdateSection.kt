package io.github.lujinxin.nextep.config

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.lujinxin.nextep.R
import java.util.concurrent.Executors

internal object UpdateSection {
    fun create(activity: AppCompatActivity): View {
        val installed = activity.packageManager.getPackageInfo(activity.packageName, PackageManager.PackageInfoFlags.of(0))
        val version = installed.versionName.orEmpty()
        val endpoint = activity.getString(R.string.update_manifest_url).trim()
        val padding = (18 * activity.resources.displayMetrics.density).toInt()
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val status = TextView(activity).apply {
            text = "当前版本 $version（${installed.longVersionCode}）\n点击检查更新以获取最新版本。"
            textSize = 14f
            setTextColor(Color.rgb(82, 97, 106))
        }
        container.addView(TextView(activity).apply {
            text = "应用更新"
            textSize = 18f
            setTextColor(Color.rgb(24, 33, 38))
        })
        container.addView(status)
        var downloadUrl = UpdateChecker.RELEASES_URL
        val download = MaterialButton(activity).apply {
            text = "在浏览器打开下载页面"
            setOnClickListener { openBrowser(activity, downloadUrl) }
        }
        val check = MaterialButton(activity).apply { text = "检查更新" }
        val executor = Executors.newSingleThreadExecutor()
        var disposed = false
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) {
                disposed = true
                executor.shutdownNow()
            }
        })
        check.setOnClickListener {
            if (disposed) return@setOnClickListener
            check.isEnabled = false
            check.text = "正在检查…"
            status.text = "正在连接${if (endpoint.isBlank()) " GitHub" else "更新服务器"}…"
            executor.execute {
                val result = runCatching { UpdateChecker.check(endpoint, installed.longVersionCode, version) }
                activity.runOnUiThread {
                    if (disposed || activity.isFinishing || activity.isDestroyed) return@runOnUiThread
                    check.isEnabled = true
                    check.text = "检查更新"
                    result.onSuccess { release ->
                        if (release == null) {
                            status.text = "当前版本 $version，暂无更新。"
                            downloadUrl = UpdateChecker.RELEASES_URL
                            download.text = "在浏览器打开下载页面"
                        } else {
                            downloadUrl = release.downloadUrl
                            download.text = "在浏览器下载 ${release.version}"
                            status.text = "当前版本 $version · 发现新版本 ${release.version}"
                            MaterialAlertDialogBuilder(activity)
                                .setTitle("发现新版本 ${release.version}")
                                .setMessage(release.notes.ifBlank { "发布者未提供更新说明。" } + "\n\n下载后请手动安装，并按发布说明重启设备。")
                                .setPositiveButton("浏览器下载") { _, _ -> openBrowser(activity, release.downloadUrl) }
                                .setNegativeButton("稍后再说", null)
                                .show()
                        }
                    }.onFailure {
                        status.text = "检查失败，请检查网络或稍后重试。也可打开下载页面。"
                    }
                }
            }
        }
        container.addView(check)
        container.addView(download)
        return container
    }

    private fun openBrowser(activity: AppCompatActivity, address: String) {
        try {
            // A browser selector avoids handing GitHub links to the GitHub app.
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(UpdateChecker.requireHttps(address))).apply {
                addCategory(Intent.CATEGORY_BROWSABLE)
                selector = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
            }
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "没有可用浏览器，请先安装或启用浏览器", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(activity, "无法打开下载地址，请稍后重试", Toast.LENGTH_LONG).show()
        }
    }
}
