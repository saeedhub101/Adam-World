#!/usr/bin/env python3
"""Package repository-owned city data as compact spatial tiles for Android assets.

OpenFreeMap/OpenMapTiles are authoritative for building footprints and roads.
OSM supplies supplementary building tags/missing building geometry and non-road
context only. No data is downloaded by this script.
"""
from __future__ import annotations
import argparse
import json
import math
import shutil
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "data" / "facades" / "cities"
FULL_SOURCE = ROOT / "data" / "offline-world"
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "cities"
TILE_METERS = 250.0
KY = 111132.0


def read_json(path: Path):
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def rings(geometry):
    if not geometry:
        return []
    kind = geometry.get("type")
    coords = geometry.get("coordinates") or []
    if kind == "Polygon":
        return [coords[0]] if coords else []
    if kind == "MultiPolygon":
        return [poly[0] for poly in coords if poly]
    return []


def normalize_osm_element(element, lat0, lon0, kx, cover_bbox=False):
    """Return a normalized OSM element and its spatial bucket, or None."""
    kind = element.get("type")
    tags = dict(element.get("tags") or {})
    if kind == "node":
        try:
            lat, lon = float(element["lat"]), float(element["lon"])
        except (KeyError, TypeError, ValueError):
            return None
        ix = math.floor(((lon - lon0) * kx) / TILE_METERS)
        iz = math.floor((-(lat - lat0) * KY) / TILE_METERS)
        return [((ix, iz), {
            "type": "node", "id": int(element.get("id", 0)),
            "lat": lat, "lon": lon, "tags": tags,
        })]

    if kind != "way":
        return None
    geometry = element.get("geometry") or []
    points = []
    for point in geometry:
        try:
            lat, lon = float(point["lat"]), float(point["lon"])
        except (KeyError, TypeError, ValueError):
            continue
        if math.isfinite(lat) and math.isfinite(lon):
            points.append({"lat": lat, "lon": lon})
    if len(points) < 2:
        return None
    center_lat = sum(p["lat"] for p in points) / len(points)
    center_lon = sum(p["lon"] for p in points) / len(points)
    element_out = {
        "type": "way", "id": int(element.get("id", 0)),
        "tags": tags, "geometry": points,
    }
    if cover_bbox:
        min_lon = min(p["lon"] for p in points); max_lon = max(p["lon"] for p in points)
        min_lat = min(p["lat"] for p in points); max_lat = max(p["lat"] for p in points)
        ix0 = math.floor(((min_lon - lon0) * kx) / TILE_METERS)
        ix1 = math.floor(((max_lon - lon0) * kx) / TILE_METERS)
        iz0 = math.floor((-(max_lat - lat0) * KY) / TILE_METERS)
        iz1 = math.floor((-(min_lat - lat0) * KY) / TILE_METERS)
        # Long ways are replicated into every intersected cell so roads and
        # large footprints remain visible when the player crosses cell edges.
        if (ix1 - ix0 + 1) * (iz1 - iz0 + 1) <= 256:
            return [((ix, iz), element_out)
                    for ix in range(ix0, ix1 + 1)
                    for iz in range(iz0, iz1 + 1)]
    ix = math.floor(((center_lon - lon0) * kx) / TILE_METERS)
    iz = math.floor((-(center_lat - lat0) * KY) / TILE_METERS)
    return [((ix, iz), element_out)]


def _osm_id(value):
    if value is None:
        return None
    text = str(value).strip()
    for prefix in ("way/", "relation/", "node/"):
        if text.startswith(prefix):
            text = text[len(prefix):]
            break
    try:
        return int(text)
    except (TypeError, ValueError):
        return None


def _feature_tags(feature):
    props = dict(feature.get("properties") or {})
    nested = props.get("tags")
    tags = dict(nested) if isinstance(nested, dict) else {}
    # OpenFreeMap properties are already flattened; OSM details preserve tags
    # in the nested object. Keep useful source attributes without overwriting tags.
    for key, value in props.items():
        if value is not None and key not in {
            "tags", "osm_feature_id", "osm_id", "osm_way_id", "osm_type",
            "source", "source_feature_id"
        }:
            tags.setdefault(str(key), value)
    return tags


