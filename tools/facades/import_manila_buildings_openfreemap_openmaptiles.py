#!/usr/bin/env python3
"""Build Manila buildings.geojson from OpenFreeMap and OpenMapTiles building tiles.

Both services publish OSM-derived vector tiles. This importer converts their
building layer to a static GeoJSON FeatureCollection for the game's existing
local-file pipeline. The previous GeoJSON is replaced only after both sources
return usable building polygons and the merged output validates.
"""
from __future__ import annotations

import gzip
import hashlib
import json
import math
import os
import subprocess
import tempfile
import time
from collections import defaultdict
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

import mapbox_vector_tile
from shapely.geometry import box, mapping, shape
from shapely.ops import unary_union, transform

ROOT = Path(__file__).resolve().parents[2]
CITY_DIR = ROOT / "data/facades/cities/manila"
CITY_CONFIG = CITY_DIR / "city.json"
OUTPUT = CITY_DIR / "buildings.geojson"
CHECKPOINT_DIR = CITY_DIR / ".import-checkpoints"
ZOOMS = (13, 14)
BOUNDARY = ROOT / "data/full-city-imports/manila/01_admin_boundary/city-boundary.geojson"
SOURCES = {
    "OpenFreeMap": "https://tiles.openfreemap.org/planet/latest/{z}/{x}/{y}.pbf",
    "OpenMapTiles": "https://openmaptiles.data.gouv.fr/data/planet-vector/{z}/{x}/{y}.pbf",
}
USER_AGENT = "AdamWorld-Manila-BuildingGeoJSON/1.0"


def bounds_from_config():
    if not BOUNDARY.is_file():
        raise RuntimeError(f"Manila administrative boundary is missing: {BOUNDARY}")
    doc = json.loads(BOUNDARY.read_text(encoding="utf-8"))
    parts = [shape(feature["geometry"]) for feature in doc.get("features", []) if feature.get("geometry")]
    if not parts:
        raise RuntimeError("Manila administrative boundary contains no geometry")
    boundary = unary_union(parts)
    if boundary.is_empty or not boundary.is_valid:
        boundary = boundary.buffer(0)
    if boundary.is_empty:
        raise RuntimeError("Manila administrative boundary is invalid or empty")
    return boundary, boundary.bounds


def tile_range(west, south, east, north, z):
    n = 2 ** z
    def tx(lon):
        return int(math.floor((lon + 180.0) / 360.0 * n))
    def ty(lat):
        lat = max(-85.05112878, min(85.05112878, lat))
        rad = math.radians(lat)
        return int(math.floor((1.0 - math.asinh(math.tan(rad)) / math.pi) / 2.0 * n))
    return range(tx(west), tx(east) + 1), range(ty(north), ty(south) + 1)


def fetch(url, attempts=4):
    errors = []
    for attempt in range(attempts):
        try:
            req = Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/x-protobuf"})
            with urlopen(req, timeout=45) as response:
                payload = response.read()
                # Some vector-tile hosts return gzip-compressed PBF bodies.
                # urllib does not transparently decompress HTTP content.
                if payload.startswith(bytes((0x1f, 0x8b))):
                    payload = gzip.decompress(payload)
                return payload
        except (HTTPError, URLError, TimeoutError, OSError) as exc:
            errors.append(str(exc))
            if isinstance(exc, HTTPError) and exc.code not in (408, 429, 500, 502, 503, 504):
                break
            time.sleep(min(2 ** attempt, 12))
    raise RuntimeError(f"tile download failed: {url}: {'; '.join(errors)}")


def tile_xy_to_lonlat(gx, gy, z, extent):
    n = 2 ** z
    xx = gx / extent
    yy = gy / extent
    lon = xx / n * 360.0 - 180.0
    lat = math.degrees(math.atan(math.sinh(math.pi * (1.0 - 2.0 * yy / n))))
    return lon, lat


def convert_geometry(raw_geometry, tile_x, tile_y, z, extent, clip_geometry):
    geom = shape(raw_geometry)
    def project(x, y, zcoord=None):
        if hasattr(x, "__iter__"):
            pairs = [tile_xy_to_lonlat(px + tile_x * extent, py + tile_y * extent, z, extent) for px, py in zip(x, y)]
            xs, ys = zip(*pairs) if pairs else ((), ())
            return (xs, ys) if zcoord is None else (xs, ys, zcoord)
        return tile_xy_to_lonlat(x + tile_x * extent, y + tile_y * extent, z, extent)
    geom = transform(project, geom)
    if not geom.is_valid:
        geom = geom.buffer(0)
    if geom.is_empty:
        return None
    geom = geom.intersection(clip_geometry)
    if geom.is_empty:
        return None
    polys = []
    if geom.geom_type == "Polygon":
        polys = [geom]
    elif geom.geom_type == "MultiPolygon":
        polys = list(geom.geoms)
    elif geom.geom_type == "GeometryCollection":
        polys = [g for g in geom.geoms if g.geom_type == "Polygon"]
        for g in geom.geoms:
            if g.geom_type == "MultiPolygon":
                polys.extend(list(g.geoms))
    if not polys:
        return None
    merged = unary_union(polys)
    return merged if not merged.is_empty else None


