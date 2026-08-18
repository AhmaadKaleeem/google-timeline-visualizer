import struct
import re
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
PLAY_STORE = ROOT / "play-store"


def png_info(path: Path) -> tuple[int, int, int]:
    data = path.read_bytes()[:26]
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    assert data[12:16] == b"IHDR"
    width, height = struct.unpack(">II", data[16:24])
    return width, height, data[25]


def test_listing_text_meets_play_limits():
    for locale in ("en-US", "ko-KR", "ja-JP"):
        listing = PLAY_STORE / "listing" / locale
        assert len((listing / "title.txt").read_text(encoding="utf-8").strip()) <= 30
        assert len((listing / "short-description.txt").read_text(encoding="utf-8").strip()) <= 80
        assert len((listing / "full-description.txt").read_text(encoding="utf-8").strip()) <= 4_000


def test_primary_graphics_meet_play_dimensions_and_formats():
    icon = PLAY_STORE / "assets" / "app-icon-512.png"
    assert png_info(icon) == (512, 512, 6)  # 32-bit RGBA PNG
    assert icon.stat().st_size <= 1_024 * 1_024

    for locale in ("en", "ko", "ja"):
        feature = PLAY_STORE / "assets" / f"feature-graphic-{locale}-1024x500.png"
        assert png_info(feature) == (1024, 500, 2)  # 24-bit RGB PNG


def test_phone_screenshots_are_current_play_recommended_size():
    for locale in ("en-US", "ko-KR", "ja-JP"):
        screenshots = sorted((PLAY_STORE / "assets" / "screenshots" / locale).glob("*.png"))
        assert len(screenshots) == 4
        for screenshot in screenshots:
            width, height, color_type = png_info(screenshot)
            assert (width, height) == (1080, 1920)
            assert color_type == 2  # 24-bit RGB PNG, no alpha
            assert max(width, height) <= 2 * min(width, height)
            assert screenshot.stat().st_size <= 8 * 1_024 * 1_024


def test_play_release_metadata_is_consistent():
    build_file = (ROOT / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    assert 'versionCode = 11' in build_file
    assert 'versionName = "1.8.1"' in build_file
    assert (ROOT / "docs" / "release-notes-v1.8.1.md").is_file()


def test_android_locales_have_matching_resources_and_placeholders():
    files = {
        "en": ROOT / "app/src/main/res/values/strings.xml",
        "ko": ROOT / "app/src/main/res/values-ko/strings.xml",
        "ja": ROOT / "app/src/main/res/values-ja/strings.xml",
    }

    def resources(path: Path):
        result = {}
        for element in ET.parse(path).getroot():
            name = element.attrib["name"]
            text = " ".join(element.itertext())
            placeholders = sorted(set(re.findall(r"%\d+\$[a-zA-Z]|\{(?:year|name)\}", text)))
            result[(element.tag, name)] = placeholders
        return result

    localized = {locale: resources(path) for locale, path in files.items()}
    assert localized["ko"] == localized["en"]
    assert localized["ja"] == localized["en"]


def test_localized_restoration_guides_are_complete_and_accessible():
    guides = {
        "en": ROOT / "docs/restore-google-maps-timeline.md",
        "ko": ROOT / "docs/restore-google-maps-timeline.ko.md",
        "ja": ROOT / "docs/restore-google-maps-timeline.ja.md",
    }
    diagrams = {
        "en": ROOT / "docs/images/restore-timeline-en.svg",
        "ko": ROOT / "docs/images/restore-timeline-ko.svg",
        "ja": ROOT / "docs/images/restore-timeline-ja.svg",
    }
    for locale, guide in guides.items():
        text = guide.read_text(encoding="utf-8")
        assert f"images/restore-timeline-{locale}.svg" in text
        assert "support.google.com/maps/answer/6258979" in text
        assert "Timeline Visualizer" in text or "타임라인 비주얼라이저" in text or "タイムライン可視化" in text

        svg = ET.parse(diagrams[locale]).getroot()
        namespace = {"svg": "http://www.w3.org/2000/svg"}
        assert svg.find("svg:title", namespace).text.strip()
        assert svg.find("svg:desc", namespace).text.strip()

    assert "docs/restore-google-maps-timeline.md" in (ROOT / "README.md").read_text(encoding="utf-8")
    assert "docs/restore-google-maps-timeline.ko.md" in (ROOT / "README.ko.md").read_text(encoding="utf-8")
    assert "docs/restore-google-maps-timeline.ja.md" in (ROOT / "README.ja.md").read_text(encoding="utf-8")