def _geojson_way_elements(path, only_building_parts=False):
    if not path.is_file():
        return []
    data = read_json(path)
    output = []
    used_ids = set()
    for feature in data.get("features", []):
        props = feature.get("properties") or {}
        tags = _feature_tags(feature)
        if only_building_parts:
            if not tags.get("building:part"):
                continue
        else:
            # Every feature in building-footprints.geojson is a building polygon;
            # vector tiles do not always publish a separate "building" property.
            if not (tags.get("building") or tags.get("building:part")):
                tags["building"] = "yes"
        raw_id = (props.get("osm_feature_id") or props.get("osm_way_id") or
                  props.get("osm_id") or props.get("source_feature_id") or
                  feature.get("id"))
        way_id = _osm_id(raw_id)
        if way_id is None:
            # Stable local IDs for non-OSM OpenFreeMap feature IDs. These remain
            # unique and never masquerade as a real OSM identifier.
            seed = str(raw_id or len(output))
            way_id = 8_000_000_000 + (sum((i + 1) * ord(c) for i, c in enumerate(seed)) % 900_000_000)
        geometry = feature.get("geometry") or {}
        kind, coords = geometry.get("type"), geometry.get("coordinates") or []
        polygons = [coords] if kind == "Polygon" else (coords if kind == "MultiPolygon" else [])
        for polygon_index, polygon in enumerate(polygons):
            if not polygon:
                continue
            ring = polygon[0]
            points = []
            for point in ring:
                if not isinstance(point, (list, tuple)) or len(point) < 2:
                    continue
                lon, lat = float(point[0]), float(point[1])
                if math.isfinite(lat) and math.isfinite(lon):
                    points.append({"lat": lat, "lon": lon})
            if len(points) < 4:
                continue
            # OSM ways use their original ID for the first outer ring. Additional
            # multipolygon components get deterministic synthetic IDs while
            # retaining the original ID in tags for provenance/facade matching.
            element_id = way_id if polygon_index == 0 else way_id * 1000 + polygon_index
            while element_id in used_ids:
                element_id += 1
            used_ids.add(element_id)
            element_tags = dict(tags)
            element_tags.setdefault("building", "yes")
            element_tags["adam:source_osm_id"] = str(way_id)
            element_tags["adam:source_geometry"] = str(data.get("source") or "GeoJSON")
            output.append({
                "type": "way", "id": element_id, "tags": element_tags,
                "geometry": points,
            })
    return output




def _overture_record_osm_id(properties):
    for source in properties.get("sources") or []:
        if not isinstance(source, dict):
            continue
        record = str(source.get("record_id") or "").split("@", 1)[0]
        if record.startswith("w") and record[1:].isdigit():
            return int(record[1:])
        if record.startswith("r") and record[1:].isdigit():
            return int(record[1:])
        if record.startswith("n") and record[1:].isdigit():
            return int(record[1:])
        parsed = _osm_id(record)
        if parsed is not None:
            return parsed
    return None


def _geojson_overture_elements(path, part=False):
    """Normalize Overture buildings while preserving source provenance and address/height fields."""
    if not path.is_file():
        return []
    output = []
    for feature in read_json(path).get("features", []):
        props = dict(feature.get("properties") or {})
        geom = feature.get("geometry") or {}
        kind, coords = geom.get("type"), geom.get("coordinates") or []
        polygons = [coords] if kind == "Polygon" else (coords if kind == "MultiPolygon" else [])
        if not polygons:
            continue
        tags = {"building": "yes", "adam:source": "Overture Maps"}
        if part:
            tags["building:part"] = "yes"
        mapping = {
            "height": "height", "min_height": "min_height",
            "num_floors": "building:levels",
            "num_floors_underground": "building:levels:underground",
            "min_floor": "building:min_level",
            "roof_height": "roof:height", "roof_shape": "roof:shape",
            "roof_material": "roof:material", "roof_color": "roof:colour",
            "facade_material": "building:material", "facade_color": "building:colour",
            "class": "adam:source:building_class", "subtype": "adam:source:building_subtype",
        }
        for src, dst in mapping.items():
            value = props.get(src)
            if value not in (None, ""):
                tags[dst] = str(value)
        names = props.get("names") or props.get("name")
        if isinstance(names, dict):
            primary = names.get("primary") or names.get("common")
            if primary:
                tags["name"] = str(primary)
            for lang, value in (names.get("common") or {}).items() if isinstance(names.get("common"), dict) else []:
                if value and lang in ("ar", "en"):
                    tags["name:" + lang] = str(value)
        elif names:
            tags["name"] = str(names)
        address = props.get("address") or props.get("addresses")
        if isinstance(address, list):
            address = address[0] if address else {}
        if isinstance(address, dict):
            number = address.get("number") or address.get("house_number") or address.get("houseNumber")
            street = address.get("street")
            unit = address.get("unit")
            if number: tags["addr:housenumber"] = str(number)
            if street: tags["addr:street"] = str(street)
            if unit: tags["addr:unit"] = str(unit)
        for pair in props.get("source_tags") or []:
            if isinstance(pair, (list, tuple)) and len(pair) >= 2 and pair[1] not in (None, ""):
                key = str(pair[0])
                if key in ("name", "name:ar", "name:en", "addr:housenumber", "addr:street",
                           "height", "building:levels", "ref"):
                    tags.setdefault(key, str(pair[1]))
        datasets = sorted({str(src.get("dataset")) for src in props.get("sources") or []
                           if isinstance(src, dict) and src.get("dataset")})
        if datasets:
            tags["adam:source_datasets"] = ";".join(datasets)
        height_sources = sorted({str(src.get("dataset")) for src in props.get("sources") or []
                                 if isinstance(src, dict) and src.get("property") == "height" and src.get("dataset")})
        if height_sources:
            tags["adam:height_source"] = ";".join(height_sources)
        osm_id = _overture_record_osm_id(props)
        if osm_id is not None:
            tags["adam:source_osm_id"] = str(osm_id)
        tags["adam:source_overture_id"] = str(feature.get("id") or props.get("id") or "")
        tags["adam:source_geometry"] = "Overture Maps conflated building geometry"
        seed = str(feature.get("id") or props.get("id") or len(output))
        synthetic_id = 7000000000 + (sum((i + 1) * ord(ch) for i, ch in enumerate(seed)) % 900000000)
        for polygon_index, polygon in enumerate(polygons):
            if not polygon:
                continue
            ring = polygon[0]
            points = []
            for point in ring:
                if isinstance(point, (list, tuple)) and len(point) >= 2:
                    lon, lat = float(point[0]), float(point[1])
                    if math.isfinite(lat) and math.isfinite(lon):
                        points.append({"lat": lat, "lon": lon})
            if len(points) < 4:
                continue
            output.append({"type": "way", "id": synthetic_id + polygon_index,
                           "tags": dict(tags), "geometry": points})
    return output



