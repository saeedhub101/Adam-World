#!/usr/bin/env python3
"""Reconcile Manilas already-committed facade photos with local building candidates.

This script is deliberately offline: it never contacts a catalogue, downloads an
image, or writes a verified facade assignment. It only joins photo UUID filenames
to the existing Panoramax metadata queue and builds a compact visual review page.
"""
from __future__ import annotations

import argparse
import html
import json
from pathlib import Path
try:
    from .review_queue_io import load_review_queue
except ImportError:
    from review_queue_io import load_review_queue

ROOT = Path(__file__).resolve().parents[2]
CITY = ROOT / "data/facades/cities/manila"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--city-dir", type=Path, default=CITY)
    args = parser.parse_args()
    city = args.city_dir
    queue_path = city / "review_queue.json"
    if not queue_path.is_file():
        raise SystemExit(f"Missing existing review queue: {queue_path}")
    queue = load_review_queue(queue_path)

    candidates_by_photo: dict[str, list[dict]] = {}
    for building in queue.get("buildings", []):
        for photo in building.get("facade_photo_candidates", []):
            photo_id = str(photo.get("photo_id") or "")
            if not photo_id:
                continue
            candidates_by_photo.setdefault(photo_id, []).append({
                "osm_way_id": str(building.get("osm_way_id") or ""),
                "building_name": building.get("name") or "",
                "distance_m": photo.get("distance_m"),
                "azimuth_delta_deg": photo.get("azimuth_delta_deg"),
                "facade_edge_index": photo.get("facade_edge_index"),
                "target_bearing_deg": photo.get("target_bearing_deg"),
                "capture_lonlat": photo.get("capture_lonlat"),
                "target_point_lonlat": photo.get("target_point_lonlat"),
                "license": photo.get("license"),
                "capture_datetime": photo.get("capture_datetime"),
                "metadata_url": photo.get("metadata_url"),
                "identity_status": "unverified_candidate_only",
            })

    image_dir = city / "facade-review" / "images"
    full_dir = city / "facade-review" / "full"
    previews = {p.stem: p for p in image_dir.iterdir() if p.is_file()} if image_dir.is_dir() else {}
    full_images = {p.stem: p for p in full_dir.iterdir() if p.is_file()} if full_dir.is_dir() else {}
    photo_ids = sorted(set(previews) | set(full_images))
    items = []
    for photo_id in photo_ids:
        preview = previews.get(photo_id)
        full = full_images.get(photo_id)
        items.append({
            "photo_id": photo_id,
            "preview": str(preview.relative_to(city)).replace("\\", "/") if preview else None,
            "full_image": str(full.relative_to(city)).replace("\\", "/") if full else None,
            "existing_asset_only": True,
            "building_candidates": sorted(
                candidates_by_photo.get(photo_id, []),
                key=lambda x: (
                    float(x["distance_m"]) if x["distance_m"] is not None else 1e9,
                    float(x["azimuth_delta_deg"]) if x["azimuth_delta_deg"] is not None else 1e9,
                ),
            ),
            "identity_status": "requires_visual_identity_review",
            "texture_assignment_allowed": False,
        })

    report = {
        "schema_version": 1,
        "city": "manila",
        "method": "offline UUID join of already-committed photo files to existing Panoramax/building candidate metadata",
        "network_requests": 0,
        "new_images_downloaded": 0,
        "existing_preview_images": len(previews),
        "existing_full_resolution_images": len(full_images),
        "photos_with_building_candidates": sum(bool(x["building_candidates"]) for x in items),
        "photos_without_building_candidates": sum(not x["building_candidates"] for x in items),
        "candidate_building_links": sum(len(x["building_candidates"]) for x in items),
        "identity_policy": "Proximity, camera bearing and image presence are discovery evidence only. No image is attached to a building until exact identity and facade side are visually confirmed.",
        "texture_assignment_allowed": False,
        "items": items,
    }
    out_json = city / "facade-review" / "existing-photo-audit.json"
    out_json.parent.mkdir(parents=True, exist_ok=True)
    out_json.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    cards = []
    for item in items:
        image_src = item["preview"] or item["full_image"]
        if image_src:
            image_tag = f'<a href="{html.escape(item["full_image"] or image_src)}"><img loading="lazy" src="{html.escape(image_src)}" alt="Panoramax {html.escape(item["photo_id"])}"></a>'
        else:
            image_tag = "<p>No local image file</p>"
        links = []
        for candidate in item["building_candidates"][:8]:
            way = html.escape(candidate["osm_way_id"])
            name = html.escape(candidate["building_name"] or "Unnamed building")
            distance = candidate["distance_m"]
            delta = candidate["azimuth_delta_deg"]
            links.append(
                f'<li><a href="https://www.openstreetmap.org/way/{way}" target="_blank" rel="noopener">OSM way {way}</a> — {name}; '
                f'distance={html.escape(str(distance))} m; camera-angle-delta={html.escape(str(delta))}°; '
                f'edge={html.escape(str(candidate["facade_edge_index"]))}</li>'
            )
        if not links:
            links = ["<li>No building candidate matched this existing photo UUID.</li>"]
        cards.append(
            f'<article><h2>{html.escape(item["photo_id"])}</h2>{image_tag}'
            f'<p>Existing file only. Identity not verified; no texture applied.</p>'
            f'<ul>{"".join(links)}</ul></article>'
        )

    page = """<!doctype html>
<html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width">
<title>Manila — existing facade photos</title>
<style>
body{font:15px system-ui;background:#101820;color:#edf2f7;margin:20px}
header{max-width:1100px;margin:0 auto 20px}main{display:grid;grid-template-columns:repeat(auto-fit,minmax(320px,1fr));gap:16px;max-width:1500px;margin:auto}
article{background:#202d38;padding:14px;border-radius:10px;overflow-wrap:anywhere}
img{width:100%;height:260px;object-fit:contain;background:#080c10}
a{color:#83c9ff}li{margin:8px 0}small{color:#b8c4ce}
</style>
<header><h1>Manila — existing facade photos</h1>
<p>Only files already stored in this repository are shown. Candidate links are geometric hints, not proof of building identity. No image is automatically assigned to a building.</p>
<p>Generated by an offline audit: zero network requests and zero new image downloads.</p></header>
<main>""" + "".join(cards) + """</main></html>
"""
    (city / "facade-review" / "index.html").write_text(page, encoding="utf-8")
    print(json.dumps({k: report[k] for k in (
        "existing_preview_images", "existing_full_resolution_images",
        "photos_with_building_candidates", "photos_without_building_candidates",
        "candidate_building_links", "new_images_downloaded", "network_requests"
    )}, sort_keys=True))


if __name__ == "__main__":
    main()
