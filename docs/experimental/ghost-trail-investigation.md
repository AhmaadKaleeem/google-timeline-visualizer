# Ghost Trail Investigation

## Current Rendering Pipeline
- The app renders the journey in `TimelinePainter.kt` (`draw` method).
- It calculates the current position based on the journey progress.
- It determines a `trailStart` distance based on a time window (e.g., last 2.5 seconds of travel).
- It calls `drawRouteRange` three times to draw the recent trail with fading opacities (old, middle, recent).
- `drawRouteRange` iterates through the `PreparedJourney` points between the start and end distances.
- Inside the loop, it projects world coordinates to screen coordinates and builds a standard Android `Path` object to draw.

## Why `drawRouteRange` is expensive
- It allocates `MutableWorldPoint` and calculates interpolations and projections on the CPU.
- It iterates through potentially thousands of points on *every single frame*.
- It creates a new Android `Path` object and populates it with `lineTo` calls on every frame.
- If we were to draw the full history, this loop would grow linearly as the animation progresses, causing severe frame drops.

## Proposed Caching Mechanism
- **World-Coordinate Path Cache**: Instead of a `Bitmap` (which suffers from resolution/pixelation issues during zoom and requires invalidation on pan), we can cache the historical route as a `Path` built in **world coordinates** (WebMercator, unwrapped).
- The `Path` will be generated once.
- During rendering, the Canvas will be transformed using a `Matrix` to map world coordinates to the current screen viewport.
- This delegates the transformation and scaling to the GPU/hardware-accelerated Canvas, avoiding CPU per-frame iterations.
- *Alternative*: If the historical route should only show *past* travel (not future), we could maintain an incrementally growing world `Path` or use a `Bitmap` layer that we draw into as the trail progresses. However, a single full-journey World Path drawn at low opacity is the most robust and performant "Ghost Trail" implementation that satisfies "render once" without pixelation.

## Cache Lifecycle & Invalidation
- **Creation**: Built lazily when Ghost Trail is enabled and the cached path is null.
- **Invalidation**: 
  - Journey data changes (detected by instance comparison of `Journey`).
  - Viewport dimension changes (not panning/zooming, but canvas width/height if using screen coords. If using world coords, no invalidation needed on viewport changes!).
  - Settings changes (if affecting rendering).

## Risks
- Drawing a very complex `Path` (100k+ points) in a single `drawPath` call might still hit GPU tessellation limits or canvas drawing limits. We will include a distance-based point decimation (simplification) when building the cached path to ensure it remains lightweight.

## Testing Strategy
- Unit tests: Add tests in a new or existing test class to verify `TimelinePainter` cache invalidation and Ghost Trail toggle logic.
- Performance: Run long journeys (e.g., `visualizer.py` generated data or existing fixtures) with Ghost Trail ON and OFF. Observe FPS and CPU usage.
- UI: Verify that the toggle exists in `SettingsScreen` and behaves correctly without modifying default behavior.