def _geojson_openbuildingmap_elements(path):
    """Decode OpenBuildingMap GEM height strings into explicit metres and storeys."""
    if not path.is_file():
        return []
    output = []
    for feature in read_json(path).get("features", []):
        props = dict(feature.get("properties") or {})
        geom = feature.get("geometry") or {}
        kind, coords = geom.get("type"), geom.get("coordinates") or []
        polygons = [coords] if kind == "Polygon" else (coords if kind == "MultiPolygon" else [])
        raw_height = str(props.get("height") or "")
        height_m = None
        floors = None
        floor_range = None
        for token in raw_height.split("+"):
            if ":" not in token:
                continue
            code, raw_value = token.split(":", 1)
            numbers = []
            import re
            for value in re.findall(r"\\d+(?:\\.\\d+)?", raw_value):
                try: numbers.append(float(value))
                except ValueError: pass
            if not numbers:
                continue
            if code == "HHT":
                height_m = max(numbers)
            elif code == "H":
                floors = max(numbers)
            elif code == "HBET":
                floor_range = numbers
        if floors is None and floor_range:
            floors = sum(floor_range) / len(floor_range)
        source_id = props.get("source_id")
        source_name = {0: "OpenStreetMap", 1: "Google Open Buildings", 2: "Microsoft Global ML Building Footprints"}.get(
            int(source_id) if str(source_id).isdigit() else -1, "Unknown")
        tags = {"building": "yes", "adam:source": "OpenBuildingMap",
                "adam:source_dataset": source_name, "adam:obm_height_code": raw_height}
        if height_m is not None and height_m > 0:
            tags["height"] = str(height_m)
            tags["adam:height_source"] = "OpenBuildingMap/GHSL 2023A; GEM HHT"
        if floors is not None and floors > 0:
            tags["building:levels"] = str(floors)
            tags["adam:floors_source"] = "OpenBuildingMap/GEM taxonomy"
            if floor_range:
                tags["adam:floors_quality"] = "estimated_range"
        occupancy = str(props.get("occupancy") or "")
        if occupancy:
            tags["adam:occupancy_gem"] = occupancy
            prefix = occupancy[:3]
            tags["building"] = {"RES": "residential", "COM": "commercial", "MIX": "yes",
                                "IND": "industrial", "EDU": "school", "GOV": "government",
                                "AGR": "farm", "ASS": "civic"}.get(prefix, "yes")
        try:
            sid = int(props.get("id")) if int(source_id) == 0 else None
        except (TypeError, ValueError):
            sid = None
        if sid is not None:
            tags["adam:source_osm_id"] = str(sid)
        tags["adam:source_geometry"] = "OpenBuildingMap OSM/Google/Microsoft conflation"
        seed = str(props.get("id") or len(output))
        synthetic_id = 6000000000 + (sum((i + 1) * ord(ch) for i, ch in enumerate(seed)) % 900000000)
        for polygon_index, polygon in enumerate(polygons):
            if not polygon:
                continue
            points = []
            for point in polygon[0]:
                if isinstance(point, (list, tuple)) and len(point) >= 2:
                    lon, lat = float(point[0]), float(point[1])
                    if math.isfinite(lat) and math.isfinite(lon):
                        points.append({"lat": lat, "lon": lon})
            if len(points) >= 4:
                output.append({"type": "way", "id": synthetic_id + polygon_index,
                               "tags": dict(tags), "geometry": points})
    return output

