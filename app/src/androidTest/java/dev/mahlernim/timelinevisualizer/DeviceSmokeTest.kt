package dev.mahlernim.timelinevisualizer

import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeviceSmokeTest {
    @Test
    fun videosFirstLaunchAndBackNavigationWorkOnDevice() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.videosScreen).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.newVideoScreen).visibility)

                activity.findViewById<View>(R.id.navigationCreate).performClick()
                assertEquals(View.GONE, activity.findViewById<View>(R.id.videosScreen).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.newVideoScreen).visibility)

                activity.onBackPressedDispatcher.onBackPressed()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.videosScreen).visibility)

                activity.findViewById<View>(R.id.navigationSettings).performClick()
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.settingsScreen).visibility)
            }
        }
    }
}
