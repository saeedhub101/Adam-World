#!/usr/bin/env python3
"""Build incremental 1 km Manila offline shards from Adam-City OpenMapTiles PBF,
OpenFreeMap transportation GeoJSON, and OSM building-detail supplements.
"""
from __future__ import annotations
import gzip, hashlib, json, math, os
from collections import OrderedDict, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PBF_ROOT = Path(os.environ.get("ADAM_CITY_MANILA_PBF", ROOT / "Adam-City-source/Manila/vector-tiles"))
TRANSPORT = ROOT / "data/facades/cities/manila/transportation.geojson"
DETAILS = ROOT / "data/reference/adam-city/manila/ermita/ermita-building-details.geojson"
OSM_CONTEXT = ROOT / "data/offline-world/manila/elements.json"
OUT = ROOT / "data/offline-city-stream/manila"
LAT0, LON0 = 14.57394, 120.98283
KY, KX, CELL, HALO = 111132.0, 111320.0 * math.cos(math.radians(LAT0)), 1000.0, 240.0

def sid(source, raw, part=0):
    b=f"{source}:{raw}:{part}".encode()
    return int.from_bytes(hashlib.blake2b(b,digest_size=8).digest(),"big") & 0x7fffffffffffffff

def text(v):
    if isinstance(v, dict):
        for k in ("primary","common","value","name"):
            if v.get(k) not in (None,""): return text(v[k])
        return ""
    if isinstance(v,list):
        return next((text(x) for x in v if text(x)), "")
    return "" if v is None else str(v)

def coords_to_points(coords, closed=False):
    pts=[{"lon":float(p[0]),"lat":float(p[1])} for p in coords if isinstance(p,(list,tuple)) and len(p)>=2]
    if closed and len(pts)>=3 and pts[0]!=pts[-1]: pts.append(dict(pts[0]))
    return pts

def tile_lonlat(x,y,z,extent):
    n=2**z; xx=x/extent; yy=y/extent
    lon=xx/n*360.0-180.0
    lat=math.degrees(math.atan(math.sinh(math.pi*(1-2*yy/n))))
    return lon,lat

def mvt_geometry(geom, x, y, z, extent):
    typ=geom.get("type"); c=geom.get("coordinates") or []
    def ring(r): return [tile_lonlat(p[0]+x*extent,p[1]+y*extent,z,extent) for p in r]
    if typ=="Polygon": return [ring(r) for r in c]
    if typ=="MultiPolygon": return [[ring(r) for r in poly] for poly in c]
    return []

def polygons(g):
    if g.get("type")=="Polygon": return [g.get("coordinates",[])]
    if g.get("type")=="MultiPolygon": return g.get("coordinates",[])
    return []

def lines(g):
    if g.get("type")=="LineString": return [g.get("coordinates",[])]
    if g.get("type")=="MultiLineString": return g.get("coordinates",[])
    return []

def cells_for(points):
    if not points: return []
    lons=[p["lon"] for p in points]; lats=[p["lat"] for p in points]
    x0=math.floor((min(lons)-LON0)*KX/CELL); x1=math.floor((max(lons)-LON0)*KX/CELL)
    z0=math.floor(-(max(lats)-LAT0)*KY/CELL); z1=math.floor(-(min(lats)-LAT0)*KY/CELL)
    hx=HALO/KX; hy=HALO/KY
    out=[]
    for ix in range(x0-1,x1+2):
        west=LON0+ix*CELL/KX-hx; east=LON0+(ix+1)*CELL/KX+hx
        for iz in range(z0-1,z1+2):
            north=LAT0-iz*CELL/KY+hy; south=LAT0-(iz+1)*CELL/KY-hy
            if max(lons)>=west and min(lons)<=east and max(lats)>=south and min(lats)<=north:
                out.append(f"{ix}_{iz}")
    return out

class Shards:
    def __init__(self):
        OUT.mkdir(parents=True,exist_ok=True)
        for p in OUT.glob("*.jsonl"): p.unlink()
        self.handles=OrderedDict(); self.counts=defaultdict(int); self.cells=set()
    def add(self,e,layer):
        pts=e.get("geometry") or []
        for cell in cells_for(pts):
            h=self.handles.pop(cell,None)
            if h is None: h=(OUT/(cell+".jsonl")).open("a",encoding="utf-8")
            self.handles[cell]=h
            if len(self.handles)>40: self.handles.popitem(last=False)[1].close()
            h.write(json.dumps(e,ensure_ascii=False,separators=(",",":"))+"\n")
            self.cells.add(cell); self.counts[layer]+=1
    def close(self):
        for h in self.handles.values(): h.close()
        self.handles.clear()
        for p in OUT.glob("*.jsonl"):
            with p.open("rb") as src, (OUT/(p.stem+".jsonl.gz")).open("wb") as raw:
                with gzip.GzipFile(fileobj=raw,mode="wb",compresslevel=6) as dst:
                    while b:=src.read(1024*1024): dst.write(b)
            p.unlink()

