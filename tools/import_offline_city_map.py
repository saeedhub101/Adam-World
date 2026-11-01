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
ENDPOINTS = [
    "https://overpass-api.de/api/interpreter",
    "https://overpass.kumi.systems/api/interpreter",
    "https://overpass.private.coffee/api/interpreter",
]


def query_for(city):
    lat, lon = city["center"]["lat"], city["center"]["lon"]
    radius = int(city.get("discovery_radius_m", 650))
    around = f"(around:{radius},{lat},{lon})"
    return (
        "[out:json][timeout:55];("
        f'way["building"]{around};'
        f'way["highway"]{around};'
        f'way["leisure"~"park|garden|pitch|playground"]{around};'
        f'way["landuse"~"grass|forest|recreation_ground|village_green|meadow"]{around};'
        f'way["natural"~"water|wood|scrub"]{around};'
        f'way["waterway"="riverbank"]{around};'
        f'node["natural"="tree"]{around};'
        f'node["highway"="street_lamp"]{around};'
        ");out geom;"
    )


def fetch_city(city):
    body = urlencode({"data": query_for(city)}).encode("utf-8")
    errors = []
    for endpoint in ENDPOINTS:
        for attempt in range(2):
            try:
                request = Request(endpoint, data=body, headers={
                    "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
                    "User-Agent": "AdamWorldOfflineImporter/1.0 (repository data build)",
                    "Accept": "application/json",
                })
                with urlopen(request, timeout=75) as response:
                    payload = response.read()
                data = json.loads(payload.decode("utf-8"))
                elements = data.get("elements") or []
                if not elements:
                    raise RuntimeError("Overpass returned no elements")
                return data
            except (HTTPError, URLError, TimeoutError, OSError, ValueError, RuntimeError) as exc:
                errors.append(f"{endpoint} attempt {attempt + 1}: {exc}")
                time.sleep(2 + attempt * 3)
    raise RuntimeError("; ".join(errors[-6:]))


def git(*args, check=True):
    return subprocess.run(["git", *args], cwd=ROOT, check=check,
                          text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)


def commit_city(slug, element_count):
    rel = f"data/offline-world/{slug}"
    git("add", rel)
    staged = git("diff", "--cached", "--quiet", check=False)
    if staged.returncode == 0:
        print(f"{slug}: no repository changes to commit")
        return
    git("config", "user.name", "github-actions[bot]")
    git("config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    git("commit", "-m", f"Store offline OSM city data: {slug} ({element_count} elements)")
    pushed = git("push", "origin", "HEAD:main", check=False)
    if pushed.returncode != 0:
        raise RuntimeError("Could not push incremental city data to main: " + pushed.stdout)


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
                if existing_count > 0 and existing_count == int(metadata_existing.get("element_count", -1)):
                    print(f"{city['name']}: already stored ({existing_count} elements); skip")
                    succeeded += 1
                    continue
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
                "city": city["name"],
                "slug": slug,
                "source": "OpenStreetMap Overpass",
                "attribution": "© OpenStreetMap contributors",
                "license": "ODbL 1.0",
                "center": city["center"],
                "radius_m": city.get("discovery_radius_m", 650),
                "element_count": len(data["elements"]),
                "geometry_layers": ["buildings", "roads", "parks", "landcover", "water", "trees", "street_lamps"],
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
    if succeeded == 0:
        raise SystemExit("No city data could be imported; existing repository data was left intact")


if __name__ == "__main__":
    main()
