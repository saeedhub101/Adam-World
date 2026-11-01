#!/usr/bin/env python3
"""Download verified, redistributable CC0 GLB assets into the offline game.

Each category is committed to main as soon as it is complete. Existing valid GLBs
are retained, so an interrupted run resumes without downloading them again.
"""
from __future__ import annotations

import hashlib
import json
import os
import subprocess
import tempfile
import time
from pathlib import Path
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
ASSET_ROOT = ROOT / "app/src/main/assets/models"

ASSETS = {
    "people": [
        {
            "id": "adult_woman", "file": "people/adult-woman.glb",
            "url": "https://files.innerscene.com/library/amara-3d-person-standing-office-2a16c294/bc0b8cf6d7fb55755f82/amara-woman.glb",
            "page": "https://www.innerscene.com/tools/library/3d-parts/amara-3d-person-standing-office-2a16c294",
            "license": "CC0-1.0", "pose": "standing", "animated": False,
            "height_m": 1.68, "description": "Adult woman, textured clothing and hair; static pose."
        },
        {
            "id": "adult_man", "file": "people/adult-man.glb",
            "url": "https://files.innerscene.com/library/folded-arms-3d-person-standing-b4b904f5/2d8bb9096ec29e8ba334/standing-folded-arms.glb",
            "page": "https://www.innerscene.com/tools/library/3d-parts/folded-arms-3d-person-standing-b4b904f5",
            "license": "CC0-1.0", "pose": "standing", "animated": False,
            "height_m": 1.85, "description": "Adult man in clothing; static folded-arms pose."
        },
        {
            "id": "child_boy", "file": "people/child-boy.glb",
            "url": "https://files.innerscene.com/library/leo-3d-person-child-standing-88a69d44/a31eb1ba88c4f417763a/leo-boy.glb",
            "page": "https://www.innerscene.com/tools/library/3d-parts/leo-3d-person-child-standing-88a69d44",
            "license": "CC0-1.0", "pose": "standing", "animated": False,
            "height_m": 1.11, "description": "Boy, approximately eight years old; static pose."
        },
        {
            "id": "child_girl", "file": "people/child-girl.glb",
            "url": "https://files.innerscene.com/library/maya-3d-person-child-standing-bace98fd/993e9680f3471c6675a0/maya-girl.glb",
            "page": "https://www.innerscene.com/tools/library/3d-parts/maya-3d-person-child-standing-bace98fd",
            "license": "CC0-1.0", "pose": "standing", "animated": False,
            "height_m": 1.16, "description": "Girl, approximately ten years old; static pose."
        },
    ],
        {
            "id": "adult_man_walk", "file": "people/adult-man-walk.glb",
            "url": "https://files.innerscene.com/library/locomotion-collection-jordan-3d-person-animated-911f1584/a153a7018a159efb583a/locomotion-collection-jordan-3d-person-animated.glb",
            "page": "https://www.innerscene.com/tools/library/3d-parts/locomotion-collection-jordan-3d-person-animated-911f1584",
            "license": "CC0-1.0", "pose": "rigged", "animated": True,
            "height_m": 1.79, "description": "Adult man with rig and seven looping locomotion clips."
        },
        {
            "id": "adult_woman_walk", "file": "people/adult-woman-walk.glb",
            "url": "https://files.innerscene.com/library/locomotion-collection-maya-3d-person-animated-1499b2fd/565cfb8a45b8a67a79c4/locomotion-collection-maya-3d-person-animated.glb",
            "page": "https://www.innerscene.com/tools/library/3d-parts/locomotion-collection-maya-3d-person-animated-1499b2fd",
            "license": "CC0-1.0", "pose": "rigged", "animated": True,
            "height_m": 1.70, "description": "Adult woman with rig and seven looping locomotion clips."
        },
    "animals": [
        {
            "id": "dog", "file": "animals/dog.glb",
            "url": "https://files.innerscene.com/library/standing-dog-bf156da2/793263f362ea27b0faa6/dog-original.glb",
            "page": "https://www.innerscene.com/tools/library/3d-parts/standing-dog-bf156da2",
            "license": "CC0-1.0", "pose": "standing", "animated": False,
            "height_m": 0.92, "description": "Medium companion dog; static mesh."
        },
    ],
    "vehicles": [
        {
            "id": "sedan", "file": "vehicles/sedan.glb",
            "url": "https://files.innerscene.com/library/sedan-car-42109739/75c64c1b3c8b03734314/sedan-original.glb",
            "page": "https://www.innerscene.com/tools/library/3d-parts/sedan-car-42109739",
            "license": "CC0-1.0", "length_m": 4.7, "animated": False,
            "description": "Unbranded passenger sedan with glazing, lamps, wheels and trim."
        },
        {
            "id": "pickup", "file": "vehicles/pickup.glb",
            "url": "https://files.innerscene.com/library/pickup-truck-da45aa3f/aef5bc0897dcaebfa6a2/pickup-original.glb",
            "page": "https://www.innerscene.com/tools/library/3d-parts/pickup-truck-da45aa3f",
            "license": "CC0-1.0", "length_m": 5.5, "animated": False,
            "description": "Unbranded pickup with coachwork, glazing, lamps, wheels and trim."
        },
        {
            "id": "bus", "file": "vehicles/city-bus.glb",
            "url": "https://files.innerscene.com/library/low-floor-city-bus-9e7ce899/9c3a0074b0e553ba181a/city-bus-original.glb",
            "page": "https://www.innerscene.com/tools/library/3d-parts/low-floor-city-bus-9e7ce899",
            "license": "CC0-1.0", "length_m": 12.0, "animated": False,
            "description": "Low-floor urban bus exterior with doors, glazing and wheel assemblies."
        },
        {
            "id": "metro_car", "file": "vehicles/metro-car.glb",
            "url": "https://files.innerscene.com/library/metro-car-22a0c19c/cd214f951bdf5d567ca6/metro-car.glb",
            "page": "https://www.innerscene.com/tools/library/3d-parts/metro-car-22a0c19c",
            "license": "CC0-1.0", "length_m": 19.0, "animated": False,
            "description": "Metro passenger rail car; requires mapped railway route and track."
        },
        {
            "id": "motorcycle", "file": "vehicles/motorcycle.glb",
            "url": "https://files.innerscene.com/library/race-replica-superbike-fully-faired-badbd83b/9ce443dc7d0c14f443e9/race-replica-superbike-fully-faired.glb",
            "page": "https://www.innerscene.com/tools/library/3d-parts/race-replica-superbike-fully-faired-badbd83b",
            "license": "CC0-1.0", "length_m": 2.05, "animated": False,
            "description": "Detailed sport motorcycle with fairing, wheels, brakes and chain."
        },
        {
            "id": "water_taxi", "file": "vehicles/water-taxi.glb",
            "url": "https://files.innerscene.com/library/water-taxi-boat-925a8345/cf96e45adc3742d9db2b/water-taxi-boat.glb",
            "page": "https://www.innerscene.com/tools/library/3d-parts/water-taxi-boat-925a8345",
            "license": "CC0-1.0", "length_m": 9.9, "animated": False,
            "description": "Water taxi boat; requires mapped navigable water and dock."
        },
    ],
}

