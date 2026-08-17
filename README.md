# Google Timeline Visualizer

[한국어 안내](README.ko.md)

Create a polished travel animation from your Google Maps Timeline export—entirely
on your Android phone. Choose a year or month range, preview the journey, and save
an MP4 ready to share.

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![License](https://img.shields.io/github/license/mahlernim/google-timeline-visualizer)
![Build](https://github.com/mahlernim/google-timeline-visualizer/actions/workflows/validate.yml/badge.svg)

## Install on Android

The app is not yet on Google Play. Install it from this repository's
[latest release](https://github.com/mahlernim/google-timeline-visualizer/releases/latest):

1. Under **Assets**, download `TimelineVisualizer-v1.1.0.apk` on your phone.
2. Open the downloaded file.
3. If Android blocks the installation, select **Settings**, allow your browser or
   file manager to **Install unknown apps**, then return and try again.
4. After installation, you can turn that permission off again.

Only download the APK from this repository. Android may display a warning because
the app is installed outside Google Play; that warning is expected for a directly
distributed APK. Future releases can be installed over this release.

Requires Android 8.0 or newer.

## Export your Timeline.json

On Android, the export is in the phone's Settings app—not in Google Maps:

1. Open **Phone Settings**.
2. Select **Location → Location services → Timeline**.
3. Select **Export Timeline data**, then **Continue**.
4. Save `Timeline.json` somewhere easy to find, such as **Downloads**.

See [Google's Timeline Help](https://support.google.com/maps/answer/6258979) if the
Timeline menu is missing or the labels differ on your phone.

Names and menu locations can vary by phone. In Timeline Visualizer, **Get JSON**
shows these instructions and can open Location settings for you. Android does not
provide apps with a standard link directly to the Timeline page.

On iPhone, use **Google Maps → profile picture → Settings → Personal content →
Export Timeline data**, then move the JSON file to your Android phone.

## Create and share a video

1. Select **Open Timeline JSON** and choose the exported file.
2. Choose a year. The full year is selected by default; change **Start month** and
   **End month** if you want a shorter period.
3. Confirm the name and title, then choose a 15, 30, 60, or 90-second video.
4. Preview with **Play**. After playback ends, **Play** starts again from the beginning.
5. Select **Export MP4**, choose where to save it, then use **Share** to open your
   phone's social and messaging apps.

Long flights and other sparse routes are interpolated along a great-circle path,
so the camera follows the trip smoothly instead of jumping to the destination.

## Supported exports

- Current Android and iOS direct-array Timeline exports
- Older `{ "semanticSegments": [...] }` exports
- Timeline paths, activities, and visits
- String, `latLng`, degree, `geo:`, and E7 coordinates
- Routes crossing the international date line

## Privacy

No Google sign-in, location permission, account permission, analytics, or broad
storage permission is used. The app reads only the JSON file you choose, and video
rendering stays on the device.

Google Sign-In could provide a profile name, but Google does not expose the
phone's Timeline history through Sign-In. Requiring it would add account access
without removing the export step, so the app uses your editable phone name for
the default title.

The basemap is the only network feature. CARTO receives requests for the map areas
shown and serves tiles based on OpenStreetMap data. This can reveal viewed areas to
the tile provider, but the Timeline JSON is not uploaded. See the full
[privacy explanation](docs/privacy.md).

## Desktop Python version

The original Python generator remains available for desktop users. It requires
Python 3.9+, FFmpeg, and the packages in `requirements.txt`.

```bash
python -m pip install -r requirements.txt
python visualizer.py --input Timeline.json --year 2025 --output my_trip_2025.mp4
```

## Build and test

Android development requires JDK 17, Android SDK Platform 36, and Build Tools 36.0.0.

```bash
./gradlew test lint assembleDebug
python -m pip install -r requirements-dev.txt
python -m pytest
```

Basemap attribution is displayed in every preview and exported video:
© [OpenStreetMap contributors](https://www.openstreetmap.org/copyright) and
© [CARTO](https://carto.com/attributions).

Licensed under the [MIT License](LICENSE).
