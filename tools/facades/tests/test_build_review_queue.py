import importlib.util
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parents[1] / "build_review_queue.py"
spec = importlib.util.spec_from_file_location("build_review_queue", SCRIPT)
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class CandidateQueueTests(unittest.TestCase):
    def test_bearing_and_angle_wrap(self):
        self.assertAlmostEqual(mod.angle_delta(359, 1), 2)
        self.assertAlmostEqual(mod.bearing_deg((0, 0), (1, 0)), 90, delta=0.1)

    def test_near_photo_is_candidate_not_verified_assignment(self):
        building = {
            "type": "Feature",
            "id": "way/100",
            "properties": {"osm_way_id": 100, "building": "yes"},
            "geometry": {"type": "Polygon", "coordinates": [[
                [0.0005, -0.0005], [0.0015, -0.0005], [0.0015, 0.0005],
                [0.0005, 0.0005], [0.0005, -0.0005]
            ]]},
        }
        photo = {
            "type": "Feature", "id": "photo-1",
            "geometry": {"type": "Point", "coordinates": [0, 0]},
            "properties": {"view:azimuth": 90, "pers:interior_orientation": {"field_of_view": 90},
                           "license": "CC-BY-SA-4.0"},
            "assets": {"thumbnail": {"href": "https://example.invalid/thumb.jpg"}},
            "links": [{"rel": "self", "href": "https://example.invalid/api/pictures/photo-1"}],
        }
        with tempfile.TemporaryDirectory() as tmp:
            city = Path(tmp) / "test-city"
            city.mkdir()
            (city / "buildings.geojson").write_text(
                __import__("json").dumps({"type": "FeatureCollection", "features": [building]}), encoding="utf-8")
            (city / "panoramax-items.geojson").write_text(
                __import__("json").dumps({"type": "FeatureCollection", "features": [photo]}), encoding="utf-8")
            result = mod.build_queue(city, 100)
        item = result["buildings"][0]
        self.assertEqual(item["facade_photo_candidates"][0]["identity_status"], "unverified_candidate_only")
        self.assertFalse(item["texture_assignment_allowed"])
        self.assertEqual(item["identity_status"], "unverified")

    def test_outside_camera_cone_is_not_candidate(self):
        loc = (0.0, 0.0)
        target = (1.0, 0.0)
        self.assertGreater(mod.angle_delta(0, mod.bearing_deg(loc, target)), 25)


if __name__ == "__main__":
    unittest.main()
