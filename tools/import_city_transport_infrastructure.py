#!/usr/bin/env python3
"""Incrementally enrich stored city OSM data with transit, water and road-condition features."""
from __future__ import annotations
import json, math, os, subprocess, tempfile, time
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "data/facades/cities/catalog.json"
OUTPUT = ROOT / "data/offline-world"
ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
]
TRANSPORT_PROFILE = 1

def query_for(lat: float, lon: float, radius: int) -> str:
    around = f"(around:{radius},{lat:.7f},{lon:.7f})"
    return (
        "[out:json][timeout:90];("
        # Re-fetch highway ways so surface, lanes, smoothness, bridge/tunnel,
        # width, traffic-calming and pothole tags are merged into existing roads.
        f'way["highway"]{around};'
        f'way["railway"~"rail|light_rail|subway|tram|monorail|narrow_gauge|funicular|preserved"]{around};'
        f'way["waterway"~"river|stream|canal|ditch|drain|weir|dam|lock_gate"]{around};'
        f'way["waterway"="riverbank"]{around};'
        f'way["bridge"]{around};'
        f'way["tunnel"]{around};'
        f'way["man_made"~"bridge|tunnel|pier|breakwater|embankment"]{around};'
        f'way["traffic_calming"]{around};'
        f'way["hazard"="pothole"]{around};'
        f'node["natural"="waterfall"]{around};'
        f'node["waterway"="waterfall"]{around};'
        f'node["hazard"="pothole"]{around};'
        f'node["traffic_calming"]{around};'
        f'node["railway"~"station|halt|tram_stop|subway_entrance|platform|stop"]{around};'
        f'node["public_transport"~"station|platform|stop_position"]{around};'
        f'node["highway"~"bus_stop|traffic_signals|crossing"]{around};'
        ");out geom;"
    )

def fetch_chunk(query: str, offset: int):
    body = urlencode({"data": query}).encode()
    errors = []
    for attempt in range(3):
        endpoint = ENDPOINTS[(offset + attempt) % len(ENDPOINTS)]
        try:
            req = Request(endpoint, data=body, headers={
                "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
                "Accept": "application/json",
                "User-Agent": "AdamWorld-TransportImporter/1.0 (public OSM data)",
            })
            with urlopen(req, timeout=150) as response:
                payload = json.loads(response.read().decode("utf-8"))
            if "elements" not in payload or payload.get("remark"):
                raise RuntimeError(str(payload.get("remark") or "missing elements"))
            return payload
        except (HTTPError, URLError, TimeoutError, OSError, ValueError, RuntimeError) as exc:
            errors.append(f"{endpoint}: {exc}")
            time.sleep(2 + attempt * 2)
    raise RuntimeError("; ".join(errors))

def fetch_city(city):
    lat0, lon0 = float(city["center"]["lat"]), float(city["center"]["lon"])
    target_radius = max(2500, int(city.get("discovery_radius_m", 650)))
    offset_m = target_radius * 0.40
    chunk_radius = int(target_radius * 0.78)
    merged = {}
    for idx in range(8):
        angle = math.radians(idx * 45)
        lat = lat0 + math.cos(angle) * offset_m / 111320.0
        lon = lon0 + math.sin(angle) * offset_m / (111320.0 * max(0.2, math.cos(math.radians(lat0))))
        payload = fetch_chunk(query_for(lat, lon, chunk_radius), idx)
        for item in payload.get("elements", []):
            key = (item.get("type"), item.get("id"))
            if key[0] and key[1] is not None:
                if key in merged:
                    old = merged[key]
                    old.setdefault("tags", {}).update(item.get("tags") or {})
                    if item.get("geometry"):
                        old["geometry"] = item["geometry"]
                    if "lat" in item: old["lat"], old["lon"] = item["lat"], item["lon"]
                else:
                    merged[key] = item
        print(f"{city['slug']}: transport chunk {idx+1}/8; {len(merged)} unique elements", flush=True)
        time.sleep(1)
    if not merged:
        raise RuntimeError("no infrastructure elements returned")
    return list(merged.values()), target_radius

