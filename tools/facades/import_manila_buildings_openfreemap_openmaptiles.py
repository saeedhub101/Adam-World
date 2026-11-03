#!/usr/bin/env python3
"""Import every published vector-tile layer from OpenFreeMap and OpenMapTiles for Manila, z1-z16."""
from __future__ import annotations
import gzip, hashlib, json, math, os, subprocess, time
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
import mapbox_vector_tile
from shapely.geometry import box, shape, mapping
from shapely.ops import transform, unary_union
ROOT=Path(__file__).resolve().parents[2]
CITY=ROOT/"data/facades/cities/manila"; BOUNDARY=ROOT/"data/full-city-imports/manila/01_admin_boundary/city-boundary.geojson"
OUT=CITY/"layers"; CHECK=CITY/".all-layer-checkpoints"; ZOOMS=tuple(range(1,17))
SOURCES={"OpenFreeMap":"https://tiles.openfreemap.org/planet/latest/{z}/{x}/{y}.pbf","OpenMapTiles":"https://openmaptiles.data.gouv.fr/data/planet-vector/{z}/{x}/{y}.pbf"}
UA="AdamWorld-Manila-AllVectorLayers/2.0"
def boundary_geometry():
 d=json.loads(BOUNDARY.read_text(encoding="utf-8")); g=unary_union([shape(f["geometry"]) for f in d.get("features",[]) if f.get("geometry")]); return g if g.is_valid else g.buffer(0)
def tile_xy(lon,lat,z):
 n=2**z; x=int(math.floor((lon+180)/360*n)); lat=max(-85.05112878,min(85.05112878,lat)); y=int(math.floor((1-math.asinh(math.tan(math.radians(lat)))/math.pi)/2*n)); return x,y
def tile_bounds(x,y,z):
 n=2**z; west=x/n*360-180; east=(x+1)/n*360-180
 def lat(v): return math.degrees(math.atan(math.sinh(math.pi*(1-2*v/n))))
 return west,lat(y+1),east,lat(y)
def tile_to_lonlat(gx,gy,x,y,z,e):
 n=2**z; xx=(gx+x*e)/e; yy=(gy+y*e)/e; lon=xx/n*360-180; lat=math.degrees(math.atan(math.sinh(math.pi*(1-2*yy/n)))); return lon,lat
def convert(raw,x,y,z,e,boundary):
 if not raw: return None
 g=shape(raw)
 def project(a,b,zv=None):
  if hasattr(a,"__iter__"):
   pairs=[tile_to_lonlat(px,py,x,y,z,e) for px,py in zip(a,b)]
   if not pairs: return ((),()) if zv is None else ((),(),zv)
   xx,yy=zip(*pairs); return (xx,yy) if zv is None else (xx,yy,zv)
  return tile_to_lonlat(a,b,x,y,z,e)
 g=transform(project,g)
 if not g.is_valid: g=g.buffer(0)
 if g.is_empty or not g.intersects(boundary): return None
 g=g.intersection(boundary); return None if g.is_empty else g
def fetch(url):
 errors=[]
 for attempt in range(5):
  try:
   req=Request(url,headers={"User-Agent":UA,"Accept":"application/x-protobuf"})
   with urlopen(req,timeout=60) as r: data=r.read()
   return gzip.decompress(data) if data[:2]==b"\x1f\x8b" else data
  except HTTPError as e:
   if e.code in (404,204): return b""
   errors.append(str(e))
   if e.code not in (408,429,500,502,503,504): break
  except (URLError,TimeoutError,OSError) as e: errors.append(str(e))
  time.sleep(min(2**attempt,20))
 raise RuntimeError("; ".join(errors))
def key_for(src,layer,f,g):
 fid=f.get("id")
 if fid not in (None,""): return f"{src}|{layer}|id:{fid}"
 return f"{src}|{layer}|geom:"+hashlib.sha1((g.wkb.hex()+json.dumps(f.get("properties") or {},sort_keys=True,default=str)).encode()).hexdigest()
def stage_path(src,z): return CHECK/(src.lower()+"-z"+str(z)+".json.gz")
def save_stage(src,z,d):
 CHECK.mkdir(parents=True,exist_ok=True); p=stage_path(src,z); tmp=p.with_suffix(p.suffix+".tmp")
 with gzip.open(tmp,"wt",encoding="utf-8") as f: json.dump(d,f,separators=(",",":"),ensure_ascii=False)
 os.replace(tmp,p); subprocess.run(["git","add",str(p.relative_to(ROOT))],cwd=ROOT,check=True)
 if subprocess.run(["git","diff","--cached","--quiet"],cwd=ROOT).returncode:
  subprocess.run(["git","-c","user.name=github-actions[bot]","-c","user.email=41898282+github-actions[bot]@users.noreply.github.com","commit","-m",f"Checkpoint Manila all-layer {src} z{z}"],cwd=ROOT,check=True)
  subprocess.run(["git","pull","--rebase","origin","main"],cwd=ROOT,check=True); subprocess.run(["git","push","origin","HEAD:main"],cwd=ROOT,check=True)
 print(f"CHECKPOINT SAVED {src} z{z}: {sum(map(len,d['features'].values()))} features",flush=True)
