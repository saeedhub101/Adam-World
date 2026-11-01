#!/usr/bin/env python3
"""Incrementally cache public Panoramax and OpenAerialMap STAC metadata per city.

Only metadata is fetched; original images are never bulk-downloaded. Progress is
checkpointed after every page so interrupted city runs resume at the saved next URL.
"""
from __future__ import annotations
import argparse
import json
import os
import subprocess
import tempfile
import time
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urljoin
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_ROOT = ROOT / "data" / "facades" / "cities"
SOURCES = {
    "panoramax": ("https://api.panoramax.xyz/api/search", "panoramax-items.geojson"),
    "aerial": ("https://api.imagery.hotosm.org/stac/search", "aerial-items.geojson"),
}


def atomic_write(path: Path, value: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    fd, tmp = tempfile.mkstemp(prefix=path.name + ".", dir=path.parent)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            f.write(payload)
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


def bbox_from_city(city: Path) -> tuple[float, float, float, float]:
    buildings_path = city / "buildings.geojson"
    if buildings_path.exists():
        fc = json.loads(buildings_path.read_text(encoding="utf-8"))
        coords = []
        def walk(value):
            if isinstance(value, list):
                if len(value) >= 2 and isinstance(value[0], (int, float)) and isinstance(value[1], (int, float)):
                    coords.append((float(value[0]), float(value[1])))
                else:
                    for child in value:
                        walk(child)
        for feature in fc.get("features", []):
            walk((feature.get("geometry") or {}).get("coordinates", []))
        if coords:
            return min(x for x, _ in coords), min(y for _, y in coords), max(x for x, _ in coords), max(y for _, y in coords)
    config_path = city / "city.json"
    if not config_path.exists():
        raise ValueError("neither buildings.geojson nor city.json is available")
    config = json.loads(config_path.read_text(encoding="utf-8"))
    center = config.get("center") or {}
    lat, lon = float(center["lat"]), float(center["lon"])
    radius = float(config.get("discovery_radius_m", 650))
    import math
    dlat = radius / 111320.0
    dlon = radius / (111320.0 * max(0.01, math.cos(math.radians(lat))))
    return lon - dlon, lat - dlat, lon + dlon, lat + dlat


def request_json(url: str, timeout: int) -> dict:
    req = Request(url, headers={"Accept": "application/geo+json, application/json", "User-Agent": "AdamWorld-FacadeEvidence/1.0"})
    last = None
    for attempt in range(3):
        try:
            with urlopen(req, timeout=timeout) as response:
                if response.status != 200:
                    raise RuntimeError(f"HTTP {response.status}")
                return json.loads(response.read().decode("utf-8"))
        except (HTTPError, URLError, TimeoutError, RuntimeError, json.JSONDecodeError) as exc:
            last = exc
            if isinstance(exc, HTTPError) and exc.code not in (429, 500, 502, 503, 504):
                break
            if attempt < 2:
                time.sleep(2 ** attempt)
    raise RuntimeError(f"GET failed for {url}: {last}")


def load_fc(path: Path) -> dict:
    if path.exists():
        obj = json.loads(path.read_text(encoding="utf-8"))
        if obj.get("type") == "FeatureCollection" and isinstance(obj.get("features"), list):
            return obj
    return {"type": "FeatureCollection", "features": []}


def fetch_source(city: Path, source: str, bbox: tuple[float, float, float, float], args) -> None:
    endpoint, output_name = SOURCES[source]
    state_path = city / f".{source}-fetch-state.json"
    output_path = city / output_name
    if args.refresh:
        state_path.unlink(missing_ok=True)
        output_path.unlink(missing_ok=True)
    state = json.loads(state_path.read_text(encoding="utf-8")) if state_path.exists() else {}
    if state.get("complete"):
        print(f"UNCHANGED {city.name}/{source}: catalogue scan already complete (use --refresh to rescan)")
        return
    if "next_url" not in state:
        query = urlencode({"bbox": ",".join(f"{v:.7f}" for v in bbox), "limit": args.page_size})
        state = {"next_url": endpoint + "?" + query, "seen_ids": [], "pages": 0, "complete": False}
    fc = load_fc(output_path)
    seen = set(state.get("seen_ids", []))
    seen.update(str(f.get("id")) for f in fc["features"] if f.get("id") is not None)
    for _ in range(args.max_pages):
        url = state.get("next_url")
        if not url:
            state["complete"] = True
            break
        page = request_json(url, args.timeout)
        features = page.get("features") or []
        added = 0
        for feature in features:
            fid = str(feature.get("id") or "")
            if fid and fid not in seen:
                fc["features"].append(feature)
                seen.add(fid)
                added += 1
        next_link = next((x.get("href") for x in (page.get("links") or []) if x.get("rel") == "next"), None)
        state.update({
            "seen_ids": sorted(seen),
            "pages": int(state.get("pages", 0)) + 1,
            "next_url": urljoin(url, next_link) if next_link else None,
            "last_page_added": added,
            "complete": not bool(next_link),
            "bbox": list(bbox),
            "source": endpoint,
        })
        # Persist source data and cursor after every page for interruption safety.
        atomic_write(output_path, fc)
        atomic_write(state_path, state)
        if args.git_checkpoint:
            paths = [str(output_path.relative_to(Path.cwd())), str(state_path.relative_to(Path.cwd()))]
            subprocess.run(["git", "add", *paths], check=True)
            changed = subprocess.run(["git", "status", "--porcelain"], check=True, capture_output=True, text=True).stdout.strip()
            if changed:
                subprocess.run(["git", "commit", "-m", f"Checkpoint {city.name} {source} metadata page {state['pages']}"], check=True)
                subprocess.run(["git", "pull", "--rebase", "origin", "main"], check=True)
                subprocess.run(["git", "push", "origin", "HEAD:main"], check=True)
        print(f"SAVED {city.name}/{output_name}: page={state['pages']} added={added} total={len(fc['features'])}")
        if state["complete"]:
            break
        time.sleep(args.delay)
    if state.get("next_url") and not state.get("complete"):
        print(f"PAUSED {city.name}/{source}: page cap reached; rerun to resume")


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--cities-root", type=Path, default=DEFAULT_ROOT)
    p.add_argument("--city")
    p.add_argument("--max-pages", type=int, default=100)
    p.add_argument("--page-size", type=int, default=500)
    p.add_argument("--timeout", type=int, default=45)
    p.add_argument("--delay", type=float, default=0.25)
    p.add_argument("--refresh", action="store_true")
    p.add_argument("--git-checkpoint", action="store_true", help="commit and push each saved page to main")
    args = p.parse_args()
    if args.max_pages < 1 or args.page_size < 1 or args.timeout < 1 or args.delay < 0:
        p.error("page, timeout and delay arguments must be positive (delay may be zero)")
    dirs = [args.cities_root / args.city] if args.city else sorted(x for x in args.cities_root.iterdir() if x.is_dir()) if args.cities_root.exists() else []
    if not dirs:
        print("No city folders found. Add a city folder with buildings.geojson first.")
        return
    for city in dirs:
        if not (city / "buildings.geojson").exists() and not (city / "city.json").exists():
            print(f"SKIP {city.name}: buildings.geojson and city.json are missing")
            continue
        try:
            bbox = bbox_from_city(city)
        except Exception as exc:
            print(f"SKIP {city.name}: cannot determine bbox: {exc}")
            continue
        for source in SOURCES:
            try:
                fetch_source(city, source, bbox, args)
            except Exception as exc:
                # A source failure must not prevent the other source or next city.
                print(f"ERROR {city.name}/{source}: {exc}")


if __name__ == "__main__":
    main()