def _merge_overture_building_attributes(primary, overture):
    """Join Overture's multi-source attributes to primary footprints; append only unmatched real footprints."""
    primary_by_osm = {}
    grid = defaultdict(list)
    signatures = []
    cell_size = 5.0
    for index, item in enumerate(primary):
        tags = item.get("tags") or {}
        osm_id = _osm_id(tags.get("adam:source_osm_id") or item.get("id"))
        if osm_id is not None:
            primary_by_osm.setdefault(osm_id, []).append(item)
        signature = _footprint_signature(item.get("geometry") or [])
        signatures.append(signature)
        if signature:
            grid[(math.floor(signature[0] / cell_size), math.floor(signature[1] / cell_size))].append(index)
    additions = []
    matched = 0
    for item in overture:
        tags = item.get("tags") or {}
        osm_id = _osm_id(tags.get("adam:source_osm_id"))
        candidates = primary_by_osm.get(osm_id, []) if osm_id is not None else []
        target = candidates[0] if len(candidates) == 1 else None
        sig = _footprint_signature(item.get("geometry") or [])
        if target is None and sig:
            cx, cy = math.floor(sig[0] / cell_size), math.floor(sig[1] / cell_size)
            ranked = []
            for dx in (-1, 0, 1):
                for dy in (-1, 0, 1):
                    for index in grid.get((cx + dx, cy + dy), []):
                        other = signatures[index]
                        if not other:
                            continue
                        distance = math.hypot(sig[0] - other[0], sig[1] - other[1])
                        ratio = min(sig[2], other[2]) / max(sig[2], other[2])
                        if distance <= 3.0 and ratio >= 0.65:
                            ranked.append((distance, -ratio, primary[index]))
            if ranked:
                ranked.sort(key=lambda x: (x[0], x[1]))
                if len(ranked) == 1 or ranked[1][0] - ranked[0][0] > 1.0:
                    target = ranked[0][2]
        if target is not None:
            dest = target.setdefault("tags", {})
            for key, value in tags.items():
                if value not in (None, "") and dest.get(key) in (None, ""):
                    dest[key] = value
            dest["adam:enriched_by_overture"] = "yes"
            matched += 1
        else:
            # Overture includes conflated Google/Microsoft footprints; keep them
            # only when no primary footprint is geometrically equivalent.
            additions.append(item)
    print(f"OVERTURE JOIN: {matched} existing footprints enriched; {len(additions)} additional source footprints", flush=True)
    return additions

def _geojson_transportation_elements(path):
    """Build road centerlines once and merge label-layer names without drawing duplicate roads."""
    if not path.is_file():
        return []
    data = read_json(path)
    roads, labels = [], []
    try:
        from shapely.geometry import LineString
    except Exception:
        LineString = None
    for feature in data.get("features", []):
        props = dict(feature.get("properties") or {})
        tags = dict(props.get("tags") or {})
        tags.update({str(k): v for k, v in props.items() if v is not None and k != "tags"})
        if not tags.get("highway"):
            continue
        geom = feature.get("geometry") or {}
        kind, coords = geom.get("type"), geom.get("coordinates") or []
        parts = [coords] if kind == "LineString" else (coords if kind == "MultiLineString" else [])
        raw_id = props.get("osm_id") or props.get("osm_way_id") or feature.get("id") or len(roads)
        source_id = str(raw_id)
        for prefix in ("openfreemap-transportation/", "openfreemap-transportation_name/"):
            if source_id.startswith(prefix):
                source_id = source_id[len(prefix):]
                break
        explicit_id = props.get("osm_id") or props.get("osm_way_id")
        osm_id = _osm_id(explicit_id) if explicit_id is not None else (_osm_id(source_id) if source_id.startswith("way/") else None)
        for part in parts:
            points = []
            for point in part:
                if not isinstance(point, (list, tuple)) or len(point) < 2:
                    continue
                lon, lat = float(point[0]), float(point[1])
                if math.isfinite(lat) and math.isfinite(lon):
                    points.append({"lat": lat, "lon": lon})
            if len(points) < 2:
                continue
            item = {"type": "way", "id": 0, "tags": dict(tags), "geometry": points}
            item["tags"]["adam:source_geometry"] = "OpenFreeMap/OpenMapTiles"
            if osm_id is not None:
                item["tags"]["adam:source_osm_id"] = str(osm_id)
            if tags.get("adam:label_only") == "yes":
                labels.append((osm_id, item))
            else:
                seed = str(raw_id) + ":" + str(len(roads))
                item["id"] = 9000000000 + (sum((i + 1) * ord(ch) for i, ch in enumerate(seed)) % 800000000)
                roads.append(item)
    by_osm = defaultdict(list)
    for road in roads:
        rid = _osm_id((road.get("tags") or {}).get("adam:source_osm_id"))
        if rid is not None:
            by_osm[rid].append(road)
    for osm_id, label in labels:
        lt = label.get("tags") or {}
        name = lt.get("name") or lt.get("name:ar") or lt.get("name:latin") or lt.get("ref")
        if not name:
            continue
        candidates = by_osm.get(osm_id, []) if osm_id is not None else []
        if not candidates and LineString is not None:
            try:
                line = LineString([(p["lon"], p["lat"]) for p in label["geometry"]])
                nearest = []
                for road in roads:
                    other = LineString([(p["lon"], p["lat"]) for p in road["geometry"]])
                    nearest.append((line.distance(other), road))
                if nearest:
                    distance, road = min(nearest, key=lambda item: item[0])
                    if distance <= 0.00008:
                        candidates = [road]
            except Exception:
                candidates = []
        for road in candidates:
            rt = road["tags"]
            if not rt.get("name"):
                rt["name"] = name
            for key in ("name:ar", "name:en", "ref"):
                if not rt.get(key) and lt.get(key):
                    rt[key] = lt[key]
    return roads

