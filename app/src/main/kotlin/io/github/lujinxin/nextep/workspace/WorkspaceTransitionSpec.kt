package io.github.lujinxin.nextep.workspace

import android.view.animation.PathInterpolator

/** Shared timing for task surfaces, HOME and workspace panels. */
internal object WorkspaceTransitionSpec {
    const val DURATION_MS = 300L
    val interpolator = PathInterpolator(0.22f, 0f, 0.12f, 1f)
}
