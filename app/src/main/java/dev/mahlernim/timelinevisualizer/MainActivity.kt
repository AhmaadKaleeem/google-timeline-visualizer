package dev.mahlernim.timelinevisualizer

import android.animation.ValueAnimator
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.SeekBar
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dev.mahlernim.timelinevisualizer.data.TileRepository
import dev.mahlernim.timelinevisualizer.data.TimelineParser
import dev.mahlernim.timelinevisualizer.databinding.ActivityMainBinding
import dev.mahlernim.timelinevisualizer.export.Mp4Exporter
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.model.Timeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.text.DateFormatSymbols

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private var timeline: Timeline? = null
    private var journey: Journey? = null
    private var animation: ValueAnimator? = null
    private var pendingExport: ExportRequest? = null
    private var lastVideoUri: Uri? = null
    private var selectedYear: Int? = null
    private var selectedStartMonth = 1
    private var selectedEndMonth = 12
    private var titleIsAutomatic = true
    private var updatingTitle = false
    private val monthNames by lazy { DateFormatSymbols.getInstance().months.take(12) }

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
        binding.playButton.setOnClickListener { togglePlayback() }
        binding.exportButton.setOnClickListener { chooseExportDestination() }
        binding.shareButton.setOnClickListener { lastVideoUri?.let(::shareVideo) }
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
        binding.titleInput.doAfterTextChanged {
            binding.timelineView.videoTitle = it?.toString().orEmpty()
            if (!updatingTitle && timeline != null) titleIsAutomatic = false
        }
        val preferences = getSharedPreferences("display", MODE_PRIVATE)
        val savedOwner = preferences.getString("owner_name", null)
        binding.ownerInput.setText(savedOwner ?: deviceName())
        binding.ownerInput.doAfterTextChanged {
            preferences.edit().putString("owner_name", it?.toString().orEmpty()).apply()
            if (titleIsAutomatic) updateAutomaticTitle()
        }

        val durations = listOf(15, 30, 60, 90).map { resources.getQuantityString(R.plurals.duration_seconds, it, it) }
        binding.durationDropdown.setAdapter(ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, durations))
        binding.durationDropdown.setText(resources.getQuantityString(R.plurals.duration_seconds, 30, 30), false)
        makeDropdownOpenReliably(binding.durationDropdown)

        configureMonthDropdowns()

        intent?.data?.let(::importTimeline)
    }

    override fun onDestroy() {
        animation?.cancel()
        super.onDestroy()
    }

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
                binding.statusText.text = error.message ?: "This Timeline export could not be read"
                Snackbar.make(binding.root, R.string.import_failed, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun configureYears(loaded: Timeline) {
        val years = loaded.years
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, years.map(Int::toString))
        binding.yearDropdown.setAdapter(adapter)
        makeDropdownOpenReliably(binding.yearDropdown)
        binding.yearDropdown.setOnItemClickListener { _, _, position, _ -> selectYear(years[position]) }
        binding.yearDropdown.setText(String.format(Locale.getDefault(), "%d", years.first()), false)
        selectYear(years.first())
    }

    private fun selectYear(year: Int) {
        selectedYear = year
        if (titleIsAutomatic) updateAutomaticTitle()
        selectRange()
    }

    private fun selectRange() {
        val year = selectedYear ?: return
        val selected = timeline?.forRange(year, selectedStartMonth, selectedEndMonth) ?: return
        journey = selected
        binding.timelineView.journey = selected
        binding.timelineSeek.progress = 0
        showProgress(0f)
        binding.statusText.text = String.format(
            Locale.US,
            "%,d points · %,.0f km · %s",
            selected.points.size,
            selected.totalDistanceKm,
            if (selectedStartMonth == 1 && selectedEndMonth == 12) year.toString()
            else "${monthNames[selectedStartMonth - 1]}–${monthNames[selectedEndMonth - 1]} $year",
        )
        val canExport = selected.points.size >= 2 && selected.totalDistanceKm > 0
        binding.playButton.isEnabled = canExport
        binding.exportButton.isEnabled = canExport
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

    private fun togglePlayback() {
        journey ?: return
        if (animation?.isPaused == true) {
            animation?.resume()
            binding.playButton.text = getString(R.string.pause)
            return
        }
        if (animation?.isRunning == true) {
            animation?.pause()
            binding.playButton.text = getString(R.string.play)
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
                    binding.playButton.text = getString(R.string.pause)
                }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    binding.playButton.text = getString(R.string.play)
                }
                override fun onAnimationCancel(animation: android.animation.Animator) {
                    binding.playButton.text = getString(R.string.play)
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
        val title = binding.titleInput.text?.toString().orEmpty().ifBlank { getString(R.string.default_title) }
        val request = ExportRequest(selected, title, selectedDurationSeconds())
        pendingExport = request
        val slug = title.lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "timeline" }
        createVideo.launch("$slug-${selected.year}.mp4")
    }

    private fun exportVideo(uri: Uri, request: ExportRequest) {
        setExporting(true)
        lifecycleScope.launch {
            val exporter = Mp4Exporter(contentResolver, TileRepository(applicationContext))
            val result = runCatching {
                exporter.export(uri, request.journey, request.title, request.durationSeconds) { progress, message ->
                    runOnUiThread {
                        binding.exportProgress.progress = (progress * 1000).toInt()
                        binding.statusText.text = message
                    }
                }
            }
            setExporting(false)
            result.onSuccess {
                lastVideoUri = uri
                binding.shareButton.isEnabled = true
                binding.statusText.text = getString(R.string.video_saved)
                Snackbar.make(binding.root, R.string.video_saved, Snackbar.LENGTH_LONG)
                    .setAction(R.string.share) { shareVideo(uri) }
                    .show()
            }.onFailure { error ->
                binding.statusText.text = error.message ?: "Video export failed"
                Snackbar.make(binding.root, R.string.video_export_failed, Snackbar.LENGTH_LONG).show()
            }
        }
    }

    private fun setExporting(exporting: Boolean) {
        val canAnimate = journey?.let { it.points.size >= 2 && it.totalDistanceKm > 0 } == true
        binding.exportProgress.visibility = if (exporting) View.VISIBLE else View.GONE
        binding.importButton.isEnabled = !exporting
        binding.playButton.isEnabled = !exporting && canAnimate
        binding.exportButton.isEnabled = !exporting && canAnimate
        binding.shareButton.isEnabled = !exporting && lastVideoUri != null
        binding.yearDropdown.isEnabled = !exporting
        if (exporting) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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

    private fun updateAutomaticTitle() {
        val year = selectedYear ?: return
        val owner = binding.ownerInput.text?.toString()?.trim().orEmpty().ifBlank { getString(R.string.traveler) }
        updatingTitle = true
        binding.titleInput.setText(getString(R.string.automatic_title, year, owner))
        binding.titleInput.setSelection(binding.titleInput.text?.length ?: 0)
        updatingTitle = false
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
}
