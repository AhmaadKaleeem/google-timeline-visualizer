import json

import pytest

import visualizer
from visualizer import (
    FfmpegUnavailableError,
    NoDataFoundError,
    TimelineParseError,
    build_argument_parser,
    ensure_ffmpeg_available,
    parse_timeline,
)


def test_missing_input_is_rejected_by_argparse(tmp_path):
    missing = tmp_path / 'missing.json'

    with pytest.raises(SystemExit) as error:
        build_argument_parser().parse_args(['--input', str(missing)])

    assert error.value.code == 2


def test_directory_input_is_rejected_by_argparse(tmp_path):
    with pytest.raises(SystemExit) as error:
        build_argument_parser().parse_args(['--input', str(tmp_path)])

    assert error.value.code == 2


def test_malformed_json_raises_typed_error(tmp_path):
    timeline = tmp_path / 'Timeline.json'
    timeline.write_text('{not json', encoding='utf-8')

    with pytest.raises(TimelineParseError) as error:
        parse_timeline(timeline, 2026)

    assert isinstance(error.value.__cause__, json.JSONDecodeError)


def test_invalid_utf8_raises_typed_error(tmp_path):
    timeline = tmp_path / 'Timeline.json'
    timeline.write_bytes(b'\xff\xfe')

    with pytest.raises(TimelineParseError) as error:
        parse_timeline(timeline, 2026)

    assert isinstance(error.value.__cause__, UnicodeDecodeError)


def test_missing_file_raises_typed_error_when_parser_is_called_directly(tmp_path):
    with pytest.raises(TimelineParseError) as error:
        parse_timeline(tmp_path / 'missing.json', 2026)

    assert isinstance(error.value.__cause__, OSError)


def test_no_matching_year_raises_no_data_error(tmp_path):
    timeline = tmp_path / 'Timeline.json'
    timeline.write_text('[]', encoding='utf-8')

    with pytest.raises(NoDataFoundError, match='2026'):
        parse_timeline(timeline, 2026)


def test_ffmpeg_availability_uses_matplotlib_configuration(monkeypatch):
    monkeypatch.setattr(visualizer.animation.writers, 'is_available', lambda name: name == 'ffmpeg')

    ensure_ffmpeg_available()


def test_missing_ffmpeg_raises_typed_error(monkeypatch):
    monkeypatch.setattr(visualizer.animation.writers, 'is_available', lambda _name: False)

    with pytest.raises(FfmpegUnavailableError, match='ffmpeg is required'):
        ensure_ffmpeg_available()


def test_main_checks_ffmpeg_before_parsing(monkeypatch, tmp_path, capsys):
    timeline = tmp_path / 'Timeline.json'
    timeline.write_text('[]', encoding='utf-8')
    parsed = False

    def fail_ffmpeg():
        raise FfmpegUnavailableError('ffmpeg unavailable')

    def mark_parsed(*_args):
        nonlocal parsed
        parsed = True

    monkeypatch.setattr(visualizer, 'ensure_ffmpeg_available', fail_ffmpeg)
    monkeypatch.setattr(visualizer, 'parse_timeline', mark_parsed)

    assert visualizer.main(['--input', str(timeline)]) == 1
    assert not parsed
    assert 'ffmpeg unavailable' in capsys.readouterr().err


def test_main_reports_typed_parse_failure(monkeypatch, tmp_path, capsys):
    timeline = tmp_path / 'Timeline.json'
    timeline.write_text('[]', encoding='utf-8')
    monkeypatch.setattr(visualizer, 'ensure_ffmpeg_available', lambda: None)
    monkeypatch.setattr(
        visualizer,
        'parse_timeline',
        lambda *_args: (_ for _ in ()).throw(NoDataFoundError('no matching points')),
    )

    assert visualizer.main(['--input', str(timeline)]) == 1
    assert 'no matching points' in capsys.readouterr().err
