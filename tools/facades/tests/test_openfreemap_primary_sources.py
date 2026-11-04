import json
import tempfile
import unittest
from pathlib import Path

from tools import build_offline_city_tiles as builder


class OpenFreeMapPrimarySourceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        root = Path(self.tmp.name)
        builder.ROOT = root
        builder.SOURCE = root / "facades" / "cities"
        builder.FULL_SOURCE = root / "offline-world"
        self.city = builder.SOURCE / "sample"
        self.city.mkdir(parents=True)
        (builder.FULL_SOURCE / "sample").mkdir(parents=True)

    def tearDown(self):
        self.tmp.cleanup()

    def write_json(self, path, value):
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(value), encoding="utf-8")

    def feature(self, fid, props, geometry):
        return {"type": "Feature", "id": fid, "properties": props, "geometry": geometry}

    def test_openfreemap_transportation_becomes_game_road_and_label_ways(self):
        self.write_json(self.city / "transportation.geojson", {
            "type": "FeatureCollection",
            "features": [
                self.feature("road-primary", {"highway": "primary", "adam:source_geometry": "OpenFreeMap/OpenMapTiles"},
                    {"type": "LineString", "coordinates": [[35.0, 31.0], [35.001, 31.001]]}),
                self.feature("road-name", {"highway": "primary", "name": "Test Street", "adam:label_only": "yes"},
                    {"type": "LineString", "coordinates": [[35.0, 31.0], [35.001, 31.001]]})
            ]
        })
        result = builder._geojson_transportation_elements(self.city / "transportation.geojson")
        self.assertEqual(len(result), 2)
        self.assertTrue(all(x["tags"]["adam:source_geometry"] == "OpenFreeMap/OpenMapTiles" for x in result))
        self.assertEqual(sum(x["tags"].get("adam:label_only") == "yes" for x in result), 1)

    def test_osm_roads_are_excluded_and_only_missing_osm_buildings_are_added(self):
        polygon = {"type": "Polygon", "coordinates": [[[35,31],[35.0001,31],[35.0001,31.0001],[35,31.0001],[35,31]]]}
        line = {"type": "LineString", "coordinates": [[35,31],[35.001,31.001]]}
        self.write_json(self.city / "building-footprints.geojson", {
            "type": "FeatureCollection", "features": [
                self.feature("openfree/101", {"osm_id": 101, "building": "yes", "height": "20"},
                    polygon)
            ]
        })
        self.write_json(self.city / "building-details.geojson", {
            "type": "FeatureCollection", "features": [
                self.feature("osm-101", {"osm_feature_id": "way/101", "tags": {"building": "yes", "height": "12"}},
                    polygon),
                self.feature("osm-202", {"osm_feature_id": "way/202", "tags": {"building": "yes", "height": "8"}},
                    {"type": "Polygon", "coordinates": [[[35.002,31],[35.0021,31],[35.0021,31.0001],[35.002,31.0001],[35.002,31]]]})
            ]
        })
        self.write_json(self.city / "transportation.geojson", {
            "type": "FeatureCollection", "features": [
                self.feature("openfree-road", {"highway": "primary"},
                    line)
            ]
        })
        self.write_json(builder.FULL_SOURCE / "sample" / "elements.json", {
            "elements": [
                {"type": "way", "id": 101, "tags": {"building": "yes"}, "geometry": [{"lat":31,"lon":35},{"lat":31.0001,"lon":35.0001},{"lat":31,"lon":35}]},
                {"type": "way", "id": 300, "tags": {"highway": "primary", "name": "OSM road"}, "geometry": [{"lat":31,"lon":35},{"lat":31.001,"lon":35.001}]},
                {"type": "node", "id": 400, "lat":31,"lon":35,"tags":{"highway":"traffic_signals"}}
            ]
        })
        elements, _, kind = builder.source_elements("sample")
        # OSM road geometry must not be emitted as a game road; matching OSM
        # attributes may enrich the canonical OpenFreeMap geometry only.
        osm_road_geometries = [x for x in elements if str(x.get("id")) == "300"]
        self.assertEqual(osm_road_geometries, [])
        primary_road = next(x for x in elements if (x.get("tags") or {}).get("adam:source_geometry") == "OpenFreeMap/OpenMapTiles" and (x.get("tags") or {}).get("highway") == "primary")
        self.assertEqual(primary_road["tags"].get("adam:matched_osm_id"), "300")
        self.assertTrue(any(x["tags"].get("adam:source_geometry") == "OpenFreeMap/OpenMapTiles" and x["tags"].get("highway") == "primary" for x in elements))
        self.assertTrue(any(str(x.get("id")) == "202" and x["tags"].get("height") == "8" for x in elements))
        primary_building = next(x for x in elements if x.get("id") == 101)
        self.assertEqual(primary_building["tags"].get("height"), "20")
        self.assertIn("openfreemap-road-geometry-and-labels", kind)
        self.assertTrue(any(x.get("type") == "node" and x.get("id") == 400 for x in elements))


if __name__ == "__main__":
    unittest.main()
