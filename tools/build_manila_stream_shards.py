#!/usr/bin/env python3
"""Build Manila offline building/transport shards plus spatial facade-window runtime shards.
"""
from __future__ import annotations
import csv, gzip, hashlib, json, math, os, re
from collections import OrderedDict, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PBF_ROOT = Path(os.environ.get("ADAM_CITY_MANILA_PBF", ROOT / "Adam-City-source/Manila/vector-tiles"))
BUILDING_LAYER = ROOT / "data/facades/cities/manila/layers/building-layer.geojson"
TRANSPORT = ROOT / "data/facades/cities/manila/transportation.geojson"
OSM_CONTEXT = ROOT / "data/offline-world/manila/elements.json"
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


WINDOWS_SOURCE_DIR = ROOT / "data/facades/cities/manila/layers"
WINDOWS_REPORT = ROOT / "data/facades/cities/manila/facade-details-report.json"
WINDOW_TILE_DLAT = 0.003
WINDOW_TILE_DLON = WINDOW_TILE_DLAT / math.cos(math.radians(LAT0))

class WindowShards:
    """Spatially index each generated window surface to the exact 333 m game tile."""
    def __init__(self):
        self.folder = OUT / "window-shards"
        self.folder.mkdir(parents=True, exist_ok=True)
        for pattern in ("*.jsonl", "*.jsonl.gz"):
            for old in self.folder.glob(pattern):
                old.unlink()
        self.handles = OrderedDict()
        self.cells = set()
        self.count = 0

    def add_surface(self, ring, source_feature_id, surface_index, building_source_id):
        if not isinstance(ring, list) or len(ring) < 4:
            return
        points = []
        for p in ring:
            if not isinstance(p, (list, tuple)) or len(p) < 3:
                return
            lon, lat, height = float(p[0]), float(p[1]), float(p[2])
            if not (math.isfinite(lon) and math.isfinite(lat) and math.isfinite(height)):
                return
            points.append({"lon": lon, "lat": lat, "height_m": height})
        sample = points[:4]
        lon = sum(p["lon"] for p in sample) / len(sample)
        lat = sum(p["lat"] for p in sample) / len(sample)
        ix = math.floor(lon / WINDOW_TILE_DLON)
        iz = math.floor(lat / WINDOW_TILE_DLAT)
        cell = f"{ix}_{iz}"
        element = {
            "type": "way",
            "id": sid("manila-facade-window", f"{source_feature_id}:{surface_index}"),
            "tags": {
                "adam:facade_window": "yes",
                "adam:window_source": "facade-windows GeoJSON",
                "adam:building_source_id": str(building_source_id or ""),
                "adam:z_reference": "building_ground_relative_m"
            },
            "geometry": points
        }
        handle = self.handles.pop(cell, None)
        if handle is None:
            handle = (self.folder / (cell + ".jsonl")).open("a", encoding="utf-8")
        self.handles[cell] = handle
        if len(self.handles) > 40:
            self.handles.popitem(last=False)[1].close()
        handle.write(json.dumps(element, ensure_ascii=False, separators=(",", ":")) + "\n")
        self.cells.add(cell)
        self.count += 1

    def close(self):
        for handle in self.handles.values():
            handle.close()
        self.handles.clear()
        hashes = {}
        for path in sorted(self.folder.glob("*.jsonl")):
            target = self.folder / (path.stem + ".jsonl.gz")
            with path.open("rb") as source, target.open("wb") as raw:
                with gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=1) as zipped:
                    while block := source.read(1024 * 1024):
                        zipped.write(block)
            path.unlink()
        for path in sorted(self.folder.glob("*.jsonl.gz")):
            digest = hashlib.sha256()
            with path.open("rb") as source:
                while block := source.read(1024 * 1024):
                    digest.update(block)
            hashes[path.stem] = digest.hexdigest()
        return hashes

