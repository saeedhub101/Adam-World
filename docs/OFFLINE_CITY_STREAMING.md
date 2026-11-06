# Spatial streaming in the native Android game

## Runtime flow

The Android game is a native Java/OpenGL ES 2.0 application. `TileLoader` runs in a background thread and supplies nearby OSM-style elements to `TileBuilder`; it does not use the old HTML/Three.js tile renderer.

The local modes are repository-hosted, network-fetched datasets with app-private caching:

| Mode | Manifest | Cell format | Cell size |
|---|---|---|---:|
| Amman Local | `data/offline-city-stream/amman/manifest.json` | JSONL compressed with GZIP | 1,000 m |
| Manila Local | `data/offline-city-stream/manila/manifest.json` | JSONL compressed with GZIP | 1,000 m |
| Tabarbour Local | `data/offline-world/tabarbour/stream/manifest.json` | JSON object containing `elements` | 250 m |

The game calculates the render-tile bounds from the selected origin. It selects the relevant source cell(s), downloads only those cells, and filters their features to the current render-tile bounds before mesh generation. A missing or unlisted Tabarbour cell is treated as empty local coverage; the loader does not silently substitute the merged building chunks.

## Generic locations

When no local city mode is active, the loader queries public Overpass endpoints for nearby OSM features. This means generic world exploration is network-dependent and is not a global offline basemap. Nominatim is used for place search.

## Memory and cleanup

- Loader requests are ordered by distance from the player.
- `LOAD_R=1` creates a 3×3 candidate neighborhood.
- The ready queue is held below two completed tiles to apply back-pressure.
- Manila and Amman GZIP records are read line by line; the whole city dataset is not parsed into one JSON object.
- Manila/Amman decoded-shard caches and Tabarbour decoded-cell caches are bounded.
- App-private cache files are pruned according to the limits in `TileLoader`.
- GPU meshes and decal textures are released when render tiles are evicted; CPU vertex buffers are released after GPU upload.

The exact limits are code constants and may change with later commits. Do not copy historical cache sizes into new documentation without checking the current code.

## Terrain

Terrain is enabled only for Amman and Tabarbour. The runtime requests zoom-14 Terrarium XYZ tiles and samples them relative to the starting elevation. Tabarbour uses the checked-in `data/offline-world/tabarbour/terrain/{z}/{x}/{y}.png` path first and AWS Terrarium as a fallback. Manila uses a flat ground plane. The terrain JSON manifest is an inventory; Java computes tile coordinates and fetches PNG paths directly.

## Current Tabarbour stream status

The active stream is now built from the canonical merged building source plus the required road and non-building context. `TileLoader.loadBundledCityRoot()` reads `stream/manifest.json`, maps the nearby render bounds to listed 250 m cells, downloads those files (including GZIP cells), parses each cell's `elements`, deduplicates OSM type/ID pairs, and sends the selected elements to `TileBuilder`. Unlisted files are ignored.

The current verified manifest contains **1,866** cells and **107,121** elements, including **59,266** building features. The validator parsed every cell and confirmed its recorded element count, then checked the repaired start-area cells: **785 buildings** and **299 road elements** in the configured neighborhood. `runtime_validation.status` is `passed`. See [validator run 42121546729](https://github.com/saeedhub101/Adam-World/actions/runs/42121546729).

This is a repository/manifest integrity check plus code-path inspection. It does not constitute an on-device visual/gameplay test.

## Build validation

The active workflow `.github/workflows/android-release.yml` builds and signs the APK. It checks that committed Amman and Manila shard inventories exist before packaging. It does not validate Tabarbour's merged-to-stream building completeness and it does not perform on-device gameplay testing.
