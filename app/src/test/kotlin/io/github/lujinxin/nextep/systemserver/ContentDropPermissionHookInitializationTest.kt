package io.github.lujinxin.nextep.systemserver

import org.junit.Test

class ContentDropPermissionHookInitializationTest {
    @Test fun permissionHookCanInitializeBeforeTheSystemMainLooperExists() {
        // The local Android stubs reject Looper access, just as early system
        // startup cannot supply the main Looper yet. Loading the hook must not
        // create a Handler; only an actual later drop may need its timer.
        Class.forName(ContentDropPermissionHook::class.java.name, true, javaClass.classLoader)
    }
}
