#!/usr/bin/env python3
"""Import independent OpenFreeMap footprints and full OSM building details per city.

Outputs:
  data/facades/cities/<city>/building-footprints.geojson  OpenFreeMap/OpenMapTiles
  data/facades/cities/<city>/building-details.geojson     OSM Overpass tags + geometry

Panoramax facade photographs are harvested by fetch_catalogue_metadata.py; they
are evidence, not verified facade textures. Only human-verified assignments may
be baked into local facade GLBs.
"""
from __future__ import annotations
import argparse, json, math, os, subprocess, tempfile, time
from collections import defaultdict
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import mapbox_vector_tile
import osm2geojson
from shapely.geometry import shape, mapping
from shapely.ops import unary_union

ROOT = Path(__file__).resolve().parents[2]
CITY_ROOT = ROOT / "data/facades/cities"
TILEJSON = "https://tiles.openfreemap.org/planet/latest"
OVERPASS = (
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
)
EXTENT = 4096
ZOOM = 14

def bounds(city: Path):
    c = json.loads((city / "city.json").read_text(encoding="utf-8"))
    lat, lon = float(c["center"]["lat"]), float(c["center"]["lon"])
    r = float(c.get("discovery_radius_m", 650))
    dlat = r / 111320.0
    dlon = r / (111320.0 * max(0.01, math.cos(math.radians(lat))))
    return (lon-dlon, lat-dlat, lon+dlon, lat+dlat)

def tile_x(lon,z): return int((lon+180.0)/360.0*(2**z))
def tile_y(lat,z):
    lat=max(-85.05112878,min(85.05112878,lat))
    rad=math.radians(lat)
    return int((1.0-math.asinh(math.tan(rad))/math.pi)/2.0*(2**z))

def tile_point(tx,ty,x,y,z):
    n=2**z
    lon=(tx+x/EXTENT)/n*360.0-180.0
    merc=math.pi*(1.0-2.0*(ty+y/EXTENT)/n)
    return [lon, math.degrees(math.atan(math.sinh(merc)))]

def coords_to_lonlat(v,tx,ty,z):
    if isinstance(v,(list,tuple)) and len(v)>=2 and isinstance(v[0],(int,float)) and isinstance(v[1],(int,float)):
        return tile_point(tx,ty,v[0],v[1],z)
    if isinstance(v,(list,tuple)): return [coords_to_lonlat(x,tx,ty,z) for x in v]
    return v

def get_bytes(url):
    req=Request(url,headers={"User-Agent":"Adam-World-building-source-import/1.0"})
    with urlopen(req,timeout=60) as r: return r.read()

def get_json(url):
    return json.loads(get_bytes(url).decode("utf-8"))

def atomic_json(path,obj):
    path.parent.mkdir(parents=True,exist_ok=True)
    fd,tmp=tempfile.mkstemp(prefix=path.name+".",dir=path.parent)
    try:
        with os.fdopen(fd,"w",encoding="utf-8") as f:
            json.dump(obj,f,ensure_ascii=False,separators=(",",":")); f.write("\n")
        os.replace(tmp,path)
    finally:
        if os.path.exists(tmp): os.unlink(tmp)

def checkpoint(path,city):
    rel=str(path.relative_to(ROOT))
    subprocess.run(["git","add",rel],cwd=ROOT,check=True)
    if subprocess.run(["git","diff","--cached","--quiet"],cwd=ROOT).returncode:
        return
    subprocess.run(["git","commit","-m",f"Save {city.name} building source data"],cwd=ROOT,check=True)
    subprocess.run(["git","pull","--rebase","origin","main"],cwd=ROOT,check=True)
    subprocess.run(["git","push","origin","HEAD:main"],cwd=ROOT,check=True)

def import_openfreemap(city,bbox,refresh,git_checkpoint):
    out=city/"building-footprints.geojson"
    if out.exists() and not refresh:
        try:
            old=json.loads(out.read_text(encoding="utf-8"))
            if old.get("source_bounds_wsen")==list(bbox) and old.get("source")=="OpenFreeMap/OpenMapTiles vector tiles":
                print(f"KEEP {city.name}: {len(old.get('features',[]))} OpenFreeMap footprints",flush=True)
                return
        except Exception: pass
    meta=get_json(TILEJSON)
    template=(meta.get("tiles") or ["https://tiles.openfreemap.org/planet/latest/{z}/{x}/{y}.pbf"])[0]
    z=min(ZOOM,int(meta.get("maxzoom",ZOOM)))
    west,south,east,north=bbox
    groups=defaultdict(list); props={}
    fetched=0
    for x in range(tile_x(west,z),tile_x(east,z)+1):
        for y in range(tile_y(north,z),tile_y(south,z)+1):
            url=template.replace("{z}",str(z)).replace("{x}",str(x)).replace("{y}",str(y))
            try:
                tile=mapbox_vector_tile.decode(get_bytes(url),default_options={"y_coord_down":True})
                fetched+=1
            except Exception as exc:
                print(f"WARN {city.name}: tile {z}/{x}/{y}: {exc}",flush=True); continue
            layer=tile.get("building") or {}
            for f in layer.get("features",[]):
                g=f.get("geometry") or {}
                if g.get("type") not in ("Polygon","MultiPolygon"): continue
                p=dict(f.get("properties") or {})
                try: h=float(p.get("render_height") or 0)
                except (TypeError,ValueError): h=0
                if h<=0: continue
                geom=shape({"type":g["type"],"coordinates":coords_to_lonlat(g.get("coordinates"),x,y,z)})
                if geom.is_empty: continue
                fid=p.get("osm_id") or p.get("osm_way_id") or f.get("id")
                key=str(fid) if fid is not None else f"xy:{round(geom.centroid.x,6)}:{round(geom.centroid.y,6)}"
                groups[key].append(geom); props[key]=p
    features=[]
    for key,parts in groups.items():
        try: geom=unary_union(parts)
        except Exception: continue
        if geom.is_empty or geom.geom_type not in ("Polygon","MultiPolygon"): continue
        p=props[key]
        features.append({"type":"Feature","id":f"openfreemap/{key}","properties":{
            **p,"source_feature_id":key,"render_height_m":p.get("render_height"),
            "render_min_height_m":p.get("render_min_height"),
            "source":"OpenFreeMap/OpenMapTiles","underlying_data":"OpenStreetMap"
        },"geometry":mapping(geom)})
    if not features: raise RuntimeError("OpenFreeMap returned no usable building polygons; old output retained")
    fc={"type":"FeatureCollection","name":f"{city.name} building footprints",
        "source":"OpenFreeMap/OpenMapTiles vector tiles",
        "source_url":TILEJSON,"source_bounds_wsen":list(bbox),"zoom":z,"tiles_fetched":fetched,
        "attribution":"OpenFreeMap © OpenMapTiles; data from OpenStreetMap",
        "license":"OpenStreetMap data ODbL 1.0; OpenFreeMap/OpenMapTiles attribution required",
        "feature_count":len(features),"features":features}
    atomic_json(out,fc)
    print(f"SAVED {city.name}: {len(features)} OpenFreeMap footprints from {fetched} tiles",flush=True)
    if git_checkpoint: checkpoint(out,city)

