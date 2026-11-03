#!/usr/bin/env python3
"""Download all original Google Open Buildings 2.5D Temporal tiles intersecting Manila.

Uses Google's public GeoTIFF URLs (no Earth Engine account required), scans the
Philippines tile index for every year 2016-2023, and retains the original 3-band
0.5 m GeoTIFFs without resampling.
"""
from __future__ import annotations
import argparse, hashlib, json, os, pathlib, tempfile, time, urllib.request, zipfile
from datetime import datetime, timezone
from urllib.error import HTTPError, URLError
from urllib.parse import urlparse

import rasterio
from rasterio.warp import transform_bounds
from shapely.geometry import shape, box

ROOT=pathlib.Path(__file__).resolve().parents[1]
CITY_DIR=ROOT/"data/full-city-imports/manila"
OUT=CITY_DIR/"03_buildings_google_temporal"
YEARS=range(2016,2024)
URL_ARCHIVES=[
 "https://raw.githubusercontent.com/saiga143/google-2.5d-bulk-download/main/urls_part1.zip",
 "https://raw.githubusercontent.com/saiga143/google-2.5d-bulk-download/main/urls_part2.zip",
]
INDEX_REPO="https://github.com/saiga143/google-2.5d-bulk-download"
DATASET="Google Open Buildings 2.5D Temporal"
BANDS=["building_fractional_count","building_height","building_presence"]

def atomic_write(path,data):
 path.parent.mkdir(parents=True,exist_ok=True)
 tmp=path.with_suffix(path.suffix+".tmp")
 tmp.write_bytes(data);os.replace(tmp,path)

def checkpoint(paths):
 import subprocess
 rel=[str(p.relative_to(ROOT)) for p in paths if p.exists()]
 if not rel:return
 subprocess.run(["git","add","--",*rel],cwd=ROOT,check=True)
 if subprocess.run(["git","diff","--cached","--quiet"],cwd=ROOT).returncode==0:return
 subprocess.run(["git","config","user.name","github-actions[bot]"],cwd=ROOT,check=True)
 subprocess.run(["git","config","user.email","41898282+github-actions[bot]@users.noreply.github.com"],cwd=ROOT,check=True)
 subprocess.run(["git","commit","-m","Checkpoint Manila Google Open Buildings temporal tiles"],cwd=ROOT,check=True)
 subprocess.run(["git","push","origin","HEAD:main"],cwd=ROOT,check=True)

def load_boundary():
 p=CITY_DIR/"01_admin_boundary/city-boundary.geojson"
 if p.is_file():
  fc=json.loads(p.read_text(encoding="utf-8"))
  gs=[shape(f["geometry"]) for f in fc.get("features",[]) if f.get("geometry")]
  if gs:
   from shapely.ops import unary_union
   return unary_union(gs),"existing city-boundary.geojson"
 return box(120.90,14.50,121.08,14.68),"configured Manila bbox (approximate; administrative polygon unavailable)"

def fetch_bytes(url,timeout=180):
 req=urllib.request.Request(url,headers={"User-Agent":"Adam-World open-data importer"})
 with urllib.request.urlopen(req,timeout=timeout) as response:return response.read()

def load_url_lists(work):
 work.mkdir(parents=True,exist_ok=True)
 result={}
 for i,url in enumerate(URL_ARCHIVES,1):
  archive=work/f"urls_part{i}.zip"
  if not archive.exists():
   data=fetch_bytes(url,300);atomic_write(archive,data)
  with zipfile.ZipFile(archive) as z:
   for name in z.namelist():
    if name.lower().endswith(".txt"):
     base=pathlib.Path(name).name
     if base.startswith("PHL_") and base.endswith(".txt"):
      result[base]=[line.strip() for line in z.read(name).decode("utf-8").splitlines() if line.strip()]
 if not result:raise RuntimeError("Could not find Philippines PHL_YYYY URL lists in Google's published URL index archives")
 return result

def remote_bounds(url):
 # GDAL performs HTTP range reads of GeoTIFF headers; the raster itself is not downloaded here.
 vsi="/vsicurl/"+url
 with rasterio.Env(GDAL_DISABLE_READDIR_ON_OPEN="EMPTY_DIR",CPL_VSIL_CURL_ALLOWED_EXTENSIONS=".tif,.tiff"):
  with rasterio.open(vsi) as ds:
   if ds.crs is None:raise RuntimeError("remote GeoTIFF has no CRS")
   b=ds.bounds
   if ds.crs.to_epsg()==4326:return (b.left,b.bottom,b.right,b.top),ds.count
   return transform_bounds(ds.crs,"EPSG:4326",b.left,b.bottom,b.right,b.top,densify_pts=21),ds.count

def download(url,target):
 target.parent.mkdir(parents=True,exist_ok=True)
 tmp=target.with_name(target.stem+".part"+target.suffix)
 last=None
 for attempt in range(1,4):
  try:
   req=urllib.request.Request(url,headers={"User-Agent":"Adam-World open-data importer"})
   with urllib.request.urlopen(req,timeout=300) as response, tmp.open("wb") as out:
    while True:
     chunk=response.read(1024*1024)
     if not chunk:break
     out.write(chunk)
   with rasterio.open(tmp) as ds:
    if ds.count!=3:raise RuntimeError(f"Expected 3 bands, found {ds.count}")
    if ds.width<=0 or ds.height<=0:raise RuntimeError("empty GeoTIFF")
   os.replace(tmp,target);return
  except Exception as ex:
   last=ex;tmp.unlink(missing_ok=True);time.sleep(attempt*3)
 raise RuntimeError(f"download failed after 3 attempts: {last}")

