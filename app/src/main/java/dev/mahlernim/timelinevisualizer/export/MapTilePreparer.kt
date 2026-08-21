package dev.mahlernim.timelinevisualizer.export

import dev.mahlernim.timelinevisualizer.render.TileId
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class MapTilePreparationException(
    val missingTiles: List<TileId>,
    val totalTiles: Int,
) : IOException("Could not prepare ${missingTiles.size} of $totalTiles map tiles")

internal class MapTilePreparer(
    private val parallelism: Int = DEFAULT_PARALLELISM,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val retryDelayMillis: Long = DEFAULT_RETRY_DELAY_MILLIS,
    private val isReady: (TileId) -> Boolean,
    private val load: suspend (TileId) -> Unit,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    init {
        require(parallelism > 0)
        require(maxAttempts > 0)
        require(retryDelayMillis >= 0)
    }

    suspend fun prepare(
        requiredTiles: Collection<TileId>,
        onReady: (completed: Int, total: Int) -> Unit,
    ) = coroutineScope {
        val tiles = requiredTiles.distinct()
        if (tiles.isEmpty()) return@coroutineScope

        val nextIndex = AtomicInteger(0)
        val readyCount = AtomicInteger(0)
        val missing = ConcurrentLinkedQueue<TileId>()
        val cancellation = AtomicReference<CancellationException?>()
        val progressMutex = Mutex()
        val workers = List(minOf(parallelism, tiles.size)) {
            async {
                while (true) {
                    if (cancellation.get() != null) break
                    val index = nextIndex.getAndIncrement()
                    if (index >= tiles.size) break
                    val tile = tiles[index]
                    try {
                        if (loadUntilReady(tile)) {
                            progressMutex.withLock {
                                onReady(readyCount.incrementAndGet(), tiles.size)
                            }
                        } else {
                            missing.add(tile)
                        }
                    } catch (cancelled: CancellationException) {
                        cancellation.compareAndSet(null, cancelled)
                        break
                    }
                }
            }
        }
        workers.awaitAll()
        cancellation.get()?.let { throw it }

        if (missing.isNotEmpty()) {
            throw MapTilePreparationException(missing.toList(), tiles.size)
        }
    }

    private suspend fun loadUntilReady(tile: TileId): Boolean {
        if (isReady(tile)) return true
        repeat(maxAttempts) { attempt ->
            try {
                load(tile)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A failed attempt is retried below and reported if the cache remains incomplete.
            }
            if (isReady(tile)) return true
            if (attempt < maxAttempts - 1 && retryDelayMillis > 0) {
                pause(retryDelayMillis * (attempt + 1))
            }
        }
        return false
    }

    companion object {
        private const val DEFAULT_PARALLELISM = 4
        private const val DEFAULT_MAX_ATTEMPTS = 2
        private const val DEFAULT_RETRY_DELAY_MILLIS = 250L
    }
}
