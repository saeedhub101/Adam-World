#!/usr/bin/env python3
"""Build independently downloadable 1 km Amman shards from committed Overture data."""
from __future__ import annotations
import gzip, hashlib, json, math, shutil
from collections import OrderedDict, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "data/full-city-imports/amman"
OUTPUT = ROOT / "data/offline-city-stream/amman"
LAT0, LON0 = 31.9522646, 35.9389907
KY = 111132.0
KX = 111320.0 * math.cos(math.radians(LAT0))
CELL = 1000.0

ROAD = {
    "motorway":"motorway", "trunk":"trunk", "primary":"primary", "secondary":"secondary",
    "tertiary":"tertiary", "residential":"residential", "living_street":"living_street",
    "service":"service", "motorway_link":"motorway_link", "trunk_link":"trunk_link",
    "primary_link":"primary_link", "secondary_link":"secondary_link", "tertiary_link":"tertiary_link",
    "pedestrian":"pedestrian", "footway":"footway", "path":"path", "steps":"steps",
    "cycleway":"cycleway", "track":"track", "bridleway":"path", "driveway":"service",
    "parking_aisle":"service", "alley":"service", "unknown":"unclassified"
}

def sid(value, part=0):
    raw=(str(value)+":"+str(part)).encode()
    return int.from_bytes(hashlib.blake2b(raw,digest_size=8).digest(),"big") & 0x7fffffffffffffff

def val(v):
    if v is None:
        return ""
    if isinstance(v, dict):
        for key in ("value", "primary", "common", "name", "height", "meters", "feet"):
            if v.get(key) is not None:
                result = val(v[key])
                unit = v.get("unit") or v.get("units")
                if key == "value" and unit and result:
                    return f"{result} {val(unit)}"
                if result:
                    return result
        return ""
    if isinstance(v, list):
        for item in v:
            result = val(item)
            if result:
                return result
        return ""
    return str(v)

def name_value(v):
    return val(v.get("primary") or v.get("common")) if isinstance(v,dict) else val(v)

def polygon_rings(g):
    c=g.get("coordinates") or []
    if g.get("type")=="Polygon": return [c[0]] if c else []
    if g.get("type")=="MultiPolygon": return [p[0] for p in c if p]
    return []

def line_parts(g):
    c=g.get("coordinates") or []
    if g.get("type")=="LineString": return [c]
    if g.get("type")=="MultiLineString": return c
    return []

def points(coords, closed=False):
    out=[{"lat":float(p[1]),"lon":float(p[0])} for p in coords if isinstance(p,(list,tuple)) and len(p)>=2]
    if closed and len(out)>=3 and (out[0]["lat"]!=out[-1]["lat"] or out[0]["lon"]!=out[-1]["lon"]): out.append(dict(out[0]))
    return out

def poi_tags(category, props):
    c=(category or "").lower().replace(" ","_")
    if any(v in c for v in ("hospital","medical_centre","healthcare.hospital")): return {"amenity":"hospital"}
    if any(v in c for v in ("clinic","doctor","dentist","medical_clinic")): return {"amenity":"clinic"}
    if any(v in c for v in ("restaurant","fast_food","food_court")): return {"amenity":"restaurant"}
    if any(v in c for v in ("cafe","coffee_shop","coffee")): return {"amenity":"cafe"}
    if any(v in c for v in ("hotel","motel","hostel","guest_house")): return {"tourism":"hotel"}
    if any(v in c for v in ("mall","shopping_centre","shopping_center")): return {"shop":"mall"}
    if any(v in c for v in ("supermarket","convenience","department_store","retail","store")): return {"shop":c.split(".")[-1]}
    for key in ("amenity","shop","tourism","office","healthcare"):
        if props.get(key): return {key:val(props[key]).lower()}
    return {}

