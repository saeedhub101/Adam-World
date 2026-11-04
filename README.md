# Adam World

## Current Android game

The active Android application is the Gradle module `app`, package `com.explorer.world3d`. It is a native Java game rendered with Android `GLSurfaceView` and OpenGL ES 2.0:

- Entry point: `app/src/main/java/com/explorer/world3d/MainActivity.java`
- View and input: `CityGLView.java`
- Renderer: `CityRenderer.java`
- Spatial loading: `TileLoader.java`
- OSM feature-to-mesh conversion: `TileBuilder.java`
- Terrain: `Terrain.java`

The active app does not run the legacy `old_app` HTML/Three.js client. The Gradle settings include only `:app`. The old folder contributes only the explicitly added `old_app/src/main/assets/data` asset directory; its HTML and model manifest are not the current game runtime.

## Local city modes

| Mode | Runtime data | Main geometry source | Ground |
|---|---|---|---|
| Amman Local | `data/offline-city-stream/amman/` | Overture-derived spatial shards | Terrarium DEM when available |
| Manila Local | `data/offline-city-stream/manila/` | `data/facades/cities/manila/layers/building-layer.geojson`, converted to compressed spatial shards | Flat |
| Tabarbour Local | `data/offline-world/tabarbour/stream/` | Only cells listed in the active stream manifest | Repository Terrarium tiles, then AWS Terrarium fallback |
| Generic/search location | Overpass requests by nearby tile | Live OSM elements | Flat unless a supported terrain mode is enabled |

“Local” means the city-specific dataset is hosted in this repository and cached in app-private storage. It does not mean the first load is guaranteed to work without a network connection.

## Important current data facts

- **Manila:** 1,759 source building features; 1,748 have a positive `render_height` value; 11 do not. The source has 213,808 polygon components. The game builder maps `render_height` to `adam:render_height_m`; `TileBuilder.bHeight()` uses that normalized value after explicit height and floor-count tags.
- **Tabarbour:** `merged/manifest.json` reports 29,159 merged building features. This is not the same as the active `stream/manifest.json`, which reports 2,229 elements across 1,108 listed cells. The native loader reads the stream manifest and its listed cells; it does not directly read the merged chunks. Do not claim the 29,159 merged buildings are already in the active runtime stream.
- **Amman:** the native loader reads the Amman manifest and compressed JSONL/GZIP shards. It does not load the ten-city catalog as its city-data index.

## Documentation map

- [Current runtime and loading contract](docs/GAME_RUNTIME.md)
- [Spatial streaming details](docs/OFFLINE_CITY_STREAMING.md)
- [Manila local data](docs/MANILA_OFFLINE_DATA.md)
- [Manila render-height statistics](docs/manila-building-layer-height.md)
- [Tabarbour building data and known stream gap](docs/TABARBOUR_BUILDING_ENRICHMENT.md)
- [Surface rendering](docs/REAL_WORLD_SURFACE_PIPELINE.md)
- [Facade evidence limitations](docs/FACADE_RECONSTRUCTION_PIPELINE.md)\n- [Robinsons Place Manila native reconstruction](docs/ROBINSONS_PLACE_MANILA_REBUILD.md)
- [Current implementation status](docs/REALISM_REBUILD_STATUS.md)

## Build and release

The active release workflow is `.github/workflows/android-release.yml`. It rebuilds Manila spatial shards from the saved building layer, validates the committed Amman/Manila shard inventories, builds and signs the Android APK, verifies the APK signature, and publishes a GitHub release. A successful build does not replace on-device visual testing.

## Project rules

- [Mandatory incremental build, checkpoint and resume policy](docs/INCREMENTAL_BUILD_POLICY.md)

- Preserve source provenance, IDs, licences and attribution.
- Do not invent measured heights, addresses, facade details or building identities.
- Keep Tabarbour-owned source and derived data under `data/offline-world/tabarbour/`.
- Keep source-import manifests separate from runtime manifests.
- Do not describe a dataset as active in the game until the native loader actually reads it.
