import json

from visualizer import extract_timeline_points, parse_coordinate


def test_takeout_object_root():
    data = {
        "semanticSegments": [
            {
                "startTime": "2025-01-01T00:00:00Z",
                "timelinePath": [
                    {"point": "37.5°, 127.0°", "time": "2025-01-01T01:00:00Z"},
                ],
                "visit": {"topCandidate": {"placeLocation": {"latLng": "37.6°, 127.1°"}}},
            }
        ]
    }
    points = extract_timeline_points(data, 2025)
    assert [(p["lat"], p["lon"]) for p in points] == [(37.6, 127.1), (37.5, 127.0)]


def test_ios_array_root_and_activity():
    data = [
        {
            "startTime": "2024-05-01T01:00:00Z",
            "endTime": "2024-05-01T02:00:00Z",
            "activity": {"start": "geo:35.1,129.1", "end": {"latLng": "geo:35.2,129.2"}},
        },
        {
            "startTime": "2024-05-01T03:00:00Z",
            "visit": {"topCandidate": {"placeLocation": "geo:35.3,129.3"}},
        },
    ]
    points = extract_timeline_points(data, 2024)
    assert [(p["lat"], p["lon"]) for p in points] == [(35.1, 129.1), (35.2, 129.2), (35.3, 129.3)]


def test_coordinate_parser_supports_e7_and_rejects_invalid():
    assert parse_coordinate("375000000,1270000000") == (37.5, 127.0)
    assert parse_coordinate("geo:91,127") is None
    assert parse_coordinate(None) is None