def make_elements(feature, layer, part=False):
    p=feature.get("properties") or {}; g=feature.get("geometry") or {}; kind=g.get("type")
    raw=feature.get("id") or p.get("id") or p.get("source_id") or "feature"
    if layer=="building":
        cls=val(p.get("class") or p.get("subtype") or "yes").lower() or "yes"
        tags={"building":cls,"adam:source":"Overture Maps"}
        if part: tags["building:part"]="yes"
        fields={"height":"height","min_height":"min_height","num_floors":"building:levels",
                "num_floors_underground":"building:levels:underground","underground_height":"adam:underground:height",
                "plinth_height":"building:plinth:height","roof_height":"roof:height",
                "roof_shape":"roof:shape","roof_material":"roof:material","roof_color":"roof:colour",
                "facade_material":"building:material","facade_color":"building:colour",
                "facade_type":"building:use","use":"building:use","name":"name","names":"name",
                "address":"addr:full","housenumber":"addr:housenumber","street":"addr:street"}
        for src,dst in fields.items():
            if p.get(src) is not None: tags[dst]=val(p[src])
        if p.get("is_underground") is True: tags["location"]="underground"
        # Keep the original source classification alongside the normalized building tag.
        if p.get("class") is not None: tags["adam:source:building_class"]=val(p["class"])
        if p.get("subtype") is not None: tags["adam:source:building_subtype"]=val(p["subtype"])
        nm=name_value(p.get("names") or p.get("name"))
        if nm: tags["name"]=nm
        address=p.get("address") or p.get("addresses")
        if isinstance(address,dict):
            number=val(address.get("number") or address.get("house_number") or address.get("houseNumber"))
            if number: tags["addr:housenumber"]=number
        # Preserve OSM-derived activity tags embedded in Overture source_tags.
        for pair in p.get("source_tags") or []:
            if isinstance(pair,(list,tuple)) and len(pair)>=2 and pair[0] in ("amenity","shop","tourism","office","healthcare","name","addr:housenumber"):
                if pair[1] is not None: tags[str(pair[0])]=val(pair[1])
        for key in ("amenity","shop","tourism","office","healthcare"):
            if p.get(key) is not None: tags[key]=val(p[key])
        if p.get("is_underground") is True: tags["location"]="underground"
        return [{"type":"way","id":sid(raw,i),"tags":tags,"geometry":pts}
                for i,ring in enumerate(polygon_rings(g)) if len(pts:=points(ring,True))>=4]
    if layer=="transport":
        st={}
        for pair in p.get("source_tags") or []:
            if isinstance(pair,(list,tuple)) and len(pair)>=2: st[str(pair[0])]=val(pair[1])
        cls=val(p.get("class")).lower()
        subtype=val(p.get("subtype")).lower()
        tags={"adam:source":"Overture Maps"}
        highway=ROAD.get(cls)
        if highway and subtype in ("","road"):
            tags["highway"]=highway
        elif cls in ("rail","railway","train","light_rail","tram","subway","metro") or st.get("railway") or st.get("route") in ("train","subway","tram","light_rail"):
            tags["railway"]=st.get("railway") or ("subway" if cls in ("subway","metro") else "tram" if cls in ("tram","light_rail") else "rail")
            if st.get("service"): tags["service"]=st["service"]
        elif st.get("route") in ("bus","trolleybus") or st.get("bus") in ("yes","designated"):
            tags["route"]="bus"
        elif st.get("highway")=="bus_stop" or st.get("public_transport") in ("platform","stop_position","station"):
            tags.update({k:st[k] for k in ("highway","public_transport","bus","name") if k in st})
        else:
            return []
        nm=name_value(p.get("names"))
        if nm: tags["name"]=nm
        for key in ("ref","network","operator","service","layer","bridge","tunnel"):
            if p.get(key) is not None and key not in tags: tags[key]=val(p[key])
        widths=p.get("width_rules")
        if isinstance(widths,list) and widths:
            candidates=[item for item in widths if isinstance(item,dict) and item.get("value") is not None]
            if candidates: tags["width"]=val(candidates[0]["value"])
        if not tags.get("width") and p.get("width") is not None: tags["width"]=val(p["width"])
        surfaces=p.get("road_surface")
        if isinstance(surfaces,list) and surfaces and isinstance(surfaces[0],dict) and surfaces[0].get("value") is not None:
            tags["surface"]=val(surfaces[0]["value"])
        flags=json.dumps(p.get("road_flags") or []).lower()
        if "is_bridge" in flags: tags["bridge"]="yes"
        if "is_tunnel" in flags: tags["tunnel"]="yes"
        out=[]
        if kind=="Point":
            lon,lat=g.get("coordinates",[None,None])[:2]
            if lon is not None and lat is not None: out.append({"type":"node","id":sid(raw),"lon":float(lon),"lat":float(lat),"tags":tags})
        for i,line in enumerate(line_parts(g)):
            pts=points(line)
            if len(pts)>=2: out.append({"type":"way","id":sid(raw,i),"tags":tags,"geometry":pts})
        return out
    value=val(p.get("class") or p.get("subtype") or p.get("value")).lower()
    source_tags={}
    for pair in p.get("source_tags") or []:
        if isinstance(pair,(list,tuple)) and len(pair)>=2: source_tags[str(pair[0])]=val(pair[1]).lower()
    if layer=="place":
        category=val(p.get("primary_category") or p.get("category") or p.get("class")).lower()
        categories=p.get("categories") or []
        if not category and categories: category=val(categories[0]).lower()
        tags=poi_tags(category, p)
        if not tags: return []
        nm=name_value(p.get("names") or p.get("name"))
        if nm: tags["name"]=nm
        if kind=="Point":
            lon,lat=g.get("coordinates",[None,None])[:2]
            return [{"type":"node","id":sid(raw),"lon":float(lon),"lat":float(lat),"tags":tags}] if lon is not None and lat is not None else []
        return [{"type":"way","id":sid(raw,i),"tags":tags,"geometry":pts}
                for i,ring in enumerate(polygon_rings(g)) if len(pts:=points(ring,True))>=4]
    if layer=="water":
        waterway=source_tags.get("waterway",value)
        if kind in ("LineString","MultiLineString"):
            if waterway in ("river","stream","canal","ditch","drain","wadi","tidal_channel"):
                tags={"waterway":waterway,"adam:source":"Overture Maps"}
            elif value in ("coastline","shoreline"):
                tags={"natural":"coastline","adam:source":"Overture Maps"}
            else:
                return []
        elif kind=="Point":
            lon,lat=g.get("coordinates",[None,None])[:2]
            if lon is None or lat is None: return []
            if value in ("swimming_pool","pool"):
                return [{"type":"node","id":sid(raw),"lon":float(lon),"lat":float(lat),
                         "tags":{"leisure":"swimming_pool","adam:source":"Overture Maps"}}]
            return []
        else:
            water=source_tags.get("water",value) or "water"
            tags={"natural":"water","water":water,"adam:source":"Overture Maps"}
            if water in ("reservoir","basin"): tags["landuse"]=water
    elif layer=="infrastructure":
        tags={"adam:source":"Overture Maps"}
        cls=(val(p.get("class") or p.get("subtype") or p.get("type"))).lower()
        infra=source_tags.get("man_made") or source_tags.get("waterway") or cls
        if any(k in infra for k in ("wastewater","sewage","sewer","treatment_plant")):
            tags["man_made"]="wastewater_plant"
        elif any(k in infra for k in ("water_works","waterworks","water_tower","reservoir","pumping_station","pump_station")):
            tags["man_made"]="water_works" if "works" in infra else "water_tower" if "tower" in infra else "pumping_station" if "pump" in infra else "reservoir"
        elif any(k in infra for k in ("pipeline","water_pipe","water_main")):
            tags["man_made"]="pipeline"; tags["substance"]="water"
        elif any(k in infra for k in ("storm_drain","drain","culvert","ditch")):
            tags["waterway"]="drain"
        elif infra in ("bridge","pier","breakwater"):
            tags["man_made"]=infra
        else:
            utility=source_tags.get("utility","").lower()
            substance=source_tags.get("substance","").lower()
            if utility in ("water","sewer") or substance in ("water","sewage"):
                tags["man_made"]="pipeline"; tags["substance"]=substance or utility
            else: return []
        for key in ("name","operator","ref"):
            if p.get(key) is not None: tags[key]=val(p[key])
        if kind=="Point":
            lon,lat=g.get("coordinates",[None,None])[:2]
            return [{"type":"node","id":sid(raw),"lon":float(lon),"lat":float(lat),"tags":tags}] if lon is not None and lat is not None else []
    elif value in ("park","garden","recreation_ground","playground"):
        tags={"leisure":"park" if value=="park" else value,"adam:source":"Overture Maps"}
    elif value in ("forest","wood","trees"):
        tags={"natural":"wood","landuse":"forest","adam:source":"Overture Maps"}
    elif value in ("grass","grassland","meadow"):
        tags={"landuse":"grass","adam:source":"Overture Maps"}
    elif value in ("farmland","orchard","vineyard","cemetery","allotments"):
        tags={"landuse":value,"adam:source":"Overture Maps"}
    elif value in ("sand","beach","dune","desert"):
        tags={"natural":"sand","adam:source":"Overture Maps"}
    elif value in ("wetland","marsh","swamp"):
        tags={"natural":"wetland","adam:source":"Overture Maps"}
    elif value in ("bare_rock","scree","shingle"):
        tags={"natural":"bare_rock","adam:source":"Overture Maps"}
    else: return []
    out=[]
    for i,ring in enumerate(polygon_rings(g)):
        pts=points(ring,True)
        if len(pts)>=4: out.append({"type":"way","id":sid(raw,i),"tags":tags,"geometry":pts})
    for i,line in enumerate(line_parts(g)):
        pts=points(line)
        if len(pts)>=2: out.append({"type":"way","id":sid(raw,100+i),"tags":tags,"geometry":pts})
    return out

