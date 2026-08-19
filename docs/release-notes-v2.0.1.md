# Timeline Visualizer 2.0.1

This maintenance release improves compatibility with current Google Maps
Timeline exports before Google Play testing.

- Read route points that use `durationMinutesOffsetFromStartTime` instead of an
  absolute timestamp.
- Keep valid absolute timestamps as the preferred representation when both are
  present.
- Ignore invalid or out-of-range offsets without interrupting the rest of the
  Timeline import.
- Explain whether a file is malformed, uses an older Google Takeout format,
  contains only raw signals, or has no usable locations.
- Keep Android and Python parsing behavior consistent.
- Correct Play submission metadata and privacy-policy wording.

This release implements issue #33. Existing videos, settings, Timeline access,
and unfinished exports remain compatible.