def load_stage(src,z):
 p=stage_path(src,z)
 if not p.is_file(): return None
 with gzip.open(p,"rt",encoding="utf-8") as f: d=json.load(f)
 if d.get("source")!=src or d.get("zoom")!=z: raise RuntimeError("checkpoint identity mismatch")
 print(f"RESUME {src} z{z}: {sum(map(len,d['features'].values()))} features",flush=True); return d
def import_stage(src,url,z,boundary):
 minx,miny,maxx,maxy=boundary.bounds; x0,y1=tile_xy(minx,maxy,z); x1,y0=tile_xy(maxx,miny,z); data={}; hit=empty=0
 for x in range(max(0,x0),min(2**z-1,x1)+1):
  for y in range(max(0,y1),min(2**z-1,y0)+1):
   if not boundary.intersects(box(*tile_bounds(x,y,z))): continue
   payload=fetch(url.format(z=z,x=x,y=y))
   if not payload: empty+=1; continue
   tile=mapbox_vector_tile.decode(payload,default_options={"y_coord_down":True}); found=False
   for lname,layer in tile.items():
    extent=int(layer.get("extent") or 4096)
    for f in layer.get("features",[]):
     g=convert(f.get("geometry"),x,y,z,extent,boundary)
     if g is None: continue
     found=True; p=dict(f.get("properties") or {});\n     # Normalize provider building-layer records to the canonical building tag\n     # expected by the Manila GeoJSON-to-game shard builder.\n     if lname == "building": p.setdefault("building", p.get("class") or "yes")\n     p.update({"_source":src,"_source_layer":lname,"_source_zoom":z,"_tile_x":x,"_tile_y":y})
     k=key_for(src,lname,f,g); data.setdefault(lname,{})[k]={"type":"Feature","id":k,"properties":p,"geometry":mapping(g)}
   if found: hit+=1
   else: empty+=1
 print(f"FETCHED {src} z{z}: tiles_with_features={hit}, empty_or_no_features={empty}, layers={len(data)}",flush=True)
 return {"source":src,"zoom":z,"tiles_with_data":hit,"empty_tiles":empty,"features":data}
def main():
 if not BOUNDARY.is_file(): raise SystemExit("Missing Manila boundary")
 boundary=boundary_geometry(); combined={}; totals={}
 for src,url in SOURCES.items():
  totals[src]={}
  for z in ZOOMS:
   d=load_stage(src,z)
   if d is None: d=import_stage(src,url,z,boundary); save_stage(src,z,d)
   totals[src][str(z)]={"tiles_with_data":d["tiles_with_data"],"empty_tiles":d["empty_tiles"]}
   for lname,items in d["features"].items():
    target=combined.setdefault(lname,{})
    for k,v in items.items():
     old=target.get(k)
     if old is None or int(v["properties"].get("_source_zoom",0))>=int(old["properties"].get("_source_zoom",0)): target[k]=v
   print(f"COMPLETE {src} z{z}",flush=True)
 OUT.mkdir(parents=True,exist_ok=True); counts={}
 for lname,items in sorted(combined.items()):
  fs=list(items.values()); doc={"type":"FeatureCollection","name":f"Manila {lname}","source":"OpenFreeMap + OpenMapTiles","zooms":list(ZOOMS),"feature_count":len(fs),"features":fs}
  p=OUT/(lname.replace("/","_")+".geojson"); t=p.with_suffix(".geojson.tmp"); t.write_text(json.dumps(doc,ensure_ascii=False,separators=(",",":"))+"\n",encoding="utf-8"); os.replace(t,p); counts[lname]=len(fs)
  print(f"WRITE LAYER {lname}: {len(fs)} -> {p.relative_to(ROOT)}",flush=True)
 if "building" in combined:
  fs=list(combined["building"].values()); d={"type":"FeatureCollection","name":"Manila buildings — OpenFreeMap + OpenMapTiles","source":"OpenFreeMap + OpenMapTiles","zooms":list(ZOOMS),"feature_count":len(fs),"features":fs}
  t=CITY/"buildings.geojson.tmp"; t.write_text(json.dumps(d,ensure_ascii=False,separators=(",",":"))+"\n",encoding="utf-8"); os.replace(t,CITY/"buildings.geojson")
 manifest={"city":"Manila","zooms":list(ZOOMS),"sources":list(SOURCES),"source_zoom_tile_summary":totals,"layer_feature_counts":counts,"total_layers":len(counts),"total_features":sum(counts.values()),"native_zoom_policy":"Request z1-z16 exactly; empty responses are recorded, never fabricated or silently overzoomed.","outputs":"data/facades/cities/manila/layers/*.geojson"}
 (CITY/"all-layers-manifest.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
 print(json.dumps({"layers":len(counts),"features":sum(counts.values()),"building_features":counts.get("building",0)},ensure_ascii=False),flush=True)
if __name__=="__main__": main()