class Writer:
    def __init__(self,folder):
        self.folder=folder; folder.mkdir(parents=True,exist_ok=True)
        self.handles=OrderedDict(); self.cells=set(); self.counts=defaultdict(int); self.cell_layers=defaultdict(set)
    def add(self,cell,element,layer):
        h=self.handles.pop(cell,None)
        if h is None: h=(self.folder/(cell+".jsonl")).open("a",encoding="utf-8")
        self.handles[cell]=h
        if len(self.handles)>48:
            _,old=self.handles.popitem(last=False); old.close()
        h.write(json.dumps(element,ensure_ascii=False,separators=(",",":"))+"\n")
        self.cells.add(cell); self.counts[layer]+=1; self.cell_layers[cell].add(layer)
    def close(self):
        for h in self.handles.values(): h.close()
        self.handles.clear()

def cells_for(geom,box):
    # Runtime game tiles are about 333 m wide. A shard selected by tile centre
    # must also contain every feature touching that tile, including across a
    # 1 km shard edge. The 240 m halo covers the tile's half-diagonal.
    halo_lon = 240.0 / KX
    halo_lat = 240.0 / KY
    minx,miny,maxx,maxy=geom.bounds
    # Expand the candidate cell range as well as the intersection test.
    x0=math.floor((minx-halo_lon-LON0)*KX/CELL); x1=math.floor((maxx+halo_lon-LON0)*KX/CELL)
    z0=math.floor(-((maxy+halo_lat)-LAT0)*KY/CELL); z1=math.floor(-((miny-halo_lat)-LAT0)*KY/CELL)
    for x in range(x0,x1+1):
        w=LON0+x*CELL/KX; e=LON0+(x+1)*CELL/KX
        for z in range(z0,z1+1):
            n=LAT0-z*CELL/KY; s=LAT0-(z+1)*CELL/KY
            if geom.intersects(box(w-halo_lon,s-halo_lat,e+halo_lon,n+halo_lat)):
                yield f"{x}_{z}"