def feature_key(feature, geom):
    fid = feature.get("id")
    if fid not in (None, ""):
        return "osm-feature:" + str(fid)
    c = geom.centroid
    return "geometry:" + hashlib.sha1(
        f"{c.x:.6f},{c.y:.6f},{geom.area:.10f}".encode()
    ).hexdigest()


def import_source(name, template, bbox, clip_geometry, z):
    xs, ys = tile_range(bbox[0], bbox[1], bbox[2], bbox[3], z)
    geometries = defaultdict(list)
    properties = {}
    tile_count = 0
    for x in xs:
        for y in ys:
            url = template.format(z=z, x=x, y=y)
            payload = fetch(url)
            if not payload:
                continue
            tile = mapbox_vector_tile.decode(payload, default_options={"y_coord_down": True})
            layer = tile.get("building")
            if not layer:
                continue
            extent = int(layer.get("extent") or 4096)
            for feature in layer.get("features", []):
                raw_geom = feature.get("geometry") or {}
                if raw_geom.get("type") not in ("Polygon", "MultiPolygon"):
                    continue
                geom = convert_geometry(raw_geom, x, y, z, extent, clip_geometry)
                if geom is None or geom.area <= 0:
                    continue
                key = feature_key(feature, geom)
                geometries[key].append(geom)
                props = dict(feature.get("properties") or {})
                current = properties.setdefault(key, {})
                for prop, value in props.items():
                    if value not in (None, "") and (prop not in current or current[prop] in (None, "")):
                        current[prop] = value
            tile_count += 1
    result = {}
    for key, parts in geometries.items():
        geom = unary_union(parts)
        if geom.is_empty:
            continue
        result[key] = (geom, properties.get(key, {}))
    if not result:
        raise RuntimeError(f"{name}: no building polygons returned from {tile_count} tiles")
    return result, tile_count


def merge_sources(imports):
    merged = {}
    for source_name, source_data in imports.items():
        for key, (geom, props) in source_data.items():
            if key not in merged:
                merged[key] = {"geometries": [], "properties": {}, "sources": []}
            item = merged[key]
            item["geometries"].append(geom)
            item["sources"].append(source_name)
            for k, v in props.items():
                if v not in (None, "") and (k not in item["properties"] or item["properties"][k] in (None, "")):
                    item["properties"][k] = v
    features = []
    for key, item in merged.items():
        geom = unary_union(item["geometries"])
        if geom.is_empty:
            continue
        props = dict(item["properties"])
        props["building"] = props.get("building") or "yes"
        if props.get("render_height") not in (None, ""):
            props.setdefault("height", props["render_height"])
        if props.get("colour") not in (None, ""):
            props.setdefault("building:colour", props["colour"])
        if props.get("render_min_height") not in (None, ""):
            props.setdefault("min_height", props["render_min_height"])
        props["source"] = "OpenFreeMap + OpenMapTiles"
        props["source_providers"] = ", ".join(sorted(set(item["sources"])))
        props["source_feature_key"] = key
        features.append({
            "type": "Feature",
            "id": key,
            "properties": props,
            "geometry": mapping(geom),
        })
    features.sort(key=lambda f: str(f["id"]))
    return features


