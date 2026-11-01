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

The ten registered destinations have committed full OSM extracts under
`data/offline-world/<city>/elements.json`, with per-city source metadata. Import
profile version 4 expands the stored land-cover and water tags to include farmland,
orchards, vineyards, sand, beaches, desert, bare rock, scree, shingle, heath,
wetlands, marshes, mud, grassland, reservoirs and mapped landcover values. The
profile refresh is checkpointed city-by-city directly to `main`; subsequent
reruns validate the profile and stored element count and skip completed cities.
A partial run attempts every city and preserves each successful checkpoint.

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
- OSM road names (`name:ar`, `name`, `name:en`, then `ref`) are rendered as outlined text directly on the road surface, aligned with the road and repeated sparingly on long named streets. Unnamed roads receive no invented labels.
- Mapped bus-stop, stop-sign and pedestrian-crossing nodes are rendered at their OSM coordinates. Detached facade-photo cards remain hidden.

## Real facade evidence boundary

OpenAerialMap metadata is used for aerial/roof context only; Panoramax metadata
is used to discover street-level facade-photo candidates. Catalogue discovery bounds
include an additional 1.8 km around mapped building extents to find nearby captures;
this wider radius only expands candidate discovery and does not verify any match. Candidate proximity or
camera direction is not proof of building identity. Detached photo cards are
hidden in gameplay. A photo may appear on a building only after its exact
building/facade, camera direction, image quality and redistribution licence are
verified and the resulting local texture is baked onto that building's own mesh.
The current verified facade manifest is still empty; the ten-city OSM geometry
import is complete, but photorealistic facade texturing remains a separate,
evidence-gated production phase.


## Street-name labels and update delivery

- Named OSM road ways render their actual mapped name directly on the road surface, aligned to the road direction. Arabic `name:ar` is preferred, then `name:en`, `name`, `official_name`, `loc_name`, and finally the mapped `ref`; no street names are fabricated. The visible label limit is 240 placements per loaded area.
- Release workflow publishes a small `adam-world-web-update.zip` containing the changed game HTML. When a release marks `Native-Required: false` and `Web-Content-Changed: true`, Android downloads and applies this bundle into app-private storage, then reloads the game without reinstalling the APK or removing user data. Native-code or packaged offline-data changes still require a normal APK update.
- Facade photo candidates remain unverified until the exact building identity and visible facade are confirmed. The latest review harvest validated 78 preview images (33 HD source images) across the cities with available Panoramax coverage; this is not 78 verified facade assignments. No candidate is assigned to a building merely because it is nearby or camera-aligned.
