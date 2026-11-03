#!/usr/bin/env python3
"""Generate Manila facade-detail GeoJSON from canonical buildings.geojson only.

Mirrors Adam-City's Ermita facade method: metric wall edges, estimated floors,
repeated windows, ground-floor doors and occasional balconies. Output is 3D
GeoJSON, never GLB. Details are procedural placeholders, not surveyed features.
"""
from __future__ import annotations
import hashlib, json, math, re
from pathlib import Path
from typing import Iterable
from pyproj import Transformer
from shapely.geometry import Polygon, shape
from shapely.ops import transform as geom_transform

ROOT = Path(__file__).resolve().parents[2]
CITY = ROOT / "data/facades/cities/manila"
SOURCE = CITY / "buildings.geojson"
OUT = CITY / "layers"
MAX_CHUNK_BYTES = 35_000_000  # safely below GitHub's 100 MB hard limit
MAX_POLYGONS_PER_FEATURE = 1000
WINDOWS_STEM = "facade-windows"
DOORS_STEM = "facade-doors"
BALCONIES_STEM = "facade-balconies"
REPORT_OUT = CITY / "facade-details-report.json"
TO_METERS = Transformer.from_crs("EPSG:4326", "EPSG:3857", always_xy=True).transform
TO_LONLAT = Transformer.from_crs("EPSG:3857", "EPSG:4326", always_xy=True).transform
ATTRIBUTION = "OpenFreeMap + OpenMapTiles; source buildings.geojson"

def tags_for(feature):
    props = feature.get("properties") or {}
    tags = props.get("tags")
    if isinstance(tags, dict):
        merged = dict(tags)
        for key, value in props.items():
            if key not in ("tags", "type", "id") and value not in (None, "", [], {}):
                merged.setdefault(key, value)
        return merged
    return props

def number(value, minimum, maximum):
    match = re.search(r"\d+(?:\.\d+)?", str(value).replace(",", ".")) if value not in (None, "") else None
    if not match: return None
    result = float(match.group())
    return result if minimum <= result <= maximum else None

def height_for(tags):
    height = number(tags.get("height"), 1.5, 500)
    if height is not None: return height
    levels = number(tags.get("building:levels") or tags.get("levels"), 1, 150)
    return levels * 3.2 if levels is not None else 9.6

def levels_for(tags, height):
    levels = number(tags.get("building:levels") or tags.get("levels"), 1, 150)
    return max(1, min(100, int(levels))) if levels is not None else max(1, round(height / 3.2))

def feature_key(feature, index):
    props = feature.get("properties") or {}
    raw = props.get("osm_feature_id") or props.get("osm_way_id") or props.get("osm_id") or props.get("id") or feature.get("id")
    if raw in (None, ""):
        raw = hashlib.sha256(json.dumps(feature.get("geometry"), sort_keys=True).encode()).hexdigest()
    return str(raw)

def polygon_parts(geometry) -> Iterable[Polygon]:
    if geometry.geom_type == "Polygon": yield geometry
    elif geometry.geom_type == "MultiPolygon": yield from geometry.geoms
    elif hasattr(geometry, "geoms"):
        for part in geometry.geoms:
            if part.geom_type == "Polygon": yield part
            elif part.geom_type == "MultiPolygon": yield from part.geoms

def xyz(mx, my, z):
    lon, lat = TO_LONLAT(mx, my)
    return [round(lon, 8), round(lat, 8), round(z, 3)]

def vertical_rectangle(a, ux, uy, nx, ny, along, width, normal_offset, z0, z1):
    cx, cy, half = a[0]+ux*along+nx*normal_offset, a[1]+uy*along+ny*normal_offset, width/2
    return [[xyz(cx-ux*half,cy-uy*half,z0), xyz(cx+ux*half,cy+uy*half,z0),
             xyz(cx+ux*half,cy+uy*half,z1), xyz(cx-ux*half,cy-uy*half,z1),
             xyz(cx-ux*half,cy-uy*half,z0)]]

def balcony_surfaces(a, ux, uy, nx, ny, along, width, z):
    cx, cy, hw, hd = a[0]+ux*along+nx*.48, a[1]+uy*along+ny*.48, width/2, .375
    deck = [[xyz(cx-ux*hw-nx*hd,cy-uy*hw-ny*hd,z), xyz(cx+ux*hw-nx*hd,cy+uy*hw-ny*hd,z),
             xyz(cx+ux*hw+nx*hd,cy+uy*hw+ny*hd,z), xyz(cx-ux*hw+nx*hd,cy-uy*hw+ny*hd,z),
             xyz(cx-ux*hw-nx*hd,cy-uy*hw-ny*hd,z)]]
    rx, ry, rh = a[0]+ux*along+nx*.82, a[1]+uy*along+ny*.82, width/2
    rail = [[xyz(rx-ux*rh,ry-uy*rh,z), xyz(rx+ux*rh,ry+uy*rh,z),
             xyz(rx+ux*rh,ry+uy*rh,z+.85), xyz(rx-ux*rh,ry-uy*rh,z+.85),
             xyz(rx-ux*rh,ry-uy*rh,z)]]
    return deck, rail

