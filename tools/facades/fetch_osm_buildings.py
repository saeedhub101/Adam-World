#!/usr/bin/env python3
"""Fetch OSM building-way footprints for every configured Adam World city.

Uses the public Overpass API without an API key. Existing valid city files are
kept unchanged unless --refresh is supplied. Each city's GeoJSON is atomically
written and optionally committed/pushed to main before the next city starts.
"""
from __future__ import annotations

import argparse
import json
import math
import os
import subprocess
import tempfile
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ROOT = ROOT / "data" / "facades" / "cities"
ENDPOINTS = (
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
)


def bbox_from_city(city: Path) -> tuple[float, float, float, float]:
    config = json.loads((city / "city.json").read_text(encoding="utf-8"))
    center = config["center"]
    lat, lon = float(center["lat"]), float(center["lon"])
    radius = float(config.get("discovery_radius_m", 650))
    if not (-90 <= lat <= 90 and -180 <= lon <= 180 and radius > 0):
        raise ValueError("invalid city centre or discovery radius")
    dlat = radius / 111320.0
    dlon = radius / (111320.0 * max(0.01, math.cos(math.radians(lat))))
    return lon - dlon, lat - dlat, lon + dlon, lat + dlat


def overpass_query(bbox: tuple[float, float, float, float]) -> str:
    west, south, east, north = bbox
    # Overpass order is south,west,north,east. Query building ways only:
    # relation-based multipolygon footprints are reported separately as a known
    # coverage gap rather than silently converted into incorrect polygons.
    return (
        "[out:json][timeout:180];\n"
        f'way["building"]({south:.7f},{west:.7f},{north:.7f},{east:.7f});\n'
        "out tags geom;"
    )


def request_overpass(query: str, timeout: int = 210) -> dict:
    payload = urlencode({"data": query}).encode("utf-8")
    errors = []
    for endpoint in ENDPOINTS:
        for attempt in range(3):
            try:
                req = Request(
                    endpoint, data=payload, method="POST",
                    headers={
                        "Accept": "application/json",
                        "Content-Type": "application/x-www-form-urlencoded",
                        "User-Agent": "AdamWorld-FacadeFootprints/1.0 (public OSM data import)",
                    },
                )
                with urlopen(req, timeout=timeout) as response:
                    result = json.loads(response.read().decode("utf-8"))
                    if result.get("remark"):
                        raise RuntimeError(str(result["remark"]))
                    return result
            except (HTTPError, URLError, TimeoutError, OSError, json.JSONDecodeError, RuntimeError) as exc:
                errors.append(f"{endpoint} attempt {attempt + 1}: {exc}")
                if isinstance(exc, HTTPError) and exc.code not in (429, 500, 502, 503, 504):
                    break
                if attempt < 2:
                    time.sleep(2 ** attempt)
    raise RuntimeError("all public Overpass endpoints failed: " + " | ".join(errors))


def to_feature(element: dict) -> dict | None:
    if element.get("type") != "way":
        return None
    geometry = element.get("geometry") or []
    coords = [[float(p["lon"]), float(p["lat"])] for p in geometry if "lon" in p and "lat" in p]
    if len(coords) < 4:
        return None
    if coords[0] != coords[-1]:
        coords.append(coords[0])
    if len(set((p[0], p[1]) for p in coords[:-1])) < 3:
        return None
    tags = element.get("tags") or {}
    props = dict(tags)
    props.update({
        "osm_way_id": int(element["id"]),
        "osm_type": "way",
        "source": "OpenStreetMap",
    })
    return {
        "type": "Feature",
        "id": f"way/{int(element['id'])}",
        "properties": props,
        "geometry": {"type": "Polygon", "coordinates": [coords]},
    }


def make_feature_collection(payload: dict, bbox: tuple[float, float, float, float]) -> dict:
    features = []
    seen = set()
    for element in payload.get("elements", []):
        feature = to_feature(element)
        if feature and feature["id"] not in seen:
            features.append(feature)
            seen.add(feature["id"])
    features.sort(key=lambda f: int(f["properties"]["osm_way_id"]))
    return {
        "type": "FeatureCollection",
        "name": "OSM building footprints",
        "license": "Open Database License (ODbL) 1.0",
        "attribution": "© OpenStreetMap contributors",
        "source": "https://www.openstreetmap.org/",
        "source_query_bbox_wsen": list(bbox),
        "source_timestamp_osm_base": (payload.get("osm3s") or {}).get("timestamp_osm_base"),
        "coverage_note": "Building ways only; multipolygon building relations are not yet included.",
        "features": features,
    }


def atomic_write(path: Path, obj: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            json.dump(obj, handle, ensure_ascii=False, separators=(",", ":"))
            handle.write("\n")
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def checkpoint(path: Path, city: Path) -> None:
    relative = str(path.relative_to(ROOT))
    subprocess.run(["git", "add", relative], cwd=ROOT, check=True)
    changed = subprocess.run(
        ["git", "status", "--porcelain"], cwd=ROOT, check=True,
        capture_output=True, text=True,
    ).stdout.strip()
    if changed:
        subprocess.run(
            ["git", "commit", "-m", f"Save {city.name} OSM building footprints"],
            cwd=ROOT, check=True,
        )
        subprocess.run(["git", "push", "origin", "main"], cwd=ROOT, check=True)


def valid_existing(path: Path) -> bool:
    try:
        obj = json.loads(path.read_text(encoding="utf-8"))
        return obj.get("type") == "FeatureCollection" and isinstance(obj.get("features"), list)
    except (OSError, json.JSONDecodeError, AttributeError):
        return False


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cities-root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--city")
    parser.add_argument("--refresh", action="store_true")
    parser.add_argument("--git-checkpoint", action="store_true")
    parser.add_argument("--delay", type=float, default=3.0)
    args = parser.parse_args()
    if args.delay < 0:
        parser.error("--delay must be non-negative")
    cities = [args.cities_root / args.city] if args.city else sorted(
        p for p in args.cities_root.iterdir() if p.is_dir()
    )
    for city in cities:
        output = city / "buildings.geojson"
        if not (city / "city.json").exists():
            print(f"SKIP {city.name}: city.json missing", flush=True)
            continue
        if output.exists() and not args.refresh and valid_existing(output):
            print(f"KEEP {city.name}: existing buildings.geojson ({len(json.loads(output.read_text(encoding='utf-8')).get('features', []))} features)", flush=True)
            continue
        try:
            bbox = bbox_from_city(city)
            payload = request_overpass(overpass_query(bbox))
            fc = make_feature_collection(payload, bbox)
            if not fc["features"]:
                raise RuntimeError("Overpass returned no closed building ways; existing output was not replaced")
            atomic_write(output, fc)
            print(f"SAVED {city.name}: {len(fc['features'])} building footprints", flush=True)
            if args.git_checkpoint:
                checkpoint(output, city)
            time.sleep(args.delay)
        except Exception as exc:
            print(f"ERROR {city.name}: {exc}", flush=True)
            continue


if __name__ == "__main__":
    main()
