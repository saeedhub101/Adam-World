#!/usr/bin/env python3
"""Download every band and annual snapshot of Google's Open Buildings 2.5D Temporal data for Manila.

The source is an Earth Engine ImageCollection with three float raster bands for 2016-2023.
Exports are tiled at the dataset's effective 4 m resolution (the published 0.5 m raster
grid does not represent 0.5 m independent information). Original band values are retained.
"""
from __future__ import annotations
import argparse, json, math, os, pathlib, tempfile, zipfile
from datetime import datetime, timezone
from urllib.request import Request, urlopen

import ee
import numpy as np
import rasterio
from rasterio.transform import from_origin
from shapely.geometry import shape, box, mapping
from shapely.ops import unary_union

ROOT = pathlib.Path(__file__).resolve().parents[1]
CITY_DIR = ROOT / "data/full-city-imports/manila"
OUT = CITY_DIR / "03_buildings_google_temporal"
COLLECTION = "GOOGLE/Research/open-buildings-temporal/v1"
BANDS = ["building_presence", "building_height", "building_fractional_count"]
YEARS = range(2016, 2024)
SCALE_M = 4
TILE_M = 5000
MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024

def atomic_write(path: pathlib.Path, data: bytes):
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_bytes(data)
    os.replace(tmp, path)

def load_boundary():
    path = CITY_DIR / "01_admin_boundary/city-boundary.geojson"
    if path.is_file():
        fc = json.loads(path.read_text(encoding="utf-8"))
        geoms = [shape(f["geometry"]) for f in fc.get("features", []) if f.get("geometry")]
        if geoms:
            return unary_union(geoms), "existing city-boundary.geojson"
    # Existing project extent, explicitly marked approximate if no administrative polygon exists.
    return box(120.90, 14.50, 121.08, 14.68), "configured Manila bbox (approximate; administrative boundary unavailable)"

def checkpoint(paths):
    """Commit each completed tile immediately so an interrupted run resumes safely."""
    import subprocess
    rel=[str(path.relative_to(ROOT)) for path in paths if path.exists()]
    if not rel: return
    subprocess.run(["git","add","--",*rel],cwd=ROOT,check=True)
    if subprocess.run(["git","diff","--cached","--quiet"],cwd=ROOT).returncode==0: return
    subprocess.run(["git","config","user.name","github-actions[bot]"],cwd=ROOT,check=True)
    subprocess.run(["git","config","user.email","41898282+github-actions[bot]@users.noreply.github.com"],cwd=ROOT,check=True)
    subprocess.run(["git","commit","-m","Checkpoint Manila Google Open Buildings temporal tile"],cwd=ROOT,check=True)
    subprocess.run(["git","push","origin","HEAD:main"],cwd=ROOT,check=True)

def ee_init():
    raw = os.environ.get("GEE_SERVICE_ACCOUNT_JSON", "").strip()
    project = os.environ.get("GEE_PROJECT_ID", "").strip()
    if not raw or not project:
        raise RuntimeError("Missing GitHub Actions secrets GEE_SERVICE_ACCOUNT_JSON and/or GEE_PROJECT_ID. Add an Earth Engine service-account JSON with access to the project, then rerun this workflow.")
    key_path = pathlib.Path(tempfile.gettempdir()) / "gee-service-account.json"
    key_path.write_text(raw, encoding="utf-8")
    service_account = json.loads(raw).get("client_email")
    if not service_account:
        raise RuntimeError("GEE_SERVICE_ACCOUNT_JSON is not a valid service-account key: client_email is missing")
    credentials = ee.ServiceAccountCredentials(service_account, str(key_path))
    ee.Initialize(credentials, project=project)

def tile_grid(poly):
    minx, miny, maxx, maxy = poly.bounds
    # Approximate metres per degree at Manila latitude.
    mx = 111320 * math.cos(math.radians(14.58))
    my = 110574
    dx, dy = TILE_M / mx, TILE_M / my
    x0, y0 = math.floor(minx/dx)*dx, math.floor(miny/dy)*dy
    ix0, ix1 = math.floor((minx-x0)/dx), math.floor((maxx-x0)/dx)
    iy0, iy1 = math.floor((miny-y0)/dy), math.floor((maxy-y0)/dy)
    result=[]
    for ix in range(ix0, ix1+1):
        for iy in range(iy0, iy1+1):
            west, south = x0+ix*dx, y0+iy*dy
            cell=box(west,south,west+dx,south+dy)
            if cell.intersects(poly):
                result.append((ix,iy,cell.intersection(poly),cell))
    return result

def download_tile(image, region, outpath):
    params = {
        "name": outpath.stem,
        "scale": SCALE_M,
        "crs": "EPSG:32651",
        "region": mapping(region),
        "format": "GEO_TIFF",
        "filePerBand": False,
    }
    url = image.getDownloadURL(params)
    req = Request(url, headers={"User-Agent": "Adam-World open-data importer"})
    with urlopen(req, timeout=240) as response:
        payload = response.read()
    if len(payload) > MAX_DOWNLOAD_BYTES:
        raise RuntimeError(f"Earth Engine returned {len(payload)} bytes; reduce tile size")
    if payload[:2] == b"PK":
        with zipfile.ZipFile(__import__("io").BytesIO(payload)) as zf:
            tif_names = [n for n in zf.namelist() if n.lower().endswith((".tif", ".tiff"))]
            if len(tif_names) != 1:
                raise RuntimeError(f"Expected one GeoTIFF in archive, got {tif_names}")
            payload = zf.read(tif_names[0])
    atomic_write(outpath, payload)