def as_feature(kind, building_id, name, height, levels, polygons):
    if not polygons: return None
    return {"type":"Feature","id":f"{kind}:{hashlib.sha1(building_id.encode()).hexdigest()[:16]}",
        "properties":{"building_source_id":building_id,"building_name":str(name or ""),
        "building_height_m":round(height,2),"estimated_floor_count":levels,"detail_type":kind,
        "z_reference":"building_ground_relative_m",
        "geometry_dimensions":"3D WGS84 positions [longitude, latitude, height_m]",
        "is_procedural_placeholder":True,"source":ATTRIBUTION},
        "geometry":{"type":"MultiPolygon","coordinates":polygons}}

class FeatureWriter:
    """Write bounded-size, independently valid GeoJSON chunks."""
    def __init__(self, directory, stem, name):
        self.directory, self.stem, self.name = directory, stem, name
        self.file = None
        self.tmp = None
        self.current_path = None
        self.current_bytes = 0
        self.current_count = 0
        self.count = 0
        self.files = []
        self.total_bytes = 0
        self.chunk_index = 0

    def _open_chunk(self):
        self.chunk_index += 1
        filename = f"{self.stem}-{self.chunk_index:04d}.geojson"
        self.current_path = self.directory / filename
        self.tmp = self.current_path.with_suffix(".geojson.tmp")
        self.file = self.tmp.open("w", encoding="utf-8")
        header = '{"type":"FeatureCollection","name":' + json.dumps(self.name) + ',"features":['
        self.file.write(header)
        self.current_bytes = len(header.encode("utf-8"))
        self.current_count = 0

    def _close_chunk(self):
        if self.file is None:
            return
        self.file.write("]}\\n")
        self.file.close()
        self.tmp.replace(self.current_path)
        size = self.current_path.stat().st_size
        if size > MAX_CHUNK_BYTES:
            raise RuntimeError(f"Chunk exceeds safe size limit: {self.current_path} ({size:,} bytes)")
        self.files.append({"file": str(self.current_path.relative_to(ROOT)), "features": self.current_count, "bytes": size})
        self.total_bytes += size
        self.file = self.tmp = self.current_path = None

    def add(self, feature):
        if feature is None:
            return
        coordinates = feature.get("geometry", {}).get("coordinates", [])
        if not coordinates:
            return
        # A single source record may contain thousands of separate building surfaces.
        # Split those into smaller features so no individual record can become enormous.
        for offset in range(0, len(coordinates), MAX_POLYGONS_PER_FEATURE):
            piece = dict(feature)
            piece["id"] = f"{feature.get('id', self.stem)}:{offset // MAX_POLYGONS_PER_FEATURE + 1}"
            piece["geometry"] = {"type": "MultiPolygon", "coordinates": coordinates[offset:offset + MAX_POLYGONS_PER_FEATURE]}
            encoded = json.dumps(piece, ensure_ascii=False, separators=(",", ":"))
            encoded_bytes = len(encoded.encode("utf-8"))
            extra = encoded_bytes + (1 if self.current_count else 0)
            if self.file is None:
                self._open_chunk()
            elif self.current_count and self.current_bytes + extra + 3 > MAX_CHUNK_BYTES:
                self._close_chunk()
                self._open_chunk()
            if self.current_count:
                self.file.write(",")
                self.current_bytes += 1
            self.file.write(encoded)
            self.current_bytes += encoded_bytes
            self.current_count += 1
            self.count += 1

    def finish(self):
        if self.file is None:
            self._open_chunk()  # even an empty optional layer is a valid collection
        self._close_chunk()

