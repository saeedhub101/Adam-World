#!/usr/bin/env python3
"""Build incremental 1 km Manila offline shards from Adam-City OpenMapTiles PBF,
OpenFreeMap transportation GeoJSON, and OSM building-detail supplements.
"""
from __future__ import annotations
import csv, gzip, hashlib, json, math, os
from collections import OrderedDict, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PBF_ROOT = Path(os.environ.get("ADAM_CITY_MANILA_PBF", ROOT / "Adam-City-source/Manila/vector-tiles"))
BUILDINGS = ROOT / "data/facades/cities/manila/buildings.geojson"
TRANSPORT = ROOT / "data/facades/cities/manila/transportation.geojson"
DETAILS = ROOT / "data/facades/cities/manila/building-details.geojson"
FOOTPRINTS = ROOT / "data/facades/cities/manila/building-footprints.geojson"
OVERTURE_BUILDINGS = ROOT / "data/full-city-imports/manila/02_buildings_overture/building"
OVERTURE_PARTS = ROOT / "data/full-city-imports/manila/02_buildings_overture/building_part"
OSM_CONTEXT = ROOT / "data/offline-world/manila/elements.json"
BUILDING_CSV = Path(os.environ.get("ADAM_CITY_BUILDING_CSV", ROOT / "Adam-City-source/building-data/buildings.csv"))
POI_CSV = Path(os.environ.get("ADAM_CITY_POI_CSV", ROOT / "Adam-City-source/ermita/maps/generated/ermita-public_services.csv"))
ACCESS_CSV = Path(os.environ.get("ADAM_CITY_ACCESS_CSV", ROOT / "Adam-City-source/ermita/maps/generated/ermita-accessibility.csv"))
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
    if typ=="Polygon": return [ring(c[0])] if c else []
    if typ=="MultiPolygon": return [ring(poly[0]) for poly in c if poly]
    return []

def polygons(g):
    if g.get("type")=="Polygon":
        c=g.get("coordinates",[])
        return c[:1]
    if g.get("type")=="MultiPolygon":
        return [poly[0] for poly in g.get("coordinates",[]) if poly]
    return []

def lines(g):
    if g.get("type")=="LineString": return [g.get("coordinates",[])]
    if g.get("type")=="MultiLineString": return g.get("coordinates",[])
    return []

def tile_line_points(coords,x,y,z,extent):
    return [tile_lonlat(p[0]+x*extent,p[1]+y*extent,z,extent) for p in coords if len(p)>=2]

def lines_unused(g):
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
        self.folder=OUT/"shards"
        self.folder.mkdir(parents=True,exist_ok=True)
        for p in self.folder.glob("*.jsonl"): p.unlink()
        for p in self.folder.glob("*.jsonl.gz"): p.unlink()
        self.handles=OrderedDict(); self.counts=defaultdict(int); self.cells=set()
    def add(self,e,layer):
        pts=e.get("geometry") or []
        if e.get("type")=="node" and "lat" in e and "lon" in e:
            pts=[{"lat":float(e["lat"]),"lon":float(e["lon"])}]
        for cell in cells_for(pts):
            h=self.handles.pop(cell,None)
            if h is None: h=(self.folder/(cell+".jsonl")).open("a",encoding="utf-8")
            self.handles[cell]=h
            if len(self.handles)>40: self.handles.popitem(last=False)[1].close()
            h.write(json.dumps(e,ensure_ascii=False,separators=(",",":"))+"\n")
            self.cells.add(cell); self.counts[layer]+=1
    def close(self):
        for h in self.handles.values(): h.close()
        self.handles.clear()
        for p in self.folder.glob("*.jsonl"):
            with p.open("rb") as src, (self.folder/(p.stem+".jsonl.gz")).open("wb") as raw:
                with gzip.GzipFile(fileobj=raw,mode="wb",compresslevel=6) as dst:
                    while b:=src.read(1024*1024): dst.write(b)
            p.unlink()

