package io.github.lujinxin.nextep.systemui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.ceil
import kotlin.math.sin

/** Original scenery drawn at several parallax speeds behind the player and obstacles. */
internal class WorkspaceJumpScenery(private val density: Float) {
    private data class Sprite(val image: Bitmap, val width: Float, val height: Float)
    private data class FlyingBird(val x: Float, val y: Float, val size: Float, val phase: Float)
    private data class Flock(val spacing: Float, val altitude: Float, val members: List<FlyingBird>)

    private val flocks = listOf(
        Flock(173f, 0.49f, listOf(FlyingBird(0f, 0f, 0.92f, 0f))),
        Flock(235f, 0.33f, listOf(
            FlyingBird(0f, 0f, 0.91f, 1.3f),
            FlyingBird(15f, 6f, 0.72f, 3.8f),
            FlyingBird(32f, -2f, 0.66f, 5.1f),
        )),
        Flock(144f, 0.43f, listOf(
            FlyingBird(0f, 0f, 0.77f, 2.5f),
            FlyingBird(21f, -7f, 0.67f, 6f),
        )),
        Flock(207f, 0.30f, listOf(FlyingBird(0f, 0f, 0.67f, 4.1f))),
    )
    private val flockCycle = flocks.sumOf { it.spacing.toDouble() }.toFloat()
    private var skyHeight = -1f
    private val sky = Paint()
    private val shape = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)
    private val bounds = RectF()
    private val birdPath = Path()
    private val roof = Path()
    private val farHills = Path().apply {
        moveTo(0f, 0.25f)
        cubicTo(0.11f, 0.25f, 0.18f, 0f, 0.34f, 0f)
        cubicTo(0.48f, 0f, 0.53f, 0.56f, 0.66f, 0.56f)
        cubicTo(0.80f, 0.56f, 0.88f, 0.25f, 1f, 0.25f)
        lineTo(1f, 1f)
        lineTo(0f, 1f)
        close()
    }
    private val nearHills = Path().apply {
        moveTo(0f, 0.42f)
        cubicTo(0.12f, 0.42f, 0.18f, 0.75f, 0.31f, 0.75f)
        cubicTo(0.52f, 0.75f, 0.57f, 0f, 0.73f, 0f)
        cubicTo(0.86f, 0f, 0.88f, 0.42f, 1f, 0.42f)
        lineTo(1f, 1f)
        lineTo(0f, 1f)
        close()
    }
    private val clouds = listOf(
        sprite(47f, 16f) {
            oval(1f, 7f, 46f, 15f, CLOUD_SHADE)
            oval(1f, 5f, 46f, 13f, CLOUD_CREAM)
            oval(7f, 3f, 25f, 13f, CLOUD_CREAM)
            oval(21f, 1f, 38f, 13f, CLOUD_CREAM)
        },
        sprite(61f, 13f) {
            oval(1f, 6f, 59f, 12f, CLOUD_SHADE)
            oval(1f, 4f, 57f, 10f, CLOUD_CREAM)
            oval(15f, 1f, 41f, 10f, CLOUD_CREAM)
            oval(41f, 5f, 60f, 10f, CLOUD_CREAM)
        },
        sprite(37f, 21f) {
            oval(1f, 10f, 36f, 20f, CLOUD_SHADE)
            oval(1f, 9f, 36f, 18f, CLOUD_CREAM)
            oval(6f, 5f, 21f, 18f, CLOUD_CREAM)
            oval(14f, 1f, 30f, 18f, CLOUD_CREAM)
        },
    )
    private val houses = listOf(
        cottage(0xfff1dab1.toInt(), 0xffca8a72.toInt(), 0xff734e49.toInt(), 0xff6b8990.toInt(),
            width = 32f, height = 23f, ridge = 12f, chimney = true),
        cottage(0xffc5d8ce.toInt(), 0xff647e8b.toInt(), 0xff3a5668.toInt(), 0xff466878.toInt(),
            width = 33f, height = 20f, ridge = 22f, chimney = false),
        cottage(0xffeddfc3.toInt(), 0xff85997b.toInt(), 0xff536c63.toInt(), 0xff857666.toInt(),
            width = 26f, height = 27f, ridge = 13f, chimney = true),
    )
    private val trees = listOf(
        sprite(27f, 34f) {
            rect(12f, 19f, 15f, 34f, TRUNK)
            oval(1f, 8f, 25f, 27f, LEAF_DARK)
            oval(5f, 2f, 24f, 20f, LEAF_MID)
            oval(1f, 9f, 16f, 22f, LEAF_MID)
            oval(7f, 4f, 18f, 12f, LEAF_LIGHT)
        },
        sprite(32f, 32f) {
            val branches = Path().apply {
                moveTo(15f, 32f)
                quadTo(17f, 25f, 14f, 19f)
                lineTo(8f, 14f)
                lineTo(10f, 12f)
                lineTo(17f, 18f)
                lineTo(21f, 11f)
                lineTo(23f, 13f)
                quadTo(17f, 22f, 19f, 32f)
                close()
            }
            shape.color = TRUNK
            drawPath(branches, shape)
            oval(2f, 8f, 16f, 22f, BLOSSOM_SHADE)
            oval(13f, 8f, 31f, 23f, BLOSSOM_SHADE)
            oval(5f, 1f, 23f, 18f, BLOSSOM_PINK)
            oval(15f, 4f, 29f, 17f, BLOSSOM_PINK)
            oval(2f, 7f, 13f, 18f, BLOSSOM_PINK)
            oval(7f, 4f, 14f, 10f, BLOSSOM_LIGHT)
            oval(20f, 8f, 25f, 12f, BLOSSOM_LIGHT)
        },
        sprite(24f, 31f) {
            rect(11f, 22f, 14f, 31f, TRUNK)
            triangle(1f, 25f, 12f, 3f, 23f, 25f, LEAF_DARK)
            triangle(3f, 19f, 12f, 1f, 21f, 19f, LEAF_MID)
            triangle(5f, 13f, 12f, 1f, 12f, 15f, LEAF_LIGHT)
        },
    )

    fun draw(canvas: Canvas, width: Int, height: Int, floor: Float, distance: Float) {
        if (skyHeight != floor) {
            skyHeight = floor
            sky.shader = LinearGradient(0f, 0f, 0f, floor, SKY_BLUE, SKY_WARM, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, width.toFloat(), floor, sky)
        val scale = (floor / 74f).coerceIn(density * 0.52f, density * 1.25f)
        drawSprites(canvas, clouds, width, floor * 0.40f, scale, distance * 0.13f, 110f * scale, 12f * scale, 220)
        drawBirds(canvas, width, floor, scale, distance)
        drawHills(canvas, farHills, width, floor, 24f * scale, 285f * scale, distance * 0.20f, FAR_HILL)
        drawHills(canvas, nearHills, width, floor, 15f * scale, 204f * scale, distance * 0.25f + 73f * scale, NEAR_HILL)
        val horizon = floor - 2f * scale
        drawSprites(canvas, houses, width, horizon, scale, distance * 0.36f, 126f * scale, 84f * scale, 230)
        drawSprites(canvas, trees, width, floor, scale, distance * 0.53f, 119f * scale, 45f * scale, 240)
        shape.color = GROUND
        canvas.drawRect(0f, floor, width.toFloat(), height.toFloat(), shape)
    }

    private fun drawHills(
        canvas: Canvas, hills: Path, width: Int, floor: Float, height: Float, span: Float, offset: Float, color: Int,
    ) {
        shape.color = color
        var x = -(offset % span)
        while (x < width) {
            canvas.save()
            canvas.translate(x, floor - height)
            canvas.scale(span, height)
            canvas.drawPath(hills, shape)
            canvas.restore()
            x += span
        }
    }

    private fun drawSprites(
        canvas: Canvas, sprites: List<Sprite>, width: Int, bottom: Float, scale: Float,
        offset: Float, spacing: Float, start: Float, alpha: Int,
    ) {
        val cycle = spacing * sprites.size
        var x = start - offset % cycle - cycle
        var index = 0
        blit.alpha = alpha
        while (x < width) {
            val sprite = sprites[index]
            val right = x + sprite.width * scale
            if (right > 0) {
                bounds.set(x, bottom - sprite.height * scale, right, bottom)
                canvas.drawBitmap(sprite.image, null, bounds, blit)
            }
            x += spacing
            index = (index + 1) % sprites.size
        }
    }

    private fun drawBirds(canvas: Canvas, width: Int, floor: Float, scale: Float, distance: Float) {
        val progress = distance / (11f * density)
        val offset = (distance * 0.52f / scale) % flockCycle
        var anchor = (94f - offset - flockCycle) * scale
        var index = 0
        val margin = 10f * scale
        while (anchor < width + margin) {
            val flock = flocks[index]
            for (member in flock.members) {
                val x = anchor + member.x * scale
                if (x < -margin || x > width + margin) continue
                val phase = progress + member.phase
                val y = floor * flock.altitude + (member.y + sin(phase * 0.34f) * 2.5f) * scale
                val flap = sin(phase) * (0.60f + 0.40f * sin(phase * 0.28f))
                bird(canvas, x, y, scale * member.size, flap)
            }
            anchor += flock.spacing * scale
            index = (index + 1) % flocks.size
        }
    }

    private fun bird(canvas: Canvas, x: Float, y: Float, scale: Float, flap: Float) {
        canvas.save()
        canvas.translate(x, y)
        canvas.scale(scale, scale)
        shape.color = BIRD
        val lift = flap * 4f
        birdPath.rewind()
        birdPath.moveTo(-6f, -0.7f)
        birdPath.quadTo(-3.3f, -1.2f, -2f, -1.3f)
        birdPath.cubicTo(-4.8f, -2f - lift, -5.8f, -3f - lift, -5.2f, -4.2f - lift)
        birdPath.quadTo(-1.9f, -3.2f - lift, 0.7f, -1.8f)
        birdPath.cubicTo(3.4f, -2.4f - lift, 5.8f, -4.7f - lift, 8.1f, -4.8f - lift)
        birdPath.cubicTo(7.2f, -3.1f - lift, 4.9f, -0.3f, 2.5f, 0.1f)
        birdPath.lineTo(6.1f, 0.1f)
        birdPath.quadTo(2.2f, 2.8f, -2.7f, 1.2f)
        birdPath.close()
        canvas.drawPath(birdPath, shape)
        canvas.restore()
    }

    private fun cottage(
        wall: Int, tile: Int, eave: Int, trim: Int, width: Float, height: Float, ridge: Float, chimney: Boolean,
    ) = sprite(width, height) {
        val eaveY = height * 0.43f
        if (chimney) rect(width - 9f, 4f, width - 6f, eaveY, eave)
        rect(3f, eaveY, width - 3f, height, wall)
        rect(3f, eaveY, width - 3f, eaveY + 1.5f, eave)
        triangle(1f, eaveY, ridge, 1f, width - 1f, eaveY, tile)
        triangle(1f, eaveY, ridge, 1f, ridge + 1f, 3.3f, eave)
        rect(6f, eaveY + 3f, 10f, eaveY + 7f, trim)
        rect(width - 10f, eaveY + 3f, width - 6f, eaveY + 7f, trim)
        bounds.set(width / 2f - 2.5f, height - 7.5f, width / 2f + 2.5f, height + 2f)
        shape.color = trim
        drawRoundRect(bounds, 1.8f, 1.8f, shape)
    }

    private fun sprite(width: Float, height: Float, draw: Canvas.() -> Unit): Sprite {
        val resolution = (density * 1.5f).coerceIn(1.5f, 5f)
        val image = Bitmap.createBitmap(ceil(width * resolution).toInt(), ceil(height * resolution).toInt(), Bitmap.Config.ARGB_8888)
        Canvas(image).apply {
            scale(resolution, resolution)
            draw()
        }
        return Sprite(image, width, height)
    }

    private fun Canvas.rect(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        shape.color = color
        drawRect(left, top, right, bottom, shape)
    }

    private fun Canvas.oval(left: Float, top: Float, right: Float, bottom: Float, color: Int) {
        shape.color = color
        drawOval(left, top, right, bottom, shape)
    }

    private fun Canvas.triangle(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float, color: Int) {
        roof.rewind()
        roof.moveTo(x1, y1)
        roof.lineTo(x2, y2)
        roof.lineTo(x3, y3)
        roof.close()
        shape.color = color
        drawPath(roof, shape)
    }

    private companion object {
        val SKY_BLUE = 0xff9fdcf4.toInt()
        val SKY_WARM = 0xfffff5ce.toInt()
        val FAR_HILL = 0xffc4e4b4.toInt()
        val NEAR_HILL = 0xff99cc8a.toInt()
        val GROUND = 0xffb9db8b.toInt()
        val BIRD = 0xff52758c.toInt()
        val CLOUD_CREAM = 0xfffffdfa.toInt()
        val CLOUD_SHADE = 0xffc4e3e9.toInt()
        val TRUNK = 0xff8c694f.toInt()
        val LEAF_DARK = 0xff3f7e55.toInt()
        val LEAF_MID = 0xff6aa960.toInt()
        val LEAF_LIGHT = 0xffa7cc77.toInt()
        val BLOSSOM_SHADE = 0xffd38096.toInt()
        val BLOSSOM_PINK = 0xfff3a8b5.toInt()
        val BLOSSOM_LIGHT = 0xffffd6d5.toInt()
    }
}
