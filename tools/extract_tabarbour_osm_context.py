#!/usr/bin/env python3
"""Extract the Tabarbour neighbourhood from the already committed Amman OSM context."""
from __future__ import annotations
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "data/offline-world/amman"
OUTPUT = ROOT / "data/offline-world/tabarbour"
LAT0, LON0, RADIUS_M = 31.997579, 35.940358, 3500.0


def bounds():
    dlat = RADIUS_M / 111320.0
    dlon = RADIUS_M / (111320.0 * math.cos(math.radians(LAT0)))
    return LON0 - dlon, LAT0 - dlat, LON0 + dlon, LAT0 + dlat


def intersects(element, box):
    west, south, east, north = box
    if element.get("type") == "node":
        try:
            lat, lon = float(element["lat"]), float(element["lon"])
        except (KeyError, TypeError, ValueError):
            return False
        return south <= lat <= north and west <= lon <= east
    geometry = element.get("geometry") or []
    if not geometry:
        return False
    lats, lons = [], []
    for point in geometry:
        try:
            lats.append(float(point["lat"]))
            lons.append(float(point["lon"]))
        except (KeyError, TypeError, ValueError):
            continue
    return bool(lats and lons and max(lats) >= south and min(lats) <= north
                and max(lons) >= west and min(lons) <= east)


def extract(source, target, box):
    if not source.is_file():
        print(f"SKIP missing optional source: {source}", flush=True)
        return 0
    data = json.loads(source.read_text(encoding="utf-8"))
    elements = data.get("elements") or []
    selected = [item for item in elements if intersects(item, box)]
    target.parent.mkdir(parents=True, exist_ok=True)
    result = dict(data)
    result["elements"] = selected
    result["source_extract"] = str(source.relative_to(ROOT))
    result["clip_bounds_wsen"] = list(box)
    result["clip_radius_m"] = RADIUS_M
    target.write_text(json.dumps(result, ensure_ascii=False, separators=(",", ":")) + "\n",
                       encoding="utf-8")
    print(f"SAVED {target}: {len(selected)} / {len(elements)} OSM elements", flush=True)
    return len(selected)


def main():
    box = bounds()
    count = extract(SOURCE / "elements.json", OUTPUT / "elements.json", box)
    if count == 0:
        raise SystemExit("No Tabarbour OSM context elements were extracted")
    extract(SOURCE / "transit-routes.json", OUTPUT / "transit-routes.json", box)


if __name__ == "__main__":
    main()
