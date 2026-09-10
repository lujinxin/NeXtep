package com.nextep.shell.logging

import android.os.Build
import android.util.Log

object NeXtepLog {
    private const val TAG = "NeXtep"

    fun debug(feature: String, message: String) = write(Log.DEBUG, feature, message, null)
    fun info(feature: String, message: String) = write(Log.INFO, feature, message, null)
    fun warn(feature: String, message: String, error: Throwable? = null) =
        write(Log.WARN, feature, message, error)
    fun error(feature: String, message: String, error: Throwable? = null) =
        write(Log.ERROR, feature, message, error)

    private fun write(priority: Int, feature: String, message: String, error: Throwable?) {
        val enriched = "pid=${android.os.Process.myPid()} thread=${Thread.currentThread().name} " +
            "feature=$feature sdk=${Build.VERSION.SDK_INT} message=$message"
        val output = if (error == null) enriched else "$enriched\n${Log.getStackTraceString(error)}"
        Log.println(priority, TAG, output)
    }
}
