package dev.mahlernim.timelinevisualizer.creations

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject

data class CreationRecord(
    val uri: String,
    val title: String,
    val fileName: String,
    val createdAtMillis: Long,
    val durationSeconds: Int,
    val year: Int? = null,
    val startMonth: Int? = null,
    val endMonth: Int? = null,
)

class CreationStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun list(): List<CreationRecord> = decode(preferences.getString(KEY_RECORDS, null))
        .sortedByDescending(CreationRecord::createdAtMillis)

    fun upsert(record: CreationRecord) {
        val updated = buildList {
            add(record)
            addAll(list().filterNot { it.uri == record.uri })
        }.sortedByDescending(CreationRecord::createdAtMillis)
        save(updated)
    }

    fun remove(uri: String) {
        save(list().filterNot { it.uri == uri })
    }

    internal fun clear() {
        preferences.edit { remove(KEY_RECORDS) }
    }

    private fun save(records: List<CreationRecord>) {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject().apply {
                    put("uri", record.uri)
                    put("title", record.title)
                    put("fileName", record.fileName)
                    put("createdAtMillis", record.createdAtMillis)
                    put("durationSeconds", record.durationSeconds)
                    record.year?.let { put("year", it) }
                    record.startMonth?.let { put("startMonth", it) }
                    record.endMonth?.let { put("endMonth", it) }
                },
            )
        }
        preferences.edit { putString(KEY_RECORDS, array.toString()) }
    }

    private fun decode(raw: String?): List<CreationRecord> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val uri = item.optString("uri").takeIf(String::isNotBlank) ?: continue
                    val fileName = item.optString("fileName").takeIf(String::isNotBlank) ?: "Timeline video.mp4"
                    add(
                        CreationRecord(
                            uri = uri,
                            title = item.optString("title").takeIf(String::isNotBlank)
                                ?: fileName.substringBeforeLast('.'),
                            fileName = fileName,
                            createdAtMillis = item.optLong("createdAtMillis", 0L),
                            durationSeconds = item.optInt("durationSeconds", 0).coerceAtLeast(0),
                            year = item.optionalPositiveInt("year"),
                            startMonth = item.optionalPositiveInt("startMonth"),
                            endMonth = item.optionalPositiveInt("endMonth"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun JSONObject.optionalPositiveInt(name: String): Int? = if (has(name)) {
        optInt(name).takeIf { it > 0 }
    } else null

    companion object {
        private const val PREFERENCES_NAME = "creations"
        private const val KEY_RECORDS = "records_v1"
    }
}