def read_facade_windows():
    """Convert saved 3D GeoJSON window polygons into tile-local runtime shards."""
    if not WINDOWS_REPORT.is_file():
        raise SystemExit(f"Missing Manila facade window report: {WINDOWS_REPORT}")
    report = json.loads(WINDOWS_REPORT.read_text(encoding="utf-8"))
    if report.get("status") != "success":
        raise SystemExit("Manila facade window report is not successful")
    expected = int(report.get("outputs", {}).get("facade-windows", {}).get("window_surfaces", 0))
    files = sorted(WINDOWS_SOURCE_DIR.glob("facade-windows-*.geojson"))
    if not files or expected <= 0:
        raise SystemExit("Saved Manila facade-windows GeoJSON chunks are missing or empty")
    writer = WindowShards()
    feature_count = 0
    for file_index, path in enumerate(files, 1):
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            raise SystemExit(f"Cannot read facade windows chunk {path}: {exc}")
        features = data.get("features") if data.get("type") == "FeatureCollection" else None
        if not isinstance(features, list):
            raise SystemExit(f"Invalid facade windows FeatureCollection: {path}")
        for feature in features:
            feature_count += 1
            props = feature.get("properties") or {}
            geometry = feature.get("geometry") or {}
            if geometry.get("type") != "MultiPolygon":
                continue
            source_id = str(feature.get("id") or feature_count)
            building_id = props.get("building_source_id", "")
            surface_index = 0
            for polygon in geometry.get("coordinates") or []:
                if not polygon:
                    continue
                surface_index += 1
                writer.add_surface(polygon[0], source_id, surface_index, building_id)
        print(f"WINDOW CHUNK {file_index}/{len(files)}: {path.name}; surfaces={writer.count:,}; tiles={len(writer.cells):,}", flush=True)
    if writer.count != expected:
        raise SystemExit(f"Window surface count mismatch: expected {expected:,}, indexed {writer.count:,}")
    hashes = writer.close()
    return {
        "source_features": feature_count,
        "surfaces": writer.count,
        "tiles": sorted(writer.cells),
        "sha256": hashes,
        "tile_count": len(writer.cells),
        "source_files": len(files)
    }

def emit_polygon(writer, source, rawid, rings, tags, layer):
    for i,ring in enumerate(rings):
        pts=coords_to_points(ring,True)
        if len(pts)>=4:
            writer.add({"type":"way","id":sid(source,rawid,i),"tags":dict(tags),"geometry":pts},layer)

PBF_ROAD_COUNT=0

PBF_ROAD_COUNT=0

def read_pbf(writer):
    try: import mapbox_vector_tile
    except ImportError as e: raise SystemExit("Install mapbox-vector-tile before importing Manila PBF") from e
    files=sorted(PBF_ROOT.rglob("*.pbf")) if PBF_ROOT.is_dir() else []
    if not files:
        print(f"SKIP optional PBF roads/POIs: no files under {PBF_ROOT}; using transportation.geojson for Manila roads and names", flush=True)
        return 0
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

def _load_feature_collection(path, required=False):
    if not path.is_file() or path.stat().st_size == 0:
        if required:
            raise SystemExit(f"Missing or empty required Manila building source: {path}")
        return []
    try:
        data=json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise SystemExit(f"Cannot read Manila building source {path}: {exc}")
    if data.get("type") != "FeatureCollection" or not isinstance(data.get("features"),list):
        raise SystemExit(f"Invalid Manila building GeoJSON FeatureCollection: {path}")
    return data["features"]

def _building_key(feature):
    p=feature.get("properties") or {}
    raw=p.get("osm_feature_id") or p.get("osm_way_id") or p.get("osm_id")
    if not raw and p.get("id") not in (None,""):
        raw=f'{p.get("type","way")}/{p["id"]}'
    if not raw and feature.get("id") not in (None,""):
        feature_id=str(feature["id"]).strip()
        # The canonical vector-tile importer stores source feature IDs as
        # "<source>|<layer>|id:<numeric OSM ID>". Normalize that ID to the
        # same way/<id> key used by the saved OSM footprint/detail extracts,
        # so all three sources enrich one building instead of creating twins.
        match=re.search(r"(?:^|\|)id:(\d+)$",feature_id)
        if match:
            raw=f"way/{match.group(1)}"
        elif re.fullmatch(r"\d+",feature_id):
            raw=f"way/{feature_id}"
        else:
            raw=feature_id
    if raw in (None,""):
        raw=hashlib.sha256(json.dumps(feature.get("geometry"),sort_keys=True).encode()).hexdigest()
    return str(raw).strip()

