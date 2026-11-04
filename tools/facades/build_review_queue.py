#!/usr/bin/env python3
"""Build conservative, city-independent Panoramax-only facade review queues.

Input per city:
  buildings.geojson       OSM building footprints (FeatureCollection)
  panoramax-items.geojson Panoramax/STAC photo items (FeatureCollection)
  aerial-items.geojson    optional OpenAerialMap/STAC imagery items

Outputs:
  review_queue.json       candidates only; never authorizes texture assignment

The script uses proximity and camera bearing only to discover review candidates.
It never declares building identity verified and never writes the verified facade
manifest. A human must inspect each photo and building before approval.
"""
from __future__ import annotations

import argparse
import subprocess
import hashlib
import json
import math
import os
import re
import tempfile
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ROOT = ROOT / "data" / "facades" / "cities"
MAX_DISTANCE_M = 100.0
EARTH_M = 6_371_008.8


def read_fc(path: Path, required: bool = True) -> dict[str, Any]:
    if not path.exists():
        if required:
            raise FileNotFoundError(path)
        return {"type": "FeatureCollection", "features": []}
    obj = json.loads(path.read_text(encoding="utf-8"))
    if obj.get("type") != "FeatureCollection" or not isinstance(obj.get("features"), list):
        raise ValueError(f"{path}: expected a GeoJSON FeatureCollection")
    return obj


def coords_of(geom: dict[str, Any] | None):
    if not geom:
        return
    typ, coords = geom.get("type"), geom.get("coordinates")
    if typ == "Point" and coords and len(coords) >= 2:
        yield float(coords[0]), float(coords[1])
    elif typ in ("Polygon", "MultiPoint", "LineString"):
        for ring in coords or []:
            if typ == "Polygon":
                for p in ring:
                    if len(p) >= 2:
                        yield float(p[0]), float(p[1])
            else:
                for p in coords or []:
                    if len(p) >= 2:
                        yield float(p[0]), float(p[1])
                break
    elif typ in ("MultiPolygon", "MultiLineString"):
        for poly in coords or []:
            for ring in poly:
                for p in ring:
                    if len(p) >= 2:
                        yield float(p[0]), float(p[1])


def centroid_lonlat(geom: dict[str, Any]) -> tuple[float, float] | None:
    pts = list(coords_of(geom))
    if not pts:
        return None
    # A simple vertex mean is intentionally used only for candidate discovery.
    return sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts)


def haversine_m(a: tuple[float, float], b: tuple[float, float]) -> float:
    lon1, lat1, lon2, lat2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    dlat, dlon = lat2 - lat1, lon2 - lon1
    h = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlon / 2) ** 2
    return 2 * EARTH_M * math.asin(min(1.0, math.sqrt(h)))


def bearing_deg(a: tuple[float, float], b: tuple[float, float]) -> float:
    lon1, lat1, lon2, lat2 = map(math.radians, (a[0], a[1], b[0], b[1]))
    y = math.sin(lon2 - lon1) * math.cos(lat2)
    x = math.cos(lat1) * math.sin(lat2) - math.sin(lat1) * math.cos(lat2) * math.cos(lon2 - lon1)
    return (math.degrees(math.atan2(y, x)) + 360) % 360

def exterior_rings(geom: dict[str, Any] | None) -> list[list[tuple[float, float]]]:
    if not geom: return []
    typ, coords = geom.get("type"), geom.get("coordinates") or []
    if typ == "Polygon":
        return [[(float(p[0]), float(p[1])) for p in (coords[0] if coords else []) if len(p) >= 2]]
    if typ == "MultiPolygon":
        return [[(float(p[0]), float(p[1])) for p in (poly[0] if poly else []) if len(p) >= 2] for poly in coords]
    return []

def nearest_footprint_point(capture: tuple[float, float], geom: dict[str, Any]) -> tuple[float, tuple[float, float], int]:
    """Distance and nearest exterior point in local metres, not centroid distance."""
    sx = 111320.0 * math.cos(math.radians(capture[1]))
    sy = 111132.0
    best_d, best_point, best_edge = float("inf"), capture, -1
    for ring in exterior_rings(geom):
        if len(ring) < 2: continue
        for i, a in enumerate(ring):
            b = ring[(i + 1) % len(ring)]
            ax, ay = (a[0] - capture[0]) * sx, (a[1] - capture[1]) * sy
            bx, by = (b[0] - capture[0]) * sx, (b[1] - capture[1]) * sy
            dx, dy = bx - ax, by - ay
            t = max(0.0, min(1.0, -(ax * dx + ay * dy) / (dx * dx + dy * dy or 1.0)))
            x, y = ax + t * dx, ay + t * dy
            dist = math.hypot(x, y)
            if dist < best_d:
                best_d, best_point, best_edge = dist, (capture[0] + x / sx, capture[1] + y / sy), i
    return best_d, best_point, best_edge

