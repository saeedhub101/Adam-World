#!/usr/bin/env python3
"""Create photo-specific Manila GeoJSON targets from saved Panoramax images.

Fetches authoritative STAC metadata for the three existing image UUIDs, resolves
camera positions/headings against the committed building and road geometry, and
writes image-grounded descriptions. It never propagates details to neighbours.
"""
from __future__ import annotations
import json, math, time
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
from pyproj import Transformer
from shapely.geometry import Point, LineString, shape
from shapely.ops import transform, nearest_points

ROOT=Path(__file__).resolve().parents[2]
CITY=ROOT/"data/facades/cities/manila"
IDS=[
"0cb04844-65a3-418b-beb8-7d6a65915903",
"134abd91-1ba0-43d5-b1e5-71bb394f71a7",
"150e2cd0-7c24-42f3-83c7-71e8f551e1fc"]
DESCRIPTIONS={
IDS[0]: {
 "scene":"Narrow pedestrian street paved with reddish interlocking brick. On the left is a low pale-pink perimeter wall with a tall dark metal gate, a red medical-cross sign and a small posted sign; palm foliage and dense overhead utility cables cross the view. A white multi-storey building rises behind the trees. On the right is a long weathered light-grey masonry facade with repeated openings and a narrow raised concrete sidewalk.",
 "street_surface":"reddish interlocking brick pavers",
 "visible_facade":"pale-pink plaster perimeter wall, dark metal gate and red medical-cross sign; adjoining weathered light-grey masonry facade",
 "openings":"large metal gate; repeated rectangular openings on the right-side facade, details partly obscured",
 "street_furniture":"palm trees, overhead utility cables, narrow concrete sidewalks",
 "signage":"red medical-cross sign and a small wall-mounted poster",
 "cautions":"Do not infer unseen windows, doors, balconies, exact material composition or building use beyond visible evidence."
},
IDS[1]: {
 "scene":"Straight urban street with grey asphalt/concrete carriageway and a curb painted alternating yellow and white. The left side has a pale masonry wall and an ornate light-coloured facade with a tall arched entrance, decorative mouldings and turquoise/green trim around the ground-floor openings. The right side is a long yellow-and-cream multi-storey facade with repeated arched ground-floor bays, columns and dark awnings/signboards.",
 "street_surface":"grey paved carriageway",
 "visible_facade":"left: pale facade with decorative arched portal and turquoise/green trim; right: yellow-and-cream facade with repeated arcaded bays and columns",
 "openings":"large arched entrance and smaller ground-floor openings on the left; repeated arched openings on the right",
 "street_furniture":"yellow-and-white curb, utility poles and overhead wires, dark shop awnings",
 "signage":"dark shop signboards/awnings are visible but lettering is not reliably legible",
 "cautions":"Do not invent upper-floor window counts, balcony details, building names or exact facade materials."
},
IDS[2]: {
 "scene":"Street bordered on the left by a long dark, rough stone masonry wall and an older pale stone building rising behind it, with a high curved/parapet-like roofline. In the middle distance is a low light-coloured building with repeated arched openings and a reddish tiled roof. On the right foreground is a tall modern light-grey building with large plain wall panels. A red car is parked near the right side.",
 "street_surface":"grey asphalt/concrete road",
 "visible_facade":"left: dark rough-cut stone boundary wall and pale historic masonry above; centre: light facade with repeated arches and red tiled roof; right: modern light-grey panelled facade",
 "openings":"repeated arches on the central building; individual openings on the right are not clearly visible",
 "street_furniture":"overhead utility wires and parked red car",
 "signage":"no confidently legible sign is visible",
 "cautions":"Do not infer unseen doors, windows, balconies, floor counts or exact building function."
}}
TO_M=Transformer.from_crs("EPSG:4326","EPSG:3857",always_xy=True).transform
TO_LL=Transformer.from_crs("EPSG:3857","EPSG:4326",always_xy=True).transform
def get_json(url):
 req=Request(url,headers={"Accept":"application/geo+json, application/json","User-Agent":"AdamWorld-Panoramax-VisualGeoJSON/1.0"})
 last=None
 for attempt in range(4):
  try:
   with urlopen(req,timeout=35) as r:return json.loads(r.read().decode("utf-8"))
  except (HTTPError,URLError,TimeoutError,ValueError) as e:
   last=e
   if isinstance(e,HTTPError) and e.code not in (429,500,502,503,504):break
   time.sleep(1+attempt*2)
 raise RuntimeError(f"Panoramax metadata request failed: {url}: {last}")
def read_fc(path):
 obj=json.loads(path.read_text(encoding="utf-8"))
 if obj.get("type")!="FeatureCollection" or not isinstance(obj.get("features"),list):raise ValueError(f"Invalid FeatureCollection: {path}")
 return obj["features"]
