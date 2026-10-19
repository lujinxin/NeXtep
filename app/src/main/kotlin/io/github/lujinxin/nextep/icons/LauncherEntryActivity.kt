package io.github.lujinxin.nextep.icons

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.lujinxin.nextep.config.MainActivity

/** An alias can be disabled without closing the settings task that it opens. */
class LauncherEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(this, MainActivity::class.java).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
        ))
        finish()
    }
}
