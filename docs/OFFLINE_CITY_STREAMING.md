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

## Current ten-city checkpoint (2026-11-01)

The ten registered destinations now have committed full OSM extracts under
`data/offline-world/<city>/elements.json`, with per-city source metadata. The
import workflow checkpoints each completed city directly to `main`, retries
fetch/rebase/push when another workflow updates `main`, and resumes by validating
the import profile and stored element count. A partial run reports failure only
after attempting every city, so rerunning it skips already-complete city data.

The import profile includes building and road ways, parks/land cover, water,
mapped tree and street-lamp nodes, traffic signals, crossings, bus stops,
level crossings, barriers and bridge/pier ways. The Android packager prefers
these full extracts over the smaller building-only fallback GeoJSON.

## Player and camera controls

- Initial player placement is selected from mapped road/path geometry after
  building collision data is built. Candidate positions are tested against
  building footprints; the old fixed city-origin fallback is not used when a
  clear street/path position is available.
- Camera presets: street, nearby elevated, high view and near-vertical drone view.
  Zoom-in/zoom-out controls and mouse-wheel zoom change camera distance.
- During a city jump or local tile reload, movement is paused until the new
  offline tile neighborhood has loaded. Movement streaming recenters the local
  origin and loads nearby tiles from APK assets.

## Real facade evidence boundary

OpenAerialMap metadata is used for aerial/roof context only; Panoramax metadata
is used to discover street-level facade-photo candidates. Candidate proximity or
camera direction is not proof of building identity. Detached photo cards are
hidden in gameplay. A photo may appear on a building only after its exact
building/facade, camera direction, image quality and redistribution licence are
verified and the resulting local texture is baked onto that building's own mesh.
The current verified facade manifest is still empty; the ten-city OSM geometry
import is complete, but photorealistic facade texturing remains a separate,
evidence-gated production phase.