def sources():
    # Strict source boundary: only the existing imported Amman directory.
    definitions=[
      ("building","02_buildings_overture/building",False),
      ("building_part","02_buildings_overture/building_part",True),
      # Overture road segments are the authoritative road geometry for offline Amman.
      ("transport","09_transport_overture/segment",False),
      ("land","10_landcover_overture/land",False),
      ("land_cover","10_landcover_overture/land_cover",False),
      ("land_use","10_landcover_overture/land_use",False),
      ("water","10_landcover_overture/water",False),
      ("place","11_places_addresses/place",False),
      ("infrastructure","10_landcover_overture/infrastructure",False)]
    for layer,relative,part in definitions:
        for path in sorted((SOURCE/relative).glob("part-*.geojson")):
            yield layer,path,part

def add_existing_osm_context(writer, box, shape):
    # Merge the already-imported Amman OSM context into the same spatial shards.
    # Do not duplicate primary building or road geometry from the OSM fallback.
    folder=ROOT/"data/offline-world/amman"
    sources=[folder/"elements.json",folder/"transit-routes.json"]
    allowed=("waterway","railway","public_transport","route","man_made","utility","substance","bus","highway")
    for path in sources:
        if not path.is_file():
            continue
        payload=json.loads(path.read_text(encoding="utf-8"))
        for item in payload.get("elements",[]):
            tags=item.get("tags") or {}
            if tags.get("building") or tags.get("building:part"):
                continue
            if tags.get("highway") and tags.get("highway") not in ("bus_stop",):
                continue
            if not any(tags.get(k) for k in allowed):
                continue
            typ=item.get("type")
            geom=None
            if typ=="node" and item.get("lat") is not None and item.get("lon") is not None:
                geom={"type":"Point","coordinates":[float(item["lon"]),float(item["lat"])]}
            elif typ=="way" and item.get("geometry"):
                coords=[]
                for point in item["geometry"]:
                    if point.get("lon") is not None and point.get("lat") is not None:
                        coords.append([float(point["lon"]),float(point["lat"])])
                if len(coords)>=2:
                    geom={"type":"LineString","coordinates":coords}
            if not geom:
                continue
            try:
                geometry=shape(geom)
                if geometry.is_empty:
                    continue
                element={"type":typ,"id":int(item.get("id") or 0),"tags":tags}
                if typ=="node":
                    element["lon"],element["lat"]=geom["coordinates"]
                else:
                    element["geometry"]=[{"lon":xy[0],"lat":xy[1]} for xy in geom.coords]
                for cell in cells_for(geometry,box):
                    writer.add(cell,element,"osm_context")
            except Exception:
                continue
        print("Merged existing Amman OSM context:",path.name,flush=True)