CITY_PROFILES = {
    "amman": {"road": ["sedan", "sedan", "pickup", "motorcycle"], "transit": ["bus"]},
    "manila": {"road": ["sedan", "pickup", "motorcycle"], "transit": ["bus", "metro_car"], "pending_local": ["jeepney", "philippine_tricycle"]},
    "bangkok": {"road": ["sedan", "pickup", "motorcycle"], "transit": ["bus", "metro_car"], "pending_local": ["tuk_tuk"]},
    "new-york": {"road": ["sedan", "pickup", "motorcycle"], "transit": ["bus", "metro_car"]},
    "paris": {"road": ["sedan", "motorcycle"], "transit": ["bus", "metro_car"]},
    "dubai": {"road": ["sedan", "pickup", "motorcycle"], "transit": ["bus", "metro_car"]},
    "tokyo": {"road": ["sedan", "motorcycle"], "transit": ["bus", "metro_car"]},
    "london": {"road": ["sedan", "motorcycle"], "transit": ["bus", "metro_car"]},
    "riyadh": {"road": ["sedan", "pickup", "motorcycle"], "transit": ["bus", "metro_car"]},
    "cairo": {"road": ["sedan", "pickup", "motorcycle"], "transit": ["bus", "metro_car"]},
}

def read_glb_json(raw: bytes) -> dict:
    if len(raw) < 20 or raw[:4] != b"glTF":
        raise RuntimeError("invalid GLB header")
    offset = 12
    while offset + 8 <= len(raw):
        chunk_len = int.from_bytes(raw[offset:offset+4], "little")
        chunk_type = int.from_bytes(raw[offset+4:offset+8], "little")
        offset += 8
        chunk = raw[offset:offset+chunk_len]
        if len(chunk) != chunk_len:
            raise RuntimeError("truncated GLB chunk")
        if chunk_type == 0x4E4F534A:
            return json.loads(chunk.decode("utf-8").rstrip("\x00 \t\r\n"))
        offset += chunk_len
    raise RuntimeError("GLB JSON chunk missing")

def valid_glb(path: Path, animated: bool = False) -> bool:
    try:
        raw = path.read_bytes()
        if len(raw) < 20 or raw[:4] != b"glTF":
            return False
        version = int.from_bytes(raw[4:8], "little")
        declared = int.from_bytes(raw[8:12], "little")
        if version != 2 or declared != len(raw) or declared <= 1000:
            return False
        if animated:
            doc = read_glb_json(raw)
            return bool(doc.get("animations")) and bool(doc.get("skins"))
        return True
    except (OSError, ValueError, RuntimeError, UnicodeDecodeError):
        return False

