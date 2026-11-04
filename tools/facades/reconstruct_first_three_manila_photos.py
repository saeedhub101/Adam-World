#!/usr/bin/env python3
"""Resolve only the first three saved Manila Panoramax photos to their own building and street.

No downloads, no city-wide generation, and no adjacent-building propagation.
A building is selected only when the saved camera position and heading intersect
its footprint. Missing metadata is reported as unmatched rather than guessed.
"""
import json, math
from pathlib import Path
from pyproj import Transformer
from shapely.geometry import Point, LineString, shape
from shapely.ops import transform

ROOT=Path(__file__).resolve().parents[2]
CITY=ROOT/"data/facades/cities/manila"
IMAGES=CITY/"facade-review/images"
PANO=CITY/"panoramax-items.geojson"
BUILDINGS=CITY/"layers/building-layer.geojson"
ROADS=CITY/"layers/transportation.geojson"
OUT=CITY/"first_three_manila_photo_targets.json"
GEO=CITY/"first_three_manila_photo_targets.geojson"
IDS=[
"0cb04844-65a3-418b-beb8-7d6a65915903",
"134abd91-1ba0-43d5-b1e5-71bb394f71a7",
"150e2cd0-7c24-42f3-83c7-71e8f551e1fc"]
TO_M=Transformer.from_crs("EPSG:4326","EPSG:3857",always_xy=True).transform
TO_LL=Transformer.from_crs("EPSG:3857","EPSG:4326",always_xy=True).transform
HEAD=("compass_direction","heading","direction","azimuth","camera_direction","view_direction")

def collection(path):
    if not path.is_file() or not path.stat().st_size: raise RuntimeError("Missing local source: "+str(path))
    obj=json.loads(path.read_text(encoding="utf-8"))
    if obj.get("type")!="FeatureCollection": raise RuntimeError("Invalid GeoJSON: "+str(path))
    return obj["features"]

def props(f): return f.get("properties") or {}
def fid(f):
    p=props(f)
    return str(p.get("id") or p.get("image_id") or p.get("panoramax_id") or f.get("id") or "").split("/")[-1]
def heading(f):
    p=props(f)
    for k in HEAD:
        try:
            v=float(p.get(k))
            if math.isfinite(v): return v%360
        except (TypeError,ValueError): pass
    return None
def camera(f):
    g=f.get("geometry") or {}
    if g.get("type")=="Point":
        x,y=TO_M(*g["coordinates"][:2]); return Point(x,y)
    p=props(f)
    lon=p.get("longitude",p.get("lon",p.get("lng")))
    lat=p.get("latitude",p.get("lat"))
    if lon is None or lat is None: return None
    x,y=TO_M(float(lon),float(lat)); return Point(x,y)
def ray_point(c,b,d):
    a=math.radians(b)
    return Point(c.x+math.sin(a)*d,c.y+math.cos(a)*d)
def target(c,b,features,kind,limit):
    ray=LineString([ray_point(c,b,0),ray_point(c,b,limit)])
    best=None; best_d=limit+1
    for f in features:
        if not f.get("geometry"): continue
        try:
            g=transform(TO_M,shape(f["geometry"]))
            if kind=="building" and g.geom_type not in ("Polygon","MultiPolygon"): continue
            if kind=="road" and g.geom_type not in ("LineString","MultiLineString"): continue
            hit=ray.intersection(g)
            if hit.is_empty: continue
            d=c.distance(hit if hit.geom_type=="Point" else hit.representative_point())
            if 2<d<best_d: best,best_d=f,d
        except Exception: continue
    return best,round(best_d,2) if best else None
def osm_id(f):
    p=props(f)
    return str(p.get("osm_id") or p.get("osm_way_id") or p.get("osm_feature_id") or p.get("id") or f.get("id") or "")

def main():
    for i in IDS:
        if not (IMAGES/(i+".jpg")).is_file(): raise RuntimeError("Missing selected photo "+i)
    pano=collection(PANO); buildings=collection(BUILDINGS); roads=collection(ROADS)
    by_id={fid(f):f for f in pano}
    report={"city":"manila","scope":"only the three selected photographs, each photographed building and its visible street","photos":[]}
    out=[]
    for n,i in enumerate(IDS,1):
        item={"sequence":n,"image_id":i,"image":f"data/facades/cities/manila/facade-review/images/{i}.jpg",
              "building_osm_id":None,"road_osm_id":None,"status":"unmatched"}
        f=by_id.get(i)
        if not f:
            item["reason"]="No matching Panoramax feature in the local metadata."
        else:
            c=camera(f); b=heading(f)
            item["panoramax_properties"]=props(f)
            item["camera_heading_degrees"]=b
            if c:
                lon,lat=TO_LL(c.x,c.y); item["camera_location"]={"lon":round(lon,8),"lat":round(lat,8)}
            if c is None or b is None:
                item["reason"]="Camera location or heading missing; target is not guessed."
            else:
                bf,bd=target(c,b,buildings,"building",65)
                rf,rd=target(c,b,roads,"road",45)
                if bf:
                    item["building_osm_id"]=osm_id(bf); item["building_distance_m"]=bd
                    out.append({"type":"Feature","properties":{"photo_id":i,"target":"photographed_building_only","osm_id":osm_id(bf)},"geometry":bf["geometry"]})
                if rf:
                    item["road_osm_id"]=osm_id(rf); item["road_distance_m"]=rd
                    out.append({"type":"Feature","properties":{"photo_id":i,"target":"photographed_street_only","osm_id":osm_id(rf)},"geometry":rf["geometry"]})
                item["status"]="target_resolved" if bf and rf else "partially_resolved"
                item["reason"]="Only this photo's matched building and road are targets. Facade elements must be tied to this building and supported by image-space annotations."
        report["photos"].append(item)
    OUT.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    GEO.write_text(json.dumps({"type":"FeatureCollection","name":"Three photographed targets only","features":out},ensure_ascii=False)+"\n",encoding="utf-8")
    print(json.dumps({"photos":len(IDS),"buildings":sum(bool(x["building_osm_id"]) for x in report["photos"]),
                      "roads":sum(bool(x["road_osm_id"]) for x in report["photos"]),
                      "unmatched":sum(x["status"]=="unmatched" for x in report["photos"]),
                      "report":str(OUT.relative_to(ROOT)),"targets":str(GEO.relative_to(ROOT))},indent=2))
if __name__=="__main__": main()
