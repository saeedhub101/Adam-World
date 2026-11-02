#!/usr/bin/env python3
"""Fetch Tabarbour's own OSM context and elevation tiles directly from their sources.

This script intentionally has no dependency on Amman exports or any other city folder.
"""
from __future__ import annotations
import json
import math
import os
import tempfile
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
CITY = ROOT / "data/facades/cities/tabarbour/city.json"
OUTPUT = ROOT / "data/offline-world/tabarbour"
TERRAIN_OUTPUT = OUTPUT / "terrain"
LAT0, LON0, RADIUS_M = 31.997579, 35.940358, 3500.0
OVERPASS = (
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
)
TERRARIUM_ZOOM = 14


def bounds():
    dlat = RADIUS_M / 111320.0
    dlon = RADIUS_M / (111320.0 * math.cos(math.radians(LAT0)))
    return LON0 - dlon, LAT0 - dlat, LON0 + dlon, LAT0 + dlat


def atomic_write(path: Path, payload: bytes):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        with os.fdopen(fd, "wb") as stream:
            stream.write(payload)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def get_bytes(url: str, data: bytes | None = None, timeout: int = 180) -> bytes:
    request = Request(url, data=data, headers={
        "User-Agent": "Adam-World-Tabarbour-Offline-Importer/1.0",
        "Content-Type": "application/x-www-form-urlencoded" if data else "application/octet-stream",
    })
    with urlopen(request, timeout=timeout) as response:
        if response.status != 200:
            raise RuntimeError(f"HTTP {response.status}: {url}")
        return response.read()


def overpass(query: str) -> dict:
    errors = []
    body = ("data=" + __import__("urllib.parse", fromlist=["quote"]).quote(query)).encode()
    for endpoint in OVERPASS:
        for attempt in range(3):
            try:
                return json.loads(get_bytes(endpoint, body, timeout=300).decode("utf-8"))
            except Exception as exc:
                errors.append(f"{endpoint} attempt {attempt + 1}: {exc}")
                time.sleep(min(2 ** attempt, 8))
    raise RuntimeError("All direct Tabarbour Overpass sources failed: " + " | ".join(errors))


def tile_x(lon, z):
    return int((lon + 180.0) / 360.0 * (1 << z))


def tile_y(lat, z):
    lat = max(-85.05112878, min(85.05112878, lat))
    rad = math.radians(lat)
    return int((1.0 - math.asinh(math.tan(rad)) / math.pi) / 2.0 * (1 << z))


def download_terrain(box):
    west, south, east, north = box
    z = TERRARIUM_ZOOM
    tiles = []
    for x in range(tile_x(west, z), tile_x(east, z) + 1):
        for y in range(tile_y(north, z), tile_y(south, z) + 1):
            rel = Path(str(z)) / str(x) / f"{y}.png"
            target = TERRAIN_OUTPUT / rel
            if target.is_file() and target.stat().st_size > 100:
                tiles.append(str(rel))
                continue
            url = f"https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png"
            try:
                content = get_bytes(url, timeout=60)
                if not content.startswith(b"\x89PNG\r\n\x1a\n"):
                    raise ValueError("source did not return a PNG")
                atomic_write(target, content)
                tiles.append(str(rel))
                print(f"SAVED Tabarbour terrain tile {rel}", flush=True)
            except Exception as exc:
                print(f"WARN terrain tile {rel}: {exc}", flush=True)
    manifest = {
        "city": "tabarbour",
        "source": "AWS Terrain Tiles / Terrarium",
        "source_url": "https://s3.amazonaws.com/elevation-tiles-prod/terrarium/{z}/{x}/{y}.png",
        "zoom": z,
        "bounds_wsen": list(box),
        "center": {"lat": LAT0, "lon": LON0},
        "radius_m": RADIUS_M,
        "tile_count": len(tiles),
        "tiles": tiles,
        "encoding": "Terrarium RGB: elevation_m = (R*256 + G + B/256) - 32768",
        "attribution": "Mapzen Terrarium elevation tiles",
    }
    atomic_write(TERRAIN_OUTPUT / "manifest.json", (json.dumps(manifest, ensure_ascii=False, indent=2) + "\n").encode())
    if not tiles:
        raise RuntimeError("No Tabarbour elevation tiles were downloaded")
    print(f"SAVED Tabarbour terrain manifest: {len(tiles)} tiles", flush=True)


