#!/usr/bin/env python3
"""Free, local computer-vision screening for facade-photo candidates.

Uses OpenCV only; no hosted API, API key, or paid service. It measures image
integrity, sharpness, exposure, contrast, entropy, edge structure and vertical
facade-line evidence, then combines those signals with existing camera/footprint
geometry. It is a screening stage, NOT proof of exact building identity:
ambiguous cases remain human-review-only and can never write verified_facades.json.
"""
from __future__ import annotations
import argparse, hashlib, json, math, tempfile, time, os, subprocess
from pathlib import Path
import cv2
import numpy as np
try:
    from .review_queue_io import load_review_queue
except ImportError:
    from review_queue_io import load_review_queue

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ROOT = ROOT / "data/facades/cities"
MAX_BYTES = 12 * 1024 * 1024


def analyze_image(raw: bytes) -> dict:
    arr = np.frombuffer(raw, dtype=np.uint8)
    image = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    if image is None: raise ValueError("OpenCV could not decode image")
    h,w = image.shape[:2]
    if w < 320 or h < 180: raise ValueError(f"image too small: {w}x{h}")
    # Bound CPU and memory while retaining enough facade detail.
    scale = min(1.0, 1280.0 / max(w,h))
    if scale < 1: image = cv2.resize(image, (int(w*scale),int(h*scale)), interpolation=cv2.INTER_AREA)
    gray = cv2.cvtColor(image, cv2.COLOR_BGR2GRAY)
    sharpness = float(cv2.Laplacian(gray, cv2.CV_64F).var())
    mean = float(gray.mean()) / 255.0
    std = float(gray.std()) / 255.0
    hist = cv2.calcHist([gray],[0],None,[64],[0,256]).ravel()
    prob = hist / max(1.0, float(hist.sum()))
    entropy = float(-(prob[prob>0] * np.log2(prob[prob>0])).sum())
    edges = cv2.Canny(gray, 70, 160)
    edge_density = float(np.count_nonzero(edges)) / edges.size
    lines = cv2.HoughLinesP(edges,1,np.pi/180,threshold=35,
                            minLineLength=max(25,int(min(gray.shape)*0.10)),maxLineGap=12)
    vertical = horizontal = 0.0
    if lines is not None:
        for line in lines.reshape(-1,4):
            x1,y1,x2,y2 = map(int,line)
            length = math.hypot(x2-x1,y2-y1)
            angle = abs(math.degrees(math.atan2(y2-y1,x2-x1))) % 180
            if 70 <= angle <= 110: vertical += length
            if angle <= 20 or angle >= 160: horizontal += length
    line_total = vertical + horizontal
    vertical_ratio = vertical / line_total if line_total else 0.0
    checks = {
        "resolution": w >= 640 and h >= 360,
        "sharpness": sharpness >= 45,
        "exposure": 0.12 <= mean <= 0.92,
        "contrast": std >= 0.10,
        "entropy": entropy >= 3.0,
        "edge_structure": 0.015 <= edge_density <= 0.45,
        "facade_line_cues": vertical_ratio >= 0.18 and vertical >= 80,
    }
    score = round(100 * sum(checks.values()) / len(checks))
    return {
        "width":w,"height":h,"sharpness_laplacian":round(sharpness,2),
        "mean_luminance":round(mean,4),"contrast_std":round(std,4),
        "entropy_bits":round(entropy,3),"edge_density":round(edge_density,4),
        "vertical_line_ratio":round(vertical_ratio,4),"vertical_line_length_px":round(vertical,1),
        "checks":checks,"visual_quality_score":score,
        "visual_quality_pass":all(checks.values()),
    }

