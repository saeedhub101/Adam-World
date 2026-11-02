#!/usr/bin/env python3
"""Incrementally enrich stored city OSM data with transit, water and road-condition features."""
from __future__ import annotations
import json, math, os, shutil, subprocess, tempfile, time
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
TRANSPORT_PROFILE = 2

def query_for(lat: float, lon: float, radius: int) -> str:
    around = f"(around:{radius},{lat:.7f},{lon:.7f})"
    return (
        "[out:json][timeout:90];("
        # Re-fetch highway ways so surface, lanes, smoothness, bridge/tunnel,
        # width, traffic-calming and pothole tags are merged into existing roads.
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
        f'node["man_made"~"water_works|water_tower|wastewater_plant|pumping_station"]{around};'
        f'node["utility"~"water|sewer|sanitary|stormwater"]{around};'
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
            with urlopen(req, timeout=120) as response:
                payload = json.loads(response.read().decode("utf-8"))
            if "elements" not in payload or payload.get("remark"):
                raise RuntimeError(str(payload.get("remark") or "missing elements"))
            return payload
        except (HTTPError, URLError, TimeoutError, OSError, ValueError, RuntimeError) as exc:
            errors.append(f"{endpoint}: {exc}")
            time.sleep(2 + attempt * 2)
    raise RuntimeError("; ".join(errors))

def load_checkpoint(path):
    raw = path.read_text(encoding="utf-8")
    try:
        return json.loads(raw)
    except json.JSONDecodeError:
        # Recover checkpoints written by the earlier workflow, which appended
        # the two literal characters backslash+n instead of a newline.
        if raw.endswith("\\n"):
            return json.loads(raw[:-2])
        raise

def fetch_city(city, folder):
    lat0, lon0 = float(city["center"]["lat"]), float(city["center"]["lon"])
    target_radius = max(2500, int(os.environ.get("ADAM_AMMAN_TRANSPORT_RADIUS_M", city.get("discovery_radius_m", 650)))) if city["slug"] == "amman" else max(2500, int(city.get("discovery_radius_m", 650)))
    # Twelve smaller overlapping circles cover the requested radius while keeping
    # each Overpass request substantially lighter than the former eight large ones.
    offset_m = target_radius * 0.50
    chunk_radius = int(target_radius * 0.60)
    progress = folder / "transport-progress"
    progress.mkdir(parents=True, exist_ok=True)
    merged, failed, completed = {}, [], []
    for idx in range(8):
        chunk_path = progress / f"chunk-{idx:02d}.json"
        if chunk_path.is_file():
            payload = load_checkpoint(chunk_path)
            print(f"{city['slug']}: resume transport chunk {idx+1}/8 from checkpoint", flush=True)
        else:
            angle = math.radians(idx * 45)
            lat = lat0 + math.cos(angle) * offset_m / 111320.0
            lon = lon0 + math.sin(angle) * offset_m / (111320.0 * max(0.2, math.cos(math.radians(lat0))))
            try:
                payload = fetch_chunk(query_for(lat, lon, chunk_radius), idx)
                fd, tmp = tempfile.mkstemp(prefix="transport-chunk.", suffix=".tmp", dir=progress)
                with os.fdopen(fd, "w", encoding="utf-8") as handle:
                    json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"))
                    handle.write("\n")
                os.replace(tmp, chunk_path)
                checkpoint(city["slug"])
                print(f"{city['slug']}: TRANSPORT_CHUNK_CHECKPOINT {idx+1}/8 saved={len(payload.get('elements', []))}", flush=True)
            except Exception as exc:
                failed.append((idx, str(exc)))
                print(f"{city['slug']}: transport chunk {idx+1}/8 pending; continuing: {exc}", flush=True)
                continue
        completed.append(idx)
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
    return list(merged.values()), target_radius, failed, completed

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
    succeeded, pending = 0, []
    selected = {x.strip() for x in os.environ.get("ADAM_CITY_SLUGS", "").split(",") if x.strip()}
    for city in catalog.get("cities", []):
        slug = city["slug"]
        if selected and slug not in selected:
            continue
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
            incoming, radius, failed, completed = fetch_city(city, folder)
            complete = len(completed) == 8
            if complete:
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
                with os.fdopen(fd, "w", encoding="utf-8") as handle:
                    json.dump(base, handle, ensure_ascii=False, separators=(",", ":"))
                    handle.write("\n")
                os.replace(tmp, elements_path)
                shutil.rmtree(folder / "transport-progress", ignore_errors=True)
            else:
                added = updated = 0
            metadata.update({
                "transport_profile_version": TRANSPORT_PROFILE if complete else max(0, TRANSPORT_PROFILE - 1),
                "transport_complete": complete,
                "transport_radius_m": radius,
                "transport_elements_seen": len(incoming),
                "transport_elements_added": added,
                "transport_elements_updated": updated,
                "transport_completed_chunks": completed,
                "transport_pending_chunks": [idx for idx in range(8) if idx not in completed],
                "transport_layers": ["railways", "stations", "subway_entrances", "tram_stops", "bus_stops", "waterway_lines", "waterfalls", "water_pipelines", "sewer_networks", "storm_drains", "water_works", "wastewater_plants", "pumping_stations", "bridges", "tunnels", "road_surface", "smoothness", "lanes", "traffic_calming", "potholes"],
                "transport_source": "OpenStreetMap via public Overpass endpoints",
            })
            metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            checkpoint(slug)
            print(f"{slug}: {'SAVED' if complete else 'PARTIAL_CHECKPOINT'} added={added} updated={updated} seen={len(incoming)} completed_chunks={len(completed)}/12 pending_chunks={len(failed)}", flush=True)
            succeeded += 1
            if failed:
                pending.append((slug, failed))
        except Exception as exc:
            pending.append((slug, [(-1, str(exc))]))
            print(f"{slug}: FAILED; continuing: {exc}", flush=True)
    print(f"TRANSPORT_IMPORT_RESULT success={succeeded} pending={len(pending)}", flush=True)
    for slug, reasons in pending:
        for idx, reason in reasons:
            print(f"TRANSPORT_PENDING {slug} chunk={idx+1}: {reason}", flush=True)
    # Partial Overpass outages are checkpoints, not workflow-fatal errors.
    # A later dispatch resumes only chunks that do not yet have saved files.


if __name__ == "__main__":
    main()
