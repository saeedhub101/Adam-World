import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "reconcile_building_sources.py"


class ReconcileBuildingSourcesTests(unittest.TestCase):
    def run_tool(self, root, first, second):
        a = root / "a.geojson"
        b = root / "b.geojson"
        out = root / "out.json"
        a.write_text(json.dumps({"type": "FeatureCollection", "features": first}), encoding="utf-8")
        b.write_text(json.dumps({"type": "FeatureCollection", "features": second}), encoding="utf-8")
        subprocess.run([
            sys.executable, str(SCRIPT),
            "--input", f"osm={a}",
            "--input", f"provider_b={b}",
            "--output", str(out),
        ], check=True, capture_output=True, text=True)
        return json.loads(out.read_text(encoding="utf-8"))

    def test_only_explicit_osm_ids_merge_and_conflicts_are_retained(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            a = [{"type": "Feature", "id": "way/10",
                  "geometry": {"type": "Point", "coordinates": [1, 2]},
                  "properties": {"osm_feature_id": "way/10", "name": "Old name"}}]
            b = [
                {"type": "Feature", "id": "local-1",
                 "geometry": {"type": "Point", "coordinates": [1.1, 2]},
                 "properties": {"osm_feature_id": "way/10", "name": "New name"}},
                {"type": "Feature", "id": "local-2",
                 "geometry": {"type": "Point", "coordinates": [1, 2]},
                 "properties": {"name": "Nearby but unrelated"}},
            ]
            result = self.run_tool(root, a, b)
            self.assertEqual(result["entity_count"], 2)
            merged = next(x for x in result["entities"] if x["osm_id"] == "way/10")
            self.assertEqual(merged["observation_count"], 2)
            self.assertTrue(merged["has_source_conflict"])
            self.assertIn("name", merged["tag_conflicts"])
            self.assertEqual(merged["geometry_variant_count"], 2)
            self.assertIsNone(next(x for x in result["entities"] if x["osm_id"] is None)["osm_id"])

    def test_missing_stable_id_is_source_scoped_even_if_geometry_matches(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            geom = {"type": "Point", "coordinates": [31.9, 35.9]}
            result = self.run_tool(root,
                [{"type": "Feature", "id": "same", "geometry": geom, "properties": {"name": "A"}}],
                [{"type": "Feature", "id": "same", "geometry": geom, "properties": {"name": "A"}}])
            self.assertEqual(result["entity_count"], 2)


if __name__ == "__main__":
    unittest.main()