def atomic_report(target: Path, report: dict) -> None:
    target.parent.mkdir(parents=True, exist_ok=True)
    fd, temp = tempfile.mkstemp(prefix=target.name + ".", dir=target.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
        os.replace(temp, target)
    finally:
        if os.path.exists(temp):
            os.unlink(temp)


def process_city(city: Path, limit: int, delay: float = 0.0,
                 checkpoint_every: int = 100, publish_checkpoints: bool = False) -> dict:
    """Screen repository-local images with resumable, durable checkpoints."""
    queue_path = city / "review_queue.json"
    if not queue_path.is_file():
        return {"city": city.name, "status": "no_queue", "checked": 0}
    queue_bytes = queue_path.read_bytes()
    queue_hash = hashlib.sha256(queue_bytes).hexdigest()
    queue = load_review_queue(queue_path)
    target = city / "visual_verification.json"
    previous = json.loads(target.read_text(encoding="utf-8")) if target.is_file() else {}
    if previous.get("source_sha256") == queue_hash and previous.get("complete") is True:
        print(f"CV_ALREADY_COMPLETE city={city.name} items={len(previous.get('items', []))}")
        return {"city": city.name, "checked": previous.get("checked", 0),
                "candidates": previous.get("quality_and_geometry_candidates", 0),
                "failed": previous.get("failed", 0)}

    records = previous.get("items", []) if previous.get("source_sha256") == queue_hash else []
    processed = {(str(x.get("osm_way_id")), str(x.get("photo_id"))) for x in records}
    checked = sum(1 for x in records if x.get("computer_vision") or x.get("screening_status") == "local_image_decode_failed")
    failed = sum(1 for x in records if x.get("screening_status") == "local_image_decode_failed")
    passed = sum(1 for x in records if x.get("screening_status") == "candidate_for_human_identity_review")
    unmatched = sum(1 for x in records if x.get("screening_status") == "no_matching_repository_local_image")
    local_roots = (city / "facade-review" / "full", city / "facade-review" / "images")
    checkpoint_every = max(1, int(checkpoint_every))

    def make_report(complete: bool) -> dict:
        return {
            "schema_version": 1, "city": city.name,
            "engine": "OpenCV local computer vision; repository images only",
            "source_sha256": queue_hash, "complete": complete,
            "processed_items": len(records),
            "network_requests": 0, "new_images_downloaded": 0,
            "checked": checked, "quality_and_geometry_candidates": passed,
            "failed": failed, "unmatched_local_images": unmatched,
            "identity_policy": "Quality and geometry are candidate evidence only; exact building identity requires human visual confirmation.",
            "texture_assignment_allowed": False, "items": records,
        }

    for building in queue.get("buildings", []):
        building_id = str(building.get("osm_way_id"))
        candidates = [p for p in building.get("facade_photo_candidates", [])
                      if p.get("eligible_for_visual_review")]
        for photo in candidates[:limit]:
            photo_id = str(photo.get("photo_id") or "")
            key = (building_id, photo_id)
            if key in processed:
                continue
            item = {
                "osm_way_id": building_id, "building_name": building.get("name"),
                "photo_id": photo_id, "distance_m": photo.get("distance_m"),
                "azimuth_delta_deg": photo.get("azimuth_delta_deg"),
                "facade_edge_index": photo.get("facade_edge_index"),
                "capture_lonlat": photo.get("capture_lonlat"),
                "target_point_lonlat": photo.get("target_point_lonlat"),
                "metadata_url": photo.get("metadata_url"), "license": photo.get("license"),
                "camera": photo.get("camera"), "source": "panoramax",
                "identity_status": "unverified_requires_human_confirmation",
                "texture_assignment_allowed": False,
            }
            matches = [p for root in local_roots if root.is_dir()
                       for p in root.glob(photo_id + ".*") if p.is_file()]
            if not matches:
                unmatched += 1
                item["screening_status"] = "no_matching_repository_local_image"
            else:
                checked += 1
                errors, selected = [], None
                for path in matches:
                    try:
                        raw = path.read_bytes()
                        metrics = analyze_image(raw)
                        selected = (path, raw, metrics)
                        break
                    except Exception as exc:
                        errors.append(f"{path.name}: {exc}")
                if selected is None:
                    failed += 1
                    item["screening_status"] = "local_image_decode_failed"
                    item["errors"] = errors
                else:
                    path, raw, metrics = selected
                    item["local_image_path"] = path.relative_to(city).as_posix()
                    item["image_sha256"] = hashlib.sha256(raw).hexdigest()
                    item["computer_vision"] = metrics
                    geom_ok = (float(photo.get("distance_m") or 999) <= 35 and
                               photo.get("azimuth_delta_deg") is not None and
                               float(photo["azimuth_delta_deg"]) <= 25)
                    item["geometry_screen_pass"] = geom_ok
                    item["screening_status"] = (
                        "candidate_for_human_identity_review"
                        if metrics["visual_quality_pass"] and geom_ok
                        else "rejected_or_low_confidence"
                    )
                    if item["screening_status"] == "candidate_for_human_identity_review":
                        passed += 1
            records.append(item)
            processed.add(key)
            if publish_checkpoints and len(records) % checkpoint_every == 0:
                atomic_report(target, make_report(False))
                subprocess.run(["bash", str(ROOT / "tools/ci/publish-checkpoint.sh"),
                                f"Checkpoint Manila visual screening {len(records)} items",
                                str(target.relative_to(ROOT))], cwd=ROOT, check=True)
                print(f"CV_CHECKPOINT city={city.name} items={len(records)}", flush=True)
            if delay > 0:
                time.sleep(delay)

    report = make_report(True)
    atomic_report(target, report)
    print(f"CV_REPORT_SAVED city={city.name} checked={checked} candidates={passed} failed={failed} unmatched={unmatched} complete=true network=0 downloads=0")
    return {"city": city.name, "checked": checked, "candidates": passed, "failed": failed}


def main():
    p=argparse.ArgumentParser()
    p.add_argument("--cities-root",type=Path,default=DEFAULT_ROOT)
    p.add_argument("--city")
    p.add_argument("--limit-per-building",type=int,default=3)
    p.add_argument("--delay",type=float,default=0.05)
    p.add_argument("--checkpoint-every",type=int,default=100)
    p.add_argument("--publish-checkpoints",action="store_true")
    a=p.parse_args()
    dirs=[a.cities_root/a.city] if a.city else sorted(x for x in a.cities_root.iterdir() if x.is_dir())
    totals={"checked":0,"candidates":0,"failed":0}
    errors=[]
    for city in dirs:
        try:
            result=process_city(city,a.limit_per_building,a.delay,a.checkpoint_every,a.publish_checkpoints)
            for key in totals: totals[key]+=result.get(key,0)
        except Exception as exc:
            errors.append(f"{city.name}: {exc}")
            print(f"CV_CITY_ERROR {city.name}: {exc}")
    print("CV_TOTAL "+json.dumps(totals,sort_keys=True))
    if errors:
        raise SystemExit("Visual verification failed: " + " | ".join(errors))
if __name__=="__main__": main()
