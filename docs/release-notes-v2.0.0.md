# Timeline Visualizer 2.0.0

Version 2.0 makes video creation easier to navigate, calmer to watch, and more
flexible for short and long journeys.

- Move between My videos, Create video, and Settings with a persistent bottom navigation bar.
- Keep unfinished Create video choices when switching tabs.
- Watch completed videos in a full-screen player inside the app with seeking, sharing, and an external-player fallback.
- Choose Steady, Fixed, or Dynamic camera movement. Steady is the recommended default.
- Use Balanced long-trip compression to keep one unusually long trip from consuming most of the video. Route geometry and total duration remain unchanged.
- Choose 480p, 720p, or 1080p output quality.
- Select exact start and end dates for trips lasting only a few days.
- Delete every available video from the library after one explicit confirmation.
- Use the app in Simplified Chinese, Traditional Chinese, Spanish, French, German, or Brazilian Portuguese, in addition to English, Korean, and Japanese.
- Use the same timing and camera behavior in Android previews, exported videos, and the Python renderer.

The camera work was informed by Ricardo Villanueva Montenegro's suggestion in
pull request #28. The final implementation supersedes that patch with adaptive
long-hop detection, user settings, timing separation, and renderer parity.

Android 8.0 and later remain supported. Existing videos, preferences, remembered
Timeline access, and unfinished exports remain compatible.