def reusable_photo_license(value: Any) -> bool:
    if isinstance(value, dict): value = value.get("id") or value.get("name") or value.get("license")
    text = str(value or "").strip().lower().replace("_", "-").replace(" ", "-")
    if "creativecommons.org/publicdomain/zero" in text: text = "cc0"
    elif "creativecommons.org/licenses/by-sa/" in text: text = "cc-by-sa"
    elif "creativecommons.org/licenses/by/" in text: text = "cc-by"
    if any(token in text for token in ("noncommercial", "non-commercial", "-nc", "no-derivatives", "-nd", "all-rights-reserved", "proprietary")):
        return False
    return any(token in text for token in ("cc0", "public-domain", "cc-by", "cc-by-sa", "etalab-2.0"))

def photo_quality_gate(photo: dict[str, Any], distance_m: float, delta: float | None) -> tuple[bool, list[str], int]:
    reasons = []
    if distance_m > 35: reasons.append("capture_too_far_from_facade")
    if photo.get("azimuth_deg") is None: reasons.append("camera_azimuth_missing")
    elif delta is None or delta > 25: reasons.append("camera_not_facing_facade")
    fov = photo.get("field_of_view_deg")
    if fov is not None and not 25 <= fov <= 120: reasons.append("usable_field_of_view_invalid")
    pitch = photo.get("pitch_deg")
    if pitch is not None and not -45 <= pitch <= 45: reasons.append("camera_pitch_not_facade_level")
    accuracy = photo.get("horizontal_accuracy_m")
    if accuracy is not None and accuracy > 20: reasons.append("capture_position_accuracy_too_low")
    if not reusable_photo_license(photo.get("license")): reasons.append("redistribution_license_not_confirmed")
    if not photo.get("preview_url"): reasons.append("no_real_image_preview_asset")
    if not photo.get("image_url"): reasons.append("no_downloadable_photo_asset")
    score = 100 - min(55, int(distance_m * 1.5)) - min(35, int((delta or 0) * 1.2))
    if fov is None: score -= 12
    if pitch is None: score -= 5
    if not photo.get("capture_datetime"): score -= 5
    if not reusable_photo_license(photo.get("license")): score -= 30
    return not reasons, reasons, max(0, score)


def angle_delta(a: float, b: float) -> float:
    return abs((a - b + 180) % 360 - 180)


def number(value: Any) -> float | None:
    try:
        n = float(value)
        return n if math.isfinite(n) else None
    except (TypeError, ValueError):
        return None


def photo_metadata(feature: dict[str, Any]) -> dict[str, Any]:
    p = feature.get("properties") or {}
    camera = p.get("pers:interior_orientation") or {}
    az = number(p.get("view:azimuth"))
    fov = number(camera.get("field_of_view"))
    if fov is None:
        fov = number(p.get("pers:field_of_view"))
    license_value = p.get("license") or p.get("licence")
    assets = feature.get("assets") or {}
    def asset_with_role(role: str) -> dict[str, Any]:
        for asset in assets.values():
            if isinstance(asset, dict) and role in (asset.get("roles") or []) and asset.get("href"):
                return asset
        return {}
    thumbnail = asset_with_role("thumbnail")
    visual = asset_with_role("visual")
    # Panoramax names assets by implementation; the STAC role, not the key name,
    # identifies the actual display image. Do not mistake tiled "data" assets for photos.
    preview_asset = thumbnail or visual
    full_image = visual
    photo_id = str(feature.get("id") or "")
    # The federated Panoramax catalogue may omit per-item assets while the
    # public picture endpoint still serves thumbnail and HD derivatives by UUID.
    if re.fullmatch(r"[0-9a-fA-F-]{36}", photo_id):
        if not preview_asset:
            preview_asset = {"href": f"https://api.panoramax.xyz/api/pictures/{photo_id}/thumb.jpg"}
        # The federated API's HD endpoint is preferable to an SD "visual" derivative
        # when the image is eventually baked into a facade texture.
        full_image = {"href": f"https://api.panoramax.xyz/api/pictures/{photo_id}/hd.jpg"}
    links = feature.get("links") or []
    self_link = next((x.get("href") for x in links if x.get("rel") == "self"), None)
    return {
        "photo_id": str(feature.get("id") or ""),
        "capture_lonlat": list(centroid_lonlat(feature.get("geometry") or {}) or []),
        "capture_datetime": p.get("datetime") or p.get("datetimetz"),
        "azimuth_deg": az,
        "field_of_view_deg": fov,
        "pitch_deg": number(p.get("pers:pitch")),
        "roll_deg": number(p.get("pers:roll")),
        "horizontal_accuracy_m": number(p.get("quality:horizontal_accuracy")),
        "horizontal_pixel_density": number(p.get("panoramax:horizontal_pixel_density")),
        "license": license_value,
        "instance": p.get("instance"),
        "metadata_url": self_link,
        "preview_url": preview_asset.get("href") if isinstance(preview_asset, dict) else None,
        "image_url": full_image.get("href") if isinstance(full_image, dict) else None,
    }


