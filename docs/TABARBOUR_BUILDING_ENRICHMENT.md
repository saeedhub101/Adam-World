# Tabarbour — building sources, merge and active runtime

## Canonical merged source

Tabarbour source material is kept under `data/offline-world/tabarbour/`. The merged building checkpoint is:

- Manifest: `merged/manifest.json`
- Output: 12 chunks named `merged/buildings_000.geojson` through `buildings_011.geojson`
- Reported merged building count: **29,159**
- Input source counts: OSM 7,223; OpenFreeMap 124; Overture 28,333; OpenBuildingMap 28,753.
- Attached addresses: 858; attached entrances: 3; attached building parts: 0; Panoramax photos: 0.

The merge policy prefers explicit OSM identity and uses conservative spatial matching. It preserves source provenance and does not treat a nearby Panoramax candidate as a verified facade photo.

## Active Android stream — important distinction

The native Android loader does **not** read `merged/manifest.json` or the 12 merged GeoJSON chunks. It reads only:

`data/offline-world/tabarbour/stream/manifest.json`

and the cell files named by that manifest. The current active stream manifest reports:

- 1,108 listed cells
- 250 m cell size
- 2,229 elements across the listed cells
- `source_kind: osm-nonroad-context-only+transit-routes`
- `status: complete` and `complete: true` for that listed stream dataset

This completion flag does not mean the 29,159 merged buildings were published into the stream. The merged source and active runtime stream are currently disconnected. The Java loader checks the manifest's `tiles` array; a `tile_*.json` file that exists in the directory but is not listed is ignored. The stream must be regenerated from the merged building source and its manifest updated before claiming those buildings are playable.

## Height and property policy

For building features that do reach `TileBuilder`, the renderer prefers explicit metric `height`, then `building:height`/ `height:meters`, then mapped floor counts, then `adam:render_height_m`, then a conservative class fallback. `render_height` is not treated as a surveyed measurement. Missing heights remain estimates and should be labelled approximate.

Overture/OpenBuildingMap estimated heights and floor counts must retain source provenance and quality flags. Do not overwrite explicit OSM measurements with model estimates. Keep house numbers separate from internal feature IDs.

## Terrain

The terrain source is stored in `terrain/`. Its manifest records 25 zoom-14 Terrarium tiles around 31.997579, 35.940358. The Android sampler requests `terrain/{z}/{x}/{y}.png` directly from the repository first, then falls back to AWS Terrarium if the repository tile is unavailable. The game samples relative elevation above the Tabarbour starting point.

## Remaining work

1. Build the active 250 m stream from the canonical merged building chunks plus the required roads and non-building context.
2. Write each finished cell into the repository during the build and support resumable checkpoints.
3. Regenerate `stream/manifest.json` with the exact active tile list, element counts, source paths and dataset version.
4. Verify the central Tabarbour cells are listed and contain building/road features before release.
5. Test the published stream through the actual Android loader; source merge success alone is not runtime success.
