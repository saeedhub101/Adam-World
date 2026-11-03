# Building source inventory and runtime usage

This document distinguishes source datasets stored in the repository from the datasets the native Android app currently reads.

## Amman Local

The active runtime path is `data/offline-city-stream/amman/manifest.json` and its compressed shards. The source build pipeline is `tools/build_amman_stream_shards.py`; the source inventory lives under `data/full-city-imports/amman/`. The manifest's source datasets include Overture buildings/building parts, transport, land cover, water, infrastructure, places and addresses. The Android loader consumes the generated shards, not the original full-city GeoJSON/GeoParquet files directly.

## Manila Local

The active runtime building source is `data/facades/cities/manila/layers/building-layer.geojson`, converted by `tools/build_manila_stream_shards.py` into `data/offline-city-stream/manila/shards/`. Roads and names are sourced from `transportation.geojson`; PBF and saved OSM/thematic context are supplementary inputs where available. The current builder does not merge the legacy `buildings.geojson`, `building-footprints.geojson` and `building-details.geojson` into this runtime path.

## Tabarbour Local

The merged source inventory is `data/offline-world/tabarbour/merged/`, with 29,159 features reported in its manifest. The active native loader reads only `data/offline-world/tabarbour/stream/manifest.json` and listed cells. The merged chunks are not currently the active building stream. See `TABARBOUR_BUILDING_ENRICHMENT.md`.

## Generic locations

When no local city mode is selected, `TileLoader` requests nearby OSM data through public Overpass endpoints. This is a runtime network fallback and should not be described as an offline source archive.

## Other source archives

- `data/facades/cities/` contains source inputs, vector-tile exports and facade evidence catalogues.
- `data/reference/adam-city/` preserves selected Adam-City reference data with provenance limits.
- `data/regions/philippines/manila/rizal-park/` is regional research and inventory context.
- `data/full-city-imports/` contains a separate 15-city source-import project. Its per-layer manifests are import records, not proof that those cities are playable in the current Android app.

Always verify a source path against the active Java loader and current workflow before describing it as a runtime input.
