#!/usr/bin/env python3
"""One-time, resumable import of complete OSM city layers into the repository.

This runs in GitHub Actions, not in the Android app. Successful cities are
committed and pushed to main immediately; failed cities do not stop later cities.
"""
from __future__ import annotations
import json
import subprocess
import time
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import Request, urlopen
from urllib.error import HTTPError, URLError

ROOT = Path(__file__).resolve().parents[1]
CATALOG = ROOT / "data" / "facades" / "cities" / "catalog.json"
OUTPUT = ROOT / "data" / "offline-world"
IMPORT_PROFILE_VERSION = 4
ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
]


def query_for(city, lat=None, lon=None, radius=None):
    lat = city["center"]["lat"] if lat is None else lat
    lon = city["center"]["lon"] if lon is None else lon
    radius = int(city.get("discovery_radius_m", 650) if radius is None else radius)
    around = f"(around:{radius},{lat},{lon})"
    return (
        "[out:json][timeout:55];("
        f'way["building"]{around};'
        f'way["highway"]{around};'
        f'way["leisure"~"park|garden|pitch|playground"]{around};'
        f'way["landuse"~"grass|forest|recreation_ground|village_green|meadow|farmland|orchard|vineyard|plant_nursery|reservoir|basin|salt_pond"]{around};'
        f'way["natural"~"water|wood|scrub|sand|beach|desert|bare_rock|scree|shingle|rock|heath|wetland|marsh|mud|grassland"]{around};'
        f'way["landcover"~"trees|scrub|grass|sand|bare_ground"]{around};'
        f'way["water"]{around};'
        f'way["waterway"="riverbank"]{around};'
        f'node["natural"="tree"]{around};'
        f'node["highway"="street_lamp"]{around};'
        f'node["highway"~"traffic_signals|crossing|stop|bus_stop"]{around};'
        f'node["railway"="level_crossing"]{around};'
        f'way["barrier"]{around};'
        f'way["man_made"~"bridge|pier"]{around};'
        ");out geom;"
    )


def fetch_city(city):
    """
    Split large city requests into eight overlapping circles. The union covers
    the requested discovery circle while keeping each Overpass response smaller.
    Merge by OSM type/id so overlaps never create duplicate elements.
    """
    import math

    lat0 = float(city["center"]["lat"])
    lon0 = float(city["center"]["lon"])
    target_radius = float(city.get("discovery_radius_m", 650))
    chunk_offset = min(1000.0, target_radius * 0.4)
    chunk_radius = max(700, int(target_radius * 0.72))
    merged = {}
    chunk_errors = []

    for chunk_index in range(8):
        angle = math.radians(chunk_index * 45.0)
        north_m = math.cos(angle) * chunk_offset
        east_m = math.sin(angle) * chunk_offset
        lat = lat0 + north_m / 111320.0
        lon = lon0 + east_m / (111320.0 * max(0.2, math.cos(math.radians(lat0))))
        body = urlencode({"data": query_for(city, lat, lon, chunk_radius)}).encode("utf-8")
        errors = []
        endpoints = ENDPOINTS[chunk_index % len(ENDPOINTS):] + ENDPOINTS[:chunk_index % len(ENDPOINTS)]
        data = None
        for endpoint in endpoints:
            try:
                request = Request(endpoint, data=body, headers={
                    "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
                    "User-Agent": "AdamWorldOfflineImporter/1.1 (repository data build)",
                    "Accept": "application/json",
                })
                with urlopen(request, timeout=75) as response:
                    payload = response.read()
                candidate = json.loads(payload.decode("utf-8"))
                if "elements" not in candidate:
                    raise RuntimeError("Overpass response has no elements array")
                data = candidate
                break
            except (HTTPError, URLError, TimeoutError, OSError, ValueError, RuntimeError) as exc:
                errors.append(f"{endpoint}: {exc}")
                time.sleep(2)
        if data is None:
            chunk_errors.append(f"chunk {chunk_index + 1}/8: " + "; ".join(errors))
            print(f"{city['name']}: chunk {chunk_index + 1}/8 failed; continuing retries")
            continue
        for element in data.get("elements") or []:
            key = (element.get("type"), element.get("id"))
            if key[0] is not None and key[1] is not None:
                merged[key] = element
        print(f"{city['name']}: chunk {chunk_index + 1}/8 saved in memory ({len(merged)} unique elements)")

    if chunk_errors:
        raise RuntimeError("One or more spatial chunks failed; refusing partial city replacement: " + " | ".join(chunk_errors))
    if not merged:
        raise RuntimeError("Overpass returned no elements across all spatial chunks")
    return {"version": 0.6, "generator": "Adam World resumable chunked Overpass importer",
            "elements": list(merged.values())}

def git(*args, check=True):
    return subprocess.run(["git", *args], cwd=ROOT, check=check,
                          text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)


