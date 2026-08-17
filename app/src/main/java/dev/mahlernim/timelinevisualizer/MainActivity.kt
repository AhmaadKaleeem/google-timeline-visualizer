package dev.mahlernim.timelinevisualizer

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.mahlernim.timelinevisualizer.data.TileRepository
import dev.mahlernim.timelinevisualizer.data.TimelineParser
import dev.mahlernim.timelinevisualizer.databinding.ActivityMainBinding
import dev.mahlernim.timelinevisualizer.export.ExportPhase
import dev.mahlernim.timelinevisualizer.export.ExportProgress
import dev.mahlernim.timelinevisualizer.export.Mp4Exporter
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.model.Timeline
import dev.mahlernim.timelinevisualizer.model.TitleTemplate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormatSymbols
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.ceil

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var timeline: Timeline? = null
    private var journey: Journey? = null
    private var animation: ValueAnimator? = null
    private var exportJob: Job? = null
    private var pendingExport: ExportRequest? = null
    private var lastVideoUri: Uri? = null
    private var selectedYear: Int? = null
    private var selectedStartMonth = 1
    private var selectedEndMonth = 12
    private val titleHandler = Handler(Looper.getMainLooper())
    private val monthNames by lazy { DateFormatSymbols.getInstance().months.take(12) }
    private val preferences by lazy { getSharedPreferences("display", MODE_PRIVATE) }
    private val applyTitleChanges = Runnable { commitTitlePreferences() }

    private val openTimeline = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importTimeline(uri)
    }

    private val createVideo = registerForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        val request = pendingExport
        pendingExport = null
        if (uri != null && request != null) exportVideo(uri, request)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        binding.importButton.setOnClickListener { openTimeline.launch(arrayOf("application/json", "text/json", "text/plain")) }
        binding.exportHelpButton.setOnClickListener { showExportHelp() }
        binding.playButton.setOnClickListener { togglePreview() }
        binding.exportButton.setOnClickListener { chooseExportDestination() }
        binding.cancelExportButton.setOnClickListener { exportJob?.cancel() }
        binding.shareButton.setOnClickListener { lastVideoUri?.let(::shareVideo) }
        binding.watchVideoButton.setOnClickListener { lastVideoUri?.let(::watchVideo) }
        binding.createAnotherButton.setOnClickListener { prepareAnotherVideo() }
        binding.timelineSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    animation?.cancel()
                    showProgress(progress / 1000f)
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })

        binding.ownerInput.setText(preferences.getString("owner_name", null) ?: deviceName())
        binding.titleInput.setText(
            preferences.getString("title_template", null) ?: getString(R.string.default_title_template),
        )
        binding.ownerInput.doAfterTextChanged { scheduleTitleUpdate() }
        binding.titleInput.doAfterTextChanged { scheduleTitleUpdate() }
        binding.ownerInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitTitlePreferences() }
        binding.titleInput.setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) commitTitlePreferences() }

        val durations = listOf(15, 30, 60, 90).map { resources.getQuantityString(R.plurals.duration_seconds, it, it) }
        binding.durationDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, durations))
        binding.durationDropdown.setText(resources.getQuantityString(R.plurals.duration_seconds, 30, 30), false)
        makeDropdownOpenReliably(binding.durationDropdown)
        configureMonthDropdowns()

        intent?.data?.let(::importTimeline)
    }

    override fun onDestroy() {
        titleHandler.removeCallbacks(applyTitleChanges)
        animation?.cancel()
        exportJob?.cancel()
        super.onDestroy()
    }

    private fun scheduleTitleUpdate() {
        titleHandler.removeCallbacks(applyTitleChanges)
        titleHandler.postDelayed(applyTitleChanges, TITLE_UPDATE_DELAY_MS)
    }

    private fun commitTitlePreferences() {
        titleHandler.removeCallbacks(applyTitleChanges)
        preferences.edit()
            .putString("owner_name", binding.ownerInput.text?.toString().orEmpty())
            .putString("title_template", binding.titleInput.text?.toString().orEmpty())
            .apply()
        updateResolvedTitle()
    }

    private fun updateResolvedTitle() {
        val year = selectedYear ?: return
        binding.timelineView.videoTitle = resolvedTitle(year)
    }

    private fun resolvedTitle(year: Int): String = TitleTemplate.resolve(
        template = binding.titleInput.text?.toString().orEmpty(),
        year = year,
        name = binding.ownerInput.text?.toString().orEmpty().ifBlank { getString(R.string.traveler) },
        fallback = getString(R.string.default_title),
    )

    private fun importTimeline(uri: Uri) {
        animation?.cancel()
        binding.importButton.isEnabled = false
        binding.editorGroup.visibility = View.GONE
        binding.statusText.text = getString(R.string.reading_timeline)
        lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use(TimelineParser()::parse)
                        ?: error("The selected file could not be opened")
                }
            }
            binding.importButton.isEnabled = true
            result.onSuccess { loaded ->
                timeline = loaded
                configureYears(loaded)
                binding.editorGroup.visibility = View.VISIBLE
            }.onFailure { error ->
                timeline = null
                binding.statusText.text = error.message ?: getString(R.string.import_failed)
                Snackbar.make(binding.root, R.string.import_failed, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun configureYears(loaded: Timeline) {
        val years = loaded.years
        binding.yearDropdown.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, years.map(Int::toString)),
        )
        makeDropdownOpenReliably(binding.yearDropdown)
        binding.yearDropdown.setOnItemClickListener { _, _, position, _ -> selectYear(years[position]) }
        binding.yearDropdown.setText(String.format(Locale.getDefault(), "%d", years.first()), false)
        selectYear(years.first())
    }

    private fun selectYear(year: Int) {
        selectedYear = year
        updateResolvedTitle()
        selectRange()
    }

    private fun selectRange() {
        val year = selectedYear ?: return
        val selected = timeline?.forRange(year, selectedStartMonth, selectedEndMonth) ?: return
        animation?.cancel()
        journey = selected
        binding.timelineView.journey = selected
        binding.timelineSeek.progress = 0
        showProgress(0f)
        binding.videoReadyGroup.visibility = View.GONE
        binding.statusText.text = journeySummary(selected)
        val canCreate = selected.points.size >= 2 && selected.totalDistanceKm > 0
        binding.playButton.isEnabled = canCreate
        binding.exportButton.isEnabled = canCreate
    }

    private fun journeySummary(selected: Journey): String {
        val number = NumberFormat.getNumberInstance().apply { maximumFractionDigits = 0 }
        val period = if (selectedStartMonth == 1 && selectedEndMonth == 12) {
            selected.year.toString()
        } else {
            "${monthNames[selectedStartMonth - 1]}–${monthNames[selectedEndMonth - 1]} ${selected.year}"
        }
        return getString(
            R.string.journey_summary,
            number.format(selected.points.size),
            number.format(selected.totalDistanceKm),
            period,
        )
    }

    private fun configureMonthDropdowns() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, monthNames)
        binding.startMonthDropdown.setAdapter(adapter)
        binding.endMonthDropdown.setAdapter(adapter)
        binding.startMonthDropdown.setText(monthNames.first(), false)
        binding.endMonthDropdown.setText(monthNames.last(), false)
        makeDropdownOpenReliably(binding.startMonthDropdown)
        makeDropdownOpenReliably(binding.endMonthDropdown)
        binding.startMonthDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedStartMonth = position + 1
            if (selectedStartMonth > selectedEndMonth) {
                selectedEndMonth = selectedStartMonth
                binding.endMonthDropdown.setText(monthNames[selectedEndMonth - 1], false)
            }
            selectRange()
        }
        binding.endMonthDropdown.setOnItemClickListener { _, _, position, _ ->
            selectedEndMonth = position + 1
            if (selectedEndMonth < selectedStartMonth) {
                selectedStartMonth = selectedEndMonth
                binding.startMonthDropdown.setText(monthNames[selectedStartMonth - 1], false)
            }
            selectRange()
        }
    }

    private fun togglePreview() {
        journey ?: return
        if (animation?.isPaused == true) {
            animation?.resume()
            binding.playButton.text = getString(R.string.pause_preview)
            return
        }
        if (animation?.isRunning == true) {
            animation?.pause()
            binding.playButton.text = getString(R.string.preview)
            return
        }
        val start = if (binding.timelineSeek.progress >= 1000) {
            binding.timelineSeek.progress = 0
            showProgress(0f)
            0
        } else binding.timelineSeek.progress
        val durationMs = selectedDurationSeconds() * 1000L
        animation = ValueAnimator.ofInt(start, 1000).apply {
            duration = ((1000 - start) / 1000f * durationMs).toLong().coerceAtLeast(250)
            addUpdateListener { value ->
                val progress = value.animatedValue as Int
                binding.timelineSeek.progress = progress
                showProgress(progress / 1000f)
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: android.animation.Animator) {
                    binding.playButton.text = getString(R.string.pause_preview)
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    binding.playButton.text = getString(R.string.preview)
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    binding.playButton.text = getString(R.string.preview)
                }
            })
            start()
        }
    }

    private fun showProgress(progress: Float) {
        binding.timelineView.progress = progress
    }

    private fun chooseExportDestination() {
        val selected = journey ?: return
        animation?.cancel()
        commitTitlePreferences()
        val title = resolvedTitle(selected.year)
        pendingExport = ExportRequest(selected, title, selectedDurationSeconds())
        val slug = title.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "timeline" }
        createVideo.launch("$slug-${selected.year}.mp4")
    }

    private fun exportVideo(uri: Uri, request: ExportRequest) {
        val startedAt = SystemClock.elapsedRealtime()
        setExporting(true)
        exportJob = lifecycleScope.launch {
            try {
                Mp4Exporter(contentResolver, TileRepository(applicationContext)).export(
                    uri,
                    request.journey,
                    request.title,
                    request.durationSeconds,
                ) { progress ->
                    runOnUiThread { showExportProgress(progress, startedAt) }
                }
                lastVideoUri = uri
                binding.videoReadyGroup.visibility = View.VISIBLE
                binding.statusText.text = getString(R.string.video_saved)
                Snackbar.make(binding.root, R.string.video_saved, Snackbar.LENGTH_LONG)
                    .setAction(R.string.watch_video) { watchVideo(uri) }
                    .show()
            } catch (_: CancellationException) {
                deleteIncompleteVideo(uri)
                binding.statusText.text = getString(R.string.video_creation_cancelled)
                Snackbar.make(binding.root, R.string.video_creation_cancelled, Snackbar.LENGTH_LONG).show()
            } catch (error: Throwable) {
                deleteIncompleteVideo(uri)
                binding.statusText.text = error.message ?: getString(R.string.video_export_failed)
                Snackbar.make(binding.root, R.string.video_export_failed, Snackbar.LENGTH_LONG).show()
            } finally {
                setExporting(false)
                exportJob = null
            }
        }
    }

    private fun showExportProgress(progress: ExportProgress, startedAt: Long) {
        binding.exportProgress.progress = (progress.fraction * 1000).toInt()
        if (progress.phase == ExportPhase.COMPLETE) return
        val base = when (progress.phase) {
            ExportPhase.PREPARING_MAP -> getString(
                R.string.preparing_map_progress,
                progress.completed,
                progress.total,
            )
            ExportPhase.CREATING_VIDEO -> getString(
                R.string.creating_video_progress,
                (progress.completed * 100f / progress.total.coerceAtLeast(1)).toInt(),
            )
            ExportPhase.COMPLETE -> return
        }
        val elapsedMs = SystemClock.elapsedRealtime() - startedAt
        val remaining = if (elapsedMs >= 3_000 && progress.fraction in 0.05f..0.98f) {
            ceil(elapsedMs / 1000.0 * (1.0 - progress.fraction) / progress.fraction).toInt()
        } else null
        binding.statusText.text = if (remaining != null) {
            getString(R.string.progress_with_eta, base, formatRemainingTime(remaining))
        } else base
    }

    private fun formatRemainingTime(seconds: Int): String = if (seconds < 90) {
        resources.getQuantityString(R.plurals.remaining_seconds, seconds.coerceAtLeast(1), seconds.coerceAtLeast(1))
    } else {
        val minutes = ceil(seconds / 60.0).toInt()
        resources.getQuantityString(R.plurals.remaining_minutes, minutes, minutes)
    }

    private fun deleteIncompleteVideo(uri: Uri) {
        runCatching { contentResolver.delete(uri, null, null) }
    }

    private fun setExporting(exporting: Boolean) {
        val canCreate = journey?.let { it.points.size >= 2 && it.totalDistanceKm > 0 } == true
        binding.exportProgress.visibility = if (exporting) View.VISIBLE else View.GONE
        binding.cancelExportButton.visibility = if (exporting) View.VISIBLE else View.GONE
        binding.importButton.isEnabled = !exporting
        binding.playButton.isEnabled = !exporting && canCreate
        binding.exportButton.isEnabled = !exporting && canCreate
        binding.shareButton.isEnabled = !exporting
        binding.watchVideoButton.isEnabled = !exporting
        binding.createAnotherButton.isEnabled = !exporting
        binding.yearDropdown.isEnabled = !exporting
        binding.durationDropdown.isEnabled = !exporting
        binding.startMonthDropdown.isEnabled = !exporting
        binding.endMonthDropdown.isEnabled = !exporting
        binding.ownerInput.isEnabled = !exporting
        binding.titleInput.isEnabled = !exporting
        if (exporting) {
            binding.videoReadyGroup.visibility = View.GONE
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun prepareAnotherVideo() {
        binding.videoReadyGroup.visibility = View.GONE
        binding.timelineSeek.progress = 0
        showProgress(0f)
        journey?.let { binding.statusText.text = journeySummary(it) }
    }

    private fun watchVideo(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            clipData = ClipData.newRawUri("Timeline video", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(intent) }
            .onFailure { Snackbar.make(binding.root, R.string.no_video_player, Snackbar.LENGTH_LONG).show() }
    }

    private fun shareVideo(uri: Uri) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Timeline video", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, getString(R.string.share_travel_video)))
    }

    private fun showExportHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.export_help_title)
            .setMessage(R.string.export_help_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.open_location_settings) { _, _ ->
                val settingsIntent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
                runCatching { startActivity(settingsIntent) }
                    .onFailure {
                        Snackbar.make(binding.root, R.string.location_settings_unavailable, Snackbar.LENGTH_LONG).show()
                    }
            }
            .show()
    }

    private fun deviceName(): String {
        val name = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)
            ?.replace('_', ' ')
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        return name?.takeUnless {
            it.startsWith("sdk ", ignoreCase = true) ||
                it.startsWith("generic", ignoreCase = true) ||
                it.equals("Android", ignoreCase = true)
        } ?: getString(R.string.traveler)
    }

    private fun selectedDurationSeconds(): Int = Regex("\\d+")
        .find(binding.durationDropdown.text.toString())
        ?.value
        ?.toIntOrNull()
        ?: 30

    private fun makeDropdownOpenReliably(dropdown: AutoCompleteTextView) {
        dropdown.threshold = 0
        dropdown.setOnClickListener { dropdown.showDropDown() }
    }

    private data class ExportRequest(val journey: Journey, val title: String, val durationSeconds: Int)

    companion object {
        private const val TITLE_UPDATE_DELAY_MS = 450L
    }
}
