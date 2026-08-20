import re
import xml.etree.ElementTree as ET
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def test_language_selector_matches_android_locale_configuration():
    source = (
        ROOT
        / "app/src/main/java/dev/mahlernim/timelinevisualizer/ui/AppLanguage.kt"
    ).read_text(encoding="utf-8")
    source_tags = re.search(r"supportedTags = listOf\(([^)]+)\)", source).group(1)
    supported = re.findall(r'"([^"]+)"', source_tags)

    locale_config = ET.parse(ROOT / "app/src/main/res/xml/locales_config.xml").getroot()
    configured = [element.attrib[f"{ANDROID_NS}name"] for element in locale_config]

    assert supported == configured


def test_appcompat_persists_selected_language_before_android_13():
    manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
    application = manifest.find("application")
    service = next(
        element
        for element in application.findall("service")
        if element.attrib.get(f"{ANDROID_NS}name")
        == "androidx.appcompat.app.AppLocalesMetadataHolderService"
    )
    metadata = service.find("meta-data")

    assert service.attrib[f"{ANDROID_NS}enabled"] == "false"
    assert service.attrib[f"{ANDROID_NS}exported"] == "false"
    assert metadata.attrib[f"{ANDROID_NS}name"] == "autoStoreLocales"
    assert metadata.attrib[f"{ANDROID_NS}value"] == "true"