def commit_city(slug, element_count):
    """Commit each completed city and safely rebase over concurrent main updates."""
    rel = f"data/offline-world/{slug}"
    git("add", rel)
    staged = git("diff", "--cached", "--quiet", check=False)
    if staged.returncode == 0:
        print(f"{slug}: no repository changes to commit")
        return
    git("config", "user.name", "github-actions[bot]")
    git("config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    git("commit", "-m", f"Store offline OSM city data: {slug} ({element_count} elements)")

    # Other workflows may also commit to main while this import is running.
    # Rebase our unpushed city checkpoints instead of abandoning every later city.
    last_error = ""
    for attempt in range(1, 6):
        fetched = git("fetch", "origin", "main", check=False)
        if fetched.returncode != 0:
            last_error = fetched.stdout
            time.sleep(attempt * 3)
            continue
        rebased = git("rebase", "origin/main", check=False)
        if rebased.returncode != 0:
            git("rebase", "--abort", check=False)
            last_error = rebased.stdout
            raise RuntimeError("Could not rebase city checkpoint onto main: " + last_error)
        pushed = git("push", "origin", "HEAD:main", check=False)
        if pushed.returncode == 0:
            print(f"{slug}: checkpoint pushed to main (attempt {attempt})")
            return
        last_error = pushed.stdout
        print(f"{slug}: main changed during push; retrying after fetch/rebase ({attempt}/5)")
        time.sleep(attempt * 3)
    raise RuntimeError("Could not push incremental city data after 5 fetch/rebase attempts: " + last_error)


def main():
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    OUTPUT.mkdir(parents=True, exist_ok=True)
    succeeded = 0
    failed = []
    for city in catalog.get("cities", []):
        slug = city["slug"]
        target_dir = OUTPUT / slug
        target = target_dir / "elements.json"
        metadata_path = target_dir / "metadata.json"
        if target.is_file() and metadata_path.is_file():
            try:
                existing = json.loads(target.read_text(encoding="utf-8"))
                metadata_existing = json.loads(metadata_path.read_text(encoding="utf-8"))
                existing_count = len(existing.get("elements") or [])
                same_center = metadata_existing.get("center") == city.get("center")
                same_radius = float(metadata_existing.get("radius_m", -1)) == float(city.get("discovery_radius_m", 650))
                if (existing_count > 0
                        and existing_count == int(metadata_existing.get("element_count", -1))
                        and int(metadata_existing.get("import_profile_version", 0)) == int(city.get("import_profile_version", IMPORT_PROFILE_VERSION))
                        and same_center and same_radius):
                    print(f"{city['name']}: already stored with import profile {IMPORT_PROFILE_VERSION} and matching area ({existing_count} elements); skip")
                    succeeded += 1
                    continue
                if existing_count > 0:
                    print(f"{city['name']}: refreshing existing data because profile, centre or radius changed")
            except Exception:
                print(f"{city['name']}: existing city data or metadata is invalid; re-importing")
        elif target.is_file():
            print(f"{city['name']}: legacy data has no validated metadata; re-importing")
        try:
            print(f"{city['name']}: fetching OSM layers for {city.get('discovery_radius_m', 650)} m window")
            data = fetch_city(city)
            target_dir.mkdir(parents=True, exist_ok=True)
            temp = target.with_suffix(".json.tmp")
            temp.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")),
                            encoding="utf-8")
            temp.replace(target)
            metadata = {
                "schema_version": 2,
                "import_profile_version": int(city.get("import_profile_version", IMPORT_PROFILE_VERSION)),
                "city": city["name"],
                "slug": slug,
                "source": "OpenStreetMap Overpass",
                "attribution": "© OpenStreetMap contributors",
                "license": "ODbL 1.0",
                "center": city["center"],
                "radius_m": city.get("discovery_radius_m", 650),
                "element_count": len(data["elements"]),
                "geometry_layers": ["buildings", "roads", "parks", "landcover", "water", "trees", "street_lamps", "traffic_signals", "crossings", "bus_stops", "level_crossings", "barriers", "bridges"],
                "stored_as": f"data/offline-world/{slug}/elements.json",
            }
            (target_dir / "metadata.json").write_text(
                json.dumps(metadata, ensure_ascii=False, indent=2) + "\n",
                encoding="utf-8",
            )
            commit_city(slug, len(data["elements"]))
            succeeded += 1
            print(f"{city['name']}: SAVED_AND_PUSHED {len(data['elements'])} elements")
        except Exception as exc:
            failed.append((slug, str(exc)))
            print(f"{city['name']}: FAILED; continuing to next city: {exc}")

    print(f"OFFLINE_OSM_IMPORT_RESULT success={succeeded} failed={len(failed)}")
    for slug, reason in failed:
        print(f"FAILED_CITY {slug}: {reason}")
    if failed:
        raise SystemExit(
            f"{len(failed)} city/cities remain incomplete. Completed city checkpoints "
            "were already pushed to main; re-run this workflow to resume without re-importing them."
        )
    if succeeded == 0:
        raise SystemExit("No city data could be imported; existing repository data was left intact")


if __name__ == "__main__":
    main()