def main():
    if CITY.is_file():
        city = json.loads(CITY.read_text(encoding="utf-8"))
        center = city.get("center") or {}
        if abs(float(center.get("lat", 0)) - LAT0) > 0.00001 or abs(float(center.get("lon", 0)) - LON0) > 0.00001:
            raise RuntimeError("Tabarbour city center does not match the dedicated import coordinates")
    box = bounds()
    west, south, east, north = box
    # Fetch all mapped city context directly for this bounding box. No Amman extract is read.
    query = f"""[out:json][timeout:240];
(
  way["building"]({south},{west},{north},{east});
  relation["building"]({south},{west},{north},{east});
  way["building:part"]({south},{west},{north},{east});
  relation["building:part"]({south},{west},{north},{east});
  way["highway"]({south},{west},{north},{east});
  way["railway"]({south},{west},{north},{east});
  way["public_transport"]({south},{west},{north},{east});
  node["public_transport"]({south},{west},{north},{east});
  node["highway"="bus_stop"]({south},{west},{north},{east});
  way["waterway"]({south},{west},{north},{east});
  way["natural"~"water|wood|scrub|tree_row|wetland"]({south},{west},{north},{east});
  way["landuse"]({south},{west},{north},{east});
  way["leisure"]({south},{west},{north},{east});
  way["amenity"]({south},{west},{north},{east});
  way["man_made"~"water_well|water_tower|reservoir|pipeline|wastewater_plant"]({south},{west},{north},{east});
  way["water" ]({south},{west},{north},{east});
  node["natural"="tree"]({south},{west},{north},{east});
  node["highway"="traffic_signals"]({south},{west},{north},{east});
  node["man_made"~"water_well|water_tower"]({south},{west},{north},{east});
  relation["route"~"bus|train|subway|tram|trolleybus"]({south},{west},{north},{east});
);
out body geom;
"""
    result = overpass(query)
    elements = result.get("elements") or []
    if not elements:
        raise RuntimeError("Direct Tabarbour Overpass query returned no mapped features")
    output = {
        "version": 0.6,
        "generator": result.get("generator", "Overpass API"),
        "osm3s": result.get("osm3s", {}),
        "source": "Direct Overpass query for Tabarbour only",
        "source_url": "https://overpass-api.de/api/interpreter",
        "center": {"lat": LAT0, "lon": LON0},
        "radius_m": RADIUS_M,
        "bounds_wsen": list(box),
        "elements": elements,
        "element_count": len(elements),
        "attribution": "© OpenStreetMap contributors",
        "license": "ODbL 1.0",
    }
    atomic_write(OUTPUT / "elements.json", (json.dumps(output, ensure_ascii=False, separators=(",", ":")) + "\n").encode())
    print(f"SAVED dedicated Tabarbour OSM context: {len(elements)} elements", flush=True)

    transit_query = f"""[out:json][timeout:180];
(
  relation["route"~"bus|train|subway|tram|trolleybus"]({south},{west},{north},{east});
  way["railway"~"rail|light_rail|subway|tram"]({south},{west},{north},{east});
  node["public_transport"]({south},{west},{north},{east});
  node["highway"="bus_stop"]({south},{west},{north},{east});
);
out body geom;
"""
    try:
        transit = overpass(transit_query)
        transit_elements = transit.get("elements") or []
        transit_output = {
            "source": "Direct Tabarbour-only Overpass transit query",
            "bounds_wsen": list(box),
            "elements": transit_elements,
            "element_count": len(transit_elements),
            "complete": True,
            "attribution": "© OpenStreetMap contributors",
            "license": "ODbL 1.0",
        }
        atomic_write(OUTPUT / "transit-routes.json", (json.dumps(transit_output, ensure_ascii=False, separators=(",", ":")) + "\n").encode())
        print(f"SAVED dedicated Tabarbour transit: {len(transit_elements)} elements", flush=True)
    except Exception as exc:
        print(f"WARN Tabarbour transit query failed; general OSM context is retained: {exc}", flush=True)

    download_terrain(box)


if __name__ == "__main__":
    main()