def write_atomic(features, bbox, tile_counts):
    if not features:
        raise RuntimeError("merged GeoJSON contains no buildings; refusing to replace the current file")
    doc = {
        "type": "FeatureCollection",
        "name": "Manila building footprints — OpenFreeMap + OpenMapTiles",
        "source": "OpenFreeMap vector tiles and OpenMapTiles planet-vector tiles",
        "source_urls": list(SOURCES.values()),
        "source_data_lineage": "Both providers publish OpenStreetMap-derived data; these are not independent non-OSM surveys.",
        "attribution": "© OpenFreeMap; © OpenMapTiles; © OpenStreetMap contributors",
        "license": "OpenStreetMap data is licensed under ODbL 1.0; provider attribution required",
        "zooms": list(ZOOMS),
        "coverage_bbox_wsen": list(bbox),
        "coverage": "Exact Manila administrative boundary from data/full-city-imports/manila/01_admin_boundary/city-boundary.geojson",
        "tile_counts_by_source": tile_counts,
        "feature_count": len(features),
        "geometry_note": "MVT building polygons were converted to WGS84 GeoJSON and merged by vector feature ID where available.",
        "features": features,
    }
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix="buildings.geojson.", suffix=".tmp", dir=OUTPUT.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(doc, f, ensure_ascii=False, separators=(",", ":"))
            f.write("\n")
        check = json.loads(Path(tmp).read_text(encoding="utf-8"))
        if check.get("type") != "FeatureCollection" or len(check.get("features", [])) != len(features):
            raise RuntimeError("GeoJSON validation failed")
        os.replace(tmp, OUTPUT)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def checkpoint_path(source_name, zoom):
    safe = source_name.lower().replace(" ", "-")
    return CHECKPOINT_DIR / f"{safe}-z{zoom}.json"


def save_checkpoint(source_name, zoom, data, tile_count):
    """Persist a completed source/zoom stage and push it to main immediately."""
    CHECKPOINT_DIR.mkdir(parents=True, exist_ok=True)
    path = checkpoint_path(source_name, zoom)
    payload = {
        "source": source_name, "zoom": zoom, "tile_count": tile_count,
        "features": {
            key: {"geometry": mapping(geom), "properties": props}
            for key, (geom, props) in data.items()
        },
    }
    fd, tmp = tempfile.mkstemp(prefix=path.name + ".", suffix=".tmp", dir=CHECKPOINT_DIR)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(payload, f, ensure_ascii=False, separators=(",", ":"))
            f.write("\\n")
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)
    # Each completed stage is versioned on main before the next stage starts.
    subprocess.run(["git", "add", str(path.relative_to(ROOT))], cwd=ROOT, check=True)
    changed = subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=ROOT).returncode != 0
    if changed:
        subprocess.run(["git", "commit", "-m", f"Checkpoint Manila {source_name} z{zoom} import"], cwd=ROOT, check=True)
        subprocess.run(["git", "pull", "--rebase", "origin", "main"], cwd=ROOT, check=True)
        subprocess.run(["git", "push", "origin", "HEAD:main"], cwd=ROOT, check=True)
    print(f"SAVED CHECKPOINT {source_name} z{zoom}: {len(data)} features -> {path.relative_to(ROOT)}", flush=True)


def load_checkpoint(source_name, zoom):
    path = checkpoint_path(source_name, zoom)
    if not path.is_file():
        return None
    doc = json.loads(path.read_text(encoding="utf-8"))
    if doc.get("source") != source_name or doc.get("zoom") != zoom:
        raise RuntimeError(f"Checkpoint identity mismatch: {path}")
    data = {
        key: (shape(item["geometry"]), item.get("properties") or {})
        for key, item in doc.get("features", {}).items()
    }
    print(f"RESUME {source_name} z{zoom}: loaded {len(data)} saved features", flush=True)
    return data, int(doc.get("tile_count") or 0)


def main():
    boundary, bbox = bounds_from_config()
    imports = {}
    tile_counts = {}
    for name, template in SOURCES.items():
        print(f"IMPORT {name}: city boundary bbox={bbox}", flush=True)
        combined = {}
        total_tiles = 0
        for z in ZOOMS:
            saved = load_checkpoint(name, z)
            if saved is None:
                data, count = import_source(name, template, bbox, boundary, z)
                save_checkpoint(name, z, data, count)
            else:
                data, count = saved
            total_tiles += count
            for key, value in data.items():
                if key not in combined:
                    combined[key] = value
                else:
                    old_geom, old_props = combined[key]
                    new_geom, new_props = value
                    props = dict(old_props)
                    for prop, val in new_props.items():
                        if val not in (None, "") and props.get(prop) in (None, ""):
                            props[prop] = val
                    combined[key] = (unary_union([old_geom, new_geom]), props)
            print(f"OK {name} z{z}: {len(data)} unique building features from {count} tiles", flush=True)
        imports[name] = combined
        tile_counts[name] = total_tiles
        print(f"OK {name}: {len(combined)} merged features across zooms {ZOOMS}", flush=True)
    features = merge_sources(imports)
    write_atomic(features, bbox, tile_counts)
    print(json.dumps({
        "output": str(OUTPUT.relative_to(ROOT)),
        "features": len(features),
        "openfreemap_features": len(imports["OpenFreeMap"]),
        "openmaptiles_features": len(imports["OpenMapTiles"]),
        "tile_counts": tile_counts,
        "bytes": OUTPUT.stat().st_size,
    }, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    main()
