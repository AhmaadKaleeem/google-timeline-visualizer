from __future__ import annotations

import argparse
import importlib.util
import json
import sys
from datetime import date, datetime, timezone
from pathlib import Path

import pytest


ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location("daily_metrics", ROOT / "tools" / "daily_metrics.py")
assert SPEC and SPEC.loader
daily_metrics = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = daily_metrics
SPEC.loader.exec_module(daily_metrics)


def release(tag: str, asset_id: int, count: int, *, prerelease: bool = False, name: str | None = None):
    return {
        "tag_name": tag,
        "draft": False,
        "prerelease": prerelease,
        "published_at": "2026-08-28T00:00:00Z",
        "assets": [
            {
                "id": asset_id,
                "name": name or f"TimelineVisualizer-{tag}.apk",
                "download_count": count,
            }
        ],
    }


def snapshot(day: str, assets: dict):
    return {
        "schema_version": 1,
        "report_date": day,
        "sources": {"github": {"metrics": {"assets": assets}}},
    }


def test_github_filters_production_apks_and_calculates_changes():
    previous_asset = {"v3.0.6": {"asset_id": 10, "download_count": 5}}
    source = daily_metrics.build_github_source(
        [
            release("v3.0.6", 10, 8),
            release("journal-lab-21", 11, 99),
            release("v3.0.5", 12, 4, prerelease=True),
            release("v3.0.4", 13, 7, name="checksum.sha256"),
        ],
        observed_at=datetime(2026, 8, 29, tzinfo=timezone.utc),
        report_date=date(2026, 8, 28),
        previous_snapshots=[snapshot("2026-08-27", previous_asset)],
    )
    assert list(source["metrics"]["assets"]) == ["v3.0.6"]
    assert source["metrics"]["assets"]["v3.0.6"]["daily_change"] == 3


@pytest.mark.parametrize(
    ("asset_id", "count", "expected"),
    [(11, 8, "asset_replaced"), (10, 4, "count_regressed")],
)
def test_github_invalidates_deltas_after_replacement_or_regression(asset_id, count, expected):
    source = daily_metrics.build_github_source(
        [release("v3.0.6", asset_id, count)],
        observed_at=datetime(2026, 8, 29, tzinfo=timezone.utc),
        report_date=date(2026, 8, 28),
        previous_snapshots=[snapshot("2026-08-27", {"v3.0.6": {"asset_id": 10, "download_count": 5}})],
    )
    asset = source["metrics"]["assets"]["v3.0.6"]
    assert asset["daily_change"] is None
    assert asset["comparison_status"] == expected
    assert source["status"] == "partial"


def test_cloudflare_normalizes_empty_and_populated_groups():
    windows = daily_metrics.windows_for(date(2026, 8, 28))
    source = daily_metrics.normalize_cloudflare_response(
        {
            "data": {
                "viewer": {
                    "accounts": [
                        {
                            "daily": [{"count": 8, "sum": {"visits": 5}}],
                            "previous": [],
                            "rolling": [{"count": 30, "sum": {"visits": 19}}],
                        }
                    ]
                }
            }
        },
        observed_at=datetime(2026, 8, 29, tzinfo=timezone.utc),
        windows=windows,
    )
    assert source["metrics"]["daily"] == {"page_views": 8, "visits": 5}
    assert source["metrics"]["previous_day"] == {"page_views": 0, "visits": 0}
    assert source["metrics"]["daily_changes"]["page_views"] == 8


def play_args(**overrides):
    values = {
        "data_through": "2026-08-28",
        **{name: "unavailable" for name in daily_metrics.PLAY_INTEGER_METRICS},
        **{name: "unavailable" for name in daily_metrics.PLAY_RATE_METRICS},
        **overrides,
    }
    return argparse.Namespace(**values)


def test_play_validation_preserves_unavailable_values():
    source = daily_metrics.build_play_source(
        play_args(first_opens="3", seven_day_retention_percent="suppressed"),
        datetime(2026, 8, 29, tzinfo=timezone.utc),
    )
    assert source["status"] == "partial"
    assert source["metrics"]["first_opens"] == 3
    assert source["metrics"]["seven_day_retention_percent"] is None


def test_play_comparisons_use_prior_and_seven_day_snapshots(tmp_path):
    play_dir = tmp_path / "play"
    play_dir.mkdir()
    base = {
        "observed_at": "2026-08-21T00:00:00Z",
        "status": "partial",
        "metrics": {"first_opens": 2, "daily_active_users": 1},
    }
    (play_dir / "2026-08-21.json").write_text(
        json.dumps({**base, "data_through": "2026-08-21"}), encoding="utf-8"
    )
    (play_dir / "2026-08-27.json").write_text(
        json.dumps({**base, "data_through": "2026-08-27", "metrics": {"first_opens": 4, "daily_active_users": 3}}),
        encoding="utf-8",
    )
    current = {
        **base,
        "data_through": "2026-08-28",
        "metrics": {"first_opens": 7, "daily_active_users": 5},
    }
    compared = daily_metrics.add_play_comparisons(tmp_path, current)

    assert compared["comparisons"]["previous_data_through"] == "2026-08-27"
    assert compared["comparisons"]["daily_changes"]["first_opens"] == 3
    assert compared["comparisons"]["seven_day_reference"] == "2026-08-21"
    assert compared["comparisons"]["seven_day_changes"]["daily_active_users"] == 4


@pytest.mark.parametrize(
    "overrides",
    [
        {"first_opens": "-1"},
        {"seven_day_retention_percent": "101"},
        {"user_perceived_crash_rate_percent": "not-a-number"},
    ],
)
def test_play_validation_rejects_invalid_values(overrides):
    with pytest.raises(daily_metrics.MetricsError):
        daily_metrics.build_play_source(
            play_args(**overrides), datetime(2026, 8, 29, tzinfo=timezone.utc)
        )


def test_report_handles_missing_sources_and_keeps_channels_separate():
    observed = "2026-08-29T00:00:00Z"
    unavailable = {
        "observed_at": observed,
        "data_through": "2026-08-28",
        "status": "unavailable",
        "metrics": {},
    }
    text = daily_metrics.render_report(
        {
            "schema_version": 1,
            "report_date": "2026-08-28",
            "generated_at": observed,
            "sources": {"cloudflare": unavailable, "github": unavailable, "play": unavailable},
        }
    )
    assert "not combined into a unique-user total" in text
    assert "Unavailable" in text
    assert "Timeline contents" in text


def test_written_snapshot_rejects_prohibited_fields(tmp_path):
    with pytest.raises(daily_metrics.MetricsError):
        daily_metrics.write_json(tmp_path / "bad.json", {"api_token": "secret"})


def test_collect_writes_only_beneath_requested_root(tmp_path, monkeypatch):
    monkeypatch.setattr(daily_metrics, "fetch_github_releases", lambda: [release("v3.0.6", 10, 8)])
    monkeypatch.setattr(
        daily_metrics,
        "fetch_cloudflare_source",
        lambda **kwargs: daily_metrics.unavailable_source(
            "Cloudflare", kwargs["observed_at"], "2026-08-28", "fixture"
        ),
    )
    result = daily_metrics.collect(
        argparse.Namespace(root=str(tmp_path), date="2026-08-28")
    )
    assert result == 0
    assert (tmp_path / "snapshots" / "2026-08-28.json").exists()
    assert (tmp_path / "reports" / "2026-08-28.md").exists()
    assert not list(ROOT.glob("2026-08-28.*"))
    snapshot_text = (tmp_path / "snapshots" / "2026-08-28.json").read_text(encoding="utf-8")
    assert "secret" not in snapshot_text