def _building_tags(feature):
    p=feature.get("properties") or {}
    tags=dict(p.get("tags") or {}) if isinstance(p.get("tags"),dict) else {}
    for key,value in p.items():
        if key in ("tags","type","id","osm_feature_id","osm_way_id","osm_id") or value in (None,"",[],{}):
            continue
        tags.setdefault(key,text(value))
    return {str(k):text(v) for k,v in tags.items() if v not in (None,"")}

def read_geojson_buildings(writer):
    """Render Manila buildings exclusively from the saved building-layer GeoJSON."""
    features = _load_feature_collection(BUILDING_LAYER, required=True)
    added = named = with_height = with_levels = with_color = parts = 0
    for feature in features:
        geometry = feature.get("geometry") or {}
        if geometry.get("type") not in ("Polygon", "MultiPolygon"):
            continue
        tags = _building_tags(feature)
        building = text(tags.get("building") or tags.get("building:part"))
        if not building or building.lower() == "no":
            continue
        rings = polygons(geometry)
        if not rings:
            continue
        if not tags.get("building") and tags.get("building:part"):
            tags["building"] = "yes"
        source_id = _building_key(feature)
        tags["adam:source"] = "Manila building-layer.geojson"
        tags["adam:building_sources"] = "building-layer.geojson"
        tags["adam:source_feature_id"] = source_id
        emit_polygon(writer, "manila-building-layer", source_id, rings, tags, "manila_buildings")
        added += 1
        if tags.get("name") or tags.get("name:ar") or tags.get("official_name"):
            named += 1
        if any(tags.get(k) for k in ("height", "building:height", "height:meters", "adam:render_height_m")):
            with_height += 1
        if any(tags.get(k) for k in ("building:levels", "num_floors", "levels")):
            with_levels += 1
        if any(tags.get(k) for k in ("building:colour", "building:color", "facade:colour",
                                      "facade:color", "colour", "building:material")):
            with_color += 1
        if tags.get("building:part"):
            parts += 1
    if added == 0:
        raise SystemExit("No usable building polygons found in Manila building-layer.geojson")
    count = len(features)
    return {
        "features": added, "named": named, "with_height": with_height,
        "with_levels": with_levels, "with_color": with_color,
        "building_parts": parts,
        "source_feature_counts": {"building_layer_geojson": count},
        "source_usage_counts": {"building_layer_geojson": added},
        "matched_features": {},
        "detail_nodes": 0,
        "source_files": {"building_layer_geojson": True}
    }