def download(item: dict) -> dict:
    path = ASSET_ROOT / item["file"]
    path.parent.mkdir(parents=True, exist_ok=True)
    if valid_glb(path, bool(item.get("animated"))):
        raw = path.read_bytes()
        result = {"status": "kept", "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}
        if item.get("animated"):
            doc = read_glb_json(raw)
            result.update({"animation_count": len(doc.get("animations", [])), "skin_count": len(doc.get("skins", []))})
        return result
    if "CHANGE_ME" in item["url"]:
        raise RuntimeError("download URL has not been verified")
    req = Request(item["url"], headers={"User-Agent": "AdamWorld-FreeAssetImporter/1.0", "Accept": "model/gltf-binary,*/*"})
    with urlopen(req, timeout=240) as response:
        raw = response.read()
    if len(raw) < 1024 or raw[:4] != b"glTF":
        raise RuntimeError(f"not a GLB file ({len(raw)} bytes)")
    declared = int.from_bytes(raw[8:12], "little")
    if declared != len(raw) or int.from_bytes(raw[4:8], "little") != 2:
        raise RuntimeError("invalid GLB v2 header/length")
    animation_info = {}
    if item.get("animated"):
        doc = read_glb_json(raw)
        if not doc.get("animations") or not doc.get("skins"):
            raise RuntimeError("animated character GLB must contain both skins and animation clips")
        animation_info = {"animation_count": len(doc.get("animations", [])), "skin_count": len(doc.get("skins", []))}
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_bytes(raw)
    tmp.replace(path)
    return {"status": "downloaded", "bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest(), **animation_info}

def git(*args: str, check: bool = True):
    return subprocess.run(["git", *args], cwd=ROOT, check=check, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

def checkpoint(label: str, paths: list[str]) -> None:
    git("add", *paths)
    if git("diff", "--cached", "--quiet", check=False).returncode == 0:
        return
    git("config", "user.name", "github-actions[bot]")
    git("config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    git("commit", "-m", f"Import free CC0 3D {label} assets")
    for attempt in range(1, 6):
        git("fetch", "origin", "main")
        rebased = git("rebase", "origin/main", check=False)
        if rebased.returncode:
            git("rebase", "--abort", check=False)
            raise RuntimeError(rebased.stdout)
        pushed = git("push", "origin", "HEAD:main", check=False)
        if pushed.returncode == 0:
            print(f"CHECKPOINT_PUSHED {label}", flush=True)
            return
        print(f"PUSH_RETRY {label} {attempt}: {pushed.stdout}", flush=True)
        time.sleep(attempt * 3)
    raise RuntimeError(f"could not push {label} checkpoint")

def main() -> None:
    manifest_path = ASSET_ROOT / "manifest.json"
    manifest = {"schema_version": 1, "license_policy": "Only individually verified CC0-1.0 assets are imported automatically.",
                "models": [], "city_profiles": CITY_PROFILES,
                "pending": [
                    {"id": "jeepney", "city": "manila", "status": "not_imported",
                     "reason": "A free Sketchfab CC-BY model was found, but the downloadable binary and its redistribution package were not independently verified."},
                    {"id": "philippine_tricycle", "city": "manila", "status": "not_imported",
                     "reason": "Search results found paid/editorial or game-workshop assets; no verified free redistributable GLB was accepted."},
                    {"id": "children_walking_animations", "status": "not_imported",
                     "reason": "Verified animated adult man and woman are imported; no suitable licensed animated child models have been verified."},
                    {"id": "old_jordanian_cars", "city": "amman", "status": "not_imported",
                     "reason": "No verified free, redistributable, sufficiently detailed old Jordanian vehicle model was accepted yet."}
                ]}
    failures = []
    for category, items in ASSETS.items():
        category_files = []
        for item in items:
            try:
                result = download(item)
                entry = {k: v for k, v in item.items() if k != "url"}
                entry.update(result)
                manifest["models"] = [m for m in manifest["models"] if m["id"] != item["id"]]
                manifest["models"].append(entry)
                category_files.append(str((ASSET_ROOT / item["file"]).relative_to(ROOT)))
                print(f"ASSET_{result['status'].upper()} {item['id']} bytes={result['bytes']}", flush=True)
            except Exception as exc:
                failures.append((item["id"], str(exc)))
                print(f"ASSET_FAILED {item['id']}: {exc}", flush=True)
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        rel_manifest = str(manifest_path.relative_to(ROOT))
        if category_files:
            checkpoint(category, category_files + [rel_manifest])
    if not manifest_path.exists():
        manifest_path.parent.mkdir(parents=True, exist_ok=True)
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        checkpoint("catalog", [str(manifest_path.relative_to(ROOT))])
    print(f"ASSET_IMPORT_RESULT models={len(manifest['models'])} failed={len(failures)}", flush=True)
    for key, reason in failures:
        print(f"ASSET_PENDING {key}: {reason}", flush=True)
    if failures:
        raise SystemExit("Some assets remain missing; completed category checkpoints were saved. Re-run to resume.")

if __name__ == "__main__":
    main()
