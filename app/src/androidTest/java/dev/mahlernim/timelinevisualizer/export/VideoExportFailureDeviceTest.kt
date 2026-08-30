package dev.mahlernim.timelinevisualizer.export

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.core.app.NotificationCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.mahlernim.timelinevisualizer.MainActivity
import dev.mahlernim.timelinevisualizer.model.GeoPoint
import dev.mahlernim.timelinevisualizer.model.Journey
import dev.mahlernim.timelinevisualizer.model.TimelinePeriod
import dev.mahlernim.timelinevisualizer.videos.GeneratedMediaRepository
import java.time.Instant
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VideoExportFailureDeviceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val stateStore = VideoExportStateStore(context)
    private val requestStore = VideoExportRequestStore(context)
    private val notificationManager = context.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        stateStore.clear()
        requestStore.clear()
        VideoExportCoordinator.resetForTest()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "pm grant ${context.packageName} ${Manifest.permission.POST_NOTIFICATIONS}",
            ).close()
        }
    }

    @After
    fun tearDown() {
        setNetworkEnabled(true)
        VideoExportService.clearNotification(context)
        stateStore.clear()
        requestStore.clear()
        VideoExportCoordinator.resetForTest()
    }

    @Test
    fun backgroundFailurePostsRetryableResultAndRetryUsesFreshOutput() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val originalOutput = GeneratedMediaRepository(context).createVideoDestination(
            "Failure test",
            TimelinePeriod.sameYear(2026),
        ) ?: error("Could not create test output")
        requestStore.save(
            VideoExportRequest(
                outputUri = originalOutput.toString(),
                journey = Journey.from(
                    listOf(
                        GeoPoint(Instant.parse("2026-01-01T00:00:00Z"), 37.5, 127.0),
                        GeoPoint(Instant.parse("2026-01-01T01:00:00Z"), 37.6, 127.1),
                    ),
                    TimelinePeriod.sameYear(2026),
                ),
                title = "Failure test",
                durationSeconds = 10,
            ),
        )

        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        setNetworkEnabled(false)
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("input keyevent KEYCODE_HOME").close()
        VideoExportService.start(context)
        waitUntil { stateStore.load().status == VideoExportStatus.FAILED }
        assertEquals(VideoExportFailureKind.MAP_UNAVAILABLE, stateStore.load().failureKind)

        val notification = waitForResultNotification()
        assertEquals("video_completion", notification.channelId)
        assertEquals(Notification.CATEGORY_ERROR, notification.category)
        assertEquals(1, NotificationCompat.getActionCount(notification))

        NotificationCompat.getAction(notification, 0)?.actionIntent?.send()
            ?: error("Retry action was unavailable")
        waitUntil { requestStore.load()?.outputUri != originalOutput.toString() }
        assertNotEquals(originalOutput.toString(), requestStore.load()!!.outputUri)
    }

    private fun waitForResultNotification(): Notification {
        var notification: Notification? = null
        waitUntil {
            notification = notificationManager.activeNotifications
                .firstOrNull { it.id == VideoExportService.NOTIFICATION_ID }
                ?.notification
            notification?.category == Notification.CATEGORY_ERROR
        }
        return notification ?: error("Failure notification was not posted")
    }

    private fun waitUntil(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000L
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(50)
        }
        assertTrue("Timed out waiting for device state", false)
    }

    private fun setNetworkEnabled(enabled: Boolean) {
        val command = if (enabled) "enable" else "disable"
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.executeShellCommand("svc wifi $command").close()
        automation.executeShellCommand("svc data $command").close()
    }
}
