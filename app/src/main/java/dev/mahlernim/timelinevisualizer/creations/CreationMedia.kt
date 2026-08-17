package dev.mahlernim.timelinevisualizer.creations

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import androidx.core.graphics.scale
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.min

data class VideoMetadata(
    val fileName: String,
    val durationSeconds: Int,
    val lastModifiedMillis: Long,
)

class CreationMedia(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    fun inspect(uri: Uri): VideoMetadata {
        var fileName: String? = null
        var lastModified = 0L
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val modifiedIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) fileName = cursor.getString(nameIndex)
                    if (modifiedIndex >= 0 && !cursor.isNull(modifiedIndex)) lastModified = cursor.getLong(modifiedIndex)
                }
            }
        }

        val duration = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.let { ((it + 500L) / 1_000L).toInt() }
                    ?: 0
            } finally {
                retriever.release()
            }
        }.getOrDefault(0)
        return VideoMetadata(
            fileName = fileName?.takeIf(String::isNotBlank) ?: "Timeline video.mp4",
            durationSeconds = duration.coerceAtLeast(0),
            lastModifiedMillis = lastModified,
        )
    }

    fun isAvailable(uri: Uri): Boolean = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrDefault(false)

    fun delete(uri: Uri): Boolean = runCatching {
        DocumentsContract.deleteDocument(resolver, uri)
    }.getOrDefault(false)

    fun loadThumbnail(uri: Uri): Bitmap? = thumbnailFile(uri).takeIf(File::isFile)?.let { file ->
        BitmapFactory.decodeFile(file.absolutePath)
    }

    fun createThumbnail(uri: Uri): Bitmap? {
        loadThumbnail(uri)?.let { return it }
        val retriever = MediaMetadataRetriever()
        val frame = try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } finally {
            retriever.release()
        } ?: return null

        val scale = min(THUMBNAIL_SIZE.toFloat() / frame.width, THUMBNAIL_SIZE.toFloat() / frame.height)
            .coerceAtMost(1f)
        val width = (frame.width * scale).toInt().coerceAtLeast(1)
        val height = (frame.height * scale).toInt().coerceAtLeast(1)
        val thumbnail = if (width == frame.width && height == frame.height) frame else {
            frame.scale(width, height).also { frame.recycle() }
        }
        val destination = thumbnailFile(uri)
        destination.parentFile?.mkdirs()
        FileOutputStream(destination).use { thumbnail.compress(Bitmap.CompressFormat.JPEG, 86, it) }
        return thumbnail
    }

    fun deleteThumbnail(uri: Uri) {
        thumbnailFile(uri).delete()
    }

    private fun thumbnailFile(uri: Uri): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(uri.toString().toByteArray(Charsets.UTF_8))
        val name = digest.joinToString("") { byte -> "%02x".format(byte) }
        return File(File(context.filesDir, "creation-thumbnails"), "$name.jpg")
    }

    companion object {
        private const val THUMBNAIL_SIZE = 320
    }
}
