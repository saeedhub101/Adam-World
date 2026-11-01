#!/usr/bin/env python3
"""Build compact, spatially chunked offline city assets for the Android game.

Prefer complete, repository-stored Overpass exports (buildings, roads, parks,
water, trees and lamps). Fall back to the already-versioned building GeoJSON
until a city's complete offline export has been imported.
"""
from __future__ import annotations
import json
import math
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "data" / "facades" / "cities"
OFFLINE_SOURCE = ROOT / "data" / "offline-world"
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "cities"
TILE_METERS = 250.0
KY = 111132.0


def read_json(path: Path):
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def tile_for(lat, lon, lat0, lon0):
    kx = 111320.0 * math.cos(math.radians(lat0))
    x = (lon - lon0) * kx
    z = -(lat - lat0) * KY
    return math.floor(x / TILE_METERS), math.floor(z / TILE_METERS)


def add_complete_osm(elements, buckets, lat0, lon0):
    accepted = 0
    for element in elements:
        kind = element.get("type")
        if kind == "node":
            if "lat" not in element or "lon" not in element:
                continue
            lat, lon = float(element["lat"]), float(element["lon"])
        elif kind == "way":
            geometry = element.get("geometry") or []
            if len(geometry) < 2:
                continue
            lat = sum(float(p["lat"]) for p in geometry) / len(geometry)
            lon = sum(float(p["lon"]) for p in geometry) / len(geometry)
        else:
            continue
        if not (math.isfinite(lat) and math.isfinite(lon)):
            continue
        buckets[tile_for(lat, lon, lat0, lon0)].append(element)
        accepted += 1
    return accepted


def add_building_geojson(features, buckets, lat0, lon0):
    accepted = 0
    for feature in features:
        props = dict(feature.get("properties") or {})
        way_id = props.get("osm_way_id") or props.get("osm_id") or feature.get("id")
        if way_id is None:
            continue
        try:
            way_id = int(str(way_id).replace("way/", ""))
        except (TypeError, ValueError):
            continue
        geometry = feature.get("geometry") or {}
        kind, coords = geometry.get("type"), geometry.get("coordinates") or []
        polygons = [coords] if kind == "Polygon" else (coords if kind == "MultiPolygon" else [])
        for poly in polygons:
            if not poly or len(poly[0]) < 4:
                continue
            ring = poly[0]
            valid = []
            for point in ring:
                if not isinstance(point, (list, tuple)) or len(point) < 2:
                    continue
                lon, lat = float(point[0]), float(point[1])
                if math.isfinite(lat) and math.isfinite(lon):
                    valid.append((lon, lat))
            if len(valid) < 4:
                continue
            open_ring = valid[:-1] if valid[0] == valid[-1] else valid
            if len(open_ring) < 3:
                continue
            centroid_lon = sum(p[0] for p in open_ring) / len(open_ring)
            centroid_lat = sum(p[1] for p in open_ring) / len(open_ring)
            tags = {str(k): v for k, v in props.items()
                    if v is not None and k not in {"osm_way_id", "osm_type", "source"}}
            tags.setdefault("building", "yes")
            element = {
                "type": "way",
                "id": way_id,
                "tags": tags,
                "geometry": [{"lat": lat, "lon": lon} for lon, lat in valid],
            }
            buckets[tile_for(centroid_lat, centroid_lon, lat0, lon0)].append(element)
            accepted += 1
    return accepted


def main():
    catalog = read_json(SOURCE / "catalog.json")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "catalog.json").write_text(
        json.dumps(catalog, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    total_buildings = total_elements = total_tiles = 0

    for city in catalog.get("cities", []):
        slug = city["slug"]
        lat0, lon0 = float(city["center"]["lat"]), float(city["center"]["lon"])
        buckets = defaultdict(list)
        osm_path = OFFLINE_SOURCE / slug / "elements.json"
        if osm_path.is_file():
            osm_data = read_json(osm_path)
            count = add_complete_osm(osm_data.get("elements") or [], buckets, lat0, lon0)
            source_label = f"data/offline-world/{slug}/elements.json"
        else:
            source_path = SOURCE / slug / "buildings.geojson"
            if not source_path.is_file():
                raise SystemExit(f"Missing local city source: {source_path}")
            source = read_json(source_path)
            count = add_building_geojson(source.get("features") or [], buckets, lat0, lon0)
            source_label = f"data/facades/cities/{slug}/buildings.geojson"

        city_out = OUTPUT / slug
        city_out.mkdir(parents=True, exist_ok=True)
        tiles = []
        for (ix, iz), elements in sorted(buckets.items()):
            filename = f"tile_{ix}_{iz}.json"
            (city_out / filename).write_text(
                json.dumps({"elements": elements}, ensure_ascii=False, separators=(",", ":")),
                encoding="utf-8",
            )
            tiles.append({
                "file": filename, "ix": ix, "iz": iz,
                "min_x": ix * TILE_METERS, "max_x": (ix + 1) * TILE_METERS,
                "min_z": iz * TILE_METERS, "max_z": (iz + 1) * TILE_METERS,
                "count": len(elements),
            })

        building_count = sum(
            1 for items in buckets.values()
            for el in items if el.get("type") == "way" and (el.get("tags") or {}).get("building")
        )
        manifest = {
            "schema_version": 1,
            "slug": slug,
            "name": city["name"],
            "center": city["center"],
            "coverage_radius_m": float(city.get("discovery_radius_m", 650)),
            "tile_size_m": TILE_METERS,
            "source": source_label,
            "element_count": count,
            "building_count": building_count,
            "tiles": tiles,
        }
        (city_out / "manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, separators=(",", ":")),
            encoding="utf-8",
        )
        total_elements += count
        total_buildings += building_count
        total_tiles += len(tiles)
        print(f"{city['name']}: {building_count} buildings, {count} map elements in {len(tiles)} local tiles")

    if total_elements == 0 or total_tiles == 0:
        raise SystemExit("Offline city package is empty")
    print(f"OFFLINE_PACKAGE_OK cities={len(catalog['cities'])} buildings={total_buildings} elements={total_elements} tiles={total_tiles}")


if __name__ == "__main__":
    main()
