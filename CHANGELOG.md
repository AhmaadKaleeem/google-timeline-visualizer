# Changelog

## 1.2.0

- Make page scrolling responsive by caching preview frames and prepared route geometry.
- Save reusable title templates with `{year}` and `{name}` placeholders, and apply
  typing changes after a short delay or when the field loses focus.
- Rename the main actions to Load Timeline, Preview, and Create video.
- Add cancellation with incomplete-file cleanup during video creation.
- Show phase-aware progress and an estimated time remaining once enough progress
  has been measured.
- Add a Video ready panel for watching, sharing, or creating another video.
- Refine and proofread the English and Korean guidance.

## 1.1.0

- Add smooth great-circle interpolation and camera tracking for long trips.
- Add start and end month selection; the full year remains the default.
- Build the default title from the selected year and an editable device name.
- Add in-app Timeline export instructions and a shortcut to Location settings.
- Add a visible Share button for the most recently exported video.
- Restart playback from the beginning when Play is pressed after completion.
- Add English and Korean installation and usage guides.
- Preserve and test iOS export support contributed by @keenranger in #2.

## 1.0.0

- Introduce the native Android app with local Timeline JSON import, preview, and
  H.264 MP4 export.
- Support current Android/iOS exports and older semantic-segment exports.
