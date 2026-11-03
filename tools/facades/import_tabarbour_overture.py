#!/usr/bin/env python3
"""Import Overture building, building-part and address attributes for Tabarbour only.

Overture conflates multiple upstream building sources. Source provenance is retained
per feature; geometries are clipped to the dedicated Tabarbour discovery bbox.
"""
from __future__ import annotations
import argparse, bz2, json, math, os, shutil, subprocess, tempfile, time
from urllib.request import Request, urlopen
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CITY = ROOT / "data/facades/cities/tabarbour"
OUTS = {
    "building": CITY / "overture-building-details.geojson",
    "building_part": CITY / "overture-building-parts.geojson",
    "address": CITY / "overture-address-points.geojson",
    "openbuildingmap": CITY / "openbuildingmap-buildings.geojson",
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


def quadkey(lon, lat, zoom=6):
    n = 1 << zoom
    x = int((lon + 180.0) / 360.0 * n)
    rad = math.radians(max(-85.05112878, min(85.05112878, lat)))
    y = int((1.0 - math.asinh(math.tan(rad)) / math.pi) / 2.0 * n)
    key = ""
    for level in range(zoom, 0, -1):
        mask = 1 << (level - 1)
        digit = (1 if x & mask else 0) + (2 if y & mask else 0)
        key += str(digit)
    return key

def download_to(url, path):
    request = Request(url, headers={"User-Agent": "Adam-World-Tabarbour-OBM-import/1.0"})
    with urlopen(request, timeout=180) as response, path.open("wb") as output:
        shutil.copyfileobj(response, output)

def import_openbuildingmap(bbox, refresh):
    out = OUTS["openbuildingmap"]
    if out.exists() and not refresh:
        try:
            old = json.loads(out.read_text(encoding="utf-8"))
            if old.get("source") == "OpenBuildingMap" and old.get("source_bounds_wsen") == list(bbox):
                print(f"KEEP OpenBuildingMap: {len(old.get('features', []))} buildings", flush=True)
                return
        except Exception:
            pass
    import pyogrio
    cfg = json.loads((CITY / "city.json").read_text(encoding="utf-8"))
    key = quadkey(float(cfg["center"]["lon"]), float(cfg["center"]["lat"]))
    manifest_url = "https://umap.openstreetmap.de/de/datalayer/81684/ac9788a9-c215-417d-a580-cb545acd78b9/"
    request = Request(manifest_url, headers={"User-Agent": "Adam-World-Tabarbour-OBM-import/1.0"})
    with urlopen(request, timeout=180) as response:
        manifest = json.loads(response.read().decode("utf-8"))
    tile = next((f for f in manifest.get("features", [])
                 if str((f.get("properties") or {}).get("quadkey")) == key), None)
    if not tile:
        raise RuntimeError(f"OpenBuildingMap manifest has no level-6 tile {key} for Tabarbour")
    props = tile.get("properties") or {}
    url = props.get("URL") or props.get("url")
    if not url:
        raise RuntimeError(f"OpenBuildingMap tile {key} has no download URL")
    work = CITY / ".openbuildingmap-work"
    work.mkdir(parents=True, exist_ok=True)
    compressed = work / f"building.{key}.gpkg.bz2"
    gpkg = work / f"building.{key}.gpkg"
    errors = []
    for attempt in range(3):
        try:
            compressed.unlink(missing_ok=True)
            gpkg.unlink(missing_ok=True)
            download_to(url, compressed)
            if compressed.stat().st_size < 100:
                raise RuntimeError("downloaded OpenBuildingMap tile is unexpectedly small")
            with bz2.open(compressed, "rb") as src, gpkg.open("wb") as dst:
                shutil.copyfileobj(src, dst)
            layers = pyogrio.list_layers(gpkg)
            layer_names = [str(row[0]) for row in layers]
            layer = next((name for name in layer_names if name.casefold() == "building"), None)
            if not layer:
                raise RuntimeError(f"OpenBuildingMap GeoPackage has no building layer: {layer_names}")
            w, south, east, north = bbox
            gdf = pyogrio.read_dataframe(
                gpkg, layer=layer, bbox=(w, south, east, north),
                columns=["id", "height", "occupancy", "source_id", "quadkey", "relation_id", "last_update"]
            )
            features = []
            for _, row in gdf.iterrows():
                geom = row.geometry
                if geom is None or geom.is_empty:
                    continue
                values = {}
                for name in ("id", "height", "occupancy", "source_id", "quadkey", "relation_id", "last_update"):
                    value = row.get(name)
                    if value is None:
                        continue
                    try:
                        if hasattr(value, "item"):
                            value = value.item()
                    except Exception:
                        pass
                    if isinstance(value, float) and not math.isfinite(value):
                        continue
                    values[name] = value.isoformat() if hasattr(value, "isoformat") else value
                features.append({"type": "Feature", "id": str(values.get("id", "")),
                                 "properties": values, "geometry": __import__("shapely.geometry", fromlist=["mapping"]).mapping(geom)})
            if not features:
                raise RuntimeError(f"OpenBuildingMap tile {key} contains no buildings in Tabarbour bounds")
            result = {
                "type": "FeatureCollection", "name": "Tabarbour OpenBuildingMap building enrichment",
                "source": "OpenBuildingMap", "source_bounds_wsen": list(bbox),
                "source_tile_quadkey": key, "source_tile_url": url,
                "tile_metadata": {k: props.get(k) for k in ("number_of_buildings", "percentage_known_height",
                    "percentage_source_openstreetmap", "percentage_source_google", "percentage_source_microsoft")},
                "feature_count": len(features), "features": features,
                "attribution": "OpenBuildingMap; underlying OSM, Google Open Buildings and Microsoft Global ML Building Footprints",
                "license": "ODbL 1.0"
            }
            atomic_json(out, result)
            print(f"SAVED OpenBuildingMap: {len(features)} buildings from quadkey {key}; known-height share in tile={props.get('percentage_known_height')}", flush=True)
            return
        except Exception as exc:
            errors.append(str(exc))
            time.sleep(2 ** attempt)
        finally:
            compressed.unlink(missing_ok=True)
            gpkg.unlink(missing_ok=True)
    raise RuntimeError("OpenBuildingMap import failed after 3 attempts: " + " | ".join(errors))

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
    try:
        import_openbuildingmap(bbox, args.refresh)
    except Exception as exc:
        print(f"ERROR OpenBuildingMap: {exc}", flush=True)
        failures.append("openbuildingmap")
    if failures:
        raise SystemExit("Overture failed layers: " + ", ".join(failures))

if __name__ == "__main__":
    main()
