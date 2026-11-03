#!/usr/bin/env python3
"""Import independent, bbox-clipped footprint supplements for Tabarbour.

Sources are optional and never replace existing data:
- Microsoft Global ML Building Footprints (CDLA Permissive 2.0)
- HDX "Buildings of Jordan" package, only when an exact public dataset/resource is found.
All downloads and derived files remain inside data/offline-world/tabarbour/sources/.
"""
from __future__ import annotations
import csv, gzip, io, json, math, os, tempfile, urllib.request, urllib.parse
from pathlib import Path
from shapely.geometry import shape, mapping, box

ROOT=Path(__file__).resolve().parents[2]
CITY=ROOT/"data/offline-world/tabarbour"
SOURCES=CITY/"sources"
UA="Adam-World-Tabarbour-footprint-import/1.0"
MS_LINKS="https://bfppub.blob.core.windows.net/$web/2026-08-13/dataset-links.csv"
HDX_API="https://data.humdata.org/api/3/action/package_search"

def fetch(url, timeout=90):
    req=urllib.request.Request(url,headers={"User-Agent":UA})
    with urllib.request.urlopen(req,timeout=timeout) as r: return r.read()

def atomic(path,obj):
    path.parent.mkdir(parents=True,exist_ok=True)
    fd,tmp=tempfile.mkstemp(prefix=path.name+".",dir=path.parent)
    try:
        with os.fdopen(fd,"w",encoding="utf-8") as f:
            json.dump(obj,f,ensure_ascii=False,separators=(",",":")); f.write("\n")
        os.replace(tmp,path)
    finally:
        if os.path.exists(tmp): os.unlink(tmp)

def bbox():
    c=json.loads((CITY/"city.json").read_text())
    lat,lon=float(c["center"]["lat"]),float(c["center"]["lon"])
    r=float(c.get("discovery_radius_m",3500))
    dy=r/111320.0; dx=r/(111320.0*max(.01,math.cos(math.radians(lat))))
    return (lon-dx,lat-dy,lon+dx,lat+dy)

def tile_xy(lon,lat,z):
    n=2**z
    x=int((lon+180)/360*n)
    lat=max(-85.05112878,min(85.05112878,lat))
    rad=math.radians(lat)
    y=int((1-math.asinh(math.tan(rad))/math.pi)/2*n)
    return max(0,min(n-1,x)),max(0,min(n-1,y))

def quadkey(x,y,z):
    out=""
    for i in range(z,0,-1):
        bit=1<<(i-1); digit=0
        if x&bit: digit+=1
        if y&bit: digit+=2
        out+=str(digit)
    return out

def covering_quadkeys(b,z=9):
    w,s,e,n=b; x0,y0=tile_xy(w,n,z); x1,y1=tile_xy(e,s,z)
    return {quadkey(x,y,z) for x in range(min(x0,x1),max(x0,x1)+1)
            for y in range(min(y0,y1),max(y0,y1)+1)}

def save_fc(path,features,source,license_name,extra=None):
    obj={"type":"FeatureCollection","name":path.stem,"source":source,
         "license":license_name,"source_bounds_wsen":list(bbox()),
         "attribution":source,"feature_count":len(features),"features":features}
    if extra: obj.update(extra)
    atomic(path,obj)

def import_microsoft(b):
    out=SOURCES/"microsoft"/"buildings.geojson"
    if out.is_file():
        try:
            old=json.loads(out.read_text())
            if old.get("source_bounds_wsen")==list(b) and old.get("source")=="Microsoft Global ML Building Footprints":
                print("KEEP Microsoft source: existing matching bbox"); return len(old.get("features",[]))
        except Exception: pass
    try:
        raw=fetch(MS_LINKS)
        rows=list(csv.DictReader(io.StringIO(raw.decode("utf-8-sig"))))
        keys=covering_quadkeys(b)
        selected=[r for r in rows if str(r.get("Location","")).strip().lower() in {"jordan","hashemite kingdom of jordan"}
                  and str(r.get("QuadKey","")).strip() in keys and r.get("Url")]
        features=[]
        clip=box(*b)
        for row in selected:
            url=row["Url"].strip()
            compressed=fetch(url,timeout=180)
            with gzip.GzipFile(fileobj=io.BytesIO(compressed)) as stream:
                for line in io.TextIOWrapper(stream,encoding="utf-8"):
                    if not line.strip(): continue
                    f=json.loads(line)
                    geom=f.get("geometry")
                    if not geom: continue
                    try: g=shape(geom)
                    except Exception: continue
                    if g.is_empty or not g.is_valid: g=g.buffer(0)
                    if g.is_empty or not g.intersects(clip): continue
                    clipped=g.intersection(clip)
                    if clipped.is_empty or clipped.geom_type not in ("Polygon","MultiPolygon"): continue
                    p=dict(f.get("properties") or {})
                    p.update({"source":"Microsoft Global ML Building Footprints",
                              "source_quadkey":row.get("QuadKey"),"source_upload_date":row.get("UploadDate"),
                              "source_url":url,"license":"CDLA Permissive 2.0"})
                    features.append({"type":"Feature","id":"microsoft:"+str(f.get("id") or len(features)),
                                     "properties":p,"geometry":mapping(clipped)})
        save_fc(out,features,"Microsoft Global ML Building Footprints","CDLA Permissive 2.0",
                {"dataset_links_url":MS_LINKS,"quadkeys_checked":sorted(keys),
                 "matched_quadkeys":[r.get("QuadKey") for r in selected]})
        print(f"Microsoft: {len(features)} clipped footprints from {len(selected)} Jordan tiles")
        return len(features)
    except Exception as exc:
        # Preserve old data; publish a machine-readable unavailable status.
        report={"source":"Microsoft Global ML Building Footprints","status":"unavailable",
                "reason":str(exc),"source_bounds_wsen":list(b),"dataset_links_url":MS_LINKS}
        atomic(SOURCES/"microsoft"/"import-status.json",report)
        print("Microsoft source unavailable:",exc)
        return 0

