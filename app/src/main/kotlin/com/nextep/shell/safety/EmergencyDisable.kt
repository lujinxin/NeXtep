package com.nextep.shell.safety

import com.nextep.shell.logging.NeXtepLog
import java.io.File

object EmergencyDisable {
    private val disableFile = File("/data/adb/nextep/disable")

    fun isRequested(): Boolean = try {
        disableFile.exists().also { disabled ->
            if (disabled) NeXtepLog.warn("safety", "Emergency disable file is present")
        }
    } catch (error: SecurityException) {
        NeXtepLog.warn("safety", "Emergency disable file could not be inspected", error)
        false
    }
}