def osm_query(b):
    w,s,e,n=b
    box=f"{s:.7f},{w:.7f},{n:.7f},{e:.7f}"
    return f"""[out:json][timeout:240];
(
  way["building"]({box});
  relation["building"]({box});
  way["building:part"]({box});
  relation["building:part"]({box});
);
out body geom;
"""

def fetch_overpass(query):
    data=urlencode({"data":query}).encode()
    errors=[]
    for endpoint in OVERPASS:
        for attempt in range(3):
            try:
                req=Request(endpoint,data=data,headers={"User-Agent":"Adam-World-building-details/1.0","Content-Type":"application/x-www-form-urlencoded"})
                with urlopen(req,timeout=270) as r:
                    obj=json.loads(r.read().decode("utf-8"))
                if obj.get("remark"): raise RuntimeError(obj["remark"])
                return obj,endpoint
            except Exception as exc:
                errors.append(f"{endpoint}: {exc}"); time.sleep(attempt+1)
    raise RuntimeError("Overpass failed: "+" | ".join(errors))

def import_osm_details(city,bbox,refresh,git_checkpoint):
    out=city/"building-details.geojson"
    if out.exists() and not refresh:
        try:
            old=json.loads(out.read_text(encoding="utf-8"))
            if old.get("source_bounds_wsen")==list(bbox) and old.get("source")=="OpenStreetMap Overpass":
                print(f"KEEP {city.name}: {len(old.get('features',[]))} OSM building detail features",flush=True)
                return
        except Exception: pass
    raw,endpoint=fetch_overpass(osm_query(bbox))
    fc=osm2geojson.json2geojson(raw)
    features=[]
    for f in fc.get("features",[]):
        p=f.get("properties") or {}
        tags=p.get("tags") if isinstance(p.get("tags"),dict) else p
        if not (tags.get("building") or tags.get("building:part")): continue
        if not f.get("geometry"): continue
        osm_type=p.get("type") or p.get("osm_type") or "feature"
        osm_id=p.get("id") or p.get("osm_id")
        p=dict(p); p["tags"]=tags
        p["osm_feature_id"]=f"{osm_type}/{osm_id}" if osm_id is not None else f.get("id")
        p["source"]="OpenStreetMap"
        f["properties"]=p; features.append(f)
    if not features: raise RuntimeError("Overpass returned no building/building-part geometries; old output retained")
    result={"type":"FeatureCollection","name":f"{city.name} OSM building and building-part details",
        "source":"OpenStreetMap Overpass","source_endpoint":endpoint,"source_bounds_wsen":list(bbox),
        "source_timestamp_osm_base":(raw.get("osm3s") or {}).get("timestamp_osm_base"),
        "attribution":"© OpenStreetMap contributors","license":"ODbL 1.0",
        "includes":["building=*","building:part=*","all available OSM tags","polygon geometry"],
        "feature_count":len(features),"features":features}
    atomic_json(out,result)
    print(f"SAVED {city.name}: {len(features)} OSM building/building-part detail features",flush=True)
    if git_checkpoint: checkpoint(out,city)

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--city")
    p.add_argument("--refresh",action="store_true")
    p.add_argument("--git-checkpoint",action="store_true")
    p.add_argument("--cities-root",type=Path,default=CITY_ROOT)
    a=p.parse_args()
    cities=[a.cities_root/a.city] if a.city else sorted(x for x in a.cities_root.iterdir() if x.is_dir())
    failures=[]
    for city in cities:
        if not (city/"city.json").exists():
            print(f"SKIP {city.name}: missing city.json",flush=True); continue
        try:
            bbox=bounds(city)
        except Exception as exc:
            print(f"ERROR {city.name}: invalid city config: {exc}",flush=True)
            failures.append(f"{city.name}/config")
            continue
        for label,fn in (("footprints",import_openfreemap),("details",import_osm_details)):
            try:
                fn(city,bbox,a.refresh,a.git_checkpoint)
            except Exception as exc:
                print(f"ERROR {city.name}/{label}: {exc}",flush=True)
                failures.append(f"{city.name}/{label}")
        time.sleep(2)
    if failures:
        print("FAILED SOURCE STAGES: "+", ".join(failures),flush=True)
        raise SystemExit(1)

if __name__=="__main__": main()
