package dev.mahlernim.timelinevisualizer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import androidx.test.core.app.ApplicationProvider
import dev.mahlernim.timelinevisualizer.data.TimelineSourceStore
import dev.mahlernim.timelinevisualizer.export.VideoExportCoordinator
import dev.mahlernim.timelinevisualizer.export.VideoExportSnapshot
import dev.mahlernim.timelinevisualizer.export.VideoExportStatus
import dev.mahlernim.timelinevisualizer.journal.JournalOnboardingStore
import dev.mahlernim.timelinevisualizer.videos.VideoRecord
import dev.mahlernim.timelinevisualizer.videos.VideoMedia
import dev.mahlernim.timelinevisualizer.videos.VideoStore
import dev.mahlernim.timelinevisualizer.ui.TimelineView
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayStoreScreenshotTest {
    @Test
    @Config(qualifiers = "en-rUS-w360dp-h640dp-xxhdpi")
    fun renderEnglishStoreScreenshots() = renderLocale("en-US")

    @Test
    @Config(qualifiers = "ko-rKR-w360dp-h640dp-xxhdpi")
    fun renderKoreanStoreScreenshots() = renderLocale("ko-KR")

    @Test
    @Config(qualifiers = "ja-rJP-w360dp-h640dp-xxhdpi")
    fun renderJapaneseStoreScreenshots() = renderLocale("ja-JP")

    private fun renderLocale(localeDirectory: String) {
        assumeTrue("Set GENERATE_STORE_SCREENSHOTS=true to render store screenshots", shouldGenerate())
        val context = ApplicationProvider.getApplicationContext<Context>()
        reset(context)
        val output = repoRoot().resolve("play-store/assets/screenshots/$localeDirectory").apply { mkdirs() }

        seedVideos(context)
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            render(controller.get().window.decorView, output.resolve("01-videos.png"))
        }

        reset(context)
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.findViewById<View>(R.id.navigationCreate).performClick()
            activity.findViewById<View>(R.id.importButton).performClick()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            render(activity.window.decorView, output.resolve("02-timeline-file.png"), ShadowDialog.getLatestDialog()?.window?.decorView)
        }

        reset(context)
        acceptPrivacy(context)
        val fixture = repoRoot().resolve("test-fixtures/seoul-bohol-sample.json")
        Robolectric.buildActivity(MainActivity::class.java).setup().use { controller ->
            val activity = controller.get()
            activity.importTimeline(Uri.fromFile(fixture))
            waitForTimelineImport(activity)
            ShadowDialog.getLatestDialog()?.dismiss()
            waitForJournalRoutes(activity)
            activity.findViewById<View>(R.id.navigationVideos).performClick()
            activity.findViewById<View>(R.id.createTripButton).performClick()
            activity.findViewById<TextView>(R.id.projectTitleInput).text = "Seoul to Bohol 2025"
            waitForEnabled(activity.findViewById(R.id.wizardContinueButton))
            activity.findViewById<View>(R.id.wizardContinueButton).performClick()
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            findNestedScrollView(activity.findViewById(R.id.newVideoScreen))?.scrollTo(0, 0)
            render(activity.window.decorView, output.resolve("03-selected-period.png"))

            activity.findViewById<View>(R.id.createStepCreate).performClick()
            shadowOf(Looper.getMainLooper()).idle()

            val completedUri = Uri.parse("content://synthetic/completed-video")
            val cityOverview = BitmapFactory.decodeFile(
                repoRoot().resolve("play-store/assets/source/video-example.jpg").absolutePath,
            ) ?: error("Store video example could not be decoded")
            VideoMedia(context).saveGeneratedOverview(completedUri, cityOverview)
            VideoExportCoordinator.publish(
                context,
                VideoExportSnapshot(
                    status = VideoExportStatus.COMPLETE,
                    outputUri = completedUri.toString(),
                    title = "Seoul to Bohol 2025",
                ),
            )
            shadowOf(Looper.getMainLooper()).idle()
            activity.findViewById<TimelineView>(R.id.timelineView).apply {
                journey = null
                background = BitmapDrawable(activity.resources, cityOverview)
            }
            findNestedScrollView(activity.findViewById(R.id.newVideoScreen))?.scrollTo(0, 800)
            render(activity.window.decorView, output.resolve("04-video-saved.png"))

            activity.findViewById<View>(R.id.doneButton).performClick()
            activity.findViewById<View>(R.id.navigationSettings).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            findNestedScrollView(activity.findViewById(R.id.settingsScreen))?.scrollTo(0, 1_400)
            render(activity.window.decorView, output.resolve("05-settings.png"))
            cityOverview.recycle()
        }

        assertEquals(
            listOf(
                "01-videos.png",
                "02-timeline-file.png",
                "03-selected-period.png",
                "04-video-saved.png",
                "05-settings.png",
            ),
            output.listFiles()!!.map(File::getName).sorted(),
        )
        reset(context)
    }

    private fun seedVideos(context: Context) {
        val records = listOf(
            VideoRecord("", "Seoul to Bohol", "Seoul-to-Bohol.mp4", 1_787_000_000_000L, 34, 2025, 1, 2025, 8),
            VideoRecord("", "Spring in Japan", "Spring-in-Japan.mp4", 1_780_000_000_000L, 22, 2024, 3, 2024, 4),
            VideoRecord("", "My 2023 Timeline", "My-2023-Timeline.mp4", 1_770_000_000_000L, 45, 2023, 1, 2023, 12),
        )
        records.forEachIndexed { index, record ->
            val file = File(context.cacheDir, "synthetic-video-$index.mp4").apply { writeBytes(byteArrayOf()) }
            val uri = Uri.fromFile(file)
            VideoMedia(context).saveGeneratedOverview(uri, syntheticOverview(index))
            VideoStore(context).upsert(record.copy(uri = uri.toString()))
        }
    }

    private fun syntheticOverview(index: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(listOf(0xFFF4E9EF, 0xFFE8F2F4, 0xFFF3EEE3, 0xFFEDEAF7)[index].toInt())
        val route = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(208, 0, 90)
            strokeWidth = 18f
            style = Paint.Style.STROKE
        }
        canvas.drawLine(80f, 270f, 230f, 120f, route)
        canvas.drawLine(230f, 120f, 410f, 230f, route)
        canvas.drawLine(410f, 230f, 560f, 80f, route)
        canvas.drawCircle(560f, 80f, 22f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(36, 25, 29) })
        return bitmap
    }

    private fun acceptPrivacy(context: Context) {
        context.getSharedPreferences("display", Context.MODE_PRIVATE).edit()
            .putBoolean("map_privacy_accepted_v1", true)
            .commit()
    }

    private fun reset(context: Context) {
        context.getSharedPreferences("display", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("camera-settings", Context.MODE_PRIVATE).edit().clear().commit()
        JournalOnboardingStore(context).complete()
        context.deleteDatabase("travel-journal.db")
        VideoStore(context).clear()
        TimelineSourceStore(context).clearForTest()
        VideoExportCoordinator.clear(context)
        VideoExportCoordinator.resetForTest()
    }

    private fun waitForTimelineImport(activity: MainActivity) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (ShadowDialog.getLatestDialog()?.findViewById<TextView>(R.id.journalGrowthHeadline) != null) return
            Thread.sleep(25)
        }
        error("Timeline fixture did not finish loading")
    }

    private fun waitForJournalRoutes(activity: MainActivity) {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (activity.journalMetadataReady() && activity.findViewById<View>(R.id.loadingGroup).visibility == View.GONE) return
            Thread.sleep(25)
        }
        error("Travel Journal routes did not finish loading")
    }

    private fun waitForEnabled(view: View) {
        repeat(300) {
            shadowOf(Looper.getMainLooper()).idle()
            if (view.isEnabled) return
            Thread.sleep(25)
        }
        error("Create workflow did not become ready")
    }

    private fun render(root: View, output: File, overlay: View? = null) {
        val width = 1080
        val height = 1920
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        root.draw(canvas)
        overlay?.let {
            it.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST))
            it.layout(0, 0, it.measuredWidth, it.measuredHeight)
            canvas.save()
            canvas.translate((width - it.measuredWidth) / 2f, (height - it.measuredHeight) / 2f)
            it.draw(canvas)
            canvas.restore()
        }
        FileOutputStream(output).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun findNestedScrollView(view: View): NestedScrollView? {
        if (view is NestedScrollView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findNestedScrollView(view.getChildAt(index))?.let { return it }
        return null
    }

    private fun repoRoot(): File {
        var current = File(System.getProperty("user.dir") ?: error("Working directory unavailable")).absoluteFile
        while (!current.resolve("settings.gradle.kts").isFile) current = current.parentFile ?: error("Repository root unavailable")
        return current
    }

    private fun shouldGenerate() = System.getenv("GENERATE_STORE_SCREENSHOTS") == "true"
}