def building_id(feature: dict[str, Any]) -> str:
    p = feature.get("properties") or {}
    tags = p.get("tags") if isinstance(p.get("tags"), dict) else {}
    raw = (p.get("osm_feature_id") or p.get("osm_way_id") or p.get("osm_id")
           or p.get("source_feature_id") or tags.get("osm_id")
           or p.get("id") or feature.get("id"))
    if raw is None:
        return ""
    value = str(raw).strip()
    match = re.fullmatch(r"(?:way/)?([1-9][0-9]*)", value, re.I)
    if not match:
        match = re.search(r"(?:^|\\|)id:([1-9][0-9]*)$", value, re.I)
    return match.group(1) if match else ""


def aerial_candidates(building: dict[str, Any], items: list[dict[str, Any]]) -> list[dict[str, Any]]:
    bpts = list(coords_of(building.get("geometry")))
    if not bpts:
        return []
    bx = (min(p[0] for p in bpts), min(p[1] for p in bpts), max(p[0] for p in bpts), max(p[1] for p in bpts))
    found = []
    for item in items:
        ipts = list(coords_of(item.get("geometry")))
        if not ipts:
            continue
        ix = (min(p[0] for p in ipts), min(p[1] for p in ipts), max(p[0] for p in ipts), max(p[1] for p in ipts))
        if not (bx[0] <= ix[2] and bx[2] >= ix[0] and bx[1] <= ix[3] and bx[3] >= ix[1]):
            continue
        p = item.get("properties") or {}
        item_id = str(item.get("id") or "")
        title = p.get("title") or p.get("description") or ""
        gsd = number(p.get("gsd") or p.get("proj:gsd"))
        descriptive = (item_id + " " + str(title) + " " + str(p.get("platform") or "")).upper()
        is_dem = any(term in descriptive for term in ("_DEM", " DSM", "DEM_", "ELEVATION"))
        if is_dem:
            kind = "digital_elevation_model"
        elif gsd is not None and gsd <= 1.0:
            kind = "high_resolution_optical_candidate"
        elif gsd is not None:
            kind = "coarse_or_context_imagery"
        else:
            kind = "unclassified_raster"
        found.append({
            "item_id": item_id,
            "title": title,
            "datetime": p.get("datetime") or p.get("start_datetime"),
            "gsd_m": gsd,
            "kind": kind,
            "license": p.get("license") or p.get("licence"),
            "roof_shape_colour_candidate_only": kind == "high_resolution_optical_candidate",
            "facade_texture_allowed": False,
            "assets": {k: v.get("href") for k, v in (item.get("assets") or {}).items()
                       if isinstance(v, dict) and v.get("href")},
        })
    return found

