#!/usr/bin/env python3
"""Build a provenance-preserving, conservative multi-source Tabarbour building database."""
from __future__ import annotations
import argparse, json, math, os, re, tempfile
from pathlib import Path
try:
    from .review_queue_io import load_review_queue
except ImportError:
    from review_queue_io import load_review_queue
from collections import Counter, defaultdict
from shapely.geometry import shape, mapping
from shapely.strtree import STRtree

ROOT=Path(__file__).resolve().parents[2]
CITY=ROOT/"data/offline-world/tabarbour"
SOURCES={
 "osm_buildings":("building-details.geojson",100),
 "openfreemap":("building-footprints.geojson",90),
 "overture":("overture-building-details.geojson",80),
 "openbuildingmap":("openbuildingmap-buildings.geojson",60),
}
PARTS=("overture-building-parts.geojson",)
POINTS=("address-points.geojson","overture-address-points.geojson","osm-access-points.geojson")
PHOTOS=("panoramax-items.geojson",)

def read_fc(name):
 p=CITY/name
 if not p.is_file(): return []
 try:
  obj=json.loads(p.read_text(encoding="utf-8"))
  return obj.get("features",[]) if obj.get("type")=="FeatureCollection" else []
 except Exception as e:
  raise RuntimeError(f"Invalid {p}: {e}")

def props(f):
 p=f.get("properties") or {}
 return p if isinstance(p,dict) else {}

def osm_identity(f):
 p=props(f); candidates=[p.get("osm_feature_id"),p.get("osm_id"),p.get("osm_way_id"),p.get("osm_relation_id")]
 tags=p.get("tags") if isinstance(p.get("tags"),dict) else {}
 candidates += [tags.get("osm_id")]
 for c in candidates:
  if c is None: continue
  v=str(c).strip()
  m=re.fullmatch(r"([nwr])/?(\d+)",v)
  if m: return f"{m.group(1)}/{m.group(2)}"
  if v.isdigit(): return "way/"+v
  if re.fullmatch(r"[nwr]\d+",v): return v[0]+"/"+v[1:]
 # Overture exposes upstream record_id such as w123@2
 for src in p.get("sources",[]) if isinstance(p.get("sources"),list) else []:
  rid=str(src.get("record_id") or "")
  m=re.match(r"([nwr])(\d+)(?:@\d+)?$",rid)
  if m: return f"{m.group(1)}/{m.group(2)}"
 return None

def clean(v):
 if v is None or v=="" or v==[] or v=={}: return False
 if isinstance(v,float) and not math.isfinite(v): return False
 return True

def atomic(path,obj):
 path.parent.mkdir(parents=True,exist_ok=True)
 fd,tmp=tempfile.mkstemp(prefix=path.name+".",dir=path.parent)
 try:
  with os.fdopen(fd,"w",encoding="utf-8") as f:
   json.dump(obj,f,ensure_ascii=False,separators=(",",":")); f.write("\n")
  os.replace(tmp,path)
 finally:
  if os.path.exists(tmp): os.unlink(tmp)

def normalized_source_properties(source, value):
 p = dict(value or {})
 if source == "overture":
  if p.get("num_floors") not in (None, "") and p.get("building:levels") in (None, ""):
   p["building:levels"] = str(p["num_floors"])
  if p.get("num_floors_underground") not in (None, "") and p.get("building:levels:underground") in (None, ""):
   p["building:levels:underground"] = str(p["num_floors_underground"])
  if p.get("min_floor") not in (None, "") and p.get("building:min_level") in (None, ""):
   p["building:min_level"] = str(p["min_floor"])
  for pair in p.get("source_tags") or []:
   if isinstance(pair, (list, tuple)) and len(pair) >= 2 and clean(pair[1]):
    key = str(pair[0])
    if key in ("height", "building:levels", "building:height", "name", "name:ar", "name:en", "ref") and p.get(key) in (None, ""):
     p[key] = str(pair[1])
 if source == "openbuildingmap":
  raw = str(p.get("height") or "")
  heights, floors, floor_range = [], [], []
  for token in raw.split("+"):
   if ":" not in token: continue
   code, raw_value = token.split(":", 1)
   numbers = []
   for number in re.findall(r"\d+(?:\.\d+)?", raw_value):
    try: numbers.append(float(number))
    except ValueError: pass
   if not numbers: continue
   if code == "HHT": heights.extend(numbers)
   elif code == "H": floors.extend(numbers)
   elif code == "HBET": floor_range.extend(numbers)
  if heights:
   p["height"] = str(max(heights))
   p["adam:height_source"] = "OpenBuildingMap/GHSL GEM HHT"
  else:
   p.pop("height", None)
  if floors:
   p["building:levels"] = str(max(floors))
   p["adam:floors_source"] = "OpenBuildingMap/GEM H"
  elif floor_range:
   p["building:levels"] = str(sum(floor_range) / len(floor_range))
   p["adam:floors_source"] = "OpenBuildingMap/GEM HBET"
   p["adam:floors_quality"] = "estimated_range"
  p["adam:obm_height_code"] = raw
  occupancy = str(p.get("occupancy") or "")
  building = {"RES":"residential","COM":"commercial","MIX":"yes","IND":"industrial",
              "EDU":"school","GOV":"government","AGR":"farm","ASS":"civic"}.get(occupancy[:3])
  if building and not p.get("building"): p["building"] = building
 return p


