package dev.mahlernim.timelinevisualizer.ui

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.view.View
import dev.mahlernim.timelinevisualizer.data.TileRepository
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.render.TileId
import dev.mahlernim.timelinevisualizer.render.TimelinePainter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class TimelineView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val painter = TimelinePainter()
    private val tiles = TileRepository(context.applicationContext)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val loading = ConcurrentHashMap.newKeySet<TileId>()

    var journey: Journey? = null
        set(value) {
            field = value
            invalidate()
        }
    var progress: Float = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }
    var videoTitle: String = "My Trips"
        set(value) {
            field = value
            invalidate()
        }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!scope.coroutineContext[Job]!!.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        }
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = width.coerceAtLeast((320 * resources.displayMetrics.density).toInt())
        setMeasuredDimension(width, resolveSize(desiredHeight, heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = journey ?: return
        if (data.points.isEmpty()) return
        val tilesToLoad = buildSet {
            listOf(progress, (progress + 0.015f).coerceAtMost(1f), (progress + 0.03f).coerceAtMost(1f)).forEach {
                addAll(painter.requiredTiles(painter.viewport(data, it, width, height)).map { tile -> tile.id })
            }
        }
        tilesToLoad.forEach { tile ->
            if (tiles.cached(tile) == null && loading.add(tile)) {
                scope.launch {
                    tiles.load(tile)
                    loading.remove(tile)
                    invalidate()
                }
            }
        }
        painter.draw(canvas, width, height, data, progress, videoTitle, tiles::cached)
    }
}
