#!/usr/bin/env python3
"""Import only Overture road segments for Amman. Does not touch city boundaries or other layers."""
import json
import os
import pathlib
import subprocess
import sys
from datetime import datetime, timezone
from decimal import Decimal

ROOT = pathlib.Path(__file__).resolve().parents[1]
CITY_DIR = ROOT / "data/full-city-imports/amman"
ROAD_DIR = CITY_DIR / "09_transport_overture/segment"
# Keep the exact Amman extent already configured by the existing importer.
BBOX = "35.70,31.75,36.12,32.10"
MAX_PART_BYTES = 35 * 1024 * 1024

def json_default(value):
    # Overture/Arrow exposes numeric GeoJSON coordinates and properties as Decimal.
    # Keep them numeric in GeoJSON (not quoted strings).
    if isinstance(value, Decimal):
        return int(value) if value == value.to_integral_value() else float(value)
    raise TypeError(f"Object of type {type(value).__name__} is not JSON serializable")

def save(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_bytes(data)
    os.replace(tmp, path)

def checkpoint(paths):
    rel = [str(p.relative_to(ROOT)) for p in paths if p.exists()]
    subprocess.run(["git", "add", "--", *rel], cwd=ROOT, check=True)
    if subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=ROOT).returncode == 0:
        return
    subprocess.run(["git", "config", "user.name", "github-actions[bot]"], cwd=ROOT, check=True)
    subprocess.run(["git", "config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com"], cwd=ROOT, check=True)
    subprocess.run(["git", "commit", "-m", "Import Amman Overture road segments"], cwd=ROOT, check=True)
    subprocess.run(["git", "pull", "--rebase", "origin", "main"], cwd=ROOT, check=True)
    subprocess.run(["git", "push", "origin", "HEAD:main"], cwd=ROOT, check=True)

def main():
    from ijson import items
    ROAD_DIR.mkdir(parents=True, exist_ok=True)
    raw = ROAD_DIR / "download.raw.geojson"
    manifest_path = ROAD_DIR / "manifest.json"

    # Download only the road-segment layer. No boundary lookup, clipping, or other source.
    subprocess.run([
        "overturemaps", "download", f"--bbox={BBOX}",
        "-f", "geojson", "--type=segment", "-o", str(raw)
    ], cwd=ROOT, check=True, timeout=3600)

    parts = []
    feature_count = 0
    rejected_no_geometry = 0
    rejected_non_line = 0
    part = []
    part_bytes = 0
    part_index = 0

    def flush():
        nonlocal part, part_bytes, part_index, feature_count
        if not part:
            return
        part_index += 1
        name = f"part-{part_index:05d}.geojson"
        payload = (b'{"type":"FeatureCollection","source":"Overture Maps",'
                   b'"source_type":"segment","features":[' + b",".join(part) + b']}')
        save(ROAD_DIR / name, payload)
        entry = {"file": name, "bytes": len(payload), "features": len(part)}
        parts.append(entry)
        feature_count += len(part)
        print("SAVED", name, entry["features"], entry["bytes"], flush=True)
        checkpoint([ROAD_DIR / name])
        part = []
        part_bytes = 0

    try:
        with raw.open("rb") as stream:
            for feature in items(stream, "features.item"):
                geom = feature.get("geometry")
                if not geom:
                    rejected_no_geometry += 1
                    continue
                if geom.get("type") not in ("LineString", "MultiLineString"):
                    rejected_non_line += 1
                    continue
                encoded = json.dumps(feature, separators=(",", ":"), ensure_ascii=False, default=json_default).encode()
                if part and part_bytes + len(encoded) + 1 > MAX_PART_BYTES:
                    flush()
                part.append(encoded)
                part_bytes += len(encoded) + 1
            flush()
    finally:
        raw.unlink(missing_ok=True)

    if feature_count == 0:
        # Do not replace the previous manifest with a false successful result.
        raise RuntimeError(
            "Overture returned no usable LineString/MultiLineString road segments. "
            f"Rejected missing geometry={rejected_no_geometry}, non-line geometry={rejected_non_line}. "
            "Inspect source response/version before retrying."
        )

    manifest = {
        "status": "complete",
        "source": "Overture Maps",
        "source_type": "segment",
        "bbox": BBOX,
        "parts": parts,
        "feature_count": feature_count,
        "rejected_missing_geometry": rejected_no_geometry,
        "rejected_non_line_geometry": rejected_non_line,
        "completed_at": datetime.now(timezone.utc).isoformat(),
    }
    save(manifest_path, json.dumps(manifest, indent=2).encode())
    checkpoint([manifest_path])
    print("ROAD IMPORT RESULT", json.dumps(manifest), flush=True)

if __name__ == "__main__":
    main()
