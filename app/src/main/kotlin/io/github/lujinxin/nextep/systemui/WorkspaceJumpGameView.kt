package io.github.lujinxin.nextep.systemui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.os.SystemClock
import android.view.View
import androidx.core.graphics.drawable.toBitmap
import io.github.lujinxin.nextep.R
import io.github.lujinxin.nextep.config.AppLanguage
import kotlin.random.Random

internal class WorkspaceJumpGameView(context: Context, playerIcon: Drawable?) : View(context) {
    private enum class Phase { READY, RUNNING, PAUSED, LOST }
    private data class Obstacle(var x: Float, val stack: Int, val icon: Bitmap?, var counted: Boolean = false)

    private val density = resources.displayMetrics.density
    private val scenery by lazy { WorkspaceJumpScenery(density) }
    private val records by lazy {
        context.createDeviceProtectedStorageContext().getSharedPreferences("nextep_jump_game", Context.MODE_PRIVATE)
    }
    private val sprites = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(11f)
        color = Color.rgb(31, 66, 78)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1f)
        color = Color.rgb(109, 157, 86)
    }
    private val bounds = RectF()
    private val avatar = bitmap(playerIcon)
    private val obstacles = mutableListOf<Obstacle>()
    private var obstacleIcons = emptyList<Bitmap>()
    private var phase = Phase.READY
    private var active = false
    private var lastFrame = 0L
    private var jumpTime: Float? = null
    private var passed = 0
    private var distance = 0f
    private var savedBest: Int? = null
    private var scoreText = ""

    fun setIcons(icons: List<Drawable>) {
        obstacleIcons = icons.take(8).mapNotNull(::bitmap)
        invalidate()
    }

    fun setPageActive(visible: Boolean) {
        if (active == visible) return
        active = visible
        if (!active && phase == Phase.RUNNING) setPhase(Phase.PAUSED)
        lastFrame = 0L
        invalidate()
    }

    fun tap() {
        if (!active || width <= 0 || height <= 0) return
        when (phase) {
            Phase.READY, Phase.LOST -> {
                reset()
                jumpTime = 0f
                setPhase(Phase.RUNNING)
            }
            Phase.PAUSED -> setPhase(Phase.RUNNING)
            Phase.RUNNING -> if (jumpTime == null) jumpTime = 0f
        }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
        setPhase(Phase.READY)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val floor = height - dp(5f)
        val unit = minOf(dp(12f), (floor - dp(2f)) / 5.5f)
        if (unit < dp(3f)) return
        val playerX = dp(14f)
        val playerSize = 1.65f * unit
        if (phase == Phase.RUNNING && active) step(playerX, playerSize, unit)
        val lift = jumpLift(unit)
        scenery.draw(canvas, width, height, floor, distance)
        canvas.drawLine(0f, floor, width.toFloat(), floor, line)
        sprite(canvas, avatar, playerX, floor - lift - playerSize, playerSize, Color.rgb(65, 159, 100), round = true)
        obstacles.forEach { obstacle ->
            for (level in 1..obstacle.stack) {
                sprite(canvas, obstacle.icon, obstacle.x, floor - level * unit, unit, Color.rgb(71, 130, 180))
            }
        }
        if (phase != Phase.RUNNING) {
            text.textAlign = Paint.Align.CENTER
            canvas.drawText(AppLanguage.string(context, hint()), width / 2f, maxOf(dp(25f), height * 0.53f), text)
        }
        if (scoreText.isEmpty()) refreshScore()
        text.textAlign = Paint.Align.RIGHT
        canvas.drawText(scoreText, width - dp(4f), dp(12f), text)
        if (phase == Phase.RUNNING && active) postInvalidateOnAnimation()
    }

    private fun step(playerX: Float, playerSize: Float, unit: Float) {
        val now = SystemClock.uptimeMillis()
        val dt = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1_000f).coerceIn(0f, 0.05f)
        lastFrame = now
        jumpTime = jumpTime?.plus(dt)?.takeIf { it < JUMP_SECONDS }
        val speed = dp(176f + passed.coerceAtMost(24) * 4f)
        distance += speed * dt
        obstacles.forEach { it.x -= speed * dt }
        val last = obstacles.lastOrNull()
        if (last == null || width - last.x > maxOf(width * 0.46f, speed * 0.92f)) {
            val maxStack = if (passed < 3) 2 else 3
            obstacles += Obstacle(
                width.toFloat() + if (last == null) dp(8f) else dp(Random.nextInt(5, 45).toFloat()),
                Random.nextInt(1, maxStack + 1),
                obstacleIcons.randomOrNull(),
            )
        }
        val lift = jumpLift(unit)
        val edgeInset = unit * 0.12f
        if (obstacles.any {
            it.x + unit - edgeInset > playerX + edgeInset && it.x + edgeInset < playerX + playerSize - edgeInset &&
                lift + edgeInset < it.stack * unit
        }) {
            setPhase(Phase.LOST)
            return
        }
        var changed = false
        obstacles.forEach { obstacle ->
            if (!obstacle.counted && obstacle.x + unit < playerX) {
                obstacle.counted = true
                passed++
                changed = true
            }
        }
        if (changed) refreshScore()
        obstacles.removeAll { it.x + unit < 0f }
    }

    private fun sprite(canvas: Canvas, bitmap: Bitmap?, x: Float, y: Float, side: Float, color: Int, round: Boolean = false) {
        bounds.set(x, y, x + side, y + side)
        if (bitmap != null) {
            canvas.drawBitmap(bitmap, null, bounds, sprites)
        } else {
            sprites.color = color
            val radius = side * if (round) 0.5f else 0.18f
            canvas.drawRoundRect(bounds, radius, radius, sprites)
        }
    }

    private fun jumpLift(unit: Float): Float {
        val progress = (jumpTime ?: return 0f) / JUMP_SECONDS
        return 4f * progress * (1f - progress) * unit * 3.65f
    }

    private fun reset() {
        saveRecord()
        obstacles.clear()
        lastFrame = 0L
        jumpTime = null
        passed = 0
        distance = 0f
        scoreText = ""
    }

    private fun setPhase(next: Phase) {
        if (next == Phase.LOST || next == Phase.PAUSED) saveRecord()
        phase = next
        lastFrame = 0L
        contentDescription = AppLanguage.string(context, hint())
    }

    private fun hint(): Int = when (phase) {
        Phase.READY, Phase.RUNNING -> R.string.workspace_game_jump
        Phase.PAUSED -> R.string.workspace_game_resume
        Phase.LOST -> R.string.workspace_game_retry
    }

    private fun record(): Int = savedBest ?: records.getInt("best", 0).also { savedBest = it }

    private fun saveRecord() {
        if (passed <= 0) return
        val previous = records.getInt("best", 0)
        savedBest = maxOf(previous, passed)
        if (passed > previous) records.edit().putInt("best", passed).apply()
    }

    private fun refreshScore() {
        scoreText = AppLanguage.string(context, R.string.workspace_game_score, passed, maxOf(passed, record()))
    }

    private fun bitmap(icon: Drawable?): Bitmap? = runCatching { icon?.toBitmap(64, 64) }.getOrNull()
    private fun dp(value: Float) = value * density

    private companion object {
        const val JUMP_SECONDS = 0.66f
    }
}
