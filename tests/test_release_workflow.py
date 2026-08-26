from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"


def test_release_workflow_verifies_apk_before_publishing() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")

    verify_index = workflow.index("- name: Verify signed GitHub APK")
    publish_index = workflow.index("- name: Publish GitHub release")

    assert verify_index < publish_index
    for required_check in (
        '"$build_tools_dir/apksigner" verify --verbose --print-certs',
        '"$build_tools_dir/aapt" dump badging',
        'test "$package_name" = "dev.mahlernim.timelinevisualizer"',
        'test "$version_code" = "$expected_version_code"',
        'test "$version_name" = "$expected_version_name"',
        'test "$version_name" = "${RELEASE_TAG#v}"',
        'sha256sum "$apk_path"',
    ):
        assert required_check in workflow


def test_release_workflow_can_update_an_existing_release() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")

    assert "workflow_dispatch:" in workflow
    assert "Existing release tag to build and update" in workflow
    assert 'ref: ${{ env.RELEASE_TAG }}' in workflow
    assert 'gh release view "$RELEASE_TAG"' in workflow
    assert 'gh release upload "$RELEASE_TAG" "$apk" "$apk.sha256" --clobber' in workflow


def test_release_workflow_requires_the_carto_project_key() -> None:
    workflow = WORKFLOW.read_text(encoding="utf-8")

    assert "CARTO_BASEMAP_API_KEY: ${{ secrets.CARTO_BASEMAP_API_KEY }}" in workflow
    assert 'test -n "$CARTO_BASEMAP_API_KEY"' in workflow


def test_repository_normalizes_text_without_touching_release_binaries() -> None:
    attributes = (ROOT / ".gitattributes").read_text(encoding="utf-8")

    assert "* text=auto eol=lf" in attributes
    for extension in ("png", "mp4", "apk", "aab", "jks", "keystore"):
        assert f"*.{extension} binary !eol" in attributes
