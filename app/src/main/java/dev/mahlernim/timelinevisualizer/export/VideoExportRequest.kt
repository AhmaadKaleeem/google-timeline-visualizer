package dev.mahlernim.timelinevisualizer.export

import android.content.Context
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.time.Instant

data class VideoExportRequest(
    val outputUri: String,
    val journey: Journey,
    val title: String,
    val durationSeconds: Int,
    val startMonth: Int,
    val endMonth: Int,
)

class VideoExportRequestStore(context: Context) {
    private val requestFile = File(context.filesDir, REQUEST_FILE)
    private val temporaryFile = File(context.filesDir, TEMPORARY_FILE)

    @Synchronized
    fun save(request: VideoExportRequest) {
        DataOutputStream(temporaryFile.outputStream().buffered()).use { output ->
            output.writeInt(FILE_VERSION)
            output.writeUTF(request.outputUri)
            output.writeUTF(request.title)
            output.writeInt(request.durationSeconds)
            output.writeInt(request.journey.year)
            output.writeInt(request.startMonth)
            output.writeInt(request.endMonth)
            output.writeInt(request.journey.points.size)
            request.journey.points.forEach { point ->
                output.writeLong(point.instant.toEpochMilli())
                output.writeDouble(point.latitude)
                output.writeDouble(point.longitude)
            }
        }
        if (!temporaryFile.renameTo(requestFile)) {
            temporaryFile.copyTo(requestFile, overwrite = true)
            check(temporaryFile.delete()) { "Could not finish saving the video request" }
        }
    }

    @Synchronized
    fun load(): VideoExportRequest? {
        if (!requestFile.isFile) return null
        return runCatching {
            DataInputStream(requestFile.inputStream().buffered()).use { input ->
                check(input.readInt() == FILE_VERSION) { "Unsupported video request" }
                val outputUri = input.readUTF()
                val title = input.readUTF()
                val durationSeconds = input.readInt()
                val year = input.readInt()
                val startMonth = input.readInt()
                val endMonth = input.readInt()
                val pointCount = input.readInt().coerceIn(0, MAX_POINT_COUNT)
                val points = List(pointCount) {
                    GeoPoint(
                        instant = Instant.ofEpochMilli(input.readLong()),
                        latitude = input.readDouble(),
                        longitude = input.readDouble(),
                    )
                }
                VideoExportRequest(
                    outputUri = outputUri,
                    journey = Journey.from(points, year),
                    title = title,
                    durationSeconds = durationSeconds,
                    startMonth = startMonth,
                    endMonth = endMonth,
                )
            }
        }.getOrNull()
    }

    @Synchronized
    fun clear() {
        requestFile.delete()
        temporaryFile.delete()
    }

    companion object {
        private const val FILE_VERSION = 1
        private const val MAX_POINT_COUNT = 2_000_000
        private const val REQUEST_FILE = "pending-video-export.bin"
        private const val TEMPORARY_FILE = "pending-video-export.tmp"
    }
}