def tags_from_omt(p):
    t={"building":text(p.get("class") or p.get("subtype") or "yes"),"adam:source":"OpenMapTiles/OpenFreeMap"}
    mapping={"name":"name","height":"height","render_height":"adam:render_height_m","render_min_height":"adam:render_min_height_m",
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
PRIMARY_POLYGONS = []
PRIMARY_SPATIAL = defaultdict(list)
PRIMARY_GRID_M = 100.0

def primary_cells(ring):
    pts=[p for p in ring if len(p)>=2]
    if not pts: return []
    gx0=math.floor((min(p[0] for p in pts)-LON0)*KX/PRIMARY_GRID_M)
    gx1=math.floor((max(p[0] for p in pts)-LON0)*KX/PRIMARY_GRID_M)
    gy0=math.floor(-(max(p[1] for p in pts)-LAT0)*KY/PRIMARY_GRID_M)
    gy1=math.floor(-(min(p[1] for p in pts)-LAT0)*KY/PRIMARY_GRID_M)
    return [(x,y) for x in range(gx0,gx1+1) for y in range(gy0,gy1+1)]

def add_primary_polygon(ring):
    idx=len(PRIMARY_POLYGONS)
    PRIMARY_POLYGONS.append(ring)
    for cell in primary_cells(ring): PRIMARY_SPATIAL[cell].append(idx)


def point_in_ring(point, ring):
    x,y=point; inside=False
    if len(ring)<3: return False
    for i,a in enumerate(ring):
        b=ring[(i+1)%len(ring)]
        ax,ay=float(a[0]),float(a[1]); bx,by=float(b[0]),float(b[1])
        if ((ay>y)!=(by>y)) and x < (bx-ax)*(y-ay)/(by-ay+1e-30)+ax:
            inside=not inside
    return inside

def overlaps_primary(ring):
    """Reject duplicate footprints/parts already represented by a primary OMT footprint.
    Uses polygon containment and vertex coverage rather than a centre-distance threshold.
    """
    pts=[(float(p[0]),float(p[1])) for p in ring if len(p)>=2]
    if len(pts)>1 and pts[0]==pts[-1]: pts=pts[:-1]
    if len(pts)<3: return False
    cx=sum(p[0] for p in pts)/len(pts); cy=sum(p[1] for p in pts)/len(pts)
    candidate_ids=set()
    for cell in primary_cells(pts):
        candidate_ids.update(PRIMARY_SPATIAL.get(cell,()))
    for idx in candidate_ids:
        primary=PRIMARY_POLYGONS[idx]
        if not primary: continue
        ppts=[(float(p[0]),float(p[1])) for p in primary if len(p)>=2]
        if len(ppts)>1 and ppts[0]==ppts[-1]: ppts=ppts[:-1]
        if len(ppts)<3: continue
        pcx=sum(p[0] for p in ppts)/len(ppts); pcy=sum(p[1] for p in ppts)/len(ppts)
        if point_in_ring((cx,cy),ppts) or point_in_ring((pcx,pcy),pts):
            return True
        covered=sum(1 for p in pts if point_in_ring(p,ppts))
        if covered/max(1,len(pts)) >= 0.60:
            return True
    return False

def read_building_csv_index():
    index={}
    if not BUILDING_CSV.is_file(): return index
    mapping={"name":"name","official_name":"official_name","alternate_names":"alt_name",
      "street":"addr:street","house_number":"addr:housenumber","postcode":"addr:postcode",
      "full_address":"addr:full","building_type":"building","building_part":"building:part",
      "building_use":"building:use","height_m":"height","floor_count":"building:levels",
      "underground_floor_count":"building:levels:underground","minimum_height_m":"min_height",
      "roof_shape":"roof:shape","roof_material":"roof:material","roof_colour":"roof:colour",
      "facade_material":"building:material","facade_colour":"building:colour",
      "window_material":"building:windows:material","window_colour":"building:windows:colour",
      "door_material":"building:doors:material","door_colour":"building:doors:colour",
      "year_built_or_opened":"start_date","architect":"architect","heritage_status":"heritage"}
    with BUILDING_CSV.open(encoding="utf-8",newline="") as f:
        for row in csv.DictReader(f):
            key=str(row.get("osm_feature_id") or "").split("/")[-1]
            if not key: continue
            tags={}
            for src,dst in mapping.items():
                value=row.get(src)
                if value not in (None,""): tags[dst]=value.strip()
            if tags: index[key]=tags
    return index

def read_osm_detail_index():
    if not DETAILS.is_file() or DETAILS.stat().st_size == 0:
        return {"type":"FeatureCollection","features":[]}, {}
    data=json.loads(DETAILS.read_text(encoding="utf-8"))
    index={}
    for f in data.get("features",[]):
        p=f.get("properties") or {}; tags=p.get("tags") or {}
        if not (tags.get("building") or tags.get("building:part")): continue
        for raw in (p.get("id"),p.get("osm_way_id"),p.get("osm_feature_id")):
            if raw is not None: index[str(raw).split("/")[-1]]=tags
    return data,index

def osm_source_key(raw):
    value=str(raw or "").split("/")[-1].split("@")[0].strip()
    if value[:1].lower() in ("w","n","r"):
        value=value[1:]
    return value if value.isdigit() else ""

def read_overture_buildings(writer):
    """Use Overture's citywide GeoJSON building polygons as the primary Manila footprints."""
    index={}; added=0
    if not OVERTURE_BUILDINGS.is_dir():
        return index, added
    mapping={"height":"height","num_floors":"building:levels",
      "facade_material":"building:material","facade_color":"building:colour",
      "roof_material":"roof:material","roof_color":"roof:colour","roof_shape":"roof:shape",
      "min_floor":"building:min_level"}
    for path in sorted(OVERTURE_BUILDINGS.glob("part-*.geojson")):
        data=json.loads(path.read_text(encoding="utf-8"))
        for i,feature in enumerate(data.get("features",[])):
            props=feature.get("properties") or {}
            if props.get("is_underground") is True:
                continue
            rings=polygons(feature.get("geometry") or {})
            if not rings or not rings[0]:
                continue
            tags={"building":"yes","adam:source":"Overture Maps building",
                  "adam:overture_building_id":text(props.get("id") or feature.get("id") or "")}
            for source,target in mapping.items():
                value=props.get(source)
                if value not in (None,"",[],{}):
                    tags[target]=text(value)
            name=text(props.get("names"))
            if name:
                tags["name"]=name
            for source in props.get("sources") or []:
                if not isinstance(source,dict):
                    continue
                key=osm_source_key(source.get("record_id"))
                if not key:
                    continue
                current=index.setdefault(key,{})
                for field,value in tags.items():
                    if value not in (None,"") and current.get(field) in (None,""):
                        current[field]=value
                if key.isdigit():
                    tags.setdefault("adam:source_osm_id",key)
            outer=rings[0]
            emit_polygon(writer,"overture-building",feature.get("id",i),rings,tags,"overture_buildings")
            add_primary_polygon(outer)
            added+=1
    return index, added

MATCHED_OSM_IDS=set()
MATCHED_OVERTURE_IDS=set()
PBF_ROAD_COUNT=0

def read_pbf(writer, osm_index, building_index, overture_index):
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
        # MVT geometry is top-left-origin with Y increasing downward.
        # Keep that orientation for the XYZ tile-to-latitude conversion below.
        tile=mapbox_vector_tile.decode(path.read_bytes(), default_options={"y_coord_down": True})
        # PBF transportation is the primary road source across the imported tile coverage.
        global PBF_ROAD_COUNT
        road_layer=tile.get("transportation")
        road_records=[]
        if road_layer:
            ex=road_layer.get("extent",4096)
            for rf in road_layer.get("features",[]):
                rp=rf.get("properties") or {}; rg=rf.get("geometry") or {}
                cls=text(rp.get("class")).lower(); sub=text(rp.get("subclass")).lower()
                highway_map={"motorway":"motorway","trunk":"trunk","primary":"primary","secondary":"secondary",
                  "tertiary":"tertiary","minor":"residential","service":"service","track":"track",
                  "path":"path","pedestrian":"pedestrian","construction":"construction"}
                tags={"adam:source":"OpenMapTiles/OpenFreeMap"}
                if cls in ("rail","transit","subway","tram","light_rail"):
                    tags["railway"]="subway" if cls=="subway" else "tram" if cls in ("tram","light_rail") else "rail"
                elif cls in highway_map: tags["highway"]=highway_map[cls]
                if sub in ("footway","steps","cycleway","pedestrian","path"): tags["highway"]=sub
                if rp.get("oneway") not in (None,""): tags["oneway"]=text(rp["oneway"])
                if rp.get("layer") not in (None,""): tags["layer"]=text(rp["layer"])
                if rp.get("brunnel")=="bridge": tags["bridge"]="yes"
                if rp.get("brunnel")=="tunnel": tags["tunnel"]="yes"
                if rp.get("service") not in (None,""): tags["service"]=text(rp["service"])
                for part in lines(rg):
                    local=[(float(p[0]),float(p[1])) for p in part if len(p)>=2]
                    if len(local)>=2: road_records.append([local,tags,rf.get("id",len(road_records)),ex])
        # PBF supplies road geometry only. Street names are loaded independently
        # from transportation.geojson so a nearest-line guess cannot mislabel a road.
        for local,tags,rawid,ex in road_records:
            pts=[]
            for px,py in local:
                lon,lat=tile_lonlat(px+x*ex,py+y*ex,z,ex); pts.append({"lon":lon,"lat":lat})
            tags.pop("name",None)
            writer.add({"type":"way","id":sid("omt-transport",rawid),"tags":dict(tags),"geometry":pts},"openmaptiles_transportation")
            PBF_ROAD_COUNT+=1

        # buildings.geojson is the sole source of Manila building polygons.
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
                    cls=text(p.get("class") or p.get("subclass")).lower()
                    if cls in ("hospital","clinic","doctors","dentist","pharmacy","restaurant","fast_food","food_court","cafe","coffee_shop","bar","school","university","police","fire_station","library"):
                        t.setdefault("amenity",cls)
                    elif cls in ("hotel","motel","hostel","guest_house"): t.setdefault("tourism",cls)
                    elif cls in ("mall","supermarket","convenience","department_store","retail"): t.setdefault("shop",cls)
                    elif cls in ("railway_station","station","tram_stop","subway_entrance"): t.setdefault("railway","station")
                    elif cls in ("bus_stop","bus_station"): t.setdefault("highway","bus_stop")
                if len(t)>1:
                    writer.add({"type":"node","id":sid("omt-"+lname,f.get("id",0)),"lat":lat,"lon":lon,"tags":t},"openmaptiles_"+lname)
    return count

def read_geojson_buildings(writer):
    """Import every Manila building exclusively from the canonical buildings.geojson."""
    if not BUILDINGS.is_file() or BUILDINGS.stat().st_size == 0:
        raise SystemExit(f"Missing or empty canonical Manila buildings file: {BUILDINGS}")
    data=json.loads(BUILDINGS.read_text(encoding="utf-8"))
    if data.get("type") != "FeatureCollection" or not isinstance(data.get("features"),list):
        raise SystemExit(f"Invalid Manila buildings GeoJSON FeatureCollection: {BUILDINGS}")
    added=0; named=0; with_height=0; with_levels=0
    for i,feature in enumerate(data["features"]):
        props=feature.get("properties") or {}
        building=text(props.get("building"))
        if not building or building.lower()=="no":
            continue
        rings=polygons(feature.get("geometry") or {})
        if not rings:
            continue
        tags={}
        for key,value in props.items():
            if value not in (None,"",[],{}):
                tags[key]=text(value)
        tags["building"]=building
        tags["adam:source"]="Manila buildings.geojson"
        osm_id=props.get("osm_way_id") or props.get("osm_id")
        if osm_id not in (None,""):
            tags["adam:source_osm_id"]=text(osm_id)
        feature_id=feature.get("id") or osm_id or i
        emit_polygon(writer,"manila-buildings-geojson",feature_id,rings,tags,"manila_buildings_geojson")
        for ring in rings:
            if ring: add_primary_polygon(ring)
        added+=1
        if tags.get("name") or tags.get("name:ar") or tags.get("official_name"): named+=1
        if tags.get("height"): with_height+=1
        if tags.get("building:levels"): with_levels+=1
    if added == 0:
        raise SystemExit(f"No usable building polygons in canonical Manila file: {BUILDINGS}")
    return {"features":added,"named":named,"with_height":with_height,"with_levels":with_levels}

def read_transport(writer, label_only=False):
    """Use transportation.geojson for street labels, never nearest-road name guessing."""
    if not TRANSPORT.is_file() or TRANSPORT.stat().st_size == 0:
        raise SystemExit(f"Missing or empty Manila transportation GeoJSON: {TRANSPORT}")
    data=json.loads(TRANSPORT.read_text(encoding="utf-8"))
    added=0; named=0
    road_classes={"motorway","trunk","primary","secondary","tertiary","minor","residential",
        "unclassified","service","living_street","road","street","path","footway","pedestrian",
        "steps","cycleway","track","busway","raceway"}
    street_suffix=re.compile(r"\\b(street|st|road|rd|avenue|ave|boulevard|blvd|highway|hwy|drive|dr|lane|ln|circle|circ|place|plaza|way|parkway|pkwy|expressway|esplanade)\\b",re.I)
    for i,feature in enumerate(data.get("features",[])):
        p=feature.get("properties") or {}; g=feature.get("geometry") or {}
        highway=text(p.get("highway") or p.get("class") or p.get("subclass")).lower()
        if highway not in road_classes:
            continue
        name=text(p.get("name") or p.get("name:latin") or p.get("name_en") or p.get("name_int") or p.get("name:en"))
        if highway in ("path","footway","pedestrian","steps","cycleway","track") and name and not street_suffix.search(name):
            continue
        if label_only and not name:
            continue
        tags={"adam:source":"OpenFreeMap transportation.geojson"}
        for key in ("class","subclass","highway","railway","route","ref","layer","bridge","tunnel","oneway","surface","width","name:ar","name:en","name:latin"):
            if p.get(key) not in (None,""): tags[key]=text(p[key])
        if name:
            tags["name"]=name
            named+=1
        if label_only: tags["adam:label_only"]="yes"
        if name: tags["name:source"]="OpenFreeMap transportation.geojson"
        for j,line in enumerate(lines(g)):
            pts=coords_to_points(line)
            if len(pts)>=2:
                writer.add({"type":"way","id":sid("ofm-road-name" if label_only else "ofm-road",feature.get("id",i),j),
                    "tags":dict(tags),"geometry":pts},"openfreemap_street_labels" if label_only else "openfreemap_transportation")
                added+=1
    if label_only and named == 0:
        raise SystemExit("No named Manila streets found in transportation.geojson")
    return {"features":added,"named":named}

def read_city_footprints(writer):
    if not FOOTPRINTS.is_file() or FOOTPRINTS.stat().st_size == 0: return 0
    data=json.loads(FOOTPRINTS.read_text(encoding="utf-8"))
    added=0
    for i,f in enumerate(data.get("features",[])):
        p=f.get("properties") or {}; g=f.get("geometry") or {}
        rings=polygons(g)
        if not rings or not rings[0]: continue
        outer=rings[0]
        if overlaps_primary(outer): continue
        center=(sum(q[0] for q in outer)/len(outer),sum(q[1] for q in outer)/len(outer))
        source_tags=p.get("tags") if isinstance(p.get("tags"),dict) else {}
        tags={"building":source_tags.get("building") or "yes",
              "adam:source":"Manila canonical OSM building-footprints GeoJSON"}
        for k,v in source_tags.items():
            if v not in (None,"",[],{}): tags[k]=text(v)
        for src,dst in (("render_height","adam:render_height_m"),("render_min_height","adam:render_min_height_m"),("colour","building:colour")):
            if p.get(src) not in (None,""): tags[dst]=text(p[src])
        if p.get("source_feature_id") not in (None,""): tags["adam:source_feature_id"]=text(p["source_feature_id"])
        emit_polygon(writer,"manila-osm-footprint",f.get("id",i),rings,tags,"manila_osm_building_footprints")
        PRIMARY_CENTROIDS.append(center); add_primary_polygon(outer); added+=1
    return added

def read_overture_parts(writer):
    if not OVERTURE_PARTS.is_dir(): return 0
    added=0
    for path in sorted(OVERTURE_PARTS.glob("part-*.geojson")):
        data=json.loads(path.read_text(encoding="utf-8"))
        for i,f in enumerate(data.get("features",[])):
            p=f.get("properties") or {}; rings=polygons(f.get("geometry") or {})
            if not rings or overlaps_primary(rings[0]): continue
            tags={"building":"yes","building:part":"yes","adam:source":"Overture Maps building_part"}
            for source,target in (("num_floors","building:levels"),("facade_material","building:material"),
                                  ("facade_color","building:colour"),("roof_material","roof:material"),
                                  ("roof_color","roof:colour"),("roof_shape","roof:shape")):
                if p.get(source) not in (None,"",[],{}): tags[target]=text(p[source])
            name=text(p.get("names"))
            if name: tags["name"]=name
            if p.get("min_floor") not in (None,""):
                tags["building:min_level"]=text(p["min_floor"])
                try: tags["min_height"]=str(max(0.0,float(p["min_floor"])*3.2))
                except (TypeError,ValueError): pass
            if p.get("is_underground"): tags["location"]="underground"
            if p.get("building_id"): tags["adam:overture_building_id"]=text(p["building_id"])
            emit_polygon(writer,"overture-building-part",f.get("id",i),rings,tags,"overture_building_parts")
            added+=1
    return added

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
            outer=rings[0] if rings else []
            center=(sum(p[0] for p in outer)/len(outer),sum(p[1] for p in outer)/len(outer)) if outer else None
            already_covered=bool(outer) and overlaps_primary(outer)
            if t.get("building:part"):
                if already_covered:
                    matched+=1
                    continue
                t.setdefault("building","yes")
                if t.get("building:min_level") and not t.get("min_height"):
                    try: t["min_height"]=str(float(t["building:min_level"])*3.2)
                    except (TypeError,ValueError): pass
                emit_polygon(writer,"osm-building-part",osm_id,rings,t,"osm_building_parts")
                unmatched+=1
            elif rawkey not in primary_ids and not already_covered:
                emit_polygon(writer,"osm-footprint",osm_id,rings,t,"osm_building_fallback")
                add_primary_polygon(outer)
                unmatched+=1
            else: matched+=1
    return matched,unmatched

def read_thematic_csv(writer):
    count=0
    for path,layer in ((POI_CSV,"osm_public_services"),(ACCESS_CSV,"osm_accessibility")):
        if not path.is_file(): continue
        with path.open(encoding="utf-8",newline="") as f:
            for row in csv.DictReader(f):
                try: lat=float(row["latitude"]); lon=float(row["longitude"])
                except (KeyError,TypeError,ValueError): continue
                try: tags=json.loads(row.get("tags_json") or "{}")
                except json.JSONDecodeError: tags={}
                if row.get("name") and not tags.get("name"): tags["name"]=row["name"]
                tags["adam:source"]="OpenStreetMap supplemental CSV"
                raw=row.get("osm_feature_id") or f"{lat:.7f}:{lon:.7f}"
                writer.add({"type":"node","id":sid(layer,raw),"lat":lat,"lon":lon,"tags":tags},layer)
                count+=1
    return count

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
    if not BUILDINGS.is_file() or BUILDINGS.stat().st_size == 0:
        raise SystemExit(f"Missing or empty canonical Manila buildings file: {BUILDINGS}")
    writer=Shards()
    building_stats=read_geojson_buildings(writer)
    read_pbf(writer,{}, {}, {})
    # Always import dedicated street-name geometry. With PBF roads these are
    # label-only overlays; without PBF roads they also provide road surfaces.
    transport_stats=read_transport(writer,label_only=PBF_ROAD_COUNT>0)
    thematic=read_thematic_csv(writer)
    context=read_context(writer)
    writer.close()
    shard_hashes={}
    for shard in sorted((OUT/"shards").glob("*.jsonl.gz")):
        h=hashlib.sha256()
        with shard.open("rb") as f:
            while block:=f.read(1024*1024): h.update(block)
        shard_hashes[shard.stem]=h.hexdigest()
    manifest={"schema_version":2,"city":"Manila","source":"buildings.geojson (sole building source) + OpenMapTiles PBF road geometry/POIs + OpenFreeMap transportation.geojson street names",
      "building_source":"data/facades/cities/manila/buildings.geojson",
      "building_features":building_stats["features"],"building_named_features":building_stats["named"],
      "building_features_with_height":building_stats["with_height"],"building_features_with_levels":building_stats["with_levels"],
      "transportation_name_source":"data/facades/cities/manila/transportation.geojson",
      "transportation_label_features":transport_stats["features"],"transportation_named_features":transport_stats["named"],
      "building_deduplication":"canonical-buildings-geojson-only-v1",
      "origin":{"lat":LAT0,"lon":LON0},"cell_size_m":CELL,"cell_halo_m":HALO,
      "dataset_version":hashlib.sha256(json.dumps(shard_hashes,sort_keys=True).encode()).hexdigest()[:16],
      "shard_sha256":shard_hashes,
      "cells":sorted(writer.cells),"counts":dict(writer.counts),"openmaptiles_transportation_features":PBF_ROAD_COUNT,
      "osm_context_features":context,"thematic_supplement_features":thematic,
      "terrain":"disabled_flat_ground",
      "attribution":["© OpenFreeMap / OpenMapTiles","© OpenStreetMap contributors"],
      "license":"OpenStreetMap data ODbL 1.0; OpenFreeMap/OpenMapTiles attribution required"}
    (OUT/"manifest.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({k:manifest[k] for k in ("dataset_version","building_features","building_named_features","building_features_with_height","building_features_with_levels","transportation_label_features","transportation_named_features","counts","openmaptiles_transportation_features","cells")},ensure_ascii=False))
if __name__=="__main__": main()
