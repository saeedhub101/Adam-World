# Adam World offline city streaming

## Runtime contract

- The Android game must not request OpenStreetMap/Overpass for city geometry.
- Three.js is bundled into the APK at `app/src/main/assets/vendor/three.min.js`.
- City geometry is packaged under `app/src/main/assets/cities/<city>/`.
- The native Android bridge reads these assets synchronously; the game requests only nearby spatial tiles.
- Optional Panoramax/OpenAerialMap evidence lookup is user-triggered and is not part of startup or movement.

## Repository sources

- `data/facades/cities/<city>/buildings.geojson` is the existing committed building fallback.
- `data/offline-world/<city>/elements.json` is the full local OSM extract, including building ways, roads, parks, land cover, water, trees, and street lamps. The import workflow commits each city independently and resumes by validating existing city data and metadata.
- `tools/build_offline_city_tiles.py` packages the best available local source into 250 m tiles and writes a manifest per city.

## Spatial streaming

- The player position is projected against the selected city's fixed catalog origin.
- The Android client loads the 5-by-5 tile neighborhood around the player (250 m tiles), not the whole city.
- When the player moves beyond the local reload threshold, the game recenters the local origin and loads the next neighborhood from APK assets.
- Tiles are selected by manifest coordinates. Missing or out-of-coverage tiles are not replaced with fabricated geometry or online Overpass results.

## Build validation

The Android workflow must:
1. Vendor the pinned Three.js runtime before packaging.
2. Generate city tile assets from repository data.
3. Validate the embedded game JavaScript.
4. Build APK and AAB release variants.
5. Inspect the APK archive and verify Three.js plus all ten city manifests and at least one tile per city are present before publishing.

The current catalog coverage is an initial discovery window around each city's configured center, not a claim of complete municipal coverage. Expanding a city means importing and committing additional local footprints, then regenerating the tiles.