def main():
 ap=argparse.ArgumentParser()
 ap.add_argument("--chunk-size",type=int,default=2500)
 a=ap.parse_args()
 if not 100<=a.chunk_size<=10000: ap.error("--chunk-size must be 100..10000")
 records={}; counts={}; missing=[]
 for source,(filename,rank) in SOURCES.items():
  feats=read_fc(filename); counts[source]=len(feats)
  if not feats: missing.append(source)
  for i,f in enumerate(feats):
   g=f.get("geometry")
   if not g: continue
   try: geom=shape(g)
   except Exception: continue
   if geom.is_empty or geom.geom_type not in ("Polygon","MultiPolygon"): continue
   oid=osm_identity(f)
   fid=str(f.get("id") or props(f).get("id") or i)
   key="osm:"+oid if oid else f"src:{source}:{fid}"
   rec=records.setdefault(key,{"id":key,"osm_id":oid,"observations":[],"_rank":-1,"_geometry":None,"_attrs":{},"_attr_ranks":{},"_conflicts":defaultdict(list)})
   source_props=normalized_source_properties(source, props(f))
   observation={"source":source,"source_feature_id":fid,"properties":source_props,"geometry":g}
   rec["observations"].append(observation)
   for k,v in source_props.items():
    if k in ("sources","tags") and isinstance(v,dict): items=v.items()
    else: items=[(k,v)]
    for name,value in items:
     if clean(value):
      if value not in rec["_conflicts"][name]: rec["_conflicts"][name].append(value)
      if rank > rec["_attr_ranks"].get(name, -1) and clean(value):
       rec["_attrs"][name] = value
       rec["_attr_ranks"][name] = rank
   if rank>rec["_rank"]:
    rec["_rank"]=rank; rec["_geometry"]=g
 # Conservative spatial reconciliation for provider records lacking explicit shared OSM IDs.
 # Only merge when polygon overlap is very high AND centroids are close; otherwise keep separate.
 base=[r for r in records.values() if r["_geometry"] is not None]
 geoms=[shape(r["_geometry"]) for r in base]
 ambiguous_candidates=[]
 if geoms:
  tree=STRtree(geoms)
  for rec in list(base):
   if rec["id"] not in records or rec["osm_id"] or len(rec["observations"])>1: continue
   geom=shape(rec["_geometry"])
   try: hits=tree.query(geom)
   except Exception: continue
   best=None; bestscore=0
   for hit in hits:
    idx=int(hit) if isinstance(hit,(int,)) or hasattr(hit,"__index__") else next((i for i,g in enumerate(geoms) if g is hit),-1)
    if idx<0: continue
    other=base[idx]
    if other is rec or other["_rank"]==rec["_rank"]: continue
    og=geoms[idx]
    if geom.centroid.distance(og.centroid)>0.000035: continue
    inter=geom.intersection(og).area
    union=geom.union(og).area
    score=inter/union if union else 0
    if score>bestscore: bestscore=score; best=other
   if best is not None and bestscore>=0.78:
    # Keep the source feature in the stronger record and preserve the geometry variant.
    best["observations"].extend(rec["observations"])
    best["_conflicts"]["spatial_match_iou"].append(round(bestscore,4))
    for name, value in rec["_attrs"].items():
     if name not in best["_attrs"] or rec["_attr_ranks"].get(name, -1) > best["_attr_ranks"].get(name, -1):
      best["_attrs"][name] = value
      best["_attr_ranks"][name] = rec["_attr_ranks"].get(name, -1)
    records.pop(rec["id"],None)
 # Keep uncertain partial overlaps as explicit QA candidates; do not auto-merge them.
 review_dir=CITY/"sources"; review_dir.mkdir(parents=True,exist_ok=True)
 atomic(review_dir/"footprint-review-candidates.geojson",{"type":"FeatureCollection","name":"Tabarbour footprint overlap review","features":ambiguous_candidates,"feature_count":len(ambiguous_candidates),"policy":"IoU 0.20-0.78 candidates are not merged automatically."})
 # Attach building parts separately; never let them replace or merge building footprints.
 part_count=0
 for filename in PARTS:
  for i,f in enumerate(read_fc(filename)):
   g=f.get("geometry")
   if not g: continue
   try: geom=shape(g)
   except Exception: continue
   candidates=[]
   for rec in records.values():
    try:
     bg=shape(rec["_geometry"])
     if bg.buffer(0.000015).intersects(geom):
      candidates.append((bg.intersection(geom).area/max(geom.area,1e-15),rec))
    except Exception: pass
   if candidates:
    score,rec=max(candidates,key=lambda x:x[0])
    if score>=0.45:
     rec.setdefault("building_parts",[]).append({"source":"Overture Maps","source_feature_id":str(f.get("id") or i),"properties":props(f),"geometry":g})
     part_count+=1
 # Attach entrances and address nodes by point-in-footprint or <= 2m boundary proximity.
 point_counts=Counter()
 point_records=list(records.values())
 point_geoms=[shape(r["_geometry"]) for r in point_records]
 point_tree=STRtree(point_geoms) if point_geoms else None
 for filename in POINTS:
  for i,f in enumerate(read_fc(filename)):
   g=f.get("geometry")
   if not g or g.get("type")!="Point" or point_tree is None: continue
   try: point=shape(g)
   except Exception: continue
   best=None; bestdist=float("inf")
   for hit in point_tree.query(point.buffer(0.00002)):
    idx=int(hit) if isinstance(hit,(int,)) or hasattr(hit,"__index__") else next((j for j,x in enumerate(point_geoms) if x is hit),-1)
    if idx<0: continue
    rec=point_records[idx]; bg=point_geoms[idx]; dist=bg.distance(point)
    if bg.buffer(0.00002).covers(point) and dist<bestdist:
     best=rec; bestdist=dist
   if best is not None:
    item={"source":props(f).get("source") or ("Overture Maps" if "overture" in filename else "OpenStreetMap"),"source_feature_id":str(f.get("id") or i),"properties":props(f),"geometry":g}
    if "entrance" in props(f) or "door" in props(f): best.setdefault("entrances",[]).append(item); point_counts["entrances"]+=1
    else: best.setdefault("addresses",[]).append(item); point_counts["addresses"]+=1
 # Panoramax candidates are attached only as unverified evidence from the review queue.
 photo_features=[]
 for filename in PHOTOS: photo_features.extend(read_fc(filename))
 photo_candidate_count=0
 review_path=CITY/"review_queue.json"
 if review_path.is_file():
  review=load_review_queue(review_path)
  for item in review.get("buildings",[]):
   oid=str(item.get("osm_way_id") or "")
   rec=records.get("osm:way/"+oid)
   if rec is None: continue
   candidates=item.get("facade_photo_candidates",[])
   if candidates:
    rec["photo_candidates"]=candidates
    photo_candidate_count+=len(candidates)
 # Final records, conflicts and provenance.
 final=[]
 for key,rec in sorted(records.items()):
  attrs=rec["_attrs"]
  conflict={k:v for k,v in rec["_conflicts"].items() if len(v)>1}
  final.append({"type":"Feature","id":key,"geometry":rec["_geometry"],"properties":{
   **attrs,"adam:entity_id":key,"adam:osm_id":rec["osm_id"],
   "adam:sources":sorted({o["source"] for o in rec["observations"]}),
   "adam:observation_count":len(rec["observations"]),
   "adam:has_attribute_conflicts":bool(conflict),
   "adam:attribute_conflicts":conflict,
   "adam:source_observations":rec["observations"],
   "adam:building_parts":rec.get("building_parts",[]),
   "adam:entrances":rec.get("entrances",[]),
   "adam:addresses":rec.get("addresses",[]),
   "adam:facade_photo_candidates":rec.get("photo_candidates",[])
  }})
 # Chunk output so the game and repository do not create millions of tiny files.
 outdir=CITY/"merged"; outdir.mkdir(parents=True,exist_ok=True)
 for old in outdir.glob("buildings_*.geojson"): old.unlink()
 chunks=[]
 for start in range(0,len(final),a.chunk_size):
  name=f"buildings_{start//a.chunk_size:03d}.geojson"
  subset=final[start:start+a.chunk_size]
  atomic(outdir/name,{"type":"FeatureCollection","name":"Tabarbour merged buildings","features":subset})
  chunks.append({"file":name,"feature_count":len(subset)})
 report={"schema_version":1,"city":"tabarbour","source_feature_counts":counts,
  "merged_building_count":len(final),"chunk_count":len(chunks),"chunk_size":a.chunk_size,
  "attached_building_parts":part_count,"attached_entrances":point_counts["entrances"],
  "attached_addresses":point_counts["addresses"],"panoramax_photo_count":len(photo_features),
  "sources_missing_or_empty":missing,
  "merge_policy":"Explicit OSM identity first. Spatial merge only when polygon IoU >= 0.78 and centroids are within about 4m. Parts remain parts; entrances and addresses attach only within 2m of a footprint. Photos are not assigned as verified facades.",
  "chunks":chunks,"ambiguous_footprint_review_candidates":len(ambiguous_candidates)}
 atomic(outdir/"manifest.json",report)
 atomic(outdir/"source-report.json",{"source_feature_counts":counts,"missing_or_empty":missing,"panoramax_photo_count":len(photo_features),"panoramax_building_photo_candidate_count":photo_candidate_count,"attached_building_parts":part_count,"attached_entrances":point_counts["entrances"],"attached_addresses":point_counts["addresses"]})
 print(json.dumps(report,ensure_ascii=False))
if __name__=="__main__": main()