def props(f):return f.get("properties") or {}
def first_number(p,keys):
 for k in keys:
  v=p.get(k)
  try:
   n=float(v)
   if math.isfinite(n):return n
  except (TypeError,ValueError):pass
 return None
def get_heading(p):
 for k in ("view:azimuth","compass_direction","heading","azimuth","panoramax:azimuth","camera_direction"):
  v=first_number(p,[k])
  if v is not None:return v%360,k
 ori=p.get("pers:interior_orientation") or {}
 for k in ("heading","azimuth","yaw"):
  v=first_number(ori,[k])
  if v is not None:return v%360,"pers:interior_orientation."+k
 return None,None
def feature_id(f):
 p=props(f)
 for k in ("osm_id","osm_way_id","osm_feature_id","source_feature_id","id"):
  if p.get(k) is not None:return str(p[k])
 return str(f.get("id") or "")
def projected_geom(f):
 return transform(TO_M,shape(f["geometry"]))
def ray_hit(camera,heading,features,limit,kind):
 a=math.radians(heading)
 end=Point(camera.x+math.sin(a)*limit,camera.y+math.cos(a)*limit)
 ray=LineString([camera,end])
 best=None;bestd=limit+1
 for f in features:
  if not f.get("geometry"):continue
  try:
   g=projected_geom(f)
   if kind=="building" and g.geom_type not in ("Polygon","MultiPolygon"):continue
   if kind=="road" and g.geom_type not in ("LineString","MultiLineString"):continue
   hit=ray.intersection(g)
   if hit.is_empty:continue
   pt=hit if hit.geom_type=="Point" else nearest_points(camera,hit)[1]
   d=camera.distance(pt)
   if 2<d<bestd:best,bestd=f,d
  except Exception:continue
 return best,round(bestd,2) if best else None
def nearest_candidates(camera,features,kind,limit=3,maxdist=45):
 found=[]
 for f in features:
  if not f.get("geometry"):continue
  try:
   g=projected_geom(f)
   if kind=="building" and g.geom_type not in ("Polygon","MultiPolygon"):continue
   if kind=="road" and g.geom_type not in ("LineString","MultiLineString"):continue
   d=camera.distance(g)
   if d<=maxdist:found.append((d,f))
  except Exception:continue
 return [(round(d,2),f) for d,f in sorted(found,key=lambda x:x[0])[:limit]]
def photo_metadata(pid):
 # Panoramax's federated STAC item endpoint returns geometry and camera properties.
 return get_json(f"https://api.panoramax.xyz/api/pictures/{pid}")
