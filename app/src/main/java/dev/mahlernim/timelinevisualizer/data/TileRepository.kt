package dev.mahlernim.timelinevisualizer.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import dev.mahlernim.timelinevisualizer.render.TileId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class TileRepository(context: Context) {
    private val cacheDirectory = File(context.cacheDir, "carto-tiles").apply { mkdirs() }
    private val memory = object : LruCache<String, Bitmap>(32 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun cached(id: TileId): Bitmap? {
        val key = id.key()
        memory.get(key)?.let { return it }
        val file = File(cacheDirectory, "$key.png")
        if (!file.isFile) return null
        return BitmapFactory.decodeFile(file.absolutePath)?.also { memory.put(key, it) }
    }

    suspend fun load(id: TileId): Bitmap? = withContext(Dispatchers.IO) {
        cached(id)?.let { return@withContext it }
        val key = id.key()
        val target = File(cacheDirectory, "$key.png")
        val temp = File(cacheDirectory, "$key.tmp")
        val connection = URL("https://a.basemaps.cartocdn.com/light_all/${id.zoom}/${id.x}/${id.y}.png")
            .openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "TimelineVisualizer-Android/1.0")
            if (connection.responseCode !in 200..299) return@withContext null
            connection.inputStream.use { input -> temp.outputStream().use(input::copyTo) }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            BitmapFactory.decodeFile(target.absolutePath)?.also { memory.put(key, it) }
        } catch (_: Exception) {
            temp.delete()
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun TileId.key(): String = "${zoom}_${x}_${y}"
}