def main():
 ap=argparse.ArgumentParser()
 ap.add_argument("--refresh",action="store_true")
 args=ap.parse_args()
 OUT.mkdir(parents=True,exist_ok=True)
 poly,boundary_source=load_boundary()
 work=pathlib.Path(tempfile.gettempdir())/"google-open-buildings-url-index"
 url_lists=load_url_lists(work)
 inventory={
  "dataset":DATASET,"source":"Google Research public GeoTIFF tiles",
  "source_url":"https://data.humdata.org/dataset/google-open-buildings-temporal",
  "url_index_repository":INDEX_REPO,"city":"Manila, Philippines",
  "boundary_source":boundary_source,"years":list(YEARS),"bands":BANDS,
  "band_order_in_source_geotiff":"building_fractional_count, building_height, building_presence",
  "native_raster_pixel_size_m":0.5,"effective_information_resolution_m":4,
  "license":["CC-BY-4.0","ODbL-1.0"],"imagery":"Copernicus Sentinel-2",
  "updated_at":datetime.now(timezone.utc).isoformat(),"status":"partial","annual":{}
 }
 invpath=OUT/"source-inventory.json"
 if invpath.exists() and not args.refresh:
  try: inventory.update(json.loads(invpath.read_text(encoding="utf-8")))
  except Exception: pass
 all_failed=[]
 for year in YEARS:
  key=f"PHL_{year}.txt"
  urls=url_lists.get(key)
  if urls is None:
   all_failed.append(f"{year}: missing {key}");continue
  ydir=OUT/str(year);ydir.mkdir(parents=True,exist_ok=True)
  scanned=0;intersecting=[];scan_errors=[]
  print(f"SCAN Philippines {year}: {len(urls)} source tiles",flush=True)
  for url in urls:
   scanned+=1
   try:
    bounds,bands=remote_bounds(url)
    if bands!=3:raise RuntimeError(f"source tile has {bands} bands, expected 3")
    if box(*bounds).intersects(poly):intersecting.append((url,bounds))
   except Exception as ex:
    scan_errors.append({"url":url,"error":str(ex)[:500]})
    if len(scan_errors)<=10:print("SCAN ERROR",year,url,str(ex)[:200],flush=True)
  completed=[];download_errors=[]
  for url,bounds in intersecting:
   filename=pathlib.Path(urlparse(url).path).name
   if not filename.lower().endswith((".tif",".tiff")):filename=hashlib.sha256(url.encode()).hexdigest()+".tif"
   target=ydir/filename
   if target.exists() and not args.refresh:
    try:
     with rasterio.open(target) as ds:
      if ds.count==3 and ds.width>0 and ds.height>0:
       completed.append(target);continue
    except Exception:target.unlink(missing_ok=True)
   try:
    print("DOWNLOAD",year,filename,flush=True)
    download(url,target);completed.append(target)
    record={"year":year,"source_url":url,"file":str(target.relative_to(ROOT)),
            "bytes":target.stat().st_size,"bounds_wgs84":list(bounds),"bands":BANDS}
    sidecar=target.with_suffix(".source.json")
    atomic_write(sidecar,json.dumps(record,indent=2).encode())
    checkpoint([target,sidecar])
    print("SAVED AND CHECKPOINTED",target.relative_to(ROOT),target.stat().st_size,flush=True)
   except Exception as ex:
    download_errors.append({"url":url,"error":str(ex)[:500]})
  year_status="complete" if intersecting and not scan_errors and not download_errors and len(completed)==len(intersecting) else "partial"
  inventory["annual"][str(year)]={
   "status":year_status,"source_url_count":len(urls),"scanned_url_count":scanned,
   "intersecting_tile_count":len(intersecting),"downloaded_tile_count":len(completed),
   "scan_errors":scan_errors,"download_errors":download_errors,
   "tiles":[{"file":p.name,"bytes":p.stat().st_size} for p in completed]
  }
  inventory["updated_at"]=datetime.now(timezone.utc).isoformat()
  inventory["status"]="partial"
  atomic_write(invpath,json.dumps(inventory,indent=2).encode());checkpoint([invpath])
  print(f"YEAR {year}: {year_status}; source={len(urls)}, intersecting={len(intersecting)}, saved={len(completed)}, scan_errors={len(scan_errors)}, download_errors={len(download_errors)}",flush=True)
  if year_status!="complete":all_failed.append(str(year))
 inventory["status"]="complete" if all(inventory["annual"].get(str(y),{}).get("status")=="complete" for y in YEARS) else "partial"
 inventory["updated_at"]=datetime.now(timezone.utc).isoformat()
 atomic_write(invpath,json.dumps(inventory,indent=2).encode());checkpoint([invpath])
 print("GOOGLE OPEN BUILDINGS TEMPORAL RESULT",json.dumps({"status":inventory["status"],"years":len(inventory["annual"]),"failures":all_failed}),flush=True)
 if inventory["status"]!="complete":raise SystemExit(1)

if __name__=="__main__":main()
