package io.github.lujinxin.nextep.systemui

/** Keep an owned main rotation local even when a player also changes its fullscreen content. */
internal object WorkspaceDisplayRotationPolicy {
    enum class Kind { MAIN, MAIN_CONTENT, HOME, DISPLAY, DISPLAY_CONTAINER, DISPLAY_DECOR, WALLPAPER, OTHER }
    data class Change(
        val kind: Kind,
        val mode: Int,
        val displayId: Int,
        val startRotation: Int,
        val endRotation: Int,
        val samePhysicalSize: Boolean = true,
    ) {
        val rotates: Boolean get() = startRotation != endRotation
    }

    /** Parent tokens remain usable when a fullscreen activity changes without its task. */
    fun <T : Any> isMainContent(container: T, parents: Map<T, T?>, ownsMainContainer: (T) -> Boolean): Boolean {
        var parent = parents[container]
        val visited = mutableSetOf(container)
        while (parent != null && visited.add(parent)) {
            if (ownsMainContainer(parent)) return true
            parent = parents[parent]
        }
        return false
    }

    fun targetRotation(type: Int, changes: List<Change>): Int? {
        // Player fullscreen requests can be grouped with OPEN/CLOSE/TO_FRONT/TO_BACK.
        // Ownership and a real primary-display rotation establish the scope, rather
        // than requiring the global transition to be CHANGE in every app.
        if (type !in setOf(1, 2, 3, 4, 6) ||
            changes.none { it.kind == Kind.MAIN || it.kind == Kind.MAIN_CONTENT }) return null
        val display = changes.singleOrNull { it.kind == Kind.DISPLAY && it.rotates } ?: return null
        if (display.mode != 6 || display.startRotation !in 0..3 || display.endRotation !in 0..3 ||
            !display.samePhysicalSize) return null
        for (change in changes) {
            if (change.kind == Kind.OTHER || change.displayId != 0) return null
            when (change.kind) {
                Kind.MAIN_CONTENT, Kind.DISPLAY_DECOR, Kind.WALLPAPER -> {
                    // Opening/closing activity and decor windows can have an
                    // undefined endpoint or retain a fixed content orientation.
                    if (change.mode !in setOf(1, 2, 3, 4, 6) ||
                        change.startRotation !in -1..3 || change.endRotation !in -1..3) return null
                }
                else -> {
                    if (change.mode != 6 || (change.rotates &&
                            (change.startRotation !in 0..3 || change.endRotation != display.endRotation))) return null
                }
            }
        }
        return display.endRotation
    }
}
