# Timeline Visualizer for iPhone and the web

This is the browser version of Timeline Visualizer. It loads a Google Maps
`Timeline.json` export, renders the selected journey, and creates an H.264 MP4
entirely in the browser.

## Privacy

- The Timeline JSON is read locally and is not uploaded.
- No account, analytics, location permission, or broad file permission is used.
- CARTO receives requests for the map tiles needed to render the selected route.
- The browser tab must remain open while video creation is running.

## Browser support

Video creation requires the WebCodecs API and H.264 encoding. The primary target
is Safari 16.4 or newer on iPhone. The app detects browsers without WebCodecs and
disables video creation while leaving Timeline loading available.

## Local development

```bash
cd web
pnpm install --frozen-lockfile
pnpm test
pnpm build
pnpm dev
```

The Vite base path targets the default GitHub Pages project URL at
`/google-timeline-visualizer/`.

The current test deployment is available at
<https://ahn-lab.org/google-timeline-visualizer/>. Before merging the Pages
workflow, change the repository's Pages publishing source from `gh-pages` to
GitHub Actions. Keep the project base path unchanged unless a dedicated custom
subdomain is configured at the same time.

Set `VITE_PREVIEW=true` when building a public test deployment. Preview builds
show a visible warning. The current web app includes `noindex`, `nofollow`, and
`noarchive` directives until the physical iPhone gate is complete.

## Current proof-of-concept scope

The current implementation proves the complete private browser path.

1. Load current direct-array or older `semanticSegments` Timeline JSON.
2. Read absolute path timestamps or current minute offsets from segment start.
3. Choose a month range or exact dates, title, and duration.
4. Require explicit acknowledgement before contacting CARTO for map tiles.
5. Preview the journey on a 480 by 480 Canvas.
6. Encode Canvas frames as H.264 and mux them into an MP4.
7. Keep the screen awake when supported and allow video creation to be cancelled.
8. Preview, share, or download the completed MP4.

Physical iPhone validation remains required before calling the web app ready for
public use. Longer exports also need memory, thermal, interruption, and foreground
execution testing on representative iPhone models.