def build_queue(city_dir: Path, max_distance: float, checkpoint_every: int = 100, publish_checkpoints: bool = False) -> dict[str, Any]:
    # Manila's committed canonical building source is the 48 MB
    # layers/building-layer.geojson. Older folders may still use the two
    # compatibility filenames below; use them only when present.
    candidates = (
        city_dir / "layers" / "building-layer.geojson",
        city_dir / "buildings.geojson",
        city_dir / "building-footprints.geojson",
    )
    buildings_path = next((path for path in candidates if path.is_file() and path.stat().st_size > 0), None)
    if buildings_path is None:
        raise FileNotFoundError("No non-empty buildings.geojson, building-footprints.geojson, or layers/building-layer.geojson")
    buildings_fc = read_fc(buildings_path)
    photos_fc = read_fc(city_dir / "panoramax-items.geojson", required=False)
    aerial_fc = read_fc(city_dir / "aerial-items.geojson", required=False)
    photos = [photo_metadata(f) | {"_geometry": f.get("geometry"), "source": "panoramax"} for f in photos_fc["features"]]
    source_hash = hashlib.sha256()
    for name, path in ((buildings_path.name, buildings_path),
                       ("panoramax-items.geojson", city_dir / "panoramax-items.geojson"),
                       ("aerial-items.geojson", city_dir / "aerial-items.geojson")):
        source_hash.update(name.encode())
        if path.exists():
            source_hash.update(path.read_bytes())
    source_sha256 = source_hash.hexdigest()
    final_path = city_dir / "review_queue.json"
    if final_path.is_file():
        previous_final = json.loads(final_path.read_text(encoding="utf-8"))
        if previous_final.get("source_sha256") == source_sha256 and previous_final.get("building_count", 0) > 0:
            print(f"QUEUE_ALREADY_COMPLETE city={city_dir.name} buildings={previous_final['building_count']}")
            return previous_final
    checkpoint_path = city_dir / ".review_queue_checkpoint.json"
    previous = json.loads(checkpoint_path.read_text(encoding="utf-8")) if checkpoint_path.is_file() else {}
    queue = previous.get("buildings", []) if previous.get("source_sha256") == source_sha256 else []
    seen = {str(item.get("osm_way_id")) for item in queue}
    checkpoint_every = max(1, int(checkpoint_every))
    for building_index, b in enumerate(buildings_fc["features"], 1):
        osm_id = building_id(b)
        center = centroid_lonlat(b.get("geometry") or {})
        if not osm_id or not center or str(osm_id) in seen:
            continue
        raw_props = b.get("properties") or {}
        bp = dict(raw_props.get("tags") or {}) if isinstance(raw_props.get("tags"), dict) else {}
        bp.update({k: v for k, v in raw_props.items() if k != "tags"})
        key = osm_id
        if key in seen:
            continue
        seen.add(key)
        candidates = []
        for photo in photos:
            loc = photo.get("capture_lonlat") or []
            if len(loc) != 2:
                continue
            dist, target_point, edge_index = nearest_footprint_point((loc[0], loc[1]), b.get("geometry") or {})
            if dist > max_distance:
                continue
            az = photo.get("azimuth_deg")
            target_bearing = bearing_deg((loc[0], loc[1]), target_point)
            if az is None:
                direction, delta = "unknown", None
            else:
                delta = angle_delta(az, target_bearing)
                direction = "within_camera_cone" if delta <= 25.0 else "outside_camera_cone"
            eligible, rejection_reasons, quality_score = photo_quality_gate(photo, dist, delta)
            candidates.append({
                "photo_id": photo["photo_id"],
                "distance_m": round(dist, 2),
                "capture_lonlat": [round(loc[0], 7), round(loc[1], 7)],
                "target_point_lonlat": [round(target_point[0], 7), round(target_point[1], 7)],
                "facade_edge_index": edge_index,
                "target_bearing_deg": round(target_bearing, 2),
                "azimuth_delta_deg": round(delta, 2) if delta is not None else None,
                "direction_status": direction,
                "capture_datetime": photo["capture_datetime"],
                "camera": {k: photo[k] for k in ("azimuth_deg", "field_of_view_deg", "pitch_deg", "roll_deg", "horizontal_accuracy_m", "horizontal_pixel_density")},
                "license": photo["license"],
                "instance": photo["instance"],
                "metadata_url": photo["metadata_url"],
                "preview_url": photo["preview_url"],
                "image_url": photo["image_url"],
                "quality_score": quality_score,
                "eligible_for_visual_review": eligible,
                "quality_rejection_reasons": rejection_reasons,
                "quality_warnings": (["camera_fov_missing_manual_calibration_required"] if photo["field_of_view_deg"] is None else []),
                "identity_status": "unverified_candidate_only",
                "must_human_verify": True,
            })
        candidates.sort(key=lambda x: (not x["eligible_for_visual_review"], -x["quality_score"], x["distance_m"], x["azimuth_delta_deg"] if x["azimuth_delta_deg"] is not None else 999))
        queue.append({
            "osm_way_id": osm_id,
            "name": bp.get("name"),
            "building_tags": {k: bp[k] for k in ("building", "building:material", "building:colour", "roof:material", "roof:colour", "height", "building:levels") if k in bp},
            "footprint_geometry": b.get("geometry"),
            "aerial_evidence_candidates": aerial_candidates(b, aerial_fc["features"]),
            "facade_photo_candidates": candidates,
            "identity_status": "unverified",
            "texture_assignment_allowed": False,
        })
        seen.add(str(osm_id))
        if publish_checkpoints and len(queue) % checkpoint_every == 0:
            atomic_json(checkpoint_path, {
                "source_sha256": source_sha256, "complete": False,
                "processed_buildings": len(queue), "buildings": queue,
            })
            subprocess.run(["bash", str(ROOT / "tools/ci/publish-checkpoint.sh"),
                            f"Checkpoint {city_dir.name} review queue {len(queue)} buildings",
                            str(checkpoint_path.relative_to(ROOT))], cwd=ROOT, check=True)
            print(f"QUEUE_CHECKPOINT city={city_dir.name} buildings={len(queue)}", flush=True)
    queue.sort(key=lambda x: int(x["osm_way_id"]))
    if not queue:
        sample = [(f.get("properties") or {}).get("osm_feature_id") or f.get("id")
                  for f in buildings_fc["features"][:5]]
        raise ValueError(
            f"{buildings_path}: {len(buildings_fc['features'])} features but zero usable OSM way IDs; "
            f"sample identifiers={sample}"
        )
    return {
        "schema_version": 1,
        "city": city_dir.name,
        "source_sha256": source_sha256,
        "matching_policy": {
            "maximum_discovery_distance_m": max_distance,
            "proximity_is_not_identity": True,
            "camera_cone_is_candidate_filter_only": False,
            "unknown_camera_azimuth_kept_for_manual_review": True,
            "photo_source": "Panoramax only; KartaView excluded",
            "automatic_texture_assignment": False,
            "strict_visual_review_gate": {"max_facade_distance_m": 35, "max_azimuth_delta_deg": 25, "requires_camera_azimuth": True, "camera_fov_missing_is_manual_calibration_warning": True, "requires_open_redistributable_license": True, "requires_real_preview_and_image_assets": True},
        },
        "building_count": len(queue),
        "buildings": queue,
    }


