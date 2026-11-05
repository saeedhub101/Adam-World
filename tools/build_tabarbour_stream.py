#!/usr/bin/env python3
"""Build the Tabarbour runtime stream from the durable source cache without loading it all into RAM."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import math
import os
import sqlite3
import subprocess
import tempfile
import zlib
from pathlib import Path

from build_offline_city_tiles import (
    FULL_SOURCE,
    SOURCE,
    TILE_METERS,
    KY,
    atomic_json,
    normalize_osm_element,
    publish_git_paths,
    read_json,
    source_tree_fingerprint,
)

ROOT = Path(__file__).resolve().parents[1]
SLUG = "tabarbour"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--checkpoint-every", type=int, default=10)
    parser.add_argument("--publish-checkpoints", action="store_true")
    args = parser.parse_args()

    catalog = read_json(SOURCE / "catalog.json")
    city = next((item for item in catalog.get("cities", []) if item.get("slug") == SLUG), None)
    if city is None:
        raise SystemExit("Tabarbour is missing from the city catalog")

    city_source = FULL_SOURCE / SLUG
    city_out = city_source / "stream"
    cache_dir = city_out / ".build-cache"
    city_out.mkdir(parents=True, exist_ok=True)

    center = city["center"]
    lat0, lon0 = float(center["lat"]), float(center["lon"])
    kx = 111320.0 * math.cos(math.radians(lat0))
    tree_fingerprint = source_tree_fingerprint(city_source)
    print(f"SOURCE_TREE_FINGERPRINT {SLUG} {tree_fingerprint}", flush=True)

    cache_manifest_path = cache_dir / "manifest.json"
    if not cache_manifest_path.is_file():
        raise SystemExit("Durable Tabarbour source cache manifest is missing; refusing to re-import sources")
    cache_manifest = read_json(cache_manifest_path)
    if cache_manifest.get("complete") is not True:
        raise SystemExit("Tabarbour source cache is not marked complete")
    if cache_manifest.get("source_fingerprint") != tree_fingerprint:
        raise SystemExit("Tabarbour source cache fingerprint differs from repository sources; refusing stale cache")
    source_kind = str(cache_manifest.get("source_kind") or "")
    if not source_kind.startswith("tabarbour-canonical-merged-multisource-buildings"):
        raise SystemExit(f"Refusing incomplete Tabarbour source kind: {source_kind}")
    chunks = cache_manifest.get("chunks") or []
    if not chunks:
        raise SystemExit("Tabarbour source cache has no chunks")

    merged_manifest = read_json(city_source / "merged" / "manifest.json")
    expected_buildings = int(merged_manifest.get("merged_building_count", 0))
    if expected_buildings < 20000:
        raise SystemExit(f"Unexpected canonical merged building count: {expected_buildings}")

    db_path = Path(tempfile.gettempdir()) / f"adam-world-{SLUG}-{os.getpid()}.sqlite3"
    if db_path.exists():
        db_path.unlink()
    conn = sqlite3.connect(str(db_path))
    conn.execute("PRAGMA journal_mode=OFF")
    conn.execute("PRAGMA synchronous=OFF")
    conn.execute("PRAGMA temp_store=FILE")
    conn.execute("PRAGMA cache_size=-32768")
    conn.execute(
        """CREATE TABLE bucket_elements (
            seq INTEGER PRIMARY KEY,
            ix INTEGER NOT NULL,
            iz INTEGER NOT NULL,
            kind TEXT NOT NULL,
            element_id INTEGER NOT NULL,
            is_building INTEGER NOT NULL,
            payload BLOB NOT NULL,
            UNIQUE(ix, iz, kind, element_id)
        )"""
    )
    conn.execute("CREATE INDEX bucket_elements_cell ON bucket_elements(ix, iz, seq)")

    source_element_count = 0
    source_building_count = 0
    for chunk_index, chunk in enumerate(chunks, 1):
        chunk_path = cache_dir / str(chunk.get("file") or "")
        if not chunk_path.is_file() or chunk_path.stat().st_size >= 100_000_000:
            raise RuntimeError(f"Missing or oversized durable source chunk: {chunk_path}")
        with gzip.open(chunk_path, "rt", encoding="utf-8") as handle:
            chunk_elements = json.load(handle)
        if not isinstance(chunk_elements, list):
            raise RuntimeError(f"Source chunk is not a JSON array: {chunk_path}")

        before = conn.total_changes
        for raw in chunk_elements:
            if not isinstance(raw, dict):
                continue
            raw_tags = raw.get("tags") or {}
            if raw_tags.get("building") or raw_tags.get("building:part"):
                source_building_count += 1

            # Legacy source-cache label ways all used id=0. Give each label a
            # stable synthetic identity so labels in the same cell cannot mask
            # one another during spatial de-duplication.
            if raw_tags.get("adam:label_only") == "yes" and int(raw.get("id") or 0) == 0:
                seed = json.dumps(
                    [
                        raw_tags.get("name"),
                        raw_tags.get("name:ar"),
                        raw_tags.get("name:en"),
                        raw.get("geometry") or [],
                    ],
                    ensure_ascii=False,
                    sort_keys=True,
                    separators=(",", ":"),
                )
                raw = dict(raw)
                raw["id"] = 10_000_000_000 + int(hashlib.sha256(seed.encode("utf-8")).hexdigest()[:10], 16)

            normalized_items = normalize_osm_element(raw, lat0, lon0, kx, cover_bbox=True)
            for (ix, iz), element in normalized_items or []:
                tags = element.get("tags") or {}
                is_building = int(bool(tags.get("building") or tags.get("building:part")))
                encoded = json.dumps(
                    element, ensure_ascii=False, separators=(",", ":")
                ).encode("utf-8")
                payload = zlib.compress(encoded, level=1)
                conn.execute(
                    """INSERT OR IGNORE INTO bucket_elements
                       (ix, iz, kind, element_id, is_building, payload)
                       VALUES (?, ?, ?, ?, ?, ?)""",
                    (ix, iz, str(element["type"]), int(element["id"]), is_building, payload),
                )

        source_element_count += len(chunk_elements)
        conn.commit()
        added = conn.total_changes - before
        print(
            f"SOURCE_CHUNK_BUCKETED {chunk_index}/{len(chunks)}: "
            f"source_elements={source_element_count}/{cache_manifest.get('element_count')} "
            f"new_cell_memberships={added}",
            flush=True,
        )
        del chunk_elements

    expected_source_elements = int(cache_manifest.get("element_count", -1))
    if source_element_count != expected_source_elements:
        raise RuntimeError(
            f"Source cache element mismatch: expected={expected_source_elements}, actual={source_element_count}"
        )
    if source_building_count < expected_buildings:
        raise RuntimeError(
            f"Canonical source buildings incomplete: expected={expected_buildings}, actual={source_building_count}"
        )

    tile_rows = conn.execute(
        "SELECT ix, iz, COUNT(*), SUM(is_building) FROM bucket_elements "
        "GROUP BY ix, iz ORDER BY ix, iz"
    ).fetchall()
    if not tile_rows:
        raise RuntimeError("Tabarbour spatial bucketing produced no cells")
    ordered_buckets = [(int(row[0]), int(row[1])) for row in tile_rows]
    element_count = sum(int(row[2]) for row in tile_rows)
    building_count = sum(int(row[3] or 0) for row in tile_rows)
    if building_count < expected_buildings or element_count < building_count:
        raise RuntimeError(
            f"Tabarbour stream incomplete: buildings={building_count}, "
            f"expected={expected_buildings}, elements={element_count}"
        )

    start_buildings = 0
    start_roads = 0
    for ix in (-1, 0, 1):
        for iz in (-1, 0, 1):
            for (payload,) in conn.execute(
                "SELECT payload FROM bucket_elements WHERE ix=? AND iz=? ORDER BY seq", (ix, iz)
            ):
                item = json.loads(zlib.decompress(payload).decode("utf-8"))
                tags = item.get("tags") or {}
                if tags.get("building") or tags.get("building:part"):
                    start_buildings += 1
                if tags.get("highway"):
                    start_roads += 1
    if start_buildings <= 0 or start_roads <= 0:
        raise RuntimeError(
            f"Tabarbour start neighborhood is incomplete: buildings={start_buildings}, roads={start_roads}"
        )

    target_rows = conn.execute(
        "SELECT payload FROM bucket_elements WHERE ix=-9 AND iz=-22 ORDER BY seq"
    ).fetchall()
    target_has_label = False
    for (payload,) in target_rows:
        item = json.loads(zlib.decompress(payload).decode("utf-8"))
        tags = item.get("tags") or {}
        if tags.get("adam:label_only") == "yes" and tags.get("name:en") == "Shafa Badran Street":
            target_has_label = True
            break
    if not target_has_label:
        raise RuntimeError("Source cache does not produce the Shafa Badran Street label in tile (-9,-22)")

    existing_manifest_path = city_out / "manifest.json"
    existing_manifest = read_json(existing_manifest_path) if existing_manifest_path.is_file() else {}
    state_path = city_out / ".build-state.json"
    prior_state = read_json(state_path) if state_path.is_file() else {}
    prior_tiles = {
        (int(item["ix"]), int(item["iz"])): item
        for item in existing_manifest.get("tiles", [])
    }
    if prior_state.get("source_fingerprint") == tree_fingerprint:
        prior_tiles.update({
            (int(item["ix"]), int(item["iz"])): item
            for item in prior_state.get("tiles", [])
        })

    source_commit = subprocess.run(
        [
            "git", "log", "-1", "--format=%ct", "--",
            str(city_source.relative_to(ROOT)),
            f":(exclude){city_source.relative_to(ROOT)}/stream/**",
        ],
        cwd=ROOT,
        capture_output=True,
        text=True,
    )
    try:
        source_time = int(source_commit.stdout.strip())
    except (TypeError, ValueError):
        source_time = int(existing_manifest.get("source_tree_latest_commit", 0) or 0)

    tiles = []
    reused_tile_count = 0
    rebuilt_tile_count = 0
    digest = hashlib.sha256()
    checkpoint_every = max(1, args.checkpoint_every)

    def save_checkpoint() -> None:
        state = {
            "status": "building",
            "complete": False,
            "slug": SLUG,
            "source_fingerprint": tree_fingerprint,
            "completed_tiles": len(tiles),
            "expected_tiles": len(ordered_buckets),
            "tiles": tiles,
        }
        atomic_json(state_path, state)
        if args.publish_checkpoints:
            publish_git_paths(
                [*[city_out / item["file"] for item in tiles], state_path],
                f"Checkpoint {SLUG} stream: {len(tiles)} tiles",
            )
        print(
            f"PUBLISHED_BUILD_CHECKPOINT {SLUG}: {len(tiles)}/{len(ordered_buckets)} tiles",
            flush=True,
        )

    for tile_index, (ix, iz) in enumerate(ordered_buckets, 1):
        rows = conn.execute(
            "SELECT payload FROM bucket_elements WHERE ix=? AND iz=? ORDER BY seq", (ix, iz)
        ).fetchall()
        elements = [json.loads(zlib.decompress(row[0]).decode("utf-8")) for row in rows]
        old = prior_tiles.get((ix, iz))
        old_file = str(old.get("file") or "") if old else ""
        old_path = city_out / old_file if old_file else None
        reusable = False
        if old_path and old_path.is_file() and int(old.get("count", -1)) == len(elements):
            try:
                opener = gzip.open if old_path.suffix == ".gz" else open
                with opener(old_path, "rt", encoding="utf-8") as handle:
                    existing = json.load(handle)
                reusable = existing.get("elements", []) == elements
            except Exception:
                reusable = False

        filename = old_file if reusable else f"tile_{ix}_{iz}.json.gz"
        tile_path = city_out / filename
        if not reusable:
            tmp_tile = city_out / (filename + ".tmp")
            with gzip.open(tmp_tile, "wt", encoding="utf-8", compresslevel=9) as handle:
                json.dump({"elements": elements}, handle, ensure_ascii=False, separators=(",", ":"))
                handle.write("\n")
            os.replace(tmp_tile, tile_path)
            rebuilt_tile_count += 1
        else:
            reused_tile_count += 1

        if tile_path.stat().st_size >= 100_000_000:
            raise RuntimeError(f"Tabarbour tile exceeds GitHub's 100 MB limit: {tile_path}")
        with tile_path.open("rb") as handle:
            for block in iter(lambda: handle.read(1024 * 1024), b""):
                digest.update(block)
        digest.update(filename.encode("utf-8"))

        tiles.append(
            {
                "file": filename,
                "ix": ix,
                "iz": iz,
                "min_x": ix * TILE_METERS,
                "max_x": (ix + 1) * TILE_METERS,
                "min_z": iz * TILE_METERS,
                "max_z": (iz + 1) * TILE_METERS,
                "count": len(elements),
            }
        )
        if tile_index % checkpoint_every == 0:
            save_checkpoint()

    if tiles:
        save_checkpoint()

    active_files = {item["file"] for item in tiles}
    for old_cell in list(city_out.glob("tile_*.json")) + list(city_out.glob("tile_*.json.gz")):
        if old_cell.name not in active_files:
            old_cell.unlink()

    manifest = {
        "schema_version": 1,
        "slug": SLUG,
        "name": city["name"],
        "center": center,
        "coverage_radius_m": float(city.get("discovery_radius_m", 650)),
        "tile_size_m": TILE_METERS,
        "source": cache_manifest.get("source"),
        "source_kind": source_kind,
        "status": "complete",
        "complete": True,
        "element_count": element_count,
        "expected_tile_count": len(tiles),
        "tiles": tiles,
        "dataset_version": digest.hexdigest()[:24],
        "building_count": building_count,
        "source_fingerprint": tree_fingerprint,
        "source_tree_latest_commit": source_time,
    }
    atomic_json(existing_manifest_path, manifest)
    if state_path.exists():
        state_path.unlink()

    conn.close()
    db_path.unlink(missing_ok=True)
    print(
        json.dumps(
            {
                "status": "complete",
                "source_elements": source_element_count,
                "source_buildings": source_building_count,
                "merged_buildings_expected": expected_buildings,
                "buildings_published": building_count,
                "element_count": element_count,
                "tiles": len(tiles),
                "reused_tiles": reused_tile_count,
                "rebuilt_tiles": rebuilt_tile_count,
                "start_neighborhood_buildings": start_buildings,
                "start_neighborhood_roads": start_roads,
                "repaired_cell": {
                    "file": next(item["file"] for item in tiles if (item["ix"], item["iz"]) == (-9, -22)),
                    "elements": next(item["count"] for item in tiles if (item["ix"], item["iz"]) == (-9, -22)),
                    "label": "Shafa Badran Street",
                },
            },
            ensure_ascii=False,
            indent=2,
        ),
        flush=True,
    )


if __name__ == "__main__":
    main()
