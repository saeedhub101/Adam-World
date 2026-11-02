#!/usr/bin/env python3
"""Package repository-owned city data as compact spatial tiles for Android assets.

Prefer complete offline OSM extracts (buildings, roads, parks, water, trees,
street lamps). Fall back to the already committed building GeoJSON until each
city's full extract has been imported. No data is downloaded by this script.
"""
from __future__ import annotations
import json
import math
import shutil
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "data" / "facades" / "cities"
FULL_SOURCE = ROOT / "data" / "offline-world"
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "cities"
TILE_METERS = 250.0
KY = 111132.0


def read_json(path: Path):
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def rings(geometry):
    if not geometry:
        return []
    kind = geometry.get("type")
    coords = geometry.get("coordinates") or []
    if kind == "Polygon":
        return [coords[0]] if coords else []
    if kind == "MultiPolygon":
        return [poly[0] for poly in coords if poly]
    return []


def normalize_osm_element(element, lat0, lon0, kx):
    """Return a normalized OSM element and its spatial bucket, or None."""
    kind = element.get("type")
    tags = dict(element.get("tags") or {})
    if kind == "node":
        try:
            lat, lon = float(element["lat"]), float(element["lon"])
        except (KeyError, TypeError, ValueError):
            return None
        ix = math.floor(((lon - lon0) * kx) / TILE_METERS)
        iz = math.floor((-(lat - lat0) * KY) / TILE_METERS)
        return (ix, iz), {
            "type": "node", "id": int(element.get("id", 0)),
            "lat": lat, "lon": lon, "tags": tags,
        }

    if kind != "way":
        return None
    geometry = element.get("geometry") or []
    points = []
    for point in geometry:
        try:
            lat, lon = float(point["lat"]), float(point["lon"])
        except (KeyError, TypeError, ValueError):
            continue
        if math.isfinite(lat) and math.isfinite(lon):
            points.append({"lat": lat, "lon": lon})
    if len(points) < 2:
        return None
    center_lat = sum(p["lat"] for p in points) / len(points)
    center_lon = sum(p["lon"] for p in points) / len(points)
    ix = math.floor(((center_lon - lon0) * kx) / TILE_METERS)
    iz = math.floor((-(center_lat - lat0) * KY) / TILE_METERS)
    return (ix, iz), {
        "type": "way", "id": int(element.get("id", 0)),
        "tags": tags, "geometry": points,
    }


def source_elements(slug):
    full = FULL_SOURCE / slug / "elements.json"
    if full.is_file():
        data = read_json(full)
        elements = data.get("elements") or []
        if elements:
            route_file = full.parent / "transit-routes.json"
            if route_file.is_file():
                route_data = read_json(route_file)
                elements.extend(route_data.get("elements") or [])
            kind = "full-osm+transit-routes" if route_file.is_file() else "full-osm"
            return elements, str(full.relative_to(ROOT)), kind
    buildings = read_json(SOURCE / slug / "buildings.geojson")
    elements = []
    for feature in buildings.get("features", []):
        props = dict(feature.get("properties") or {})
        way_id = props.get("osm_way_id") or props.get("osm_id") or feature.get("id")
        if way_id is None:
            continue
        try:
            way_id = int(str(way_id).replace("way/", ""))
        except (TypeError, ValueError):
            continue
        for part_index, ring in enumerate(rings(feature.get("geometry"))):
            points = []
            for point in ring:
                if not isinstance(point, (list, tuple)) or len(point) < 2:
                    continue
                lon, lat = float(point[0]), float(point[1])
                if math.isfinite(lat) and math.isfinite(lon):
                    points.append({"lat": lat, "lon": lon})
            if len(points) < 4:
                continue
            tags = {str(k): v for k, v in props.items()
                    if v is not None and k not in {"osm_way_id", "osm_type", "source"}}
            tags.setdefault("building", "yes")
            elements.append({
                "type": "way",
                "id": way_id if part_index == 0 else way_id * 100 + part_index,
                "tags": tags,
                "geometry": points,
            })
    return elements, f"data/facades/cities/{slug}/buildings.geojson", "buildings-fallback"


def main():
    catalog = read_json(SOURCE / "catalog.json")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "catalog.json").write_text(
        json.dumps(catalog, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    total_elements = 0
    total_tiles = 0

    for city in catalog.get("cities", []):
        slug = city["slug"]
        center = city["center"]
        lat0, lon0 = float(center["lat"]), float(center["lon"])
        kx = 111320.0 * math.cos(math.radians(lat0))
        raw_elements, source_path, source_kind = source_elements(slug)
        buckets = defaultdict(list)
        seen = set()

        for raw in raw_elements:
            normalized = normalize_osm_element(raw, lat0, lon0, kx)
            if not normalized:
                continue
            (ix, iz), element = normalized
            key = (element["type"], element["id"])
            if key in seen:
                continue
            seen.add(key)
            buckets[(ix, iz)].append(element)

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

        manifest = {
            "schema_version": 1, "slug": slug, "name": city["name"],
            "center": center,
            "coverage_radius_m": float(city.get("discovery_radius_m", 650)),
            "tile_size_m": TILE_METERS,
            "source": source_path, "source_kind": source_kind,
            "element_count": sum(len(v) for v in buckets.values()),
            "tiles": tiles,
        }
        elevation_source = SOURCE / slug / "elevation-grid.json"
        if slug == "amman" and elevation_source.is_file():
            shutil.copy2(elevation_source, city_out / "elevation-grid.json")
            manifest["elevation_grid"] = "elevation-grid.json"
            manifest["elevation_source"] = "Copernicus DEM GLO-30"
        (city_out / "manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, separators=(",", ":")),
            encoding="utf-8",
        )
        count = manifest["element_count"]
        total_elements += count
        total_tiles += len(tiles)
        print(f"{city['name']}: {count} elements in {len(tiles)} tiles ({source_kind})")

    if total_elements == 0 or total_tiles == 0:
        raise SystemExit("Offline city package is empty")
    print(f"OFFLINE_PACKAGE_OK cities={len(catalog['cities'])} elements={total_elements} tiles={total_tiles}")


if __name__ == "__main__":
    main()
