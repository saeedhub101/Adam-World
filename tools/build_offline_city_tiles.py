#!/usr/bin/env python3
"""Package repository-owned city data as compact spatial tiles for Android assets.

OpenFreeMap/OpenMapTiles are authoritative for building footprints and roads.
OSM supplies supplementary building tags/missing building geometry and non-road
context only. No data is downloaded by this script.
"""
from __future__ import annotations
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


def normalize_osm_element(element, lat0, lon0, kx):
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
        return (ix, iz), {
            "type": "node", "id": int(element.get("id", 0)),
            "lat": lat, "lon": lon, "tags": tags,
        }

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
    ix = math.floor(((center_lon - lon0) * kx) / TILE_METERS)
    iz = math.floor((-(center_lat - lat0) * KY) / TILE_METERS)
    return (ix, iz), {
        "type": "way", "id": int(element.get("id", 0)),
        "tags": tags, "geometry": points,
    }


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



def _geojson_transportation_elements(path):
    """Convert OpenFreeMap/OpenMapTiles road lines and label lines to game ways."""
    if not path.is_file():
        return []
    data=read_json(path)
    output=[]
    for feature in data.get("features",[]):
        props=dict(feature.get("properties") or {})
        tags=dict(props.get("tags") or {})
        tags.update({str(k):v for k,v in props.items() if v is not None and k!="tags"})
        if not tags.get("highway"):
            continue
        geom=feature.get("geometry") or {}
        kind,coords=geom.get("type"),geom.get("coordinates") or []
        lines=[coords] if kind=="LineString" else (coords if kind=="MultiLineString" else [])
        raw_id=feature.get("id") or props.get("osm_id") or props.get("osm_way_id") or len(output)
        seed=str(raw_id)
        base_id=9_000_000_000+(sum((i+1)*ord(ch) for i,ch in enumerate(seed))%800_000_000)
        for line_index,line in enumerate(lines):
            points=[]
            for point in line:
                if not isinstance(point,(list,tuple)) or len(point)<2: continue
                lon,lat=float(point[0]),float(point[1])
                if math.isfinite(lat) and math.isfinite(lon): points.append({"lat":lat,"lon":lon})
            if len(points)<2: continue
            tags_out=dict(tags)
            tags_out["adam:source_geometry"]="OpenFreeMap/OpenMapTiles"
            output.append({"type":"way","id":base_id+line_index,"tags":tags_out,"geometry":points})
    return output

def source_elements(slug):
    # Use the newly imported GeoJSON as the authoritative building geometry.
    # Preserve the rest of the offline OSM world (roads, water, parks, transit),
    # replacing only its building ways with the OpenFreeMap footprints enriched
    # by the matching OSM Overpass tags. No GLB conversion is involved.
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
        footprint_ids = set()
        for item in footprints:
            tags = item.get("tags") or {}
            source_id = _osm_id(tags.get("adam:source_osm_id") or item.get("id"))
            if source_id is not None:
                footprint_ids.add(source_id)
                merged = dict(detail_by_id.get(source_id) or {})
                merged.update(tags)
                # Preserve explicit OSM detail tags over vector-tile defaults.
                for key, value in (detail_by_id.get(source_id) or {}).items():
                    if value not in (None, ""):
                        merged[key] = value
                item["tags"] = merged

        # Keep OSM building-part geometries that have no exact matching outer
        # footprint. They describe real sub-volumes, not invented facade detail.
        unmatched_buildings = [
            item for item in detail_buildings
            if _osm_id((item.get("tags") or {}).get("adam:source_osm_id") or item.get("id"))
            not in footprint_ids
        ]
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
            source_kind += "+temporary-legacy-osm-road-fallback"
        else:
            # The legacy GeoJSON fallback may contain only buildings; retain
            # it only when there is no complete non-building extract to preserve.
            legacy = SOURCE / slug / "buildings.geojson"
            if legacy.is_file():
                source_path += f" + {legacy.relative_to(ROOT)}"
        combined = preserved + primary_roads + footprints + unmatched_buildings + unmatched_parts
        if combined:
            return combined, source_path, source_kind

    # Backward-compatible path for cities not yet imported by the new pipeline.
    full = FULL_SOURCE / slug / "elements.json"
    if full.is_file():
        data = read_json(full)
        elements = data.get("elements") or []
        if elements:
            route_file = full.parent / "transit-routes.json"
            if route_file.is_file():
                route_data = read_json(route_file)
                elements.extend(route_data.get("elements") or [])
            kind = "full-osm+transit-routes" if route_file.is_file() else "full-osm"
            return elements, str(full.relative_to(ROOT)), kind
    buildings = read_json(SOURCE / slug / "buildings.geojson")
    elements = []
    for feature in buildings.get("features", []):
        props = dict(feature.get("properties") or {})
        way_id = props.get("osm_way_id") or props.get("osm_id") or feature.get("id")
        if way_id is None:
            continue
        try:
            way_id = int(str(way_id).replace("way/", ""))
        except (TypeError, ValueError):
            continue
        for part_index, ring in enumerate(rings(feature.get("geometry"))):
            points = []
            for point in ring:
                if not isinstance(point, (list, tuple)) or len(point) < 2:
                    continue
                lon, lat = float(point[0]), float(point[1])
                if math.isfinite(lat) and math.isfinite(lon):
                    points.append({"lat": lat, "lon": lon})
            if len(points) < 4:
                continue
            tags = {str(k): v for k, v in props.items()
                    if v is not None and k not in {"osm_way_id", "osm_type", "source"}}
            tags.setdefault("building", "yes")
            elements.append({
                "type": "way",
                "id": way_id if part_index == 0 else way_id * 100 + part_index,
                "tags": tags,
                "geometry": points,
            })
    return elements, f"data/facades/cities/{slug}/buildings.geojson", "buildings-fallback"


def main():
    catalog = read_json(SOURCE / "catalog.json")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    (OUTPUT / "catalog.json").write_text(
        json.dumps(catalog, ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )
    total_elements = 0
    total_tiles = 0

    for city in catalog.get("cities", []):
        slug = city["slug"]
        center = city["center"]
        lat0, lon0 = float(center["lat"]), float(center["lon"])
        kx = 111320.0 * math.cos(math.radians(lat0))
        raw_elements, source_path, source_kind = source_elements(slug)
        buckets = defaultdict(list)
        seen = set()

        for raw in raw_elements:
            normalized = normalize_osm_element(raw, lat0, lon0, kx)
            if not normalized:
                continue
            (ix, iz), element = normalized
            key = (element["type"], element["id"])
            if key in seen:
                continue
            seen.add(key)
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