def git(*args, check=True):
    return subprocess.run(["git", *args], cwd=ROOT, check=check, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

def checkpoint(slug):
    rel = f"data/offline-world/{slug}"
    git("add", rel)
    if git("diff", "--cached", "--quiet", check=False).returncode == 0:
        return
    git("config", "user.name", "github-actions[bot]")
    git("config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    git("commit", "-m", f"Add mapped transport and infrastructure data: {slug}")
    for attempt in range(1, 6):
        git("fetch", "origin", "main")
        rb = git("rebase", "origin/main", check=False)
        if rb.returncode:
            git("rebase", "--abort", check=False)
            raise RuntimeError(rb.stdout)
        push = git("push", "origin", "HEAD:main", check=False)
        if push.returncode == 0:
            print(f"{slug}: TRANSPORT_CHECKPOINT_PUSHED", flush=True)
            return
        time.sleep(attempt * 3)
    raise RuntimeError(f"{slug}: unable to push checkpoint")

def main():
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    succeeded, failed = 0, []
    for city in catalog.get("cities", []):
        slug = city["slug"]
        folder = OUTPUT / slug
        elements_path = folder / "elements.json"
        metadata_path = folder / "metadata.json"
        try:
            if not elements_path.is_file() or not metadata_path.is_file():
                raise RuntimeError("base offline OSM extract or metadata is missing")
            base = json.loads(elements_path.read_text(encoding="utf-8"))
            metadata = json.loads(metadata_path.read_text(encoding="utf-8"))
            if int(metadata.get("transport_profile_version", 0)) >= TRANSPORT_PROFILE and metadata.get("transport_complete") is True:
                print(f"{slug}: transport profile already complete; skip", flush=True)
                succeeded += 1
                continue
            incoming, radius = fetch_city(city)
            index = {(e.get("type"), e.get("id")): e for e in base.get("elements", [])}
            added = updated = 0
            for item in incoming:
                key = (item.get("type"), item.get("id"))
                if key in index:
                    target = index[key]
                    old_tags = target.setdefault("tags", {})
                    before = len(old_tags)
                    old_tags.update(item.get("tags") or {})
                    if len(old_tags) != before or item.get("geometry"):
                        updated += 1
                    if item.get("geometry"):
                        target["geometry"] = item["geometry"]
                    if "lat" in item: target["lat"], target["lon"] = item["lat"], item["lon"]
                else:
                    base.setdefault("elements", []).append(item)
                    index[key] = item
                    added += 1
            base["elements"].sort(key=lambda e: (e.get("type",""), int(e.get("id",0))))
            fd, tmp = tempfile.mkstemp(prefix="elements.", suffix=".tmp", dir=folder)
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(base, f, ensure_ascii=False, separators=(",", ":"))
                f.write("\n")
            os.replace(tmp, elements_path)
            metadata.update({
                "transport_profile_version": TRANSPORT_PROFILE,
                "transport_complete": True,
                "transport_radius_m": radius,
                "transport_elements_seen": len(incoming),
                "transport_elements_added": added,
                "transport_elements_updated": updated,
                "transport_layers": ["railways", "stations", "subway_entrances", "tram_stops", "waterway_lines", "waterfalls", "bridges", "tunnels", "road_surface", "smoothness", "lanes", "traffic_calming", "potholes"],
                "transport_source": "OpenStreetMap via public Overpass endpoints",
            })
            metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            checkpoint(slug)
            print(f"{slug}: SAVED added={added} updated={updated} total={len(base['elements'])}", flush=True)
            succeeded += 1
        except Exception as exc:
            failed.append((slug, str(exc)))
            print(f"{slug}: FAILED; continuing: {exc}", flush=True)
    print(f"TRANSPORT_IMPORT_RESULT success={succeeded} failed={len(failed)}", flush=True)
    for slug, reason in failed:
        print(f"TRANSPORT_PENDING {slug}: {reason}", flush=True)
    if failed:
        raise SystemExit("Some cities failed; successful city checkpoints were saved. Re-run to resume.")

if __name__ == "__main__":
    main()
