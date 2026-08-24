package dev.mahlernim.timelinevisualizer.journal

import androidx.room.ColumnInfo

/** A committed detailed observation with the best accuracy retained by active provenance. */
data class ActiveDetailedObservation(
    val instantEpochMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Double,
)

/** A committed semantic segment plus the capture time used to resolve snapshot precedence. */
data class ActiveSemanticSegment(
    val id: Long,
    val snapshotId: String,
    val sourceOrdinal: Int,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
    val kind: String,
    val activityType: String?,
    val placeId: String?,
    val geometryJson: String?,
    @ColumnInfo(name = "snapshotCapturedAtEpochMillis")
    val snapshotCapturedAtEpochMillis: Long,
)