def atomic_json(path: Path, obj: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(obj, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if path.exists() and path.read_text(encoding="utf-8") == payload:
        print(f"UNCHANGED {path}")
        return
    fd, tmp = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(payload)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)
    print(f"SAVED {path}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--cities-root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--city", help="process one city folder; default processes every city folder")
    parser.add_argument("--max-distance-m", type=float, default=MAX_DISTANCE_M)
    parser.add_argument("--checkpoint-every", type=int, default=100)
    parser.add_argument("--publish-checkpoints", action="store_true")
    args = parser.parse_args()
    if args.max_distance_m <= 0 or args.max_distance_m > 500:
        parser.error("--max-distance-m must be > 0 and <= 500")
    if args.city:
        dirs = [args.cities_root / args.city]
    else:
        dirs = sorted(p for p in args.cities_root.iterdir() if p.is_dir()) if args.cities_root.exists() else []
    if not dirs:
        print("No city input folders found; no assignments or textures were created.")
        return
    for city_dir in dirs:
        # Use the already-imported Manila building layer when compatibility
        # filenames are absent. Do not fetch or import any new source data.
        candidates = (
            city_dir / "layers" / "building-layer.geojson",
            city_dir / "buildings.geojson",
            city_dir / "building-footprints.geojson",
        )
        buildings_path = next((p for p in candidates if p.is_file() and p.stat().st_size > 0), None)
        if buildings_path is None:
            print(f"SKIP {city_dir}: no existing building footprints found")
            continue
        result = build_queue(city_dir, args.max_distance_m, args.checkpoint_every, args.publish_checkpoints)
        atomic_json(city_dir / "review_queue.json", result)
        checkpoint_path = city_dir / ".review_queue_checkpoint.json"
        if checkpoint_path.exists():
            checkpoint_path.unlink()
        candidate_count = sum(len(b["facade_photo_candidates"]) for b in result["buildings"])
        eligible_count = sum(1 for b in result["buildings"] for p in b["facade_photo_candidates"] if p["eligible_for_visual_review"])
        reason_counts: dict[str, int] = {}
        for building in result["buildings"]:
            for photo in building["facade_photo_candidates"]:
                for reason in photo["quality_rejection_reasons"]:
                    reason_counts[reason] = reason_counts.get(reason, 0) + 1
        top_reasons = sorted(reason_counts.items(), key=lambda item: (-item[1], item[0]))[:6]
        print(f"SUMMARY city={city_dir.name} buildings={result['building_count']} photo_candidates={candidate_count} strict_visual_review={eligible_count} rejection_reasons={top_reasons}")


if __name__ == "__main__":
    main()