def tags_from_omt(p):
    t={"building":text(p.get("class") or p.get("subtype") or "yes"),"adam:source":"OpenMapTiles/OpenFreeMap"}
    mapping={"name":"name","height":"height","render_height":"height","render_min_height":"min_height",
      "num_floors":"building:levels","building:levels":"building:levels","facade_material":"building:material",
      "facade_color":"building:colour","roof_material":"roof:material","roof_color":"roof:colour",
      "roof_shape":"roof:shape","housenumber":"addr:housenumber","street":"addr:street",
      "use":"building:use","class":"adam:source:building_class","subtype":"adam:source:building_subtype"}
    for a,b in mapping.items():
        if p.get(a) not in (None,""): t[b]=text(p[a])
    for k in ("addr:housenumber","addr:street","amenity","shop","tourism","office","healthcare","ref"):
        if p.get(k) not in (None,""): t[k]=text(p[k])
    return t

def emit_polygon(writer, source, rawid, rings, tags, layer):
    for i,ring in enumerate(rings):
        pts=coords_to_points(ring,True)
        if len(pts)>=4:
            writer.add({"type":"way","id":sid(source,rawid,i),"tags":dict(tags),"geometry":pts},layer)

PRIMARY_CENTROIDS = []

def read_osm_detail_index():
    data=json.loads(DETAILS.read_text(encoding="utf-8"))
    index={}
    for f in data.get("features",[]):
        p=f.get("properties") or {}; tags=p.get("tags") or {}
        if not (tags.get("building") or tags.get("building:part")): continue
        for raw in (p.get("id"),p.get("osm_way_id"),p.get("osm_feature_id")):
            if raw is not None: index[str(raw).split("/")[-1]]=tags
    return data,index

MATCHED_OSM_IDS=set()

def read_pbf(writer, osm_index):
    try: import mapbox_vector_tile
    except ImportError as e: raise SystemExit("Install mapbox-vector-tile before importing Manila PBF") from e
    files=sorted(PBF_ROOT.rglob("*.pbf"))
    if not files: raise SystemExit(f"No Manila PBF files found under {PBF_ROOT}")
    count=0
    for path in files:
        try:
            parts=path.relative_to(PBF_ROOT).parts
            z=int(parts[-3]); x=int(parts[-2]); y=int(Path(parts[-1]).stem)
        except Exception: continue
        if z!=14: continue
        tile=mapbox_vector_tile.decode(path.read_bytes())
        layer=tile.get("building") or tile.get("buildings")
        if not layer: continue
        extent=layer.get("extent",4096)
        for f in layer.get("features",[]):
            g=f.get("geometry") or {}
            rings=mvt_geometry(g,x,y,z,extent)
            if not rings: continue
            p=f.get("properties") or {}; tags=tags_from_omt(p)
            rawids=[f.get("id"),p.get("id"),p.get("osm_id"),p.get("osm_way_id"),p.get("source_id")]
            for raw in rawids:
                if raw is None: continue
                key=str(raw).split("/")[-1]
                supplement=osm_index.get(key)
                if supplement:
                    MATCHED_OSM_IDS.add(key)
                    for k,v in supplement.items():
                        if v not in (None,"") and tags.get(k) in (None,""): tags[k]=text(v)
            emit_polygon(writer,"omt",f.get("id",count),rings,tags,"openmaptiles_buildings")
            count+=1
        # Preserve named facilities and address-number points from the same tiles.
        for lname in ("poi","housenumber"):
            lyr=tile.get(lname)
            if not lyr: continue
            extent2=lyr.get("extent",4096)
            for f in lyr.get("features",[]):
                geom=f.get("geometry") or {}; p=f.get("properties") or {}
                if geom.get("type")!="Point": continue
                xy=geom.get("coordinates") or []
                if len(xy)<2: continue
                lon,lat=tile_lonlat(xy[0]+x*extent2,xy[1]+y*extent2,z,extent2)
                t={"adam:source":"OpenMapTiles"}
                if lname=="housenumber":
                    value=p.get("housenumber") or p.get("housenumber_name") or p.get("name")
                    if value: t["addr:housenumber"]=text(value)
                    if p.get("street"): t["addr:street"]=text(p["street"])
                else:
                    for k in ("name","class","subclass","ref","amenity","shop","tourism","office","healthcare","cuisine","brand","operator"):
                        if p.get(k) not in (None,""): t[k]=text(p[k])
                if len(t)>1:
                    writer.add({"type":"node","id":sid("omt-"+lname,f.get("id",0)),"lat":lat,"lon":lon,"tags":t},"openmaptiles_"+lname)
    if not count: raise SystemExit("No zoom-14 building features decoded from Manila PBF tiles")
    return count

def read_transport(writer):
    data=json.loads(TRANSPORT.read_text(encoding="utf-8"))
    for i,f in enumerate(data.get("features",[])):
        p=f.get("properties") or {}; g=f.get("geometry") or {}
        tags={"adam:source":"OpenFreeMap/OpenMapTiles"}
        for k in ("class","subclass","highway","railway","route","name","ref","layer","bridge","tunnel","oneway","surface","width"):
            if p.get(k) not in (None,""): tags[k]=text(p[k])
        if "highway" not in tags and tags.get("class") in ("path","footway","pedestrian","steps","track"):
            tags["highway"]=tags["class"]
        if "name" in tags: tags["name:source"]="OpenFreeMap/OpenMapTiles transportation_name"
        for j,line in enumerate(lines(g)):
            pts=coords_to_points(line)
            if len(pts)>=2: writer.add({"type":"way","id":sid("ofm-road",f.get("id",i),j),"tags":tags,"geometry":pts},"openfreemap_transportation")