def main():
 image_dir=CITY/"facade-review/images"
 for pid in IDS:
  if not (image_dir/(pid+".jpg")).is_file():raise RuntimeError("Missing saved Panoramax image "+pid)
 buildings=read_fc(CITY/"layers/building-layer.geojson")
 roads=read_fc(CITY/"transportation.geojson")
 if not buildings or not roads:raise RuntimeError("Building or road source has no features")
 output=[];report=[]
 for seq,pid in enumerate(IDS,1):
  item=photo_metadata(pid)
  geom=item.get("geometry")
  if not geom or geom.get("type")!="Point":raise RuntimeError(f"Panoramax item {pid} has no point geometry")
  lon,lat=geom["coordinates"][:2]
  camera=Point(*TO_M(lon,lat))
  p=item.get("properties") or {}
  heading,hkey=get_heading(p)
  desc=DESCRIPTIONS[pid]
  photo_url=f"data/facades/cities/manila/facade-review/images/{pid}.jpg"
  photo_props={"feature_role":"panoramax_photo_observation","sequence":seq,"photo_id":pid,
   "photo_file":photo_url,"panoramax_metadata_url":f"https://api.panoramax.xyz/api/pictures/{pid}",
   "capture_lon":lon,"capture_lat":lat,"camera_heading_deg":heading,"heading_source":hkey,
   "visual_description":desc["scene"],"street_surface_observed":desc["street_surface"],
   "facade_description_observed":desc["visible_facade"],"openings_observed":desc["openings"],
   "street_furniture_observed":desc["street_furniture"],"signage_observed":desc["signage"],
   "visual_cautions":desc["cautions"],"identity_policy":"Only geometry intersecting the recorded camera view is marked as a direct target. Nearby alternatives remain explicitly unverified.",
   "description_source":"manual visual inspection of the exact saved Panoramax image"}
  output.append({"type":"Feature","id":f"panoramax-{pid}","properties":photo_props,"geometry":geom})
  b=None;bd=None;r=None;rd=None
  if heading is not None:
   b,bd=ray_hit(camera,heading,buildings,80,"building")
   r,rd=ray_hit(camera,heading,roads,60,"road")
  # The road is also resolved geometrically from the camera's actual capture position.
  if r is None:
   near=nearest_candidates(camera,roads,"road",1,35)
   if near:r,rd=near[0][1],near[0][0]
  if b:
   bp=props(b)
   output.append({"type":"Feature","id":f"building-{pid}-{feature_id(b)}","properties":{
    "feature_role":"photographed_building_target","photo_id":pid,"photo_file":photo_url,
    "osm_feature_id":feature_id(b),"building_name":bp.get("name") or bp.get("name:en"),
    "target_distance_m":bd,"camera_heading_deg":heading,"visual_description":desc["visible_facade"],
    "openings_observed":desc["openings"],"signage_observed":desc["signage"],
    "texture_policy":"Description only; use only details visible in this photo; do not propagate to neighbouring buildings.",
    "identity_status":"camera_ray_intersection"},"geometry":b["geometry"]})
  else:
   near=nearest_candidates(camera,buildings,"building",3,45)
   for rank,(dist,bf) in enumerate(near,1):
    bp=props(bf)
    output.append({"type":"Feature","id":f"building-candidate-{pid}-{rank}","properties":{
     "feature_role":"nearby_building_candidate_not_confirmed","photo_id":pid,"photo_file":photo_url,
     "osm_feature_id":feature_id(bf),"building_name":bp.get("name") or bp.get("name:en"),
     "capture_to_building_distance_m":dist,"camera_heading_deg":heading,
     "visual_description":desc["visible_facade"],
     "identity_status":"unverified_no_camera_ray_intersection",
     "warning":"This is a spatial candidate only, not a confirmed photographed building; do not apply facade texture until visually matched."},"geometry":bf["geometry"]})
  if r:
   rp=props(r)
   output.append({"type":"Feature","id":f"street-{pid}-{feature_id(r)}","properties":{
    "feature_role":"photographed_street_target","photo_id":pid,"photo_file":photo_url,
    "source_feature_id":feature_id(r),"street_name":rp.get("name") or rp.get("name:en"),
    "road_class":rp.get("class") or rp.get("highway"),"road_subclass":rp.get("subclass"),
    "target_distance_m":rd,"surface_observed":desc["street_surface"],
    "visual_description":"Street surface and visible roadside context described from the linked Panoramax image.",
    "identity_status":"nearest_saved_road_to_camera" if heading is None else "camera_ray_intersection_or_nearest_saved_road"},"geometry":r["geometry"]})
  report.append({"sequence":seq,"photo_id":pid,"photo_file":photo_url,"metadata_url":f"https://api.panoramax.xyz/api/pictures/{pid}",
   "capture_location":{"lon":lon,"lat":lat},"camera_heading_deg":heading,"heading_source":hkey,
   "building_target_osm_id":feature_id(b) if b else None,"road_target_id":feature_id(r) if r else None,
   "building_match":"camera_ray_intersection" if b else "no_confirmed_building_match",
   "visual_description":desc,"output_feature_count":sum(1 for x in output if x["properties"].get("photo_id")==pid)})
  print(f"PHOTO_OK {pid} heading={heading} building={feature_id(b) if b else 'candidate-only'} road={feature_id(r) if r else 'unmatched'}")
 if len(output)<6:raise RuntimeError(f"GeoJSON unexpectedly incomplete: only {len(output)} features")
 geo={"type":"FeatureCollection","name":"Manila Panoramax first three photo-specific building and street observations",
  "description":"Only the three named existing Panoramax images. Contains capture points, camera-resolved photographed building/street targets where possible, and explicitly unverified nearby building candidates otherwise. Descriptions are manual visual observations, not inferred unseen facade details.",
  "source":"Existing repository Panoramax JPEGs + Panoramax STAC item metadata + committed Manila building/transportation GeoJSON",
  "attribution":"Panoramax contributors; OpenStreetMap contributors; OpenFreeMap/OpenMapTiles",
  "features":output}
 out=CITY/"first_three_manila_photo_targets.geojson"
 out.write_text(json.dumps(geo,ensure_ascii=False,separators=(",",":"))+"\n",encoding="utf-8")
 (CITY/"first_three_manila_photo_targets.json").write_text(json.dumps({"city":"manila","photos":report,"geojson":str(out.relative_to(ROOT)),"feature_count":len(output)},ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
 print(json.dumps({"geojson":str(out.relative_to(ROOT)),"features":len(output),"photos":len(report),"building_matches":sum(bool(x["building_target_osm_id"]) for x in report),"roads_matched":sum(bool(x["road_target_id"]) for x in report)},indent=2))
if __name__=="__main__":main()
