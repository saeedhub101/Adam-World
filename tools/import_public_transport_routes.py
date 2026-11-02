#!/usr/bin/env python3
"""Import public transport route relations into separate, resumable city files."""
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
PROFILE = 2
ROUTES = {"bus", "tram", "subway", "light_rail", "train", "ferry"}

def query_for(lat, lon, radius):
    around = f"(around:{radius},{lat:.7f},{lon:.7f})"
    return ('[out:json][timeout:90];('
            f'relation["route"~"^(bus|tram|subway|light_rail|train|ferry)$"]{around};'
            ');out geom;')

def fetch(query, offset):
    body = urlencode({"data": query}).encode()
    errors = []
    for attempt in range(3):
        endpoint = ENDPOINTS[(offset + attempt) % len(ENDPOINTS)]
        try:
            req = Request(endpoint, data=body, headers={
                "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
                "Accept": "application/json",
                "User-Agent": "AdamWorld-PublicTransportRoutes/1.0 (public OSM data)",
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

def import_city(city):
    lat0, lon0 = float(city["center"]["lat"]), float(city["center"]["lon"])
    radius = max(2500, int(os.environ.get("ADAM_AMMAN_TRANSPORT_RADIUS_M", city.get("discovery_radius_m", 650)))) if city["slug"] == "amman" else max(2500, int(city.get("discovery_radius_m", 650)))
    offset_m, chunk_radius = radius * 0.40, int(radius * 0.78)
    collected = {}
    for idx in range(8):
        angle = math.radians(idx * 45)
        lat = lat0 + math.cos(angle) * offset_m / 111320.0
        lon = lon0 + math.sin(angle) * offset_m / (111320.0 * max(0.2, math.cos(math.radians(lat0))))
        payload = fetch(query_for(lat, lon, chunk_radius), idx)
        for relation in payload.get("elements", []):
            if relation.get("type") != "relation":
                continue
            tags = relation.get("tags") or {}
            route = str(tags.get("route", "")).lower()
            if route not in ROUTES:
                continue
            for member_index, member in enumerate(relation.get("members") or []):
                if member.get("type") != "way" or len(member.get("geometry") or []) < 2:
                    continue
                coords = []
                for point in member["geometry"]:
                    try:
                        coords.append({"lat": float(point["lat"]), "lon": float(point["lon"])})
                    except (KeyError, TypeError, ValueError):
                        continue
                if len(coords) < 2:
                    continue
                relation_id = int(relation.get("id", 0))
                member_ref = int(member.get("ref", member_index + 1))
                key = (relation_id, member_ref, route)
                rail_kind = {"subway": "subway", "tram": "tram", "light_rail": "light_rail", "train": "rail"}.get(route)
                feature_tags = {
                    "public_transport_route": route,
                    "route_name": tags.get("name", ""),
                    "route_ref": tags.get("ref", ""),
                    "route_operator": tags.get("operator", ""),
                    "route_network": tags.get("network", ""),
                    "route_colour": tags.get("colour", ""),
                }
                if rail_kind:
                    feature_tags["railway"] = rail_kind
                collected[key] = {
                    "type": "way",
                    "id": -(relation_id * 10000 + member_index + 1),
                    "tags": feature_tags,
                    "geometry": coords,
                }
        print(f"{city['slug']}: route chunk {idx+1}/8; {len(collected)} mapped route segments", flush=True)
        time.sleep(1)
    if not collected:
        raise RuntimeError("no public transport route relation segments returned")
    return list(collected.values())

def git(*args, check=True):
    return subprocess.run(["git", *args], cwd=ROOT, check=check, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT)

def checkpoint(slug, path):
    git("add", str(path.relative_to(ROOT)))
    if git("diff", "--cached", "--quiet", check=False).returncode == 0:
        return
    git("config", "user.name", "github-actions[bot]")
    git("config", "user.email", "41898282+github-actions[bot]@users.noreply.github.com")
    git("commit", "-m", f"Save mapped public transport routes: {slug}")
    for attempt in range(1, 6):
        git("fetch", "origin", "main")
        rb = git("rebase", "origin/main", check=False)
        if rb.returncode:
            git("rebase", "--abort", check=False)
            raise RuntimeError(rb.stdout)
        push = git("push", "origin", "HEAD:main", check=False)
        if push.returncode == 0:
            print(f"{slug}: ROUTE_CHECKPOINT_PUSHED", flush=True)
            return
        time.sleep(attempt * 3)
    raise RuntimeError(f"{slug}: unable to push route checkpoint")

def main():
    catalog = json.loads(CATALOG.read_text(encoding="utf-8"))
    succeeded, failed = 0, []
    selected = {x.strip() for x in os.environ.get("ADAM_CITY_SLUGS", "").split(",") if x.strip()}
    for city in catalog.get("cities", []):
        slug = city["slug"]
        if selected and slug not in selected:
            continue
        folder = OUTPUT / slug
        path = folder / "transit-routes.json"
        try:
            if path.is_file():
                old = json.loads(path.read_text(encoding="utf-8"))
                if int(old.get("profile_version", 0)) >= PROFILE and old.get("complete") is True:
                    print(f"{slug}: route relations already complete; skip", flush=True)
                    succeeded += 1
                    continue
            folder.mkdir(parents=True, exist_ok=True)
            features = import_city(city)
            payload = {
                "schema_version": 1,
                "profile_version": PROFILE,
                "complete": True,
                "source": "OpenStreetMap public Overpass route relations",
                "route_segment_count": len(features),
                "elements": features,
            }
            fd, tmp = tempfile.mkstemp(prefix="transit-routes.", suffix=".tmp", dir=folder)
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"))
                handle.write("\n")
            os.replace(tmp, path)
            checkpoint(slug, path)
            print(f"{slug}: ROUTES_SAVED segments={len(features)}", flush=True)
            succeeded += 1
        except Exception as exc:
            failed.append((slug, str(exc)))
            print(f"{slug}: FAILED; continuing: {exc}", flush=True)
    print(f"PUBLIC_TRANSPORT_ROUTE_RESULT success={succeeded} failed={len(failed)}", flush=True)
    for slug, reason in failed:
        print(f"ROUTES_PENDING {slug}: {reason}", flush=True)
    if failed:
        raise SystemExit("Some city route imports failed; successful city checkpoints were saved. Re-run to resume.")

if __name__ == "__main__":
    main()
