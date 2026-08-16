package dev.mahlernim.timelinevisualizer.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.model.WebMercator
import dev.mahlernim.timelinevisualizer.model.WorldPoint
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

data class TileId(val zoom: Int, val x: Int, val y: Int)
data class VisibleTile(val id: TileId, val worldX: Int)

data class Viewport(
    val minX: Double,
    val maxX: Double,
    val minY: Double,
    val maxY: Double,
    val zoom: Int,
)

class TimelinePainter {
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(233, 0, 100)
        style = Paint.Style.STROKE
        strokeWidth = 4f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        alpha = 125
    }
    private val tailPaint = Paint(routePaint).apply {
        strokeWidth = 8f
        alpha = 255
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(36, 25, 29)
        style = Paint.Style.FILL
        setShadowLayer(8f, 0f, 2f, Color.argb(90, 0, 0, 0))
    }
    private val headRingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(233, 0, 100)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(36, 25, 29)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD)
    }
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(92, 75, 82)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL)
    }
    private val attributionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(185, 36, 25, 29)
        textAlign = Paint.Align.RIGHT
    }
    private val cardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 248, 250)
    }

    fun viewport(journey: Journey, progress: Float, width: Int, height: Int): Viewport {
        val current = journey.pointIndexAt(progress)
        val currentDistance = journey.cumulativeDistanceKm.getOrElse(current) { 0.0 }
        val tailStartDistance = max(0.0, currentDistance - 500.0)
        val lookaheadDistance = currentDistance + 500.0
        val tailStart = lowerBound(journey.cumulativeDistanceKm, tailStartDistance)
        val lookahead = min(lowerBound(journey.cumulativeDistanceKm, lookaheadDistance), journey.points.lastIndex)
        val subset = journey.points.subList(tailStart.coerceAtMost(current), max(current, lookahead) + 1)
        val projected = subset.map(WebMercator::project)
        val wrappedX = WebMercator.shortestWrappedX(projected.map { it.x })
        val ys = projected.map { it.y }

        val centerPoint = WebMercator.project(journey.points[current])
        var centerX = centerPoint.x
        if (wrappedX.any { it > 1.0 } && centerX < 0.5) centerX += 1.0
        val centerY = centerPoint.y
        val contentSpanX = max(0.00015, (wrappedX.maxOrNull() ?: centerX) - (wrappedX.minOrNull() ?: centerX))
        val contentSpanY = max(0.00015, (ys.maxOrNull() ?: centerY) - (ys.minOrNull() ?: centerY))
        val aspect = width.toDouble() / height.coerceAtLeast(1)
        var spanY = max(contentSpanY * 2.8, contentSpanX * 2.8 / aspect)
        spanY = spanY.coerceIn(0.0003, 0.72)
        val spanX = spanY * aspect
        val minY = (centerY - spanY / 2).coerceAtLeast(0.0)
        val maxY = (centerY + spanY / 2).coerceAtMost(1.0)
        val adjustedSpanY = maxY - minY
        val minX = centerX - spanX / 2
        val maxX = centerX + spanX / 2
        val zoom = floor(log2(width.coerceAtLeast(1) / (256.0 * max(maxX - minX, adjustedSpanY * aspect)))).toInt()
            .coerceIn(2, 15)
        return Viewport(minX, maxX, minY, maxY, zoom)
    }

    fun requiredTiles(viewport: Viewport): List<VisibleTile> {
        val count = 1 shl viewport.zoom
        val xMin = floor(viewport.minX * count).toInt()
        val xMax = floor(viewport.maxX * count).toInt()
        val yMin = floor(viewport.minY * count).toInt().coerceIn(0, count - 1)
        val yMax = floor(viewport.maxY * count).toInt().coerceIn(0, count - 1)
        return buildList {
            for (worldX in xMin..xMax) {
                val normalizedX = ((worldX % count) + count) % count
                for (y in yMin..yMax) add(VisibleTile(TileId(viewport.zoom, normalizedX, y), worldX))
            }
        }.take(36)
    }

    fun draw(
        canvas: Canvas,
        width: Int,
        height: Int,
        journey: Journey,
        progress: Float,
        title: String,
        tiles: (TileId) -> Bitmap?,
    ) {
        if (journey.points.isEmpty() || width <= 0 || height <= 0) return
        val viewport = viewport(journey, progress, width, height)
        drawBackground(canvas, width, height)
        drawTiles(canvas, width, height, viewport, tiles)

        val projected = journey.points.map(WebMercator::project)
        val wrappedX = WebMercator.shortestWrappedX(projected.map { it.x })
        val screen = projected.mapIndexed { index, point ->
            worldToScreen(WorldPoint(wrappedX[index], point.y), viewport, width, height)
        }
        val current = journey.pointIndexAt(progress)
        val path = Path()
        for (index in 0..current) {
            val p = screen[index]
            if (index == 0) path.moveTo(p.first, p.second) else path.lineTo(p.first, p.second)
        }
        canvas.drawPath(path, routePaint)

        val tailDistance = max(0.0, journey.cumulativeDistanceKm[current] - 500.0)
        val tailStart = lowerBound(journey.cumulativeDistanceKm, tailDistance)
        val tail = Path()
        for (index in tailStart..current) {
            val p = screen[index]
            if (index == tailStart) tail.moveTo(p.first, p.second) else tail.lineTo(p.first, p.second)
        }
        canvas.drawPath(tail, tailPaint)

        val head = screen[current]
        canvas.drawCircle(head.first, head.second, width * 0.013f, headPaint)
        canvas.drawCircle(head.first, head.second, width * 0.017f, headRingPaint)
        drawOverlay(canvas, width, height, journey, current, title)
    }

    private fun drawBackground(canvas: Canvas, width: Int, height: Int) {
        val gradient = LinearGradient(
            0f,
            0f,
            width.toFloat(),
            height.toFloat(),
            intArrayOf(Color.rgb(250, 246, 247), Color.rgb(224, 232, 239)),
            null,
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), Paint().apply { shader = gradient })
    }

    private fun drawTiles(
        canvas: Canvas,
        width: Int,
        height: Int,
        viewport: Viewport,
        tiles: (TileId) -> Bitmap?,
    ) {
        val count = 1 shl viewport.zoom
        for (tile in requiredTiles(viewport)) {
            val bitmap = tiles(tile.id) ?: continue
            val leftWorld = tile.worldX.toDouble() / count
            val rightWorld = (tile.worldX + 1).toDouble() / count
            val topWorld = tile.id.y.toDouble() / count
            val bottomWorld = (tile.id.y + 1).toDouble() / count
            val left = ((leftWorld - viewport.minX) / (viewport.maxX - viewport.minX) * width).toFloat()
            val right = ((rightWorld - viewport.minX) / (viewport.maxX - viewport.minX) * width).toFloat()
            val top = ((topWorld - viewport.minY) / (viewport.maxY - viewport.minY) * height).toFloat()
            val bottom = ((bottomWorld - viewport.minY) / (viewport.maxY - viewport.minY) * height).toFloat()
            canvas.drawBitmap(bitmap, null, RectF(left, top, right + 1, bottom + 1), null)
        }
    }

    private fun drawOverlay(canvas: Canvas, width: Int, height: Int, journey: Journey, index: Int, title: String) {
        val scale = width / 720f
        val card = RectF(34f * scale, 28f * scale, width - 34f * scale, 132f * scale)
        canvas.drawRoundRect(card, 24f * scale, 24f * scale, cardPaint)
        titlePaint.textSize = 34f * scale
        bodyPaint.textSize = 20f * scale
        attributionPaint.textSize = 13f * scale
        canvas.drawText(title.ifBlank { "My Trips" }, width / 2f, 72f * scale, titlePaint)
        val point = journey.points[index]
        val date = DATE_FORMAT.format(point.instant.atZone(ZoneId.systemDefault()))
        val distance = journey.cumulativeDistanceKm[index]
        canvas.drawText("$date  ·  ${String.format(Locale.US, "%,.0f", distance)} km", width / 2f, 108f * scale, bodyPaint)
        canvas.drawText("© OpenStreetMap  © CARTO", width - 12f * scale, height - 12f * scale, attributionPaint)
    }

    private fun worldToScreen(point: WorldPoint, viewport: Viewport, width: Int, height: Int): Pair<Float, Float> {
        var x = point.x
        if (viewport.minX > 0.5 && x < 0.5) x += 1.0
        val sx = ((x - viewport.minX) / (viewport.maxX - viewport.minX) * width).toFloat()
        val sy = ((point.y - viewport.minY) / (viewport.maxY - viewport.minY) * height).toFloat()
        return sx to sy
    }

    private fun lowerBound(values: DoubleArray, target: Double): Int {
        var low = 0
        var high = values.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (values[mid] < target) low = mid + 1 else high = mid
        }
        return low.coerceIn(0, max(0, values.lastIndex))
    }

    companion object {
        private val DATE_FORMAT = DateTimeFormatter.ofPattern("MMMM yyyy")
    }
}
