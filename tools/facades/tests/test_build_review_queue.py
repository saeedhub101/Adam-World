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
            result = mod.build_queue(city, 150)
        item = result["buildings"][0]
        self.assertEqual(item["facade_photo_candidates"][0]["identity_status"], "unverified_candidate_only")
        self.assertFalse(item["texture_assignment_allowed"])
        self.assertEqual(item["identity_status"], "unverified")

    def test_dem_is_not_classified_as_roof_imagery(self):
        building = {"geometry": {"type": "Polygon", "coordinates": [[
            [0, 0], [0.001, 0], [0.001, 0.001], [0, 0.001], [0, 0]
        ]]}}
        dem = {"id": "Copernicus_DSM_COG_DEM", "geometry": {"type": "Polygon", "coordinates": [[
            [-0.001, -0.001], [0.002, -0.001], [0.002, 0.002], [-0.001, 0.002], [-0.001, -0.001]
        ]]}, "properties": {"gsd": 30, "platform": "TanDEM-X"}}
        candidate = mod.aerial_candidates(building, [dem])[0]
        self.assertEqual(candidate["kind"], "digital_elevation_model")
        self.assertFalse(candidate["roof_shape_colour_candidate_only"])
        self.assertFalse(candidate["facade_texture_allowed"])

    def test_outside_camera_cone_is_not_candidate(self):
        loc = (0.0, 0.0)
        target = (1.0, 0.0)
        self.assertGreater(mod.angle_delta(0, mod.bearing_deg(loc, target)), 25)

    def test_strict_review_gate_requires_real_image_camera_and_open_license(self):
        photo = {
            "azimuth_deg": 90, "field_of_view_deg": 70, "pitch_deg": -5,
            "license": "CC-BY-SA-4.0", "preview_url": "https://example.org/preview.jpg",
            "image_url": "https://example.org/full.jpg", "capture_datetime": "2025-01-01T00:00:00Z",
        }
        eligible, reasons, score = mod.photo_quality_gate(photo, 8, 5)
        self.assertTrue(eligible, reasons)
        self.assertGreater(score, 70)
        photo["license"] = "CC-BY-NC-SA-4.0"
        eligible, reasons, _ = mod.photo_quality_gate(photo, 8, 5)
        self.assertFalse(eligible)
        self.assertIn("redistribution_license_not_confirmed", reasons)

    def test_missing_full_photo_asset_is_rejected_from_visual_review(self):
        photo = {
            "azimuth_deg": 90, "field_of_view_deg": 70, "pitch_deg": 0,
            "license": "CC-BY-4.0", "preview_url": "https://example.org/preview.jpg",
            "image_url": None, "capture_datetime": "2025-01-01T00:00:00Z",
        }
        eligible, reasons, _ = mod.photo_quality_gate(photo, 5, 3)
        self.assertFalse(eligible)
        self.assertIn("no_downloadable_photo_asset", reasons)

    def test_distance_uses_nearest_footprint_edge_not_centroid(self):
        geom = {"type": "Polygon", "coordinates": [[[0.0, 0.0], [0.001, 0.0], [0.001, 0.001], [0.0, 0.001], [0.0, 0.0]]]}
        distance, point = mod.nearest_footprint_point((-0.0001, 0.0005), geom)
        self.assertAlmostEqual(distance, 11.13, delta=0.5)
        self.assertAlmostEqual(point[0], 0.0, delta=1e-7)


if __name__ == "__main__":
    unittest.main()
