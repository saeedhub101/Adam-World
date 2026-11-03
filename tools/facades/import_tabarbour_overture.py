#!/usr/bin/env python3
"""Import Overture building, building-part and address attributes for Tabarbour only.

Overture conflates multiple upstream building sources. Source provenance is retained
per feature; geometries are clipped to the dedicated Tabarbour discovery bbox.
"""
from __future__ import annotations
import argparse, json, math, os, subprocess, tempfile, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CITY = ROOT / "data/facades/cities/tabarbour"
OUTS = {
    "building": CITY / "overture-building-details.geojson",
    "building_part": CITY / "overture-building-parts.geojson",
    "address": CITY / "overture-address-points.geojson",
}

def atomic_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(value, f, ensure_ascii=False, separators=(",", ":"))
            f.write("\n")
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)

def bounds():
    cfg = json.loads((CITY / "city.json").read_text(encoding="utf-8"))
    lat, lon = float(cfg["center"]["lat"]), float(cfg["center"]["lon"])
    radius = float(cfg.get("discovery_radius_m", 3500))
    dlat = radius / 111132.0
    dlon = radius / (111320.0 * max(0.01, math.cos(math.radians(lat))))
    return lon-dlon, lat-dlat, lon+dlon, lat+dlat

def import_layer(kind, bbox, refresh):
    out = OUTS[kind]
    if out.exists() and not refresh:
        try:
            old = json.loads(out.read_text(encoding="utf-8"))
            if old.get("source") == "Overture Maps" and old.get("source_type") == kind and old.get("source_bounds_wsen") == list(bbox):
                print(f"KEEP Overture {kind}: {len(old.get('features', []))} features", flush=True)
                return
        except Exception:
            pass
    w, s, e, n = bbox
    tmp = CITY / f".overture-{kind}.download.geojson"
    errors = []
    for attempt in range(3):
        try:
            tmp.unlink(missing_ok=True)
            subprocess.run([
                "overturemaps", "download", f"--bbox={w},{s},{e},{n}",
                "-f", "geojson", "--type=" + kind, "-o", str(tmp)
            ], cwd=ROOT, check=True, timeout=3600)
            if not tmp.exists():
                # Explicitly record an empty address/part layer. Buildings are required.
                features = []
            else:
                payload = json.loads(tmp.read_text(encoding="utf-8"))
                features = payload.get("features") or []
            if kind == "building" and not features:
                raise RuntimeError("Overture building query returned no building features")
            result = {
                "type": "FeatureCollection",
                "name": f"Tabarbour Overture {kind}",
                "source": "Overture Maps",
                "source_type": kind,
                "source_bounds_wsen": list(bbox),
                "source_release": "latest at import time",
                "feature_count": len(features),
                "features": features,
                "attribution": "Overture Maps; upstream source attribution retained per feature",
                "license": "Per-source licences; see feature sources and Overture attribution guidance"
            }
            atomic_json(out, result)
            tmp.unlink(missing_ok=True)
            print(f"SAVED Overture {kind}: {len(features)} features", flush=True)
            return
        except Exception as exc:
            errors.append(str(exc))
            time.sleep(2 ** attempt)
    tmp.unlink(missing_ok=True)
    atomic_json(CITY / f"overture-{kind}.failed.json", {
        "source": "Overture Maps", "source_type": kind,
        "source_bounds_wsen": list(bbox), "status": "failed_retryable",
        "errors": errors
    })
    raise RuntimeError(f"Overture {kind} import failed after 3 attempts: {' | '.join(errors)}")

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--refresh", action="store_true")
    args = parser.parse_args()
    bbox = bounds()
    failures = []
    for kind in ("building", "building_part", "address"):
        try:
            import_layer(kind, bbox, args.refresh)
        except Exception as exc:
            print(f"ERROR Overture {kind}: {exc}", flush=True)
            failures.append(kind)
    if failures:
        raise SystemExit("Overture failed layers: " + ", ".join(failures))

if __name__ == "__main__":
    main()
