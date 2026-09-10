package com.nextep.shell.launcher

import android.graphics.Matrix
import android.view.MotionEvent

object TouchCoordinateMapper {
    data class Transform(
        val scaleX: Float,
        val scaleY: Float,
        val translationX: Float,
        val translationY: Float,
    )

    fun toContent(event: MotionEvent?, transform: Transform?): Boolean {
        if (event == null || transform == null || transform.scaleX == 0f || transform.scaleY == 0f) {
            return false
        }
        event.transform(matrixToContent(transform))
        return true
    }

    fun toScreen(event: MotionEvent?, transform: Transform?) {
        if (event == null || transform == null) return
        event.transform(matrixToScreen(transform))
    }

    private fun matrixToContent(transform: Transform) = Matrix().apply {
        setValues(
            floatArrayOf(
                1f / transform.scaleX,
                0f,
                -transform.translationX / transform.scaleX,
                0f,
                1f / transform.scaleY,
                -transform.translationY / transform.scaleY,
                0f,
                0f,
                1f,
            ),
        )
    }

    private fun matrixToScreen(transform: Transform) = Matrix().apply {
        setValues(
            floatArrayOf(
                transform.scaleX,
                0f,
                transform.translationX,
                0f,
                transform.scaleY,
                transform.translationY,
                0f,
                0f,
                1f,
            ),
        )
    }
}
