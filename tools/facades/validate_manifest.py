#!/usr/bin/env python3
"""Validate explicitly verified facade-photo assignments for Adam World.

This validator deliberately rejects proximity-only matches. A record is eligible
only when a reviewer has confirmed building identity, facade identity, camera
direction, image suitability and licence, and the original image is stored locally.
"""
from __future__ import annotations
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "app/src/main/assets/facades/verified_facades.json"
ASSET_ROOT = (ROOT / "app/src/main/assets/facades").resolve()
REQUIRED_REVIEW = (
    "building_identity",
    "facade_identity",
    "camera_direction",
    "image_quality",
    "license",
)

def fail(message: str) -> None:
    raise SystemExit("FACADE MANIFEST ERROR: " + message)

def main() -> None:
    # A missing approval manifest means no facade has been human-approved yet.
    # Initialize an explicit empty manifest instead of failing the whole photo
    # audit; build_verified_glbs.py safely emits zero GLBs for an empty list.
    if not MANIFEST.exists():
        MANIFEST.parent.mkdir(parents=True, exist_ok=True)
        MANIFEST.write_text(
            json.dumps({"schema_version": 1, "buildings": []}, indent=2) + "\n",
            encoding="utf-8",
        )
        print("NO_APPROVED_FACADES: initialized empty manifest; no image assignments will be guessed.")
    try:
        data = json.loads(MANIFEST.read_text(encoding="utf-8"))
    except Exception as exc:
        fail(f"cannot read manifest: {exc}")
    if data.get("schema_version") != 1 or not isinstance(data.get("buildings"), list):
        fail("expected schema_version=1 and a buildings array")

    seen: set[tuple[str, int]] = set()
    for i, item in enumerate(data["buildings"]):
        prefix = f"buildings[{i}]"
        osm_id = str(item.get("osm_way_id", ""))
        if not re.fullmatch(r"[1-9][0-9]*", osm_id):
            fail(f"{prefix}: osm_way_id must be a positive OSM way ID")
        edge = item.get("facade_edge_index")
        if not isinstance(edge, int) or edge < 0:
            fail(f"{prefix}: facade_edge_index must be a non-negative integer")
        key = (osm_id, edge)
        if key in seen:
            fail(f"{prefix}: duplicate OSM way/facade edge assignment")
        seen.add(key)

        if item.get("source") != "panoramax":
            fail(f"{prefix}: facade texture source must be panoramax")
        if not item.get("photo_id"):
            fail(f"{prefix}: missing Panoramax photo_id")
        image = item.get("local_image", "")
        if not isinstance(image, str) or not image or image.startswith(("/", "\\\\")):
            fail(f"{prefix}: local_image must be a relative repository asset path")
        image_path = (ASSET_ROOT / image).resolve()
        if ASSET_ROOT not in image_path.parents or not image_path.is_file():
            fail(f"{prefix}: local image does not exist inside {ASSET_ROOT}")

        camera = item.get("camera", {})
        for field in ("capture_lat", "capture_lon", "azimuth_deg", "pitch_deg", "horizontal_fov_deg"):
            if not isinstance(camera.get(field), (int, float)):
                fail(f"{prefix}: camera.{field} is required and must be numeric")
        if not -90 <= camera["capture_lat"] <= 90 or not -180 <= camera["capture_lon"] <= 180:
            fail(f"{prefix}: camera capture coordinates are out of range")
        if not 0 <= camera["azimuth_deg"] < 360:
            fail(f"{prefix}: camera azimuth must be in [0, 360)")
        if not 1 <= camera["horizontal_fov_deg"] < 180:
            fail(f"{prefix}: horizontal FOV must be in (1, 180)")
        if not item.get("license", {}).get("identifier") or not item.get("license", {}).get("url"):
            fail(f"{prefix}: exact image licence identifier and URL are required")

        review = item.get("review", {})
        if review.get("status") != "human_verified":
            fail(f"{prefix}: status must be human_verified")
        if not review.get("reviewer") or not review.get("reviewed_at"):
            fail(f"{prefix}: reviewer and reviewed_at are required")
        checks = review.get("checks", {})
        missing = [name for name in REQUIRED_REVIEW if checks.get(name) is not True]
        if missing:
            fail(f"{prefix}: reviewer has not confirmed: {', '.join(missing)}")
        if item.get("match_method") != "visual_and_geometric":
            fail(f"{prefix}: proximity-only or unverified match method is forbidden")

    print(f"Facade manifest valid: {len(data['buildings'])} human-verified facade assignment(s).")
    if not data["buildings"]:
        print("No facade textures will be applied; no unverified image will be guessed onto a building.")

if __name__ == "__main__":
    main()