def _point_in_ring(lon, lat, points):
    inside = False
    if len(points) < 4:
        return False
    j = len(points) - 1
    for i in range(len(points)):
        try:
            xi, yi = float(points[i]["lon"]), float(points[i]["lat"])
            xj, yj = float(points[j]["lon"]), float(points[j]["lat"])
        except (KeyError, TypeError, ValueError):
            return False
        if ((yi > lat) != (yj > lat)) and (lon < (xj - xi) * (lat - yi) / ((yj - yi) or 1e-15) + xi):
            inside = not inside
        j = i
    return inside


def _merge_address_points(city_slug, buildings):
    """Attach only address nodes that fall inside a mapped building footprint."""
    address_path = SOURCE / city_slug / "address-points.geojson"
    if not address_path.is_file() or not buildings:
        return 0
    features = read_json(address_path).get("features") or []
    grid_size = 0.0005
    grid = defaultdict(list)
    rings_by_index = []
    for index, building in enumerate(buildings):
        points = building.get("geometry") or []
        if len(points) < 4:
            continue
        rings_by_index.append((index, points))
        min_lon = min(p["lon"] for p in points); max_lon = max(p["lon"] for p in points)
        min_lat = min(p["lat"] for p in points); max_lat = max(p["lat"] for p in points)
        gx0, gx1 = math.floor(min_lon / grid_size), math.floor(max_lon / grid_size)
        gy0, gy1 = math.floor(min_lat / grid_size), math.floor(max_lat / grid_size)
        for gx in range(gx0, gx1 + 1):
            for gy in range(gy0, gy1 + 1):
                grid[(gx, gy)].append(index)
    ring_lookup = dict(rings_by_index)
    assigned = 0
    # Overture's independent address theme is also queried. It supplies
    # structured house numbers/street names where the source has coverage.
    overture_address_path = SOURCE / city_slug / "overture-address-points.geojson"
    if overture_address_path.is_file():
        features.extend(read_json(overture_address_path).get("features") or [])
    address_keys = ("addr:housenumber", "addr:housename", "addr:street",
                    "addr:street:ar", "addr:street:en", "addr:unit",
                    "addr:postcode", "name", "name:ar")
    for feature in features:
        geometry = feature.get("geometry") or {}
        coords = geometry.get("coordinates") or []
        if geometry.get("type") != "Point" or len(coords) < 2:
            continue
        lon, lat = float(coords[0]), float(coords[1])
        candidates = grid.get((math.floor(lon / grid_size), math.floor(lat / grid_size)), [])
        address_tags = _feature_tags(feature)
        # Overture Address theme uses number/street/unit rather than OSM addr:* keys.
        if address_tags.get("number") and not address_tags.get("addr:housenumber"):
            address_tags["addr:housenumber"] = address_tags["number"]
        if address_tags.get("street") and not address_tags.get("addr:street"):
            address_tags["addr:street"] = address_tags["street"]
        if address_tags.get("unit") and not address_tags.get("addr:unit"):
            address_tags["addr:unit"] = address_tags["unit"]
        matched = None
        for index in candidates:
            if _point_in_ring(lon, lat, ring_lookup[index]):
                matched = buildings[index]
                break
        if matched is None:
            continue
        target_tags = matched.setdefault("tags", {})
        changed = False
        for key in address_keys:
            value = address_tags.get(key)
            if value in (None, ""):
                continue
            old = target_tags.get(key)
            if key == "addr:housenumber" and old not in (None, "") and str(value) not in str(old).split(", "):
                target_tags[key] = str(old) + ", " + str(value)
                changed = True
            elif old in (None, ""):
                target_tags[key] = value
                changed = True
        if changed:
            assigned += 1
    return assigned


def _footprint_signature(points):
    """Return an approximate metric centroid and area for a closed lon/lat ring."""
    coords=[]
    for point in points or []:
        try:
            coords.append((float(point["lon"]),float(point["lat"])))
        except (KeyError,TypeError,ValueError):
            return None
    if len(coords)<4:
        return None
    lat0=sum(lat for _,lat in coords)/len(coords)
    sx=111320.0*math.cos(math.radians(lat0))
    sy=111132.0
    xy=[(lon*sx,lat*sy) for lon,lat in coords]
    area=0.0
    for i,(x,y) in enumerate(xy):
        nx,ny=xy[(i+1)%len(xy)]
        area+=x*ny-nx*y
    area=abs(area)*0.5
    if area<=0:
        return None
    cx=sum(x for x,_ in xy)/len(xy)
    cy=sum(y for _,y in xy)/len(xy)
    return cx,cy,area