def main():
    if not SOURCE.is_file() or SOURCE.stat().st_size == 0: raise SystemExit(f"Missing canonical source: {SOURCE}")
    OUT.mkdir(parents=True,exist_ok=True)
    print(f"READ ONLY SOURCE: {SOURCE.relative_to(ROOT)} ({SOURCE.stat().st_size:,} bytes)",flush=True)
    data = json.loads(SOURCE.read_text(encoding="utf-8"))
    if data.get("type")!="FeatureCollection" or not isinstance(data.get("features"),list) or not data["features"]:
        raise SystemExit("buildings.geojson must be a non-empty FeatureCollection")
    source_features = data["features"]
    # Remove only chunks owned by this generator; never touch the canonical input.
    for stem in (WINDOWS_STEM, DOORS_STEM, BALCONIES_STEM):
        for stale in OUT.glob(stem + "-*.geojson*"):
            stale.unlink()
    writers = [FeatureWriter(OUT, WINDOWS_STEM, "Manila facade windows"),
               FeatureWriter(OUT, DOORS_STEM, "Manila facade doors"),
               FeatureWriter(OUT, BALCONIES_STEM, "Manila facade balconies")]
    windows, doors, balconies = writers
    skipped = invalid = building_parts = window_count = door_count = balcony_count = 0
    try:
        for fi, feature in enumerate(source_features):
            try:
                if not feature.get("geometry"): skipped += 1; continue
                geometry = geom_transform(TO_METERS,shape(feature["geometry"]))
                tags = tags_for(feature); height = height_for(tags); levels = levels_for(tags,height)
                floor_h = height/levels; bid = feature_key(feature,fi)
                name = tags.get("name") or tags.get("brand") or tags.get("shop") or tags.get("amenity") or ""
                seed = int(hashlib.sha256(str(bid or name or fi).encode()).hexdigest()[:8],16)
                wp, dp, bp = [], [], []
                for poly in polygon_parts(geometry):
                    if poly.is_empty or not poly.is_valid or poly.area < 12: skipped += 1; continue
                    building_parts += 1
                    ring = list(poly.exterior.coords)
                    for a,b in zip(ring[:-1],ring[1:]):
                        dx,dy=b[0]-a[0],b[1]-a[1]; length=math.hypot(dx,dy)
                        if length < 4.5: continue
                        ux,uy=dx/length,dy/length; nx,ny=-uy,ux
                        count=min(55,max(0,int((length-2.0)/3.0)))
                        if count:
                            usable=min(length-1.2,count*3.0); start=(length-usable)/2
                            for floor in range(levels):
                                z=floor*floor_h+min(1.25,floor_h*.38)
                                wh=min(1.35,max(.55,floor_h*.42))
                                if z+wh>height-.15: continue
                                for j in range(count):
                                    along=start+(j+.5)*usable/count
                                    width=min(1.25,max(.55,usable/count*.55))
                                    wp.append(vertical_rectangle(a,ux,uy,nx,ny,along,width,.065,z,z+wh))
                                    window_count += 1
                        if height>=12 and seed%3==0 and floor_h>2.4 and length>=9:
                            bz=min(height-1.4,floor_h*(1+(seed%max(1,levels-1))))
                            if bz>2.8:
                                bp.extend(balcony_surfaces(a,ux,uy,nx,ny,length*.5,min(2.8,length*.34),bz))
                                balcony_count += 2
                        if length>=7 and height>=2.4:
                            dp.append(vertical_rectangle(a,ux,uy,nx,ny,length*.5,.95,.075,0,1.9))
                            door_count += 1
                windows.add(as_feature("window",bid,name,height,levels,wp))
                doors.add(as_feature("door",bid,name,height,levels,dp))
                balconies.add(as_feature("balcony",bid,name,height,levels,bp))
            except Exception as exc:
                invalid += 1; print(f"SKIP feature {fi}: {exc}",flush=True)
            if (fi+1)%5000==0:
                print(f"PROGRESS {fi+1}/{len(source_features)}; windows={window_count:,}; doors={door_count:,}; balcony surfaces={balcony_count:,}",flush=True)
        if not windows.count or not doors.count: raise RuntimeError("No windows or doors generated")
        for writer in writers: writer.finish()
    except Exception:
        for writer in writers:
            if writer.file is not None and not writer.file.closed: writer.file.close()
        raise
    report={"status":"success","city":"Manila","source_file":str(SOURCE.relative_to(ROOT)),
        "source_feature_count":len(source_features),"building_polygon_parts":building_parts,
        "skipped_features_or_parts":skipped,"invalid_features":invalid,
        "chunk_limits":{"max_file_bytes":MAX_CHUNK_BYTES,"max_polygons_per_feature":MAX_POLYGONS_PER_FEATURE},
        "outputs":{
            WINDOWS_STEM:{"geojson_features":windows.count,"window_surfaces":window_count,"chunks":windows.files,"total_bytes":windows.total_bytes},
            DOORS_STEM:{"geojson_features":doors.count,"door_surfaces":door_count,"chunks":doors.files,"total_bytes":doors.total_bytes},
            BALCONIES_STEM:{"geojson_features":balconies.count,"balcony_surfaces":balcony_count,"chunks":balconies.files,"total_bytes":balconies.total_bytes}},
        "method":"Adapted from Adam-City Ermita: 3m window spacing, estimated floors, 0.95m x 1.9m doors, deterministic occasional balconies.",
        "geometry":"3D GeoJSON MultiPolygon; WGS84 longitude/latitude and third ordinate = height above building ground in metres.",
        "limitations":["Details are procedural placeholders, not measured or photo-verified.",
            "Floor counts are estimated where building:levels/levels are absent.",
            "Only buildings.geojson supplies building geometry and attributes; no other building file, PBF, remote API or GLB is read or generated.",
            "Renderer must interpret 3D polygon coordinates and add local ground elevation to relative Z."]}
    REPORT_OUT.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(report,ensure_ascii=False,indent=2),flush=True)

if __name__=="__main__": main()