def build():
    import ijson
    from shapely.geometry import box,shape
    files=list(sources())
    if not files:
        raise SystemExit("No usable data in data/full-city-imports/amman")
    if OUTPUT.exists():
        shutil.rmtree(OUTPUT)
    temp=OUTPUT/".tmp"
    shard_dir=OUTPUT/"shards"
    temp.mkdir(parents=True)
    writer=Writer(temp)
    fingerprint=hashlib.sha256()
    try:
        for layer,path,is_part in files:
            fingerprint.update(path.relative_to(ROOT).as_posix().encode())
            with path.open("rb") as hash_stream:
                for block in iter(lambda:hash_stream.read(1024*1024),b""):
                    fingerprint.update(block)
            with path.open("rb") as source:
                for feature in ijson.items(source,"features.item"):
                    try:
                        geometry=feature.get("geometry")
                        if not geometry:
                            continue
                        shape_geometry=shape(geometry)
                        if shape_geometry.is_empty:
                            continue
                        elements=make_elements(feature, "building" if layer=="building_part" else layer, is_part)
                        if not elements:
                            continue
                        for cell in cells_for(shape_geometry,box):
                            for element in elements:
                                writer.add(cell,element,layer)
                    except Exception:
                        continue
            print("Processed imported Amman:",layer,path.name,flush=True)
        add_existing_osm_context(writer,box,shape)
        writer.close()
        if not writer.cells:
            raise RuntimeError("No renderable features found in imported Amman data")
        if writer.counts.get("transport", 0) <= 0:
            raise RuntimeError("No Overture road segments were converted; refusing to publish an offline Amman package without roads")
        shard_dir.mkdir(parents=True)
        metadata={}
        for cell in sorted(writer.cells,key=lambda value:tuple(map(int,value.split("_")))):
            raw=temp/(cell+".jsonl")
            target=shard_dir/(cell+".jsonl.gz")
            with raw.open("rb") as src,target.open("wb") as dst:
                with gzip.GzipFile(fileobj=dst,mode="wb",compresslevel=6,mtime=0) as gz:
                    shutil.copyfileobj(src,gz,1024*1024)
            metadata[cell]={"file":"shards/"+target.name,"bytes":target.stat().st_size}
            raw.unlink()
        version=fingerprint.hexdigest()[:16]
        manifest={"schema_version":1,"city":"Amman","source_directory":"data/full-city-imports/amman",
          "source_datasets":["Overture buildings","Overture building parts","Overture road segments","Overture land cover","Overture water and waterways","Overture infrastructure","Overture places and addresses"],
          "dataset_version":version,"origin":{"lat":LAT0,"lon":LON0},"cell_size_m":CELL,"cell_halo_m":240,
          "cells":sorted(writer.cells,key=lambda value:tuple(map(int,value.split("_")))),
          "shards":metadata,"element_counts_by_layer":dict(writer.counts),"shard_count":len(metadata)}
        temporary=OUTPUT/"manifest.json.tmp"
        temporary.write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding="utf-8")
        temporary.replace(OUTPUT/"manifest.json")
        shutil.rmtree(temp,ignore_errors=True)
        print(json.dumps({"city":"Amman","source":"data/full-city-imports/amman",
          "shards":len(metadata),"elements":dict(writer.counts),"version":version}),flush=True)
    finally:
        writer.close()

if __name__=="__main__":
    build()
