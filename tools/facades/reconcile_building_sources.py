#!/usr/bin/env python3
"""Reconcile building observations from multiple providers without false merges.

Only explicit stable OSM IDs are used as cross-source identity keys. Provider-local
IDs remain source-scoped. Geometry and tag disagreements are preserved as evidence
and reported; no source is silently selected as canonical.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from collections import defaultdict
from pathlib import Path
from typing import Any


def load_features(path: Path) -> list[dict[str, Any]]:
    obj = json.loads(path.read_text(encoding="utf-8"))
    if isinstance(obj, dict) and obj.get("type") == "FeatureCollection":
        return obj.get("features") or []
    if isinstance(obj, list):
        return obj
    raise ValueError(f"{path}: expected GeoJSON FeatureCollection or feature array")


def props_of(feature: dict[str, Any]) -> dict[str, Any]:
    value = feature.get("properties") or {}
    return value if isinstance(value, dict) else {}


def tags_of(feature: dict[str, Any]) -> dict[str, Any]:
    props = props_of(feature)
    tags = props.get("tags")
    return tags if isinstance(tags, dict) else props


def stable_osm_id(feature: dict[str, Any]) -> str | None:
    props = props_of(feature)
    raw = (props.get("osm_feature_id") or props.get("osm_id")
           or props.get("osm_way_id") or props.get("osm_relation_id"))
    if raw is None:
        tags = props.get("tags") if isinstance(props.get("tags"), dict) else {}
        raw = tags.get("osm_id")
    if raw is None:
        return None
    value = str(raw).strip()
    if not value:
        return None
    if value.isdigit():
        kind = str(props.get("osm_type") or props.get("type") or "way").lower()
        return f"{kind}/{value}"
    if "/" in value:
        return value
    return None


def stable_json(value: Any) -> str:
    return json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(",", ":"))


def geometry_hash(geometry: Any) -> str | None:
    if not isinstance(geometry, dict):
        return None
    return hashlib.sha256(stable_json(geometry).encode("utf-8")).hexdigest()


def source_local_id(feature: dict[str, Any], index: int) -> str:
    props = props_of(feature)
    value = feature.get("id") or props.get("id") or props.get("OBJECTID") or props.get("objectid")
    return str(value) if value is not None else f"row-{index}"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", action="append", required=True,
                        help="source_name=path.geojson; repeat for each provider")
    parser.add_argument("--output", type=Path, required=True,
                        help="Output JSON evidence register")
    args = parser.parse_args()

    entities: dict[str, dict[str, Any]] = {}
    dataset_counts: dict[str, int] = {}
    for spec in args.input:
        if "=" not in spec:
            parser.error(f"invalid --input {spec!r}; expected source_name=path")
        source, raw_path = spec.split("=", 1)
        source = source.strip()
        if not source:
            parser.error("source_name cannot be empty")
        path = Path(raw_path)
        features = load_features(path)
        dataset_counts[source] = len(features)
        for index, feature in enumerate(features):
            osm_id = stable_osm_id(feature)
            local_id = source_local_id(feature, index)
            # Never spatially merge records with no explicit shared OSM identity.
            key = f"osm:{osm_id}" if osm_id else f"source:{source}:{local_id}"
            entity = entities.setdefault(key, {
                "entity_id": key,
                "osm_id": osm_id,
                "observations": [],
            })
            geometry = feature.get("geometry")
            entity["observations"].append({
                "source": source,
                "source_feature_id": local_id,
                "geometry": geometry,
                "geometry_sha256": geometry_hash(geometry),
                "properties": props_of(feature),
            })

    records = []
    conflict_count = 0
    for key, entity in sorted(entities.items()):
        observations = entity["observations"]
        geometry_hashes = sorted({o["geometry_sha256"] for o in observations if o["geometry_sha256"]})
        tag_values: dict[str, set[str]] = defaultdict(set)
        for observation in observations:
            props = observation["properties"]
            tags = props.get("tags") if isinstance(props.get("tags"), dict) else props
            for name, value in tags.items():
                if value not in (None, "", [], {}):
                    tag_values[name].add(stable_json(value))
        tag_conflicts = {
            name: [json.loads(v) for v in sorted(values)]
            for name, values in sorted(tag_values.items()) if len(values) > 1
        }
        sources = sorted({o["source"] for o in observations})
        conflicts = bool(tag_conflicts or len(geometry_hashes) > 1)
        conflict_count += int(conflicts)
        records.append({
            "entity_id": key,
            "osm_id": entity["osm_id"],
            "sources": sources,
            "observation_count": len(observations),
            "geometry_variant_count": len(geometry_hashes),
            "tag_conflicts": tag_conflicts,
            "has_source_conflict": conflicts,
            "observations": observations,
        })

    output = {
        "schema_version": 1,
        "policy": "Only explicit stable OSM IDs merge observations. Nearby geometry is never identity proof. All source variants are retained; this report does not choose a winning footprint or texture.",
        "input_feature_counts": dataset_counts,
        "entity_count": len(records),
        "conflict_entity_count": conflict_count,
        "entities": records,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temp = args.output.with_suffix(args.output.suffix + ".tmp")
    temp.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    temp.replace(args.output)
    print(json.dumps({
        "status": "success",
        "input_feature_counts": dataset_counts,
        "entity_count": len(records),
        "conflict_entity_count": conflict_count,
        "output": str(args.output),
    }, ensure_ascii=False))


if __name__ == "__main__":
    main()