def _same_footprint_signature(sa,sb):
    if not sa or not sb:
        return False
    distance=math.hypot(sa[0]-sb[0],sa[1]-sb[1])
    area_ratio=min(sa[2],sb[2])/max(sa[2],sb[2])
    return distance<=2.0 and area_ratio>=0.88

def _merge_osm_road_attributes(primary_roads, osm_elements, lat0, lon0):
    """Transfer OSM road names/lanes/width only where road geometries overlap."""
    try:
        from shapely.geometry import LineString
    except Exception:
        print("WARN shapely unavailable; OSM road-attribute geometry join skipped", flush=True)
        return 0
    kx = 111320.0 * math.cos(math.radians(lat0))
    ky = 111132.0
    cell_size = 100.0
    index = defaultdict(list)
    osm_lines = []
    for element in osm_elements:
        tags = element.get("tags") or {}
        if element.get("type") != "way" or not tags.get("highway"):
            continue
        coords = []
        for point in element.get("geometry") or []:
            try:
                coords.append(((float(point["lon"]) - lon0) * kx,
                               (float(point["lat"]) - lat0) * ky))
            except (KeyError, TypeError, ValueError):
                continue
        if len(coords) < 2:
            continue
        try:
            line = LineString(coords)
        except Exception:
            continue
        if line.is_empty or line.length < 4.0:
            continue
        min_x, min_y, max_x, max_y = line.bounds
        record = (line, tags, str(element.get("id", "")))
        record_index = len(osm_lines)
        osm_lines.append(record)
        for gx in range(math.floor(min_x / cell_size), math.floor(max_x / cell_size) + 1):
            for gy in range(math.floor(min_y / cell_size), math.floor(max_y / cell_size) + 1):
                index[(gx, gy)].append(record_index)

    matched_count = 0
    for road in primary_roads:
        road_tags = road.setdefault("tags", {})
        coords = []
        for point in road.get("geometry") or []:
            try:
                coords.append(((float(point["lon"]) - lon0) * kx,
                               (float(point["lat"]) - lat0) * ky))
            except (KeyError, TypeError, ValueError):
                continue
        if len(coords) < 2:
            continue
        try:
            line = LineString(coords)
        except Exception:
            continue
        if line.is_empty or line.length < 4.0:
            continue
        min_x, min_y, max_x, max_y = line.bounds
        candidate_ids = set()
        for gx in range(math.floor(min_x / cell_size), math.floor(max_x / cell_size) + 1):
            for gy in range(math.floor(min_y / cell_size), math.floor(max_y / cell_size) + 1):
                candidate_ids.update(index.get((gx, gy), []))
        matches = []
        corridor = line.buffer(6.0)
        for candidate_id in candidate_ids:
            osm_line, osm_tags, osm_id = osm_lines[candidate_id]
            if line.distance(osm_line) > 6.0:
                continue
            overlap = corridor.intersection(osm_line).length
            if overlap < max(6.0, min(15.0, osm_line.length * 0.45)):
                continue
            info = sum(1 for key in ("name:ar", "name", "lanes", "width", "surface")
                       if osm_tags.get(key) not in (None, ""))
            matches.append((overlap, info, osm_line.length, osm_tags, osm_id))
        if not matches:
            continue
        # Prefer the best-aligned, most informative OSM segment; do not assign
        # tags from a crossing road that only touches at an intersection.
        matches.sort(key=lambda item: (item[0], item[1], item[2]), reverse=True)
        _, _, _, best_tags, best_id = matches[0]
        for key, value in best_tags.items():
            if value not in (None, "") and road_tags.get(key) in (None, ""):
                road_tags[key] = value
        road_tags["adam:matched_osm_id"] = best_id
        road_tags["adam:road_attribute_match"] = "geometry-overlap-6m"
        matched_count += 1
    print(f"ENRICHED {matched_count}/{len(primary_roads)} OpenFreeMap road features from matching OSM geometry", flush=True)
    return matched_count


