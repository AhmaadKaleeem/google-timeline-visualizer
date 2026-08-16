# Google Timeline Visualizer

Turn a Google Maps Timeline export into an animated travel video. The Android app
opens the export directly on your device, lets you preview a year, and writes a
shareable MP4 without uploading the JSON.

![Android](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![License](https://img.shields.io/github/license/mahlernim/google-timeline-visualizer)
![Build](https://github.com/mahlernim/google-timeline-visualizer/actions/workflows/validate.yml/badge.svg)

The original Python video generator remains available for desktop users.

## Android app

### Features

- Opens `Timeline.json` with Android's system file picker—no storage or location
  permission is requested.
- Supports the current direct-array Android/iOS export and the older
  `{ "semanticSegments": [...] }` Takeout structure.
- Reads timeline paths, activity start/end coordinates, and visit locations in
  string, `latLng`, degree, `geo:`, and E7 forms.
- Lists the years actually present in the file.
- Animates travel by distance, with a 500 km highlighted trail and a moving map.
- Exports 15, 30, 60, or 90-second H.264 MP4 video locally and opens Android's
  standard share sheet.
- Handles international-date-line routes without drawing a line around the world.
- Uses CARTO's light OpenStreetMap tiles without requiring an API key.

### Install a development build

1. Download the `timeline-visualizer-debug` artifact from the latest successful
   [Actions run](https://github.com/mahlernim/google-timeline-visualizer/actions/workflows/validate.yml),
   or build it locally.
2. Extract the artifact and open `app-debug.apk` on an Android 8.0 or newer phone.
3. If Android asks, allow your browser or file manager to install this one app.

### Use it

1. In Google Maps, open **profile picture → Your Timeline → ⋮ → About & privacy →
   Export Timeline data**. The wording can vary by Google Maps version.
2. Open Timeline Visualizer and select **Open Timeline JSON**.
3. Choose a year and video duration, then preview the animation.
4. Select **Export MP4**, choose a destination, and keep the app open while the
   video is rendered.

Large exports and 90-second videos can take several minutes. The app keeps the
screen awake during export and reports map-preparation and frame progress.

### Privacy

The app does not request location access, account access, analytics permission,
or broad file access. It reads only the document chosen through Android's system
picker. Timeline coordinates and generated frames stay on the device.

The basemap is the only network feature. Standard tile identifiers for the areas
shown are sent to CARTO's tile service, whose tiles use OpenStreetMap data. This
can reveal the viewed map areas to that provider, although the Timeline JSON and
route coordinates are not uploaded. See [Privacy](docs/privacy.md).

## Build the Android app

Requirements:

- JDK 17
- Android SDK Platform 36 and Build Tools 36.0.0

```bash
./gradlew test lint assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Desktop Python version

Requirements: Python 3.9+, FFmpeg, and the packages in `requirements.txt`.

```bash
python -m pip install -r requirements.txt
python visualizer.py --input Timeline.json --year 2025 --output my_trip_2025.mp4
```

Options:

- `--input`, `-i`: Timeline JSON file
- `--year`, `-y`: year to visualize
- `--output`, `-o`: output MP4 path
- `--title`, `-t`: title displayed in the video

![Travel History Sample](travel_history_sample.gif)

## Development

Android parsing uses `android.util.JsonReader`, so large exports are streamed
instead of loading the complete JSON tree into memory. Parser tests cover both
root structures, every supported point source, E7 coordinates, duplicate removal,
distance-based playback, and international-date-line projection.

Python regression tests can be run with:

```bash
python -m pip install -r requirements-dev.txt
python -m pytest
```

## Map attribution

Basemap tiles: © [OpenStreetMap contributors](https://www.openstreetmap.org/copyright)
and © [CARTO](https://carto.com/attributions). Attribution is displayed in previews
and exported videos.

## License

[MIT](LICENSE)
