# Native Android runtime contract

## Application actually built

- Gradle project: `settings.gradle` includes only `:app`.
- Activity: `MainActivity`.
- Rendering surface: `CityGLView`, based on Android `GLSurfaceView`.
- Renderer: `CityRenderer`, implementing OpenGL ES 2.0 rendering.
- Scene data loader: `TileLoader`.
- Geometry generation: `TileBuilder`, `GeoBuf`, `Poly`, `Mesh`.
- Terrain sampler: `Terrain`.
- Player and agents: `Game`, `ProceduralHumanRig`, `ProceduralVehicleModels`.

The native app does not use Three.js, MapLibre, a WebView game page, or GLB model loading as its primary renderer. `old_app/` is a legacy archive, not a Gradle module. Only `old_app/src/main/assets/data` is explicitly added as an asset source by `app/build.gradle`.

## City selection and data sources

### Amman Local
`CityRenderer.teleportOfflineAmman()` selects `offlineAmman`. `TileLoader` downloads `data/offline-city-stream/amman/manifest.json`, selects the 1 km cell containing the render tile, downloads its `.jsonl.gz` shard, and streams records through a bounding-box filter. The manifest contains 1,778 cells at the current committed snapshot. Source preparation is performed by the Amman shard builder from the saved Overture-derived source datasets.

### Manila Local
`CityRenderer.teleportOfflineManila()` selects `offlineManila`. The loader reads `data/offline-city-stream/manila/manifest.json` and the corresponding compressed JSONL/GZIP shard. The builder `tools/build_manila_stream_shards.py` reads the saved `building-layer.geojson`, `transportation.geojson`, available local vector-tile PBFs, saved non-building OSM context and optional thematic CSVs. The building source is not the three-file merge described by older notes.

### Tabarbour Local
`CityRenderer.teleportOfflineTabarbour()` selects `offlineCitySlug="tabarbour"`. The loader fetches `data/offline-world/tabarbour/stream/manifest.json`, validates `slug`, `center`, `tiles`, `status` and `complete`, then fetches only the listed 250 m cells. It does not read `merged/manifest.json` or the merged building chunks. See the documented active-stream gap in `TABARBOUR_BUILDING_ENRICHMENT.md`.

### Generic/search location
When none of the local city flags is active, `TileLoader.loadTile()` queries public Overpass instances for nearby OSM buildings, highways, land cover, water and selected nodes. Search geocoding in `MainActivity` uses Nominatim. This is a network-backed fallback, not a packaged global basemap.

## Building-height precedence

`TileBuilder.bHeight()` currently checks:

1. `height`
2. `building:height`
3. `height:meters`
4. `building:levels`, `num_floors` or `levels` (3.2 m per level, plus `roof:height` when present)
5. `adam:render_height_m` as an approximate fallback
6. a building-class visual fallback

For Manila, `build_manila_stream_shards.py` copies the source layer's `render_height` value to `adam:render_height_m` when explicit height/level tags are absent. The Java renderer reads that normalized tag, not the original GeoJSON file at runtime. The renderer labels this cartographic estimate as approximate. Do not describe it as a surveyed height.

## Terrain

`Terrain.configureForCity()` enables terrain only for Amman and Tabarbour. It samples zoom-14 Terrarium RGB elevation tiles and stores decoded tiles in the app-private cache. Tabarbour first requests the checked-in repository path `data/offline-world/tabarbour/terrain/{z}/{x}/{y}.png`; AWS Terrarium is the fallback. Amman uses the global Terrarium source when no cached/legacy packaged tile is available. Manila explicitly uses a flat ground surface.

The Tabarbour terrain manifest inventories the 25 checked-in tiles; the Java runtime computes XYZ tile coordinates and requests the tile path directly. It does not parse that JSON manifest before sampling.

## Rendering and memory

- `TileLoader.LOAD_R=1`: a prioritized 3×3 neighborhood of render tiles.
- Completed CPU tiles are back-pressured when the ready queue reaches two.
- Manila and Amman read compressed shards incrementally and filter features to the current render tile.
- The decoded Manila/Amman shard caches are bounded; Tabarbour's decoded source-cell cache is bounded to two cells.
- `Tile` uploads mesh buffers to the GPU and releases CPU-side vertex storage.
- Far render tiles and their meshes are released as the player moves.
- The app-private offline cache is bounded; cache limits and expiry are implemented in `TileLoader`.

These are code-level safeguards, not a guarantee of zero frame drops on every device. Validate performance on the target Android device.

## APK and manifests

The current release workflow is `.github/workflows/android-release.yml`. It builds a signed APK; it does not build an AAB or run device-based gameplay tests. The Android manifest declares internet and location permissions and requires OpenGL ES 2.0.

Runtime manifests:
- `data/offline-city-stream/amman/manifest.json`
- `data/offline-city-stream/manila/manifest.json`
- `data/offline-world/tabarbour/stream/manifest.json`
- `data/offline-world/tabarbour/terrain/manifest.json` is a data inventory; the runtime fetches the underlying tile paths directly.

Other manifests under `data/full-city-imports/`, `data/facades/`, `vehicle-assets/` and `old_app/` are source-import, archive or legacy inventories unless a specific native code path is documented above. Their presence alone does not mean the app loads them.