def source_elements(slug, lat0, lon0):
    # OpenFreeMap/OpenMapTiles provide primary building and road geometry.
    # OSM contributes only missing building geometry/attributes; non-road
    # environmental context and transit records are preserved separately.
    footprints_path = SOURCE / slug / "building-footprints.geojson"
    details_path = SOURCE / slug / "building-details.geojson"
    if footprints_path.is_file():
        details = _geojson_way_elements(details_path)
        detail_by_id = {}
        detail_buildings = []
        building_parts = []
        for item in details:
            tags = item.get("tags") or {}
            source_id = _osm_id(tags.get("adam:source_osm_id") or item.get("id"))
            if tags.get("building:part"):
                building_parts.append(item)
            elif tags.get("building") and source_id is not None:
                detail_by_id[source_id] = tags
                detail_buildings.append(item)

        footprints = _geojson_way_elements(footprints_path)
        overture_buildings = _geojson_overture_elements(SOURCE / slug / "overture-building-details.geojson")
        overture_parts = _geojson_overture_elements(SOURCE / slug / "overture-building-parts.geojson", part=True)
        overture_additions = _merge_overture_building_attributes(footprints + detail_buildings, overture_buildings)
        obm_buildings = _geojson_openbuildingmap_elements(SOURCE / slug / "openbuildingmap-buildings.geojson")
        obm_additions = _merge_overture_building_attributes(footprints + detail_buildings + overture_additions, obm_buildings)
        # Re-read enriched OSM detail tags so exact OSM IDs transfer source
        # heights/floors/names to their OpenFreeMap primary footprints.
        for item in detail_buildings:
            source_id = _osm_id((item.get("tags") or {}).get("adam:source_osm_id") or item.get("id"))
            if source_id is not None:
                detail_by_id[source_id] = item.get("tags") or {}
        address_assignments = _merge_address_points(
            slug, footprints + detail_buildings + overture_additions + obm_additions)
        if address_assignments:
            print(f"MERGED {address_assignments} Tabarbour address points into building footprints", flush=True)
        footprint_ids = set()
        for item in footprints:
            tags = item.get("tags") or {}
            source_id = _osm_id(tags.get("adam:source_osm_id") or item.get("id"))
            if source_id is not None:
                footprint_ids.add(source_id)
                # OpenFreeMap/OpenMapTiles attributes always win. OSM only
                # fills properties that the primary vector-tile feature lacks.
                merged = dict(tags)
                for key, value in (detail_by_id.get(source_id) or {}).items():
                    if value not in (None, "") and merged.get(key) in (None, ""):
                        merged[key] = value
                item["tags"] = merged

        # Keep OSM building-part geometries that have no exact matching outer
        # footprint. They describe real sub-volumes, not invented facade detail.
        # Some providers can expose the same real footprint under a different
        # feature identifier. Suppress near-identical OSM geometry duplicates
        # before treating OSM as a missing-building source.
        # Spatial hash keeps the geometry-only duplicate guard linear in
        # the number of source features instead of comparing every OSM polygon
        # with every OpenFreeMap polygon.
        primary_cells = defaultdict(list)
        cell_size = 5.0
        for item in footprints:
            signature = _footprint_signature(item.get("geometry") or [])
            if signature:
                cell = (math.floor(signature[0]/cell_size), math.floor(signature[1]/cell_size))
                primary_cells[cell].append(signature)
        unmatched_buildings = []
        for item in detail_buildings:
            source_id = _osm_id((item.get("tags") or {}).get("adam:source_osm_id") or item.get("id"))
            if source_id in footprint_ids:
                continue
            signature = _footprint_signature(item.get("geometry") or [])
            duplicate = False
            if signature:
                cx,cy=math.floor(signature[0]/cell_size),math.floor(signature[1]/cell_size)
                nearby = (candidate for dx in (-1,0,1) for dy in (-1,0,1)
                          for candidate in primary_cells.get((cx+dx,cy+dy),[]))
                duplicate = any(_same_footprint_signature(signature,primary) for primary in nearby)
            if not duplicate:
                unmatched_buildings.append(item)
        unmatched_parts = [
            item for item in building_parts
            if _osm_id((item.get("tags") or {}).get("adam:source_osm_id") or item.get("id"))
            not in footprint_ids
        ]

        full = FULL_SOURCE / slug / "elements.json"
        preserved = []
        source_path = f"data/facades/cities/{slug}/building-footprints.geojson"
        source_kind = "openfreemap-building-footprints+openfreemap-transportation+osm-supplement"
        transportation_path = SOURCE / slug / "transportation.geojson"
        primary_roads = _geojson_transportation_elements(transportation_path)
        if full.is_file():
            full_data = read_json(full)
            # OpenFreeMap vector-tile IDs are not assumed to be OSM way IDs.
            # Join supplementary OSM tags by measured geometry overlap instead.
            _merge_osm_road_attributes(primary_roads, full_data.get("elements") or [], lat0, lon0)
            preserved = [
                item for item in (full_data.get("elements") or [])
                if not ((item.get("tags") or {}).get("building") or
                        (item.get("tags") or {}).get("building:part") or
                        (item.get("type") == "way" and (item.get("tags") or {}).get("highway")))
            ]
            route_file = full.parent / "transit-routes.json"
            if route_file.is_file():
                route_data = read_json(route_file)
                preserved.extend(route_data.get("elements") or [])
                source_kind += "+osm-nonroad-context+transit"
            else:
                source_kind += "+osm-nonroad-context"
            source_path += f" + {full.relative_to(ROOT)}"
        if transportation_path.is_file():
            source_path += f" + {transportation_path.relative_to(ROOT)}"
            source_kind += "+openfreemap-road-geometry-and-labels"
        else:
            primary_roads = []
            source_kind += "+openfreemap-road-layer-missing-no-osm-road-substitution"
        # Overture building parts are retained as true sub-volumes; avoid
        # exact duplicate OSM parts by their original OSM identity.
        known_part_ids = {_osm_id((x.get("tags") or {}).get("adam:source_osm_id") or x.get("id"))
                          for x in unmatched_parts + footprints + detail_buildings}
        unmatched_overture_parts = [x for x in overture_parts
            if _osm_id((x.get("tags") or {}).get("adam:source_osm_id") or x.get("id")) not in known_part_ids]
        combined = (preserved + primary_roads + footprints + unmatched_buildings +
                    unmatched_parts + overture_additions + unmatched_overture_parts + obm_additions)
        if combined:
            return combined, source_path, source_kind

    # Strict fallback for a city not yet imported from OpenFreeMap: retain
    # non-road/non-building context only. Never promote raw OSM buildings or
    # road ways to primary geometry when the provider tiles are absent.
    full = FULL_SOURCE / slug / "elements.json"
    if full.is_file():
        data = read_json(full)
        elements = [
            item for item in (data.get("elements") or [])
            if not ((item.get("tags") or {}).get("building") or
                    (item.get("tags") or {}).get("building:part") or
                    (item.get("type") == "way" and (item.get("tags") or {}).get("highway")))
        ]
        route_file = full.parent / "transit-routes.json"
        if route_file.is_file():
            route_data = read_json(route_file)
            elements.extend(route_data.get("elements") or [])
        kind = "osm-nonroad-context-only" + ("+transit-routes" if route_file.is_file() else "")
        return elements, str(full.relative_to(ROOT)), kind
    return [], "none", "no-openfreemap-primary-data"


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--city", help="Build only this city slug")
    args = parser.parse_args()
    catalog = read_json(SOURCE / "catalog.json")
    selected_cities = list(catalog.get("cities", []))
    if args.city:
        selected_cities = [city for city in selected_cities if city.get("slug") == args.city]
        if not selected_cities:
            raise SystemExit(f"Unknown city slug: {args.city}")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "catalog.json").write_text(
        json.dumps(catalog, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    total_elements = 0
    total_tiles = 0

    for city in selected_cities:
        slug = city["slug"]
        center = city["center"]
        lat0, lon0 = float(center["lat"]), float(center["lon"])
        kx = 111320.0 * math.cos(math.radians(lat0))
        raw_elements, source_path, source_kind = source_elements(slug, lat0, lon0)
        buckets = defaultdict(list)
        seen = set()

        for raw in raw_elements:
            normalized_items = normalize_osm_element(
                raw, lat0, lon0, kx, cover_bbox=(slug == "tabarbour"))
            if not normalized_items:
                continue
            for (ix, iz), element in normalized_items:
                key = (element["type"], element["id"])
                bucket_key = (ix, iz, key)
                if bucket_key in seen:
                    continue
                seen.add(bucket_key)
                buckets[(ix, iz)].append(element)

        city_out = OUTPUT / slug
        city_out.mkdir(parents=True, exist_ok=True)
        tiles = []
        for (ix, iz), elements in sorted(buckets.items()):
            filename = f"tile_{ix}_{iz}.json"
            (city_out / filename).write_text(
                json.dumps({"elements": elements}, ensure_ascii=False, separators=(",", ":")),
                encoding="utf-8",
            )
            tiles.append({
                "file": filename, "ix": ix, "iz": iz,
                "min_x": ix * TILE_METERS, "max_x": (ix + 1) * TILE_METERS,
                "min_z": iz * TILE_METERS, "max_z": (iz + 1) * TILE_METERS,
                "count": len(elements),
            })

        manifest = {
            "schema_version": 1, "slug": slug, "name": city["name"],
            "center": center,
            "coverage_radius_m": float(city.get("discovery_radius_m", 650)),
            "tile_size_m": TILE_METERS,
            "source": source_path, "source_kind": source_kind,
            "element_count": sum(len(v) for v in buckets.values()),
            "tiles": tiles,
        }
        elevation_source = SOURCE / slug / "elevation-grid.json"
        if slug == "amman" and elevation_source.is_file():
            shutil.copy2(elevation_source, city_out / "elevation-grid.json")
            manifest["elevation_grid"] = "elevation-grid.json"
            manifest["elevation_source"] = "Copernicus DEM GLO-30"
        (city_out / "manifest.json").write_text(
            json.dumps(manifest, ensure_ascii=False, separators=(",", ":")),
            encoding="utf-8",
        )
        count = manifest["element_count"]
        total_elements += count
        total_tiles += len(tiles)
        print(f"{city['name']}: {count} elements in {len(tiles)} tiles ({source_kind})")

    if total_elements == 0 or total_tiles == 0:
        raise SystemExit("Offline city package is empty")
    print(f"OFFLINE_PACKAGE_OK cities={len(catalog['cities'])} elements={total_elements} tiles={total_tiles}")


if __name__ == "__main__":
    main()
