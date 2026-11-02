# Adam World offline city streaming

## Runtime contract

- The Android game must not request OpenStreetMap/Overpass for road geometry or the map base. OpenStreetMap/Overpass is permitted only for supplementary missing building footprints/building parts when the player leaves pre-imported city coverage.
- Three.js is bundled into the APK at `app/src/main/assets/vendor/three.min.js`.
- City geometry is packaged under `app/src/main/assets/cities/<city>/`.
- The native Android bridge reads these assets synchronously; the game requests only nearby spatial tiles.
- Optional Panoramax/OpenAerialMap evidence lookup is user-triggered and is not part of startup or movement.

## Repository sources

- `data/facades/cities/<city>/buildings.geojson` is the existing committed building fallback.
- `data/facades/cities/<city>/building-footprints.geojson` is the authoritative building footprint geometry from OpenFreeMap/OpenMapTiles.
- `data/facades/cities/<city>/transportation.geojson` is the authoritative road geometry plus the `transportation_name` label lines from OpenFreeMap/OpenMapTiles.
- `data/facades/cities/<city>/building-details.geojson` is supplementary OSM data only: it enriches a matching primary footprint with available tags and contributes only unmatched buildings/building parts.
- `data/offline-world/<city>/elements.json` remains a source of supplementary non-road context and building gaps; its OSM road ways are excluded whenever primary OpenFreeMap transportation is present.
- `tools/build_offline_city_tiles.py` packages these prioritized sources into 250 m tiles and writes a manifest per city.

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

The legacy OSM import profile includes buildings, roads, parks/land cover, water,
mapped tree and street-lamp nodes, traffic signals, crossings, bus stops,
level crossings, barriers and bridge/pier ways. For game building and road geometry,
these raw OSM ways are no longer authoritative: OpenFreeMap/OpenMapTiles building
footprints and transportation lines take precedence; OSM contributes only missing
building geometry/parts and supplemental tags. Non-road environmental/context
features may still be retained from the offline extract.

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
- Street labels are sourced from the OpenFreeMap/OpenMapTiles `transportation_name` layer and rendered as outlined text above the corresponding road surface. OSM road ways are not used as the road geometry source. Names are never invented; labels are repeated sparingly along long streets.
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

- OpenFreeMap/OpenMapTiles `transportation_name` lines provide road-label geometry and names. The game raises labels above the road mesh, disables depth occlusion for the label plane, and repeats names sparingly along long streets. The label limit is 600 placements per loaded area.
- Release workflow publishes a small `adam-world-web-update.zip` containing the changed game HTML. When a release marks `Native-Required: false` and `Web-Content-Changed: true`, Android downloads and applies this bundle into app-private storage, then reloads the game without reinstalling the APK or removing user data. Native-code or packaged offline-data changes still require a normal APK update.
- Facade photo candidates remain unverified until the exact building identity and visible facade are confirmed. The latest review harvest validated 78 preview images (33 HD source images) across the cities with available Panoramax coverage; this is not 78 verified facade assignments. No candidate is assigned to a building merely because it is nearby or camera-aligned.

## Elevation and terrain conformance (2026-11-01)

- Amman is the only destination that receives a terrain grid and rendered terrain surface. All other destinations are deliberately flat; their elevation grids are not packaged or imported.
- The Amman grid remains local and is used only inside its validated coverage. Road ribbons are lifted slightly above the sampled ground to prevent the road surface being hidden by the coarse 30 m elevation mesh.
- Rebuild and verify the APK after terrain changes; the presence of the source grid alone is not proof that an installed build contains it.
- Importer: `tools/import_copernicus_terrain.py`; workflow: `.github/workflows/copernicus-terrain-import.yml`; status: `docs/REALISM_REBUILD_STATUS.md`.


## Live geographic streaming (2026-11-02)

- Local APK tiles are preferred whenever the player is inside a stored city coverage area.
- When the player moves outside stored city coverage, MapLibre continues to render the OpenFreeMap Liberty basemap and its vector-tile roads/labels/buildings. A bounded Overpass request may retrieve building/building-part geometry only as supplementary local 3D content; it does not fetch or replace roads. This requires an internet connection and no API key.
- Live OSM fallback is strictly building-only. OpenFreeMap/MapLibre remains the road and map base; OSM does not become the road source.