def raster_summary(paths):
    totals = {"pixels": 0, "presence_gt_0_5_pixels": 0, "height_valid_pixels": 0,
              "height_sum_m": 0.0, "height_max_m": None, "fractional_count_sum": 0.0}
    for path in paths:
        with rasterio.open(path) as ds:
            if ds.count != 3:
                raise RuntimeError(f"{path}: expected 3 bands, found {ds.count}")
            presence, height, fractional = ds.read()
            valid = np.isfinite(presence) & np.isfinite(height) & np.isfinite(fractional)
            mask = valid & (presence > 0.5)
            h = height[mask]
            totals["pixels"] += int(valid.sum())
            totals["presence_gt_0_5_pixels"] += int(mask.sum())
            totals["height_valid_pixels"] += int(np.isfinite(h).sum())
            if h.size:
                totals["height_sum_m"] += float(np.nansum(h))
                m=float(np.nanmax(h))
                totals["height_max_m"] = m if totals["height_max_m"] is None else max(totals["height_max_m"],m)
            totals["fractional_count_sum"] += float(np.nansum(fractional[valid]))
    if totals["height_valid_pixels"]:
        totals["height_mean_m_on_presence_gt_0_5"] = totals["height_sum_m"]/totals["height_valid_pixels"]
    return totals

def main():
    ap=argparse.ArgumentParser()
    ap.add_argument("--refresh", action="store_true")
    args=ap.parse_args()
    poly,boundary_source=load_boundary()
    ee_init()
    collection=ee.ImageCollection(COLLECTION)
    tiles=tile_grid(poly)
    if not tiles:
        raise RuntimeError("No Manila export tiles intersect the configured city boundary")
    OUT.mkdir(parents=True,exist_ok=True)
    inventory={
      "dataset":"Google Open Buildings 2.5D Temporal",
      "earth_engine_collection":COLLECTION,
      "city":"Manila, Philippines",
      "boundary_source":boundary_source,
      "years":list(YEARS),
      "bands":BANDS,
      "source_bands":"All three original bands retained without dropping source values",
      "export_scale_m":SCALE_M,
      "published_raster_grid_m":0.5,
      "effective_information_resolution_m":4,
      "tile_size_m":TILE_M,
      "tiles_expected":len(tiles),
      "license":["CC-BY-4.0","ODbL-1.0"],
      "source_imagery":"Copernicus Sentinel-2",
      "updated_at":datetime.now(timezone.utc).isoformat(),
      "status":"partial",
      "annual":{}
    }
    invpath=OUT/"source-inventory.json"
    if invpath.exists() and not args.refresh:
        try: inventory.update(json.loads(invpath.read_text(encoding="utf-8")))
        except Exception: pass
    for year in YEARS:
        start=f"{year}-01-01"; end=f"{year+1}-01-01"
        annual=collection.filterDate(start,end)
        image=ee.Image(annual.first()).select(BANDS).clip(ee.Geometry(mapping(poly)))
        # Fail explicitly if this annual snapshot is absent; never silently skip a year.
        image.bandNames().getInfo()
        year_dir=OUT/str(year); year_dir.mkdir(parents=True,exist_ok=True)
        completed=[]
        for ix,iy,clipped,cell in tiles:
            name=f"tile_{ix:+04d}_{iy:+04d}.tif"
            target=year_dir/name
            if target.exists() and not args.refresh:
                try:
                    with rasterio.open(target) as ds:
                        if ds.count==3 and ds.width>0 and ds.height>0:
                            completed.append(target); continue
                except Exception: target.unlink(missing_ok=True)
            print(f"DOWNLOAD {year} {name}",flush=True)
            # Use the full grid cell as region; the image itself is clipped to the city polygon.
            download_tile(image,cell,target)
            with rasterio.open(target) as ds:
                if ds.count!=3: raise RuntimeError(f"Invalid band count in {target}: {ds.count}")
            completed.append(target)
            checkpoint([target])
            print(f"SAVED AND CHECKPOINTED {target.relative_to(ROOT)} {target.stat().st_size}",flush=True)
        stats=raster_summary(completed)
        annual_record={
          "status":"complete" if len(completed)==len(tiles) else "partial",
          "tile_count":len(completed),
          "tiles":[{"file":p.name,"bytes":p.stat().st_size} for p in completed],
          "statistics":stats
        }
        inventory["annual"][str(year)]=annual_record
        inventory["updated_at"]=datetime.now(timezone.utc).isoformat()
        inventory["status"]="partial"
        atomic_write(invpath,json.dumps(inventory,indent=2).encode())
        checkpoint([invpath])
        print(f"YEAR COMPLETE {year}: {len(completed)}/{len(tiles)} tiles",flush=True)
    inventory["status"]="complete" if all(inventory["annual"].get(str(y),{}).get("status")=="complete" for y in YEARS) else "partial"
    inventory["updated_at"]=datetime.now(timezone.utc).isoformat()
    atomic_write(invpath,json.dumps(inventory,indent=2).encode())
    print("GOOGLE OPEN BUILDINGS TEMPORAL RESULT",json.dumps({"status":inventory["status"],"years":len(inventory["annual"]),"tiles_per_year":len(tiles)}),flush=True)
    if inventory["status"]!="complete": raise SystemExit(1)

if __name__=="__main__":
    main()
