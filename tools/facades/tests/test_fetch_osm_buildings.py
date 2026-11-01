import importlib.util
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "fetch_osm_buildings.py"
spec = importlib.util.spec_from_file_location("fetch_osm_buildings", SCRIPT)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class OSMFootprintTests(unittest.TestCase):
    def test_way_becomes_closed_polygon_with_osm_identity_and_tags(self):
        feature = mod.to_feature({
            "type": "way", "id": 123,
            "tags": {"building": "yes", "height": "12"},
            "geometry": [
                {"lon": 1, "lat": 2}, {"lon": 1.001, "lat": 2},
                {"lon": 1.001, "lat": 2.001}, {"lon": 1, "lat": 2.001},
            ],
        })
        self.assertEqual(feature["id"], "way/123")
        self.assertEqual(feature["properties"]["osm_way_id"], 123)
        self.assertEqual(feature["properties"]["height"], "12")
        ring = feature["geometry"]["coordinates"][0]
        self.assertEqual(ring[0], ring[-1])
        self.assertEqual(feature["geometry"]["type"], "Polygon")

    def test_rejects_unclosed_or_degenerate_geometry_with_too_few_points(self):
        self.assertIsNone(mod.to_feature({"type": "way", "id": 4, "geometry": [
            {"lon": 1, "lat": 2}, {"lon": 1.1, "lat": 2}
        ]}))

    def test_nodes_are_not_mistaken_for_buildings(self):
        self.assertIsNone(mod.to_feature({"type": "node", "id": 5, "geometry": []}))

    def test_collection_preserves_attribution_and_source_timestamp(self):
        fc = mod.make_feature_collection({"osm3s": {"timestamp_osm_base": "2026-11-01T00:00:00Z"},
            "elements": [{"type": "way", "id": 7, "tags": {"building": "yes"},
            "geometry": [{"lon": 0, "lat": 0}, {"lon": 1, "lat": 0},
                         {"lon": 1, "lat": 1}, {"lon": 0, "lat": 1}]}]},
            (-1, -1, 2, 2))
        self.assertEqual(fc["attribution"], "© OpenStreetMap contributors")
        self.assertEqual(fc["source_timestamp_osm_base"], "2026-11-01T00:00:00Z")
        self.assertEqual(len(fc["features"]), 1)


if __name__ == "__main__":
    unittest.main()
