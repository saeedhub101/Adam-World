#!/usr/bin/env python3
"""Import OSM entrance, door and address nodes for Tabarbour only."""
from __future__ import annotations
import argparse, json, math, os, subprocess, tempfile, time
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
CITY = ROOT / "data/offline-world/tabarbour"
ENDPOINTS = ("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter")

def atomic(path, obj):
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(prefix=path.name+".", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(obj, f, ensure_ascii=False, separators=(",", ":")); f.write("\n")
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp): os.unlink(tmp)

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--refresh", action="store_true")
    p.add_argument("--git-checkpoint", action="store_true")
    a=p.parse_args()
    cfg=json.loads((CITY/"city.json").read_text(encoding="utf-8"))
    lat=float(cfg["center"]["lat"]); lon=float(cfg["center"]["lon"]); radius=float(cfg.get("discovery_radius_m",3500))
    dlat=radius/111320.0; dlon=radius/(111320.0*max(.01,math.cos(math.radians(lat))))
    w,s,e,n=lon-dlon,lat-dlat,lon+dlon,lat+dlat
    out=CITY/"osm-access-points.geojson"
    if out.exists() and not a.refresh:
        try:
            old=json.loads(out.read_text(encoding="utf-8"))
            if old.get("source_bounds_wsen")==[w,s,e,n] and old.get("source")=="OpenStreetMap Overpass entrances and doors":
                print("KEEP OSM access points:",len(old.get("features",[]))); return
        except Exception: pass
    box=f"{s:.7f},{w:.7f},{n:.7f},{e:.7f}"
    query=f"""[out:json][timeout:240];
(
 node["entrance"]({box});
 node["door"]({box});
 node["addr:housenumber"]({box});
 node["addr:housename"]({box});
 way["entrance"]({box});
 way["door"]({box});
);
out center tags;"""
    errors=[]; raw=None; used=None
    for endpoint in ENDPOINTS:
        for attempt in range(3):
            try:
                req=Request(endpoint,data=urlencode({"data":query}).encode(),headers={"User-Agent":"Adam-World-Tabarbour-Access/1.0","Content-Type":"application/x-www-form-urlencoded"})
                with urlopen(req,timeout=270) as r: raw=json.loads(r.read().decode("utf-8"))
                if raw.get("remark"): raise RuntimeError(raw["remark"])
                used=endpoint; break
            except Exception as exc:
                errors.append(f"{endpoint}: {exc}"); time.sleep(attempt+1)
        if raw is not None: break
    if raw is None: raise RuntimeError("OSM entrance query failed: "+" | ".join(errors))
    features=[]
    for el in raw.get("elements",[]):
        tags=el.get("tags") or {}
        if not any(k in tags for k in ("entrance","door","addr:housenumber","addr:housename")): continue
        if el.get("type")=="node" and el.get("lon") is not None and el.get("lat") is not None:
            coords=[el["lon"],el["lat"]]
        elif el.get("center"):
            coords=[el["center"]["lon"],el["center"]["lat"]]
        else: continue
        props={**tags,"osm_type":el.get("type"),"osm_id":el.get("id"),"osm_feature_id":f'{el.get("type")}/{el.get("id")}',"source":"OpenStreetMap"}
        features.append({"type":"Feature","id":props["osm_feature_id"],"properties":props,"geometry":{"type":"Point","coordinates":coords}})
    fc={"type":"FeatureCollection","name":"Tabarbour OSM entrances doors and address points","source":"OpenStreetMap Overpass entrances and doors","source_endpoint":used,"source_bounds_wsen":[w,s,e,n],"source_timestamp_osm_base":(raw.get("osm3s") or {}).get("timestamp_osm_base"),"feature_count":len(features),"features":features,"attribution":"© OpenStreetMap contributors","license":"ODbL 1.0"}
    atomic(out,fc)
    print(f"SAVED OSM access points: {len(features)}")
    if a.git_checkpoint:
        rel=str(out.relative_to(ROOT)); subprocess.run(["git","add",rel],cwd=ROOT,check=True)
        if subprocess.run(["git","diff","--cached","--quiet"],cwd=ROOT).returncode:
            subprocess.run(["git","commit","-m","Save Tabarbour OSM entrances and doors"],cwd=ROOT,check=True)
            subprocess.run(["git","pull","--rebase","origin","main"],cwd=ROOT,check=True)
            subprocess.run(["git","push","origin","HEAD:main"],cwd=ROOT,check=True)

if __name__=="__main__": main()
