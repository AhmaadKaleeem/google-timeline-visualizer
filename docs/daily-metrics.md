# Private daily metrics

The daily collector keeps aggregate operational measurements outside this
repository. It does not add analytics to the Android app and does not collect
Timeline contents, locations, filenames, selected dates, titles, routes,
generated media, Google Form responses, or persistent user identifiers.

## Storage

The default private root is:

`C:\Users\admin\Documents\Timeline Visualizer Metrics`

It contains dated JSON snapshots, Markdown reports, manually recorded Play
figures, and collector logs. Do not commit or publish that directory.

## Cloudflare configuration

Create a Cloudflare API token with Account Analytics Read for only the account
that owns the Timeline Visualizer Web Analytics site. Configure these user-level
environment variables outside the repository:

- `TIMELINE_METRICS_CF_ACCOUNT_ID`
- `TIMELINE_METRICS_CF_SITE_TAG`
- `TIMELINE_METRICS_CF_API_TOKEN`

The Web Analytics site tag is not necessarily the public beacon token. The
collector reports Cloudflare as unavailable when configuration is absent or the
API cannot be read. GitHub collection continues independently.

An optional `TIMELINE_METRICS_GITHUB_TOKEN` raises the public GitHub API rate
limit. It needs only public repository read access.

## Commands

Collect yesterday's complete KST calendar day:

```powershell
py -3 tools\daily_metrics.py collect
```

Record the latest aggregate Play figures and regenerate yesterday's report:

```powershell
py -3 tools\daily_metrics.py record-play `
  --data-through 2026-08-28 `
  --tester-opt-ins 12 `
  --user-acquisitions 10 `
  --first-opens 9 `
  --installed-audience 9 `
  --daily-active-users 6 `
  --monthly-active-users 9 `
  --seven-day-retention-percent unavailable `
  --user-loss 1 `
  --user-perceived-crash-rate-percent 0 `
  --user-perceived-anr-rate-percent unavailable
```

Use `unavailable`, `suppressed`, or `delayed` for a figure Play Console does not
show. The report preserves it as unavailable rather than recording zero.

Install or refresh the 09:00 local-time scheduled task:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File tools\install_daily_metrics_task.ps1
```

The installer copies the collector to the user's local application-data folder
before registering the task. The task therefore does not depend on a temporary
worktree. Windows must remain configured for the Asia/Seoul time zone.

## Interpretation

Cloudflare visits and page views, GitHub APK downloads, and Play users or
devices have different denominators. Do not add them together or describe them
as cross-platform unique users. GitHub downloads are not installs. Small Play
cohorts may be delayed or privacy-suppressed.
