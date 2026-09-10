package com.nextep.shell.safety

enum class FeatureGate(val defaultEnabled: Boolean) {
    QUICK_SETTINGS_TILE(true),
    TOP_RIGHT_STATUS_BAR_GESTURE(true),
    HARDWARE_KEY_TRIGGER(false),
    SYSTEM_UI(true),
    LAUNCHER_TRANSFORM(true),
    MAIN_TASK_PRESENTER(true),
    VIRTUAL_DISPLAY_SLOTS(true),
    TASK_SWAP(true),
    SYSTEM_SERVER_PATCHES(true),
    FIXED_ORIENTATION_LETTERBOX(true),
    SIZE_COMPAT_DISPLAY_INSETS(true),
    MEDIA_CONTROLS(true),
    SIDEBAR_SIDE_SWITCH(true),
    EXPERIMENTAL_COLOR_OS_APIS(false),
}
