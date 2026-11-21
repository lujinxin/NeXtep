package io.github.lujinxin.nextep.systemui

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import kotlin.random.Random

internal class WorkspaceJumpWelcome(
    private val message: String,
    private val density: Float,
    animate: Boolean,
) {
    private data class Piece(
        val fromLeft: Boolean,
        val horizontalSpeed: Float,
        val liftSpeed: Float,
        val spin: Float,
        val length: Float,
        val thickness: Float,
        val color: Int,
    )

    private val startedAt = SystemClock.uptimeMillis()
    private val pieces = if (animate) List(28) { index ->
        val ribbon = index % 3 == 0
        Piece(
            fromLeft = index % 2 == 0,
            horizontalSpeed = dp(44f + Random.nextFloat() * 96f),
            liftSpeed = dp(80f + Random.nextFloat() * 92f),
            spin = 160f + Random.nextFloat() * 300f,
            length = dp(if (ribbon) 7f else 3.5f),
            thickness = dp(if (ribbon) 2f else 3f),
            color = COLORS[index % COLORS.size],
        )
    } else emptyList()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xff1f424e.toInt()
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
        textSize = dp(14f)
    }
    private val labelWidth = label.measureText(message)

    fun draw(canvas: Canvas, width: Float, floor: Float): Boolean {
        val elapsed = (SystemClock.uptimeMillis() - startedAt) / 1_000f
        val animating = pieces.isNotEmpty() && elapsed < BURST_SECONDS
        if (animating) drawConfetti(canvas, width, floor, elapsed)

        val centerX = width / 2f
        val centerY = floor * 0.53f
        val maxTextWidth = (width - dp(32f)).coerceAtLeast(1f)
        label.textSize = dp(14f) * minOf(1f, maxTextWidth / labelWidth)
        val halfWidth = minOf(labelWidth, maxTextWidth) / 2f + dp(10f)
        val halfHeight = minOf(dp(12f), floor / 2f)
        paint.color = 0xfffff4dd.toInt()
        canvas.drawRoundRect(
            centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight,
            dp(9f), dp(9f), paint,
        )
        canvas.drawText(message, centerX, centerY - (label.ascent() + label.descent()) / 2f, label)
        return animating
    }

    private fun drawConfetti(canvas: Canvas, width: Float, floor: Float, elapsed: Float) {
        val opacity = ((BURST_SECONDS - elapsed) / 0.32f).coerceIn(0f, 1f)
        val fall = dp(130f) * elapsed * elapsed
        for (piece in pieces) {
            val direction = if (piece.fromLeft) 1f else -1f
            val originX = width * if (piece.fromLeft) 0.12f else 0.88f
            val x = originX + direction * piece.horizontalSpeed * elapsed
            val y = floor - piece.liftSpeed * elapsed + fall
            if (y !in 0f..floor) continue
            paint.color = piece.color
            paint.alpha = (255 * opacity).toInt()
            val save = canvas.save()
            canvas.translate(x, y)
            canvas.rotate(direction * piece.spin * elapsed)
            canvas.drawRoundRect(
                -piece.length / 2f, -piece.thickness / 2f, piece.length / 2f, piece.thickness / 2f,
                dp(0.7f), dp(0.7f), paint,
            )
            canvas.restoreToCount(save)
        }
    }

    private fun dp(value: Float) = value * density

    private companion object {
        const val BURST_SECONDS = 1.25f
        val COLORS = intArrayOf(
            0xffcf5e4c.toInt(), 0xffeeb847.toInt(), 0xff3d967e.toInt(),
            0xff4a7fc0.toInt(), 0xff9568b6.toInt(), 0xffe67e9a.toInt(),
        )
    }
}