def read_osm_details(writer, data, primary_ids):
    if not DETAILS.is_file(): raise SystemExit(f"Missing OSM detail file: {DETAILS}")
    unmatched=0; matched=0
    # Preserve OSM building footprints/parts only when no OMT footprint matches.
    # OSM attributes are still emitted as point/attribute records for enrichment.
    for f in data.get("features",[]):
        p=f.get("properties") or {}; osm_id=p.get("id") or p.get("osm_feature_id","")
        t=dict(p.get("tags") or {})
        if not t.get("building") and not t.get("building:part"): continue
        t["adam:source"]="OpenStreetMap supplemental"
        t["adam:source_osm_id"]=str(osm_id).split("/")[-1]
        g=f.get("geometry") or {}
        if g.get("type")=="Point":
            lon,lat=g.get("coordinates",[None,None])[:2]
            if lon is not None and lat is not None:
                writer.add({"type":"node","id":sid("osm-detail",osm_id),"lon":float(lon),"lat":float(lat),"tags":t},"osm_building_attributes")
        elif g.get("type") in ("Polygon","MultiPolygon"):
            # Keep OSM geometry as fallback supplement; renderer's feature-id dedupe
            # prevents it from replacing a matched OMT footprint.
            rawkey=str(t.get("osm_way_id") or t.get("id") or osm_id).split("/")[-1]
            rings=polygons(g)
            outer=[pt for poly in rings for pt in (poly[0] if poly else [])]
            center=(sum(p[0] for p in outer)/len(outer),sum(p[1] for p in outer)/len(outer)) if outer else None
            near_primary=center is not None and any(abs(center[0]-x)*KX<4 and abs(center[1]-y)*KY<4 for x,y in PRIMARY_CENTROIDS)
            if rawkey not in primary_ids and not near_primary:
                emit_polygon(writer,"osm-footprint",osm_id,rings,t,"osm_building_fallback")
                unmatched+=1
            else: matched+=1
    return matched,unmatched

def read_context(writer):
    if not OSM_CONTEXT.is_file(): return 0
    data=json.loads(OSM_CONTEXT.read_text(encoding="utf-8")); n=0
    for e in data.get("elements",[]):
        t=e.get("tags") or {}
        # OSM supplements POIs, signals, rail/transit, utilities, water and
        # pedestrian infrastructure; OpenFreeMap remains the primary road source.
        if t.get("building") or t.get("building:part"): continue
        if e.get("type")=="way" and t.get("highway") not in ("footway","path","steps","pedestrian","cycleway","bridleway","track","corridor","platform"): 
            if t.get("highway"): continue
        if e.get("type")=="node":
            if "lat" in e and "lon" in e:
                writer.add({"type":"node","id":e.get("id"),"lat":e["lat"],"lon":e["lon"],"tags":t},"osm_context"); n+=1
        elif e.get("geometry"):
            pts=[{"lat":p["lat"],"lon":p["lon"]} for p in e["geometry"] if "lat" in p and "lon" in p]
            if len(pts)>=2:
                writer.add({"type":"way","id":e.get("id"),"tags":t,"geometry":pts},"osm_context"); n+=1
    return n

def main():
    writer=Shards()
    osm_data,osm_index=read_osm_detail_index()
    omt=read_pbf(writer,osm_index)
    read_transport(writer)
    matched,unmatched=read_osm_details(writer,osm_data,MATCHED_OSM_IDS)
    context=read_context(writer)
    writer.close()
    manifest={"schema_version":1,"city":"Manila","source":"Adam-City OpenMapTiles PBF + OpenFreeMap transportation GeoJSON + OSM supplemental details",
      "origin":{"lat":LAT0,"lon":LON0},"cell_size_m":CELL,"cell_halo_m":HALO,
      "dataset_version":hashlib.sha256(json.dumps([sorted(writer.cells),dict(writer.counts)],sort_keys=True).encode()).hexdigest()[:16],
      "cells":sorted(writer.cells),"counts":dict(writer.counts),"openmaptiles_building_features":omt,
      "osm_building_fallback_features":unmatched,"osm_context_features":context,
      "osm_detail_matched_count":matched,"terrain":"disabled_flat_ground",
      "attribution":["© OpenFreeMap / OpenMapTiles","© OpenStreetMap contributors"],
      "license":"OpenStreetMap data ODbL 1.0; OpenFreeMap/OpenMapTiles attribution required"}
    (OUT/"manifest.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({k:manifest[k] for k in ("dataset_version","counts","openmaptiles_building_features","osm_building_fallback_features","osm_context_features","cells")},ensure_ascii=False))
if __name__=="__main__": main()
