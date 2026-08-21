package dev.mahlernim.timelinevisualizer.export

import dev.mahlernim.timelinevisualizer.render.TileId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MapTilePreparerTest {
    @Test
    fun cachedTilesCompleteWithoutAnotherLoad() = runBlocking {
        val tiles = tiles(3)
        val ready = tiles.toSet()
        var loadCalls = 0
        val progress = mutableListOf<Int>()

        MapTilePreparer(
            isReady = ready::contains,
            load = { loadCalls++ },
            pause = {},
        ).prepare(tiles) { completed, _ -> progress += completed }

        assertEquals(0, loadCalls)
        assertEquals(listOf(1, 2, 3), progress.sorted())
    }

    @Test
    fun transientFailuresAreRetriedUntilTheTileIsCached() = runBlocking {
        val tile = TileId(4, 14, 6)
        val ready = mutableSetOf<TileId>()
        var attempts = 0

        MapTilePreparer(
            maxAttempts = 2,
            retryDelayMillis = 1,
            isReady = ready::contains,
            load = {
                attempts++
                if (attempts == 2) ready += it
            },
            pause = {},
        ).prepare(listOf(tile)) { _, _ -> }

        assertEquals(2, attempts)
        assertTrue(tile in ready)
    }

    @Test
    fun incompleteCacheFailsBeforeEncodingCanStart() = runBlocking {
        val tiles = tiles(3)
        val ready = ConcurrentHashMap.newKeySet<TileId>()
        ready += tiles.first()
        val attempts = ConcurrentHashMap<TileId, AtomicInteger>()

        val error = try {
            MapTilePreparer(
                parallelism = 2,
                maxAttempts = 2,
                retryDelayMillis = 0,
                isReady = ready::contains,
                load = { tile -> attempts.computeIfAbsent(tile) { AtomicInteger() }.incrementAndGet() },
                pause = {},
            ).prepare(tiles) { _, _ -> }
            fail("Expected incomplete tile preparation to fail")
            null
        } catch (error: MapTilePreparationException) {
            error
        }

        assertEquals(tiles.drop(1).toSet(), error!!.missingTiles.toSet())
        assertEquals(tiles.size, error.totalTiles)
        tiles.drop(1).forEach { assertEquals(2, attempts[it]?.get()) }
    }

    @Test
    fun downloadsUseBoundedParallelism() = runBlocking {
        val tiles = tiles(12)
        val ready = ConcurrentHashMap.newKeySet<TileId>()
        val active = AtomicInteger()
        val maximumActive = AtomicInteger()

        MapTilePreparer(
            parallelism = 4,
            maxAttempts = 1,
            isReady = ready::contains,
            load = { tile ->
                val current = active.incrementAndGet()
                maximumActive.updateAndGet { maximum -> maxOf(maximum, current) }
                try {
                    delay(10)
                    ready += tile
                } finally {
                    active.decrementAndGet()
                }
            },
            pause = {},
        ).prepare(tiles) { _, _ -> }

        assertEquals(tiles.toSet(), ready)
        assertTrue("Expected concurrent downloads", maximumActive.get() > 1)
        assertTrue("Exceeded worker limit", maximumActive.get() <= 4)
    }

    @Test
    fun cancellationIsNeverRetriedOrConvertedToAMissingTile() = runBlocking {
        val cancellation = CancellationException("cancel export")
        var attempts = 0

        try {
            MapTilePreparer(
                maxAttempts = 2,
                isReady = { false },
                load = {
                    attempts++
                    throw cancellation
                },
                pause = {},
            ).prepare(listOf(TileId(4, 14, 6))) { _, _ -> }
            fail("Expected cancellation")
        } catch (actual: CancellationException) {
            assertEquals(cancellation.message, actual.message)
        }

        assertEquals(1, attempts)
    }

    private fun tiles(count: Int): List<TileId> = List(count) { index -> TileId(5, index, index) }
}
