#!/usr/bin/env python3
"""Collect privacy-preserving aggregate metrics for Timeline Visualizer."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.request
from dataclasses import dataclass
from datetime import date, datetime, time, timedelta, timezone
from pathlib import Path
from typing import Any, Iterable
from zoneinfo import ZoneInfo


SCHEMA_VERSION = 1
KST = ZoneInfo("Asia/Seoul")
DEFAULT_ROOT = Path.home() / "Documents" / "Timeline Visualizer Metrics"
GITHUB_RELEASES_URL = (
    "https://api.github.com/repos/mahlernim/google-timeline-visualizer/releases"
)
CLOUDFLARE_GRAPHQL_URL = "https://api.cloudflare.com/client/v4/graphql"
PRODUCTION_TAG = re.compile(r"^v\d+\.\d+\.\d+$")
FORBIDDEN_OUTPUT_KEYS = {
    "coordinates",
    "filename",
    "route",
    "timeline",
    "title",
    "generated_media",
    "api_token",
}
PLAY_INTEGER_METRICS = (
    "tester_opt_ins",
    "user_acquisitions",
    "first_opens",
    "installed_audience",
    "daily_active_users",
    "monthly_active_users",
    "user_loss",
)
PLAY_RATE_METRICS = (
    "seven_day_retention_percent",
    "user_perceived_crash_rate_percent",
    "user_perceived_anr_rate_percent",
)


class MetricsError(RuntimeError):
    """An expected collection or validation failure."""


@dataclass(frozen=True)
class TimeWindows:
    report_date: date
    day_start: datetime
    day_end: datetime
    previous_start: datetime
    rolling_start: datetime


def now_utc() -> datetime:
    return datetime.now(timezone.utc)


def iso_datetime(value: datetime) -> str:
    return value.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def windows_for(report_date: date) -> TimeWindows:
    day_start = datetime.combine(report_date, time.min, KST).astimezone(timezone.utc)
    day_end = day_start + timedelta(days=1)
    return TimeWindows(
        report_date=report_date,
        day_start=day_start,
        day_end=day_end,
        previous_start=day_start - timedelta(days=1),
        rolling_start=day_start - timedelta(days=6),
    )


def source_record(
    *, observed_at: datetime, data_through: str, status: str, metrics: dict[str, Any], **extra: Any
) -> dict[str, Any]:
    return {
        "observed_at": iso_datetime(observed_at),
        "data_through": data_through,
        "status": status,
        "metrics": metrics,
        **extra,
    }


def request_json(
    url: str,
    *,
    headers: dict[str, str] | None = None,
    body: dict[str, Any] | None = None,
    timeout: int = 30,
) -> tuple[Any, dict[str, str]]:
    request_headers = {"Accept": "application/vnd.github+json", **(headers or {})}
    payload = None
    if body is not None:
        payload = json.dumps(body).encode("utf-8")
        request_headers["Content-Type"] = "application/json"
    request = urllib.request.Request(url, data=payload, headers=request_headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return json.load(response), dict(response.headers.items())
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")[:500]
        raise MetricsError(f"HTTP {exc.code} from {url}: {detail}") from exc
    except (urllib.error.URLError, TimeoutError, json.JSONDecodeError) as exc:
        raise MetricsError(f"Could not read {url}: {exc}") from exc


def _next_link(link_header: str | None) -> str | None:
    if not link_header:
        return None
    for part in link_header.split(","):
        match = re.match(r'\s*<([^>]+)>;\s*rel="([^"]+)"', part)
        if match and match.group(2) == "next":
            return match.group(1)
    return None


def fetch_github_releases() -> list[dict[str, Any]]:
    releases: list[dict[str, Any]] = []
    url: str | None = f"{GITHUB_RELEASES_URL}?per_page=100"
    headers = {"X-GitHub-Api-Version": "2022-11-28", "User-Agent": "timeline-metrics/1"}
    token = os.environ.get("TIMELINE_METRICS_GITHUB_TOKEN")
    if token:
        headers["Authorization"] = f"Bearer {token}"
    while url:
        page, response_headers = request_json(url, headers=headers)
        if not isinstance(page, list):
            raise MetricsError("GitHub releases response was not a list")
        releases.extend(page)
        url = _next_link(response_headers.get("Link"))
    return releases


def normalize_github_assets(releases: Iterable[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    assets: dict[str, dict[str, Any]] = {}
    for release in releases:
        tag = release.get("tag_name")
        if (
            not isinstance(tag, str)
            or not PRODUCTION_TAG.fullmatch(tag)
            or release.get("draft")
            or release.get("prerelease")
        ):
            continue
        expected_name = f"TimelineVisualizer-{tag}.apk"
        matching = [asset for asset in release.get("assets", []) if asset.get("name") == expected_name]
        if len(matching) != 1:
            continue
        asset = matching[0]
        asset_id = asset.get("id")
        count = asset.get("download_count")
        if not isinstance(asset_id, int) or not isinstance(count, int) or count < 0:
            continue
        assets[tag] = {
            "asset_id": asset_id,
            "asset_name": expected_name,
            "download_count": count,
            "published_at": release.get("published_at"),
        }
    return dict(sorted(assets.items(), key=lambda pair: tuple(map(int, pair[0][1:].split(".")))))


def load_snapshots_before(root: Path, report_date: date) -> list[dict[str, Any]]:
    snapshots: list[dict[str, Any]] = []
    for path in sorted((root / "snapshots").glob("????-??-??.json")):
        try:
            if date.fromisoformat(path.stem) >= report_date:
                continue
            value = json.loads(path.read_text(encoding="utf-8"))
            if value.get("schema_version") == SCHEMA_VERSION:
                snapshots.append(value)
        except (ValueError, OSError, json.JSONDecodeError):
            continue
    return snapshots


def _github_assets_from_snapshot(snapshot: dict[str, Any] | None) -> dict[str, Any]:
    if not snapshot:
        return {}
    return snapshot.get("sources", {}).get("github", {}).get("metrics", {}).get("assets", {})


def build_github_source(
    releases: Iterable[dict[str, Any]],
    *,
    observed_at: datetime,
    report_date: date,
    previous_snapshots: list[dict[str, Any]],
) -> dict[str, Any]:
    current = normalize_github_assets(releases)
    previous = _github_assets_from_snapshot(previous_snapshots[-1] if previous_snapshots else None)
    week_target = report_date - timedelta(days=7)
    week_snapshot = next(
        (item for item in reversed(previous_snapshots) if item.get("report_date") <= week_target.isoformat()),
        None,
    )
    week_assets = _github_assets_from_snapshot(week_snapshot)
    anomalies: list[dict[str, str]] = []
    enriched: dict[str, dict[str, Any]] = {}
    for tag, asset in current.items():
        prior = previous.get(tag)
        week_prior = week_assets.get(tag)
        daily_change: int | None = None
        seven_day_change: int | None = None
        state = "observed"
        if prior:
            if prior.get("asset_id") != asset["asset_id"]:
                state = "asset_replaced"
                anomalies.append({"tag": tag, "kind": state})
            elif asset["download_count"] < prior.get("download_count", 0):
                state = "count_regressed"
                anomalies.append({"tag": tag, "kind": state})
            else:
                daily_change = asset["download_count"] - prior["download_count"]
        if week_prior and week_prior.get("asset_id") == asset["asset_id"]:
            if asset["download_count"] >= week_prior.get("download_count", 0):
                seven_day_change = asset["download_count"] - week_prior["download_count"]
        enriched[tag] = {
            **asset,
            "daily_change": daily_change,
            "seven_day_change": seven_day_change,
            "comparison_status": state,
        }
    status = "available" if current else "unavailable"
    if current and anomalies:
        status = "partial"
    return source_record(
        observed_at=observed_at,
        data_through=report_date.isoformat(),
        status=status,
        metrics={
            "assets": enriched,
            "total_downloads": sum(item["download_count"] for item in current.values()),
            "daily_downloads": _sum_known(item["daily_change"] for item in enriched.values()),
            "seven_day_downloads": _sum_known(item["seven_day_change"] for item in enriched.values()),
        },
        anomalies=anomalies,
        definition="GitHub release asset downloads, not installs or unique users",
    )


def _sum_known(values: Iterable[int | None]) -> int | None:
    collected = list(values)
    if not collected or any(value is None for value in collected):
        return None
    return sum(value for value in collected if value is not None)


CLOUDFLARE_QUERY = """
query TimelineWebMetrics(
  $accountTag: string!
  $siteTag: string!
  $dayStart: Time!
  $dayEnd: Time!
  $previousStart: Time!
  $rollingStart: Time!
) {
  viewer {
    accounts(filter: {accountTag: $accountTag}) {
      daily: rumPageloadEventsAdaptiveGroups(
        limit: 1
        filter: {siteTag: $siteTag, datetime_geq: $dayStart, datetime_lt: $dayEnd}
      ) { count sum { visits } }
      previous: rumPageloadEventsAdaptiveGroups(
        limit: 1
        filter: {siteTag: $siteTag, datetime_geq: $previousStart, datetime_lt: $dayStart}
      ) { count sum { visits } }
      rolling: rumPageloadEventsAdaptiveGroups(
        limit: 1
        filter: {siteTag: $siteTag, datetime_geq: $rollingStart, datetime_lt: $dayEnd}
      ) { count sum { visits } }
    }
  }
}
"""


def _rum_totals(groups: Any) -> dict[str, int] | None:
    if not isinstance(groups, list):
        return None
    return {
        "page_views": sum(item.get("count", 0) for item in groups if isinstance(item, dict)),
        "visits": sum(
            item.get("sum", {}).get("visits", 0)
            for item in groups
            if isinstance(item, dict) and isinstance(item.get("sum", {}), dict)
        ),
    }


def normalize_cloudflare_response(
    payload: dict[str, Any], *, observed_at: datetime, windows: TimeWindows
) -> dict[str, Any]:
    errors = payload.get("errors")
    if errors:
        messages = [str(item.get("message", "GraphQL error"))[:200] for item in errors if isinstance(item, dict)]
        raise MetricsError("Cloudflare GraphQL error: " + "; ".join(messages))
    accounts = payload.get("data", {}).get("viewer", {}).get("accounts", [])
    if not accounts:
        raise MetricsError("Cloudflare returned no accessible account")
    account = accounts[0]
    daily = _rum_totals(account.get("daily"))
    previous = _rum_totals(account.get("previous"))
    rolling = _rum_totals(account.get("rolling"))
    if daily is None or previous is None or rolling is None:
        raise MetricsError("Cloudflare returned an unexpected Web Analytics shape")
    return source_record(
        observed_at=observed_at,
        data_through=windows.report_date.isoformat(),
        status="available",
        metrics={
            "daily": daily,
            "previous_day": previous,
            "rolling_seven_days": rolling,
            "daily_changes": {
                "page_views": daily["page_views"] - previous["page_views"],
                "visits": daily["visits"] - previous["visits"],
            },
        },
        definition="Cloudflare Web Analytics browser visits and page views",
    )


def fetch_cloudflare_source(*, observed_at: datetime, windows: TimeWindows) -> dict[str, Any]:
    account_tag = os.environ.get("TIMELINE_METRICS_CF_ACCOUNT_ID")
    site_tag = os.environ.get("TIMELINE_METRICS_CF_SITE_TAG")
    token = os.environ.get("TIMELINE_METRICS_CF_API_TOKEN")
    missing = [
        name
        for name, value in (
            ("TIMELINE_METRICS_CF_ACCOUNT_ID", account_tag),
            ("TIMELINE_METRICS_CF_SITE_TAG", site_tag),
            ("TIMELINE_METRICS_CF_API_TOKEN", token),
        )
        if not value
    ]
    if missing:
        return source_record(
            observed_at=observed_at,
            data_through=windows.report_date.isoformat(),
            status="unavailable",
            metrics={},
            reason="Missing required Cloudflare environment configuration",
            missing_configuration=missing,
            definition="Cloudflare Web Analytics browser visits and page views",
        )
    payload, _ = request_json(
        CLOUDFLARE_GRAPHQL_URL,
        headers={"Authorization": f"Bearer {token}"},
        body={
            "query": CLOUDFLARE_QUERY,
            "variables": {
                "accountTag": account_tag,
                "siteTag": site_tag,
                "dayStart": iso_datetime(windows.day_start),
                "dayEnd": iso_datetime(windows.day_end),
                "previousStart": iso_datetime(windows.previous_start),
                "rollingStart": iso_datetime(windows.rolling_start),
            },
        },
    )
    return normalize_cloudflare_response(payload, observed_at=observed_at, windows=windows)


def unavailable_source(name: str, observed_at: datetime, data_through: str, reason: str) -> dict[str, Any]:
    return source_record(
        observed_at=observed_at,
        data_through=data_through,
        status="unavailable",
        metrics={},
        reason=reason,
        definition=name,
    )


def parse_play_value(value: str | None, *, rate: bool) -> int | float | None:
    if value is None or value.strip().lower() in {"", "unavailable", "suppressed", "delayed", "na", "n/a"}:
        return None
    try:
        number = float(value) if rate else int(value)
    except ValueError as exc:
        raise MetricsError(f"Invalid Play metric value: {value}") from exc
    if number < 0 or (rate and number > 100):
        raise MetricsError(f"Play metric is outside its valid range: {value}")
    return number


def build_play_source(args: argparse.Namespace, observed_at: datetime) -> dict[str, Any]:
    try:
        data_date = date.fromisoformat(args.data_through)
    except ValueError as exc:
        raise MetricsError("Play data-through must be an ISO date") from exc
    if data_date > observed_at.astimezone(KST).date():
        raise MetricsError("Play data-through cannot be in the future")
    metrics: dict[str, int | float | None] = {}
    for name in PLAY_INTEGER_METRICS:
        metrics[name] = parse_play_value(getattr(args, name), rate=False)
    for name in PLAY_RATE_METRICS:
        metrics[name] = parse_play_value(getattr(args, name), rate=True)
    known = sum(value is not None for value in metrics.values())
    status = "available" if known == len(metrics) else "partial" if known else "unavailable"
    return source_record(
        observed_at=observed_at,
        data_through=data_date.isoformat(),
        status=status,
        metrics=metrics,
        unavailable_metrics=[name for name, value in metrics.items() if value is None],
        definition="Aggregate Google Play closed Alpha users, installs, engagement, and Android vitals",
    )


def load_play_sources(root: Path, through: date) -> list[dict[str, Any]]:
    sources: list[dict[str, Any]] = []
    candidates = sorted((root / "play").glob("????-??-??.json"), reverse=True)
    for path in candidates:
        try:
            if date.fromisoformat(path.stem) > through:
                continue
            source = json.loads(path.read_text(encoding="utf-8"))
            if all(key in source for key in ("observed_at", "data_through", "status", "metrics")):
                sources.append(source)
        except (ValueError, OSError, json.JSONDecodeError):
            continue
    return sources


def add_play_comparisons(root: Path, source: dict[str, Any]) -> dict[str, Any]:
    current_date = date.fromisoformat(source["data_through"])
    prior_sources = [item for item in load_play_sources(root, current_date - timedelta(days=1))]
    previous = prior_sources[0] if prior_sources else None
    week_target = current_date - timedelta(days=7)
    week = next(
        (item for item in prior_sources if date.fromisoformat(item["data_through"]) <= week_target),
        None,
    )

    def changes(reference: dict[str, Any] | None) -> dict[str, int | float | None]:
        output: dict[str, int | float | None] = {}
        reference_metrics = reference.get("metrics", {}) if reference else {}
        for name, value in source.get("metrics", {}).items():
            prior = reference_metrics.get(name)
            output[name] = value - prior if isinstance(value, (int, float)) and isinstance(prior, (int, float)) else None
        return output

    return {
        **source,
        "comparisons": {
            "previous_data_through": previous.get("data_through") if previous else None,
            "daily_changes": changes(previous),
            "seven_day_reference": week.get("data_through") if week else None,
            "seven_day_changes": changes(week),
        },
    }


def load_latest_play(root: Path, report_date: date, observed_at: datetime) -> dict[str, Any]:
    sources = load_play_sources(root, report_date)
    if sources:
        return add_play_comparisons(root, sources[0])
    return unavailable_source(
        "Aggregate Google Play closed Alpha statistics",
        observed_at,
        report_date.isoformat(),
        "No Play Console figures have been recorded",
    )


def ensure_root(root: Path) -> None:
    for name in ("snapshots", "reports", "play", "logs"):
        (root / name).mkdir(parents=True, exist_ok=True)


def write_json(path: Path, value: dict[str, Any]) -> None:
    encoded = json.dumps(value, indent=2, sort_keys=True, ensure_ascii=False) + "\n"
    lowered = encoded.lower()
    for forbidden in FORBIDDEN_OUTPUT_KEYS:
        if f'"{forbidden}"' in lowered:
            raise MetricsError(f"Refusing to write prohibited field: {forbidden}")
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(encoded, encoding="utf-8")
    temporary.replace(path)


def format_metric(value: Any, *, percent: bool = False, signed: bool = False) -> str:
    if value is None:
        return "Unavailable"
    if percent:
        return f"{value:g}%"
    if signed:
        return f"{value:+,}"
    if isinstance(value, int):
        return f"{value:,}"
    return str(value)


def _source_freshness(source: dict[str, Any], report_date: date) -> str:
    if source.get("status") == "unavailable":
        return "unavailable"
    try:
        age = (report_date - date.fromisoformat(source["data_through"])).days
    except (KeyError, ValueError):
        return "unknown freshness"
    if age <= 0:
        return "current for its stated platform date"
    return f"{age} day{'s' if age != 1 else ''} behind the report date"


def render_report(snapshot: dict[str, Any]) -> str:
    report_date = date.fromisoformat(snapshot["report_date"])
    sources = snapshot["sources"]
    cloudflare = sources["cloudflare"]
    github = sources["github"]
    play = sources["play"]
    cf_metrics = cloudflare.get("metrics", {})
    gh_metrics = github.get("metrics", {})
    play_metrics = play.get("metrics", {})
    play_comparisons = play.get("comparisons", {})
    play_daily_changes = play_comparisons.get("daily_changes", {})
    play_seven_day_changes = play_comparisons.get("seven_day_changes", {})
    lines = [
        f"# Timeline Visualizer daily metrics for {report_date.isoformat()}",
        "",
        "## Technical summary",
        "",
        f"- Web analytics status is **{cloudflare['status']}**. GitHub metrics status is **{github['status']}**. Play metrics status is **{play['status']}**.",
        f"- Web figures describe browser traffic. GitHub figures describe APK asset downloads. Play figures describe Play-distributed users and devices. They are not combined into a unique-user total.",
        f"- Play data is **{_source_freshness(play, report_date)}**. Unavailable and privacy-suppressed values remain unavailable rather than becoming zero.",
        "",
        "## Channel evidence",
        "",
        "| Channel metric | Daily value | Comparison | Rolling seven days or active base |",
        "| --- | ---: | ---: | ---: |",
    ]
    if cloudflare["status"] == "available":
        daily = cf_metrics["daily"]
        changes = cf_metrics["daily_changes"]
        rolling = cf_metrics["rolling_seven_days"]
        lines.extend(
            [
                f"| Web page views | {format_metric(daily['page_views'])} | {format_metric(changes['page_views'], signed=True)} vs previous day | {format_metric(rolling['page_views'])} |",
                f"| Web visits | {format_metric(daily['visits'])} | {format_metric(changes['visits'], signed=True)} vs previous day | {format_metric(rolling['visits'])} |",
            ]
        )
    else:
        lines.append("| Web traffic | Unavailable | Unavailable | Unavailable |")
    lines.append(
        f"| Direct APK downloads | {format_metric(gh_metrics.get('daily_downloads'))} | Daily change in cumulative asset counts | {format_metric(gh_metrics.get('seven_day_downloads'))} |"
    )
    lines.extend(
        [
            f"| Play first opens | {format_metric(play_metrics.get('first_opens'))} | {format_metric(play_daily_changes.get('first_opens'), signed=True)} vs prior Play date | {format_metric(play_seven_day_changes.get('first_opens'), signed=True)} vs seven-day reference |",
            f"| Play daily active users | {format_metric(play_metrics.get('daily_active_users'))} | {format_metric(play_daily_changes.get('daily_active_users'), signed=True)} vs prior Play date | {format_metric(play_metrics.get('monthly_active_users'))} MAU |",
            f"| Play installed audience | {format_metric(play_metrics.get('installed_audience'))} | {format_metric(play_daily_changes.get('installed_audience'), signed=True)} vs prior Play date | {format_metric(play_metrics.get('seven_day_retention_percent'), percent=True)} retention |",
            "",
            "The table is descriptive. A web visit, APK download, Play installation, and active Play user use different denominators and cannot be interpreted as stages belonging to the same identified person.",
            "",
            "## Production APK detail",
            "",
            "| Version | Cumulative downloads | Daily change | Seven-day change | Comparison status |",
            "| --- | ---: | ---: | ---: | --- |",
        ]
    )
    assets = gh_metrics.get("assets", {})
    if assets:
        for tag, asset in reversed(list(assets.items())):
            lines.append(
                f"| {tag} | {format_metric(asset['download_count'])} | {format_metric(asset['daily_change'], signed=True)} | {format_metric(asset['seven_day_change'], signed=True)} | {asset['comparison_status']} |"
            )
    else:
        lines.append("| Unavailable | Unavailable | Unavailable | Unavailable | No production APK assets found |")
    lines.extend(
        [
            "",
            "An asset replacement or count regression invalidates its download delta. This commonly follows a release workflow rerun with a replaced asset and is not negative user activity.",
            "",
            "## Play closed Alpha detail",
            "",
            f"Data through **{play['data_through']}**, observed **{play['observed_at']}**.",
            f"Prior Play date **{play_comparisons.get('previous_data_through') or 'unavailable'}**. Seven-day reference **{play_comparisons.get('seven_day_reference') or 'unavailable'}**.",
            "",
            "| Metric | Value |",
            "| --- | ---: |",
        ]
    )
    labels = {
        "tester_opt_ins": "Tester opt-ins",
        "user_acquisitions": "User acquisitions",
        "first_opens": "First opens",
        "installed_audience": "Installed audience",
        "daily_active_users": "Daily active users",
        "monthly_active_users": "Monthly active users",
        "seven_day_retention_percent": "Seven-day retention",
        "user_loss": "User loss or uninstalls",
        "user_perceived_crash_rate_percent": "User-perceived crash rate",
        "user_perceived_anr_rate_percent": "User-perceived ANR rate",
    }
    for name, label in labels.items():
        lines.append(f"| {label} | {format_metric(play_metrics.get(name), percent=name.endswith('_percent'))} |")
    lines.extend(
        [
            "",
            "Play may delay or suppress small-cohort aggregates. The report preserves those limitations and does not infer missing values.",
            "",
            "## Scope and metric definitions",
            "",
            "- **Web page views and visits** come from Cloudflare Web Analytics for the hosted web application. One visit may contain multiple page views.",
            "- **Direct APK downloads** are cumulative GitHub release asset requests. They are not verified installs, first opens, active users, or unique people.",
            "- **Play metrics** cover the closed Alpha distribution and use the dates and definitions shown by Play Console.",
            "- **Daily comparison** compares the report day with the preceding platform day. The rolling value covers seven complete KST calendar days for Cloudflare and snapshot changes for GitHub.",
            "",
            "## Method and robustness",
            "",
            "The collector stores one versioned aggregate snapshot per report date. GitHub comparisons require the same immutable asset ID. Cloudflare periods are bounded in KST and converted to UTC for the API query. Play figures are manually transcribed and range-validated before inclusion.",
            "",
            "No account identifiers, persistent user identifiers, Google Form responses, Timeline contents, coordinates, filenames, selected dates, titles, routes, or generated media are collected.",
            "",
            "## Limitations and next check",
            "",
            "Cross-channel deduplication is intentionally unavailable. Cloudflare and Play reporting delays can make same-date comparisons incomplete. Check unavailable Play values again after Play Console refreshes, and investigate any GitHub asset replacement before using its download change.",
            "",
            f"Generated at {snapshot['generated_at']}.",
            "",
        ]
    )
    return "\n".join(lines)


def write_report(root: Path, snapshot: dict[str, Any]) -> Path:
    path = root / "reports" / f"{snapshot['report_date']}.md"
    temporary = path.with_suffix(".md.tmp")
    temporary.write_text(render_report(snapshot), encoding="utf-8")
    temporary.replace(path)
    return path


def collect(args: argparse.Namespace) -> int:
    root = Path(args.root).expanduser().resolve()
    ensure_root(root)
    report_date = date.fromisoformat(args.date) if args.date else now_utc().astimezone(KST).date() - timedelta(days=1)
    windows = windows_for(report_date)
    observed_at = now_utc()
    previous = load_snapshots_before(root, report_date)
    try:
        github = build_github_source(
            fetch_github_releases(),
            observed_at=observed_at,
            report_date=report_date,
            previous_snapshots=previous,
        )
    except MetricsError as exc:
        github = unavailable_source(
            "GitHub production APK release asset downloads",
            observed_at,
            report_date.isoformat(),
            str(exc),
        )
    try:
        cloudflare = fetch_cloudflare_source(observed_at=observed_at, windows=windows)
    except MetricsError as exc:
        cloudflare = unavailable_source(
            "Cloudflare Web Analytics browser visits and page views",
            observed_at,
            report_date.isoformat(),
            str(exc),
        )
    play = load_latest_play(root, report_date, observed_at)
    snapshot = {
        "schema_version": SCHEMA_VERSION,
        "report_date": report_date.isoformat(),
        "generated_at": iso_datetime(observed_at),
        "sources": {"cloudflare": cloudflare, "github": github, "play": play},
    }
    snapshot_path = root / "snapshots" / f"{report_date.isoformat()}.json"
    write_json(snapshot_path, snapshot)
    report_path = write_report(root, snapshot)
    print(report_path)
    return 0


def record_play(args: argparse.Namespace) -> int:
    root = Path(args.root).expanduser().resolve()
    ensure_root(root)
    observed_at = now_utc()
    source = build_play_source(args, observed_at)
    play_path = root / "play" / f"{source['data_through']}.json"
    write_json(play_path, source)
    report_date = date.fromisoformat(args.report_date) if args.report_date else observed_at.astimezone(KST).date() - timedelta(days=1)
    snapshot_path = root / "snapshots" / f"{report_date.isoformat()}.json"
    if not snapshot_path.exists():
        raise MetricsError(f"No snapshot exists for {report_date}; run collect first")
    snapshot = json.loads(snapshot_path.read_text(encoding="utf-8"))
    snapshot["sources"]["play"] = add_play_comparisons(root, source)
    snapshot["generated_at"] = iso_datetime(observed_at)
    write_json(snapshot_path, snapshot)
    report_path = write_report(root, snapshot)
    print(report_path)
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", default=str(DEFAULT_ROOT), help="Private metrics directory")
    subparsers = parser.add_subparsers(dest="command", required=True)
    collect_parser = subparsers.add_parser("collect", help="Collect Cloudflare and GitHub metrics")
    collect_parser.add_argument("--date", help="KST report date in YYYY-MM-DD format; defaults to yesterday")
    collect_parser.set_defaults(handler=collect)
    play_parser = subparsers.add_parser("record-play", help="Record aggregate Play Console metrics")
    play_parser.add_argument("--data-through", required=True, help="Latest Play platform date")
    play_parser.add_argument("--report-date", help="Snapshot to regenerate; defaults to yesterday KST")
    for name in PLAY_INTEGER_METRICS + PLAY_RATE_METRICS:
        play_parser.add_argument(f"--{name.replace('_', '-')}", default="unavailable")
    play_parser.set_defaults(handler=record_play)
    return parser


def main(argv: list[str] | None = None) -> int:
    parser = build_parser()
    args = parser.parse_args(argv)
    try:
        return args.handler(args)
    except (MetricsError, ValueError, OSError, json.JSONDecodeError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
