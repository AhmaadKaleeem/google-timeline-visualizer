package dev.mahlernim.timelinevisualizer.journal.onboarding

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

class JournalOnboardingIllustrationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    var illustration: JournalOnboardingIllustration = JournalOnboardingIllustration.JOURNAL
        set(value) {
            field = value
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val route = Paint(stroke).apply { strokeWidth = 4f }
    private val primary by lazy { MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary) }
    private val onSurface by lazy { MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface) }
    private val outline by lazy { MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline) }
    private val surfaceContainer by lazy {
        MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurfaceContainerHighest)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sx = width / DESIGN_WIDTH
        val sy = height / DESIGN_HEIGHT
        val scale = minOf(sx, sy)
        canvas.save()
        canvas.translate((width - DESIGN_WIDTH * scale) / 2f, (height - DESIGN_HEIGHT * scale) / 2f)
        canvas.scale(scale, scale)
        when (illustration) {
            JournalOnboardingIllustration.JOURNAL -> drawJournal(canvas)
            JournalOnboardingIllustration.SOURCE -> drawSource(canvas)
            JournalOnboardingIllustration.LAYERS -> drawLayers(canvas)
            JournalOnboardingIllustration.PRESERVE -> drawPreserve(canvas)
            JournalOnboardingIllustration.START -> drawStart(canvas)
        }
        canvas.restore()
    }

    private fun drawJournal(canvas: Canvas) {
        drawOpenJournal(canvas, 72f, 56f, 216f, 142f)
        route.color = primary
        canvas.drawPath(Path().apply {
            moveTo(26f, 132f)
            cubicTo(54f, 104f, 78f, 146f, 111f, 112f)
            cubicTo(146f, 77f, 166f, 120f, 202f, 86f)
        }, route)
        listOf(34f to 126f, 71f to 122f, 111f to 112f, 154f to 96f).forEach { drawDot(canvas, it.first, it.second) }
        drawPin(canvas, 218f, 72f, 13f)
        drawFilmFrame(canvas, 236f, 118f, 50f, 36f)
    }

    private fun drawSource(canvas: Canvas) {
        stroke.color = onSurface
        fill.color = surfaceContainer
        canvas.drawRoundRect(RectF(25f, 36f, 101f, 162f), 17f, 17f, fill)
        canvas.drawRoundRect(RectF(25f, 36f, 101f, 162f), 17f, 17f, stroke)
        route.color = primary
        canvas.drawPath(Path().apply {
            moveTo(40f, 126f)
            cubicTo(50f, 104f, 61f, 118f, 70f, 88f)
        }, route)
        drawPin(canvas, 75f, 73f, 10f)
        drawDocument(canvas, 123f, 67f, 62f, 78f)
        drawClosedJournal(canvas, 207f, 54f, 82f, 101f)
        route.color = outline
        canvas.drawLine(104f, 104f, 119f, 104f, route)
        canvas.drawLine(188f, 104f, 203f, 104f, route)
    }

    private fun drawLayers(canvas: Canvas) {
        drawLayerCard(canvas, RectF(18f, 28f, 220f, 91f))
        route.color = primary
        canvas.drawPath(Path().apply {
            moveTo(34f, 68f)
            cubicTo(70f, 34f, 106f, 81f, 145f, 48f)
            cubicTo(171f, 27f, 188f, 61f, 207f, 49f)
        }, route)
        listOf(36f to 66f, 66f to 51f, 96f to 68f, 127f to 60f, 155f to 44f, 184f to 54f).forEach {
            drawDot(canvas, it.first, it.second, 3.8f)
        }
        drawLayerCard(canvas, RectF(18f, 108f, 220f, 171f))
        route.color = outline
        canvas.drawPath(Path().apply {
            moveTo(38f, 150f)
            lineTo(101f, 128f)
            lineTo(190f, 149f)
        }, route)
        drawPin(canvas, 39f, 137f, 8f)
        drawPin(canvas, 102f, 116f, 8f)
        drawPin(canvas, 191f, 136f, 8f)
        drawClosedJournal(canvas, 242f, 65f, 60f, 84f)
    }

    private fun drawPreserve(canvas: Canvas) {
        drawCalendar(canvas, 21f, 39f, 100f, 117f)
        listOf(139f to 76f, 158f to 92f, 178f to 74f, 198f to 101f).forEachIndexed { index, point ->
            fill.color = primary
            fill.alpha = 255 - index * 45
            canvas.drawCircle(point.first, point.second, 5f, fill)
        }
        fill.alpha = 255
        route.color = primary
        canvas.drawPath(Path().apply {
            moveTo(132f, 119f)
            cubicTo(163f, 129f, 184f, 121f, 215f, 111f)
        }, route)
        drawClosedJournal(canvas, 211f, 52f, 82f, 112f)
        listOf(230f to 126f, 250f to 104f, 271f to 120f).forEach { drawDot(canvas, it.first, it.second, 4f) }
    }

    private fun drawStart(canvas: Canvas) {
        drawDocument(canvas, 49f, 47f, 88f, 112f)
        route.color = primary
        canvas.drawPath(Path().apply {
            moveTo(72f, 124f)
            cubicTo(83f, 101f, 97f, 119f, 112f, 88f)
        }, route)
        listOf(72f to 124f, 91f to 108f, 111f to 89f).forEach { drawDot(canvas, it.first, it.second, 4f) }
        drawClosedJournal(canvas, 162f, 32f, 124f, 145f)
        route.color = primary
        canvas.drawPath(Path().apply {
            moveTo(186f, 134f)
            cubicTo(211f, 105f, 227f, 127f, 253f, 83f)
        }, route)
        drawPin(canvas, 255f, 70f, 12f)
    }

    private fun drawOpenJournal(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
        fill.color = surfaceContainer
        stroke.color = onSurface
        val middle = left + width / 2f
        canvas.drawRoundRect(RectF(left, top, middle + 4f, top + height), 16f, 16f, fill)
        canvas.drawRoundRect(RectF(middle - 4f, top, left + width, top + height), 16f, 16f, fill)
        canvas.drawRoundRect(RectF(left, top, middle + 4f, top + height), 16f, 16f, stroke)
        canvas.drawRoundRect(RectF(middle - 4f, top, left + width, top + height), 16f, 16f, stroke)
        canvas.drawLine(middle, top + 8f, middle, top + height - 8f, stroke)
    }

    private fun drawClosedJournal(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
        fill.color = surfaceContainer
        stroke.color = onSurface
        canvas.drawRoundRect(RectF(left, top, left + width, top + height), 15f, 15f, fill)
        canvas.drawRoundRect(RectF(left, top, left + width, top + height), 15f, 15f, stroke)
        canvas.drawLine(left + 15f, top + 5f, left + 15f, top + height - 5f, stroke)
    }

    private fun drawDocument(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
        fill.color = surfaceContainer
        stroke.color = outline
        canvas.drawRoundRect(RectF(left, top, left + width, top + height), 10f, 10f, fill)
        canvas.drawRoundRect(RectF(left, top, left + width, top + height), 10f, 10f, stroke)
        canvas.drawLine(left + 15f, top + 22f, left + width - 15f, top + 22f, stroke)
        canvas.drawLine(left + 15f, top + 36f, left + width - 23f, top + 36f, stroke)
    }

    private fun drawLayerCard(canvas: Canvas, bounds: RectF) {
        fill.color = surfaceContainer
        stroke.color = outline
        canvas.drawRoundRect(bounds, 16f, 16f, fill)
        canvas.drawRoundRect(bounds, 16f, 16f, stroke)
    }

    private fun drawCalendar(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
        fill.color = surfaceContainer
        stroke.color = onSurface
        canvas.drawRoundRect(RectF(left, top, left + width, top + height), 15f, 15f, fill)
        canvas.drawRoundRect(RectF(left, top, left + width, top + height), 15f, 15f, stroke)
        canvas.drawLine(left, top + 30f, left + width, top + 30f, stroke)
        listOf(45f, 69f, 93f, 117f).forEach { y ->
            listOf(43f, 69f, 95f).forEach { x ->
                fill.color = if (y == 117f && x == 95f) primary else outline
                canvas.drawCircle(x, y, 4f, fill)
            }
        }
    }

    private fun drawFilmFrame(canvas: Canvas, left: Float, top: Float, width: Float, height: Float) {
        stroke.color = outline
        canvas.drawRoundRect(RectF(left, top, left + width, top + height), 8f, 8f, stroke)
        canvas.drawCircle(left + width / 2f, top + height / 2f, 7f, stroke)
    }

    private fun drawDot(canvas: Canvas, x: Float, y: Float, radius: Float = 4.5f) {
        fill.color = primary
        canvas.drawCircle(x, y, radius, fill)
    }

    private fun drawPin(canvas: Canvas, x: Float, y: Float, radius: Float) {
        fill.color = primary
        val path = Path().apply {
            moveTo(x, y + radius * 1.9f)
            cubicTo(x - radius * .5f, y + radius * 1.1f, x - radius, y + radius * .25f, x - radius, y)
            cubicTo(x - radius, y - radius * .8f, x - radius * .55f, y - radius * 1.2f, x, y - radius * 1.2f)
            cubicTo(x + radius * .55f, y - radius * 1.2f, x + radius, y - radius * .8f, x + radius, y)
            cubicTo(x + radius, y + radius * .25f, x + radius * .5f, y + radius * 1.1f, x, y + radius * 1.9f)
            close()
        }
        canvas.drawPath(path, fill)
        fill.color = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnPrimary)
        canvas.drawCircle(x, y - radius * .1f, radius * .33f, fill)
    }

    companion object {
        private const val DESIGN_WIDTH = 320f
        private const val DESIGN_HEIGHT = 200f
    }
}
