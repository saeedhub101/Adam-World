#!/usr/bin/env python3
"""Download and validate only strict Panoramax facade-review previews.

A valid image file is not proof of building identity. This creates a compact
human-review gallery; it never marks an assignment verified or textures a mesh.
"""
from __future__ import annotations
import argparse, html, json, os, re, subprocess, tempfile, time
from pathlib import Path
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ROOT = ROOT / "data/facades/cities"

def safe_name(value: str) -> str:
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", value)[:100] or "photo"

def download(url: str, timeout: int = 30) -> bytes:
    req = Request(url, headers={"User-Agent": "AdamWorld-FacadeReview/1.0", "Accept": "image/*"})
    with urlopen(req, timeout=timeout) as response:
        ctype = response.headers.get("Content-Type", "").lower()
        if not ctype.startswith("image/"):
            raise ValueError(f"asset is not an image (Content-Type={ctype or 'missing'})")
        data = response.read(12 * 1024 * 1024 + 1)
        if len(data) > 12 * 1024 * 1024:
            raise ValueError("image exceeds 12 MiB")
        return data

def process_city(city: Path, limit: int) -> dict:
    queue_path = city / "review_queue.json"
    if not queue_path.is_file():
        print(f"SKIP {city.name}: review_queue.json missing")
        return {"city": city.name, "eligible": 0, "downloaded": 0, "failed": 0}
    queue = json.loads(queue_path.read_text(encoding="utf-8"))
    chosen = []
    for building in queue.get("buildings", []):
        eligible = [p for p in building.get("facade_photo_candidates", []) if p.get("eligible_for_visual_review")]
        if eligible:
            best = eligible[0]
            chosen.append({
                "osm_way_id": building.get("osm_way_id"),
                "building_name": building.get("name"),
                "footprint_geometry": building.get("footprint_geometry"),
                "distance_m": best.get("distance_m"),
                "azimuth_delta_deg": best.get("azimuth_delta_deg"),
                "quality_score": best.get("quality_score"),
                "photo": best,
            })
    chosen.sort(key=lambda x: (-int(x["quality_score"] or 0), float(x["distance_m"] or 999)))
    # De-duplicate same photo appearing as candidate for several neighbouring buildings.
    unique, seen = [], set()
    for item in chosen:
        pid = str(item["photo"].get("photo_id") or "")
        if not pid or pid in seen: continue
        seen.add(pid); unique.append(item)
    chosen = unique[:limit]
    out = city / "facade-review"
    images = out / "images"
    images.mkdir(parents=True, exist_ok=True)
    reviewed, failed = [], []
    for i, item in enumerate(chosen, 1):
        photo = item["photo"]
        pid = str(photo.get("photo_id") or f"candidate-{i}")
        url = photo.get("preview_url")
        try:
            if not url or not url.startswith("https://"):
                raise ValueError("missing HTTPS preview")
            raw = download(url)
            from io import BytesIO
            with Image.open(BytesIO(raw)) as im:
                im.verify()
            with Image.open(BytesIO(raw)) as im:
                width, height = im.size
                if width < 320 or height < 180:
                    raise ValueError(f"preview too small: {width}x{height}")
                fmt = (im.format or "JPEG").lower()
                ext = "jpg" if fmt in ("jpeg", "jpg") else fmt
                filename = safe_name(pid) + "." + ext
                target = images / filename
                if not target.exists():
                    target.write_bytes(raw)
            reviewed.append({
                "osm_way_id": item["osm_way_id"],
                "osm_url": "https://www.openstreetmap.org/way/" + str(item["osm_way_id"]),
                "building_name": item["building_name"], "footprint_geometry": item["footprint_geometry"],
                "capture_lonlat": photo.get("capture_lonlat"), "target_point_lonlat": photo.get("target_point_lonlat"),
                "facade_edge_index": photo.get("facade_edge_index"), "target_bearing_deg": photo.get("target_bearing_deg"),
                "photo_id": pid, "image": "images/" + filename, "width": width, "height": height,
                "distance_m": item["distance_m"], "azimuth_delta_deg": item["azimuth_delta_deg"],
                "quality_score": item["quality_score"], "license": photo.get("license"),
                "capture_datetime": photo.get("capture_datetime"), "camera": photo.get("camera"),
                "metadata_url": photo.get("metadata_url"), "full_image_url": photo.get("image_url"),
                "identity_status": "requires_visual_identity_review",
            })
            print(f"IMAGE_OK {city.name} photo={pid} size={width}x{height}")
        except Exception as exc:
            failed.append({"photo_id": pid, "osm_way_id": item["osm_way_id"], "error": str(exc)})
            print(f"IMAGE_REJECT {city.name} photo={pid}: {exc}")
        if i % 20 == 0: time.sleep(0.15)
    manifest = {
        "schema_version": 1, "city": city.name,
        "policy": "Image decode, minimum dimensions, camera geometry and licence are machine-filtered. Exact depicted-building identity remains unverified until visual inspection.",
        "eligible_building_candidates": len(chosen), "downloaded_valid_images": len(reviewed),
        "failed_or_rejected_images": len(failed), "items": reviewed, "rejected": failed,
    }
    out.mkdir(parents=True, exist_ok=True)
    (out / "review_gallery.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    cards = []
    for item in reviewed:
        cards.append(
            '<article><img loading="lazy" src="' + html.escape(item["image"], quote=True) + '">' +
            '<h3>OSM building ' + html.escape(str(item["osm_way_id"])) + ' — ' + html.escape(str(item["building_name"] or "Unnamed")) + '</h3>' +
            '<p>Photo ' + html.escape(item["photo_id"]) + ' · ' + str(item["distance_m"]) + ' m from footprint · camera delta ' + str(item["azimuth_delta_deg"]) + '°</p>' +
            '<p>License: ' + html.escape(str(item["license"])) + ' · ' + html.escape(str(item["capture_datetime"])) + '</p>' +
            '<p><a href="' + html.escape(str(item["osm_url"]), quote=True) + '">OSM building</a> · ' +
            '<a href="' + html.escape(str(item["metadata_url"] or ""), quote=True) + '">Panoramax metadata</a> · ' +
            '<a href="' + html.escape(str(item["full_image_url"] or ""), quote=True) + '">HD source image</a></p>' +
            '<strong>Identity is not yet verified; do not apply this image to a building.</strong></article>'
        )
    page = '<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Adam World facade review — ' + html.escape(city.name) + '</title><style>body{font:15px system-ui;background:#111820;color:#edf2f7;margin:20px}main{display:grid;grid-template-columns:repeat(auto-fit,minmax(300px,1fr));gap:16px}article{background:#202b36;padding:12px;border-radius:8px}img{width:100%;height:220px;object-fit:contain;background:#080c10}a{color:#82c7ff}</style><h1>Facade evidence review — ' + html.escape(city.name) + '</h1><p>Only machine-screened preview images. Verify the exact building and facade visually before assigning any texture.</p><main>' + ''.join(cards) + '</main></html>'
    (out / "index.html").write_text(page, encoding="utf-8")
    print(f"REVIEW_GALLERY city={city.name} strict_candidates={len(chosen)} images_ok={len(reviewed)} rejected={len(failed)}")
    return {"city": city.name, "eligible": len(chosen), "downloaded": len(reviewed), "failed": len(failed)}

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--cities-root", type=Path, default=DEFAULT_ROOT)
    parser.add_argument("--city")
    parser.add_argument("--limit-per-city", type=int, default=120)
    parser.add_argument("--git-checkpoint", action="store_true")
    args = parser.parse_args()
    dirs = [args.cities_root / args.city] if args.city else sorted(p for p in args.cities_root.iterdir() if p.is_dir())
    totals = {"eligible": 0, "downloaded": 0, "failed": 0}
    for city in dirs:
        result = process_city(city, args.limit_per_city)
        for key in totals: totals[key] += result.get(key, 0)
        if args.git_checkpoint:
            subprocess.run(["git", "add", "-A", "data/facades/cities"], cwd=ROOT, check=True)
            changed = subprocess.run(["git", "diff", "--cached", "--quiet"], cwd=ROOT).returncode != 0
            if changed:
                subprocess.run(["git", "commit", "-m", f"Checkpoint {city.name} facade image review gallery"], cwd=ROOT, check=True)
                subprocess.run(["git", "pull", "--rebase", "origin", "main"], cwd=ROOT, check=True)
                subprocess.run(["git", "push", "origin", "HEAD:main"], cwd=ROOT, check=True)
    print("FACADE_REVIEW_TOTAL " + json.dumps(totals))

if __name__ == "__main__":
    main()