def read_transport(writer, label_only=False):
    """Use transportation.geojson for street labels, never nearest-road name guessing."""
    if not TRANSPORT.is_file() or TRANSPORT.stat().st_size == 0:
        raise SystemExit(f"Missing or empty Manila transportation GeoJSON: {TRANSPORT}")
    data=json.loads(TRANSPORT.read_text(encoding="utf-8"))
    added=0; named=0
    road_classes={"motorway","trunk","primary","secondary","tertiary","minor","residential",
        "unclassified","service","living_street","road","street","path","footway","pedestrian",
        "steps","cycleway","track","busway","raceway"}
    street_suffix=re.compile(r"\b(street|st|road|rd|avenue|ave|boulevard|blvd|highway|hwy|drive|dr|lane|ln|circle|circ|place|plaza|way|parkway|pkwy|expressway|esplanade)\b",re.I)
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
        if not tags.get("highway"):
            tags["highway"]={"minor":"residential","street":"unclassified"}.get(highway,highway)
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
    writer=Shards()
    building_stats=read_geojson_buildings(writer)
    read_pbf(writer)
    # GeoJSON transportation is the available canonical road source. It carries
    # both road geometry and names; PBF supplements are optional, never required.
    transport_stats=read_transport(writer,label_only=False)
    thematic=read_thematic_csv(writer)
    context=read_context(writer)
    writer.close()
    # Facade-window GeoJSON is archived and disabled in the Android runtime.
    # Keep the builder usable in the APK workflow's sparse checkout without
    # requiring those archived files.
    if WINDOWS_REPORT.is_file():
        window_stats=read_facade_windows()
    else:
        window_stats={"source_features":0,"surfaces":0,"tiles":[],"sha256":{},"tile_count":0,"source_files":0}
    shard_hashes={}
    for shard in sorted((OUT/"shards").glob("*.jsonl.gz")):
        h=hashlib.sha256()
        with shard.open("rb") as f:
            while block:=f.read(1024*1024): h.update(block)
        shard_hashes[shard.stem]=h.hexdigest()
    manifest={"schema_version":5,"city":"Manila","source":"Manila building-layer.geojson; OpenMapTiles PBF POIs; OpenFreeMap transportation street geometry/names",
      "building_source":"data/facades/cities/manila/layers/building-layer.geojson",
      "building_sources":["data/facades/cities/manila/layers/building-layer.geojson"],
      "building_source_files":building_stats["source_files"],
      "building_source_feature_counts":building_stats["source_feature_counts"],
      "building_source_usage_counts":building_stats["source_usage_counts"],
      "building_source_matched_features":building_stats["matched_features"],
      "building_part_features":building_stats["building_parts"],
      "building_detail_nodes":building_stats["detail_nodes"],
      "building_features":building_stats["features"],"building_named_features":building_stats["named"],
      "building_features_with_height":building_stats["with_height"],"building_features_with_levels":building_stats["with_levels"],"building_features_with_color":building_stats["with_color"],
      "transportation_name_source":"data/facades/cities/manila/transportation.geojson",
      "transportation_label_features":transport_stats["features"],"transportation_named_features":transport_stats["named"],
      "building_deduplication":"none; source features are rendered directly from building-layer.geojson",
      "excluded_building_sources":[],
      "origin":{"lat":LAT0,"lon":LON0},"cell_size_m":CELL,"cell_halo_m":HALO,
      "dataset_version":hashlib.sha256(json.dumps({"shards":shard_hashes,"windows":window_stats["sha256"]},sort_keys=True).encode()).hexdigest()[:16],
      "shard_sha256":shard_hashes,
      "window_source":"data/facades/cities/manila/layers/facade-windows-*.geojson",
      "window_source_files":window_stats["source_files"],
      "window_source_features":window_stats["source_features"],
      "window_surfaces":window_stats["surfaces"],
      "window_tile_size_m":333.396,
      "window_tiles":window_stats["tiles"],
      "window_tile_count":window_stats["tile_count"],
      "window_shard_sha256":window_stats["sha256"],
      "cells":sorted(writer.cells),"counts":dict(writer.counts),"openmaptiles_transportation_features":PBF_ROAD_COUNT,
      "osm_context_features":context,"thematic_supplement_features":thematic,
      "terrain":"disabled_flat_ground",
      "attribution":["© OpenFreeMap / OpenMapTiles","© OpenStreetMap contributors"],
      "license":"OpenStreetMap data ODbL 1.0; OpenFreeMap/OpenMapTiles attribution required"}
    (OUT/"manifest.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps({k:manifest[k] for k in ("dataset_version","building_features","building_named_features","building_features_with_height","building_features_with_levels","building_features_with_color","transportation_label_features","transportation_named_features","counts","openmaptiles_transportation_features","window_surfaces","window_tile_count","cells")},ensure_ascii=False))
if __name__=="__main__": main()
