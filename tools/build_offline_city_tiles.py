#!/usr/bin/env python3
"""Build compact, spatially chunked offline building assets for the Android game.

Source GeoJSON remains versioned under data/facades/cities. Generated assets are
packaged into the APK so the game never needs Overpass to display these buildings.
"""
from __future__ import annotations
import json
import math
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "data" / "facades" / "cities"
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "cities"
TILE_METERS = 250.0
KY = 111132.0


def read_json(path: Path):
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def polygon_rings(geometry):
    if not geometry:
        return []
    kind = geometry.get("type")
    coords = geometry.get("coordinates") or []
    if kind == "Polygon":
        return [coords[0]] if coords else []
    if kind == "MultiPolygon":
        return [poly[0] for poly in coords if poly]
    return []


def main():
    catalog = read_json(SOURCE / "catalog.json")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "catalog.json").write_text(
        json.dumps(catalog, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    total_buildings = 0
    total_tiles = 0

    for city in catalog.get("cities", []):
        slug = city["slug"]
        center = city["center"]
        lat0, lon0 = float(center["lat"]), float(center["lon"])
        kx = 111320.0 * math.cos(math.radians(lat0))
        source_path = SOURCE / slug / "buildings.geojson"
        if not source_path.is_file():
            raise SystemExit(f"Missing local building source: {source_path}")
        source = read_json(source_path)
        buckets = defaultdict(list)
        accepted = 0

        for feature in source.get("features", []):
            props = dict(feature.get("properties") or {})
            way_id = props.get("osm_way_id") or props.get("osm_id") or feature.get("id")
            if way_id is None:
                continue
            try:
                way_id = int(str(way_id).replace("way/", ""))
            except (TypeError, ValueError):
                continue
            for part_index, ring in enumerate(polygon_rings(feature.get("geometry"))):
                if len(ring) < 4:
                    continue
                valid = []
                for point in ring:
                    if not isinstance(point, (list, tuple)) or len(point) < 2:
                        continue
                    lon, lat = float(point[0]), float(point[1])
                    if not (math.isfinite(lat) and math.isfinite(lon)):
                        continue
                    valid.append((lon, lat))
                if len(valid) < 4:
                    continue
                open_ring = valid[:-1] if valid[0] == valid[-1] else valid
                if len(open_ring) < 3:
                    continue
                centroid_lon = sum(p[0] for p in open_ring) / len(open_ring)
                centroid_lat = sum(p[1] for p in open_ring) / len(open_ring)
                x = (centroid_lon - lon0) * kx
                z = -(centroid_lat - lat0) * KY
                ix, iz = math.floor(x / TILE_METERS), math.floor(z / TILE_METERS)

                tags = {str(k): v for k, v in props.items()
                        if v is not None and k not in {"osm_way_id", "osm_type", "source"}}
                tags.setdefault("building", "yes")
                geometry = [{"lat": lat, "lon": lon} for lon, lat in valid]
                buckets[(ix, iz)].append({
                    "type": "way",
                    "id": way_id,
                    "tags": tags,
                    "geometry": geometry,
                })
                accepted += 1

        city_out = OUTPUT / slug
        city_out.mkdir(parents=True, exist_ok=True)
        tiles = []
        for (ix, iz), elements in sorted(buckets.items()):
            filename = f"tile_{ix}_{iz}.json"
            payload = {"elements": elements}
            (city_out / filename).write_text(
                json.dumps(payload, ensure_ascii=False, separators=(",", ":")),
                encoding="utf-8",
            )
            tiles.append({
                "file": filename,
                "ix": ix,
                "iz": iz,
                "min_x": ix * TILE_METERS,
                "max_x": (ix + 1) * TILE_METERS,
                "min_z": iz * TILE_METERS,
                "max_z": (iz + 1) * TILE_METERS,
                "count": len(elements),
            })

        manifest = {
            "schema_version": 1,
            "slug": slug,
            "name": city["name"],
            "center": center,
            "coverage_radius_m": float(city.get("discovery_radius_m", 650)),
            "tile_size_m": TILE_METERS,
            "source": f"data/facades/cities/{slug}/buildings.geojson",
            "building_count": accepted,
            "tiles": tiles,
        }
        (city_out / "manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, separators=(",", ":")),
            encoding="utf-8",
        )
        total_buildings += accepted
        total_tiles += len(tiles)
        print(f"{city['name']}: {accepted} buildings in {len(tiles)} local tiles")

    if total_buildings == 0 or total_tiles == 0:
        raise SystemExit("Offline city package is empty")
    print(f"OFFLINE_PACKAGE_OK cities={len(catalog['cities'])} buildings={total_buildings} tiles={total_tiles}")


if __name__ == "__main__":
    main()