def import_hdx(b):
    out=SOURCES/"jordan"/"buildings.geojson"
    if out.is_file():
        try:
            old=json.loads(out.read_text())
            if old.get("source_bounds_wsen")==list(b) and old.get("source")=="HDX Buildings of Jordan":
                print("KEEP Jordan national source: existing matching bbox"); return len(old.get("features",[]))
        except Exception: pass
    try:
        query=urllib.parse.urlencode({"q":"Buildings of Jordan","rows":100})
        data=json.loads(fetch(HDX_API+"?"+query).decode("utf-8"))
        packages=(data.get("result") or {}).get("results") or []
        exact=[p for p in packages if str(p.get("title") or "").strip().lower()=="buildings of jordan"]
        if not exact: raise RuntimeError("No exact public HDX dataset titled 'Buildings of Jordan' was returned")
        clip=box(*b); features=[]; chosen=[]
        import geopandas as gpd
        for package in exact:
            for resource in package.get("resources") or []:
                fmt=str(resource.get("format") or "").lower()
                name=str(resource.get("name") or "").lower()
                url=resource.get("url")
                if not url or not (fmt in {"gpkg","geopackage","shp","shapefile","geojson","zip"} or
                                   name.endswith((".gpkg",".geojson",".zip"))): continue
                try:
                    frame=gpd.read_file(url,bbox=b)
                    if frame.crs and str(frame.crs).lower() not in {"epsg:4326","ogc:crs84"}:
                        frame=frame.to_crs("EPSG:4326")
                    for idx,row in frame.iterrows():
                        geom=row.geometry
                        if geom is None or geom.is_empty: continue
                        clipped=geom.intersection(clip)
                        if clipped.is_empty or clipped.geom_type not in ("Polygon","MultiPolygon"): continue
                        props={str(k):v for k,v in row.drop(labels=["geometry"]).to_dict().items()
                               if v is not None and str(v)!="nan"}
                        props.update({"source":"HDX Buildings of Jordan","source_resource":resource.get("name"),
                                      "source_url":url,"license":package.get("license_title") or package.get("license_id") or "See HDX dataset metadata"})
                        features.append({"type":"Feature","id":"hdx-jordan:"+str(props.get("id") or idx),
                                         "properties":props,"geometry":mapping(clipped)})
                    chosen.append({"package":package.get("name"),"resource":resource.get("name"),
                                   "url":url,"format":resource.get("format")})
                except Exception as exc:
                    print("HDX resource skipped:",resource.get("name"),exc)
        if not chosen: raise RuntimeError("Exact HDX package found, but no supported readable footprint resource was available")
        save_fc(out,features,"HDX Buildings of Jordan","See source dataset metadata",
                {"dataset_resources":chosen,"dataset_page":"https://data.humdata.org/"})
        print(f"HDX Jordan: {len(features)} clipped footprints")
        return len(features)
    except Exception as exc:
        atomic(SOURCES/"jordan"/"import-status.json",{
            "source":"HDX Buildings of Jordan","status":"unavailable","reason":str(exc),
            "source_bounds_wsen":list(b),"dataset_search":"Buildings of Jordan"})
        print("HDX Jordan source unavailable:",exc)
        return 0

def main():
    b=bbox()
    a=import_microsoft(b)
    j=import_hdx(b)
    report={"city":"tabarbour","bbox_wsen":list(b),
            "sources":{"microsoft_global_ml":{"features":a,"license":"CDLA Permissive 2.0"},
                       "hdx_buildings_of_jordan":{"features":j,"license":"See HDX dataset metadata"}},
            "policy":"Keep existing footprints; match by explicit OSM identity then high-IoU geometry; ambiguous overlaps go to review; unmatched valid polygons are additions. No height/name/number is inferred from geometry."}
    atomic(SOURCES/"import-report.json",report)
    print(json.dumps(report,ensure_ascii=False))
if __name__=="__main__": main()
