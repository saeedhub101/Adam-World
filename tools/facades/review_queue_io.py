#!/usr/bin/env python3
"""Read and validate compact, sharded Panoramax review queues."""
from __future__ import annotations

import gzip
import hashlib
import json
from pathlib import Path


def load_review_queue(path: Path) -> dict:
    path = Path(path)
    manifest = json.loads(path.read_text(encoding="utf-8"))
    if manifest.get("storage") != "sharded-gzip":
        return manifest
    buildings = []
    for shard in manifest.get("shards", []):
        relative = Path(str(shard.get("file") or ""))
        if relative.is_absolute() or ".." in relative.parts:
            raise ValueError(f"Unsafe review-queue shard path: {relative}")
        shard_path = path.parent / relative
        raw = shard_path.read_bytes()
        expected_hash = shard.get("sha256")
        if expected_hash and hashlib.sha256(raw).hexdigest() != expected_hash:
            raise ValueError(f"Review-queue shard checksum mismatch: {shard_path}")
        with gzip.open(shard_path, "rt", encoding="utf-8") as stream:
            items = json.load(stream)
        if not isinstance(items, list):
            raise ValueError(f"Review-queue shard is not a JSON array: {shard_path}")
        if len(items) != int(shard.get("count", -1)):
            raise ValueError(f"Review-queue shard count mismatch: {shard_path}")
        buildings.extend(items)
    if len(buildings) != int(manifest.get("building_count", -1)):
        raise ValueError(
            f"Review-queue total mismatch: loaded={len(buildings)} "
            f"manifest={manifest.get('building_count')}"
        )
    result = dict(manifest)
    result["buildings"] = buildings
    return result
