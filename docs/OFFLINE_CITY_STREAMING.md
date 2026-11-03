# Adam World offline city streaming

## Runtime contract

- The Android game must not request OpenStreetMap/Overpass for road geometry or the map base. OpenStreetMap/Overpass is permitted only for supplementary missing building footprints/building parts when the player leaves pre-imported city coverage.
- Three.js is bundled into the APK at `app/src/main/assets/vendor/three.min.js`.
- Amman and Manila retain their existing city-specific delivery contracts.
- Tabarbour is never packaged in the APK: every Tabarbour source and derived dataset lives under `data/offline-world/tabarbour/`, including `stream/` and `terrain/`.
- Tabarbour is remotely streamed from `data/offline-world/tabarbour/stream/`; the Android client downloads only nearby cells into its bounded app-private cache.
- Optional Panoramax/OpenAerialMap evidence lookup is user-triggered and is not part of startup or movement.

## Repository sources

- `data/facades/cities/<city>/buildings.geojson` is the existing committed building fallback.
- `data/facades/cities/<city>/building-footprints.geojson` is the authoritative building footprint geometry from OpenFreeMap/OpenMapTiles.
- `data/facades/cities/<city>/transportation.geojson` is the authoritative road geometry plus the `transportation_name` label lines from OpenFreeMap/OpenMapTiles.
- `data/facades/cities/<city>/building-details.geojson` is supplementary OSM data only: it enriches a matching primary footprint with available tags and contributes only unmatched buildings/building parts.
- `data/offline-world/<city>/elements.json` remains a source of supplementary non-road context and building gaps; its OSM road ways are excluded whenever primary OpenFreeMap transportation is present.
- `tools/build_offline_city_tiles.py` packages these prioritized sources into 250 m tiles and writes a manifest per city.

### Manila Local building source integration (2026-11-03)

- The offline shard builder now consumes all three saved building datasets: `buildings.geojson`, `building-footprints.geojson` and `building-details.geojson`.
- A populated consolidated `buildings.geojson` has geometry priority. The OpenMapTiles/OpenFreeMap footprint file fills missing geometry; the details file enriches matching features by source ID and adds unmatched building/building-part polygons and mapped building nodes.
- Matching features are merged by source feature ID so the same footprint is not emitted twice. Supplemental properties fill missing values; existing populated primary values are preserved.
- The current committed footprint/detail extracts contain 1,528 polygons and 1,533 detail features (including five nodes). They cover the saved Ermita extract and must not be described as complete citywide Manila coverage.
- OpenMapTiles PBF remains the road geometry and non-building POI/address source. `transportation.geojson` supplies the named street geometry. The saved context import excludes building ways to avoid duplicate building meshes.
- The Android release workflow validates the footprint and details files and checks the merged building counts in the generated Manila manifest. An empty `buildings.geojson` no longer blocks use of the saved footprint/detail data.



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
The current verified facade manifest is still empty; the ten-city OpenFreeMap/OpenMapTiles
footprint and road layers are imported as the primary geometry, with OSM used only
for supplementary building attributes and missing building/building-part geometry.
Photorealistic facade texturing remains a separate, evidence-gated production phase.


## Street-name labels and update delivery

- OpenFreeMap/OpenMapTiles `transportation_name` lines provide road-label geometry and names. The game raises labels above the road mesh, disables depth occlusion for the label plane, and repeats names sparingly along long streets. The label limit is 600 placements per loaded area.
- Release workflow publishes a small `adam-world-web-update.zip` containing the changed game HTML. When a release marks `Native-Required: false` and `Web-Content-Changed: true`, Android downloads and applies this bundle into app-private storage, then reloads the game without reinstalling the APK or removing user data. Native-code or packaged offline-data changes still require a normal APK update.
- Facade photo candidates remain unverified until the exact building identity and visible facade are confirmed. The latest review harvest validated 78 preview images (33 HD source images) across the cities with available Panoramax coverage; this is not 78 verified facade assignments. No candidate is assigned to a building merely because it is nearby or camera-aligned.

## Elevation and terrain conformance (2026-11-01)

- Amman is the only destination that receives a terrain grid and rendered terrain surface. All other destinations are deliberately flat; their elevation grids are not packaged or imported.
- The Amman grid remains local and is used only inside its validated coverage. Road ribbons are lifted slightly above the sampled ground to prevent the road surface being hidden by the coarse 30 m elevation mesh.
- Rebuild and verify the APK after terrain changes; the presence of the source grid alone is not proof that an installed build contains it.
- Importer: `tools/import_copernicus_terrain.py`; workflow: `.github/workflows/copernicus-terrain-import.yml`; status: `docs/REALISM_REBUILD_STATUS.md`.


## Continuous Libre world with local city replacement (2026-11-02)

- OpenFreeMap Liberty / OpenMapTiles is the permanent world base, including outside every locally imported city. It is never treated as a temporary fallback layer.
- While the player is inside committed local tile coverage, the APK streams the nearby local city tiles into the Three.js world. Local buildings, primary OpenFreeMap/OpenMapTiles roads and labels, and stored non-road context (including mapped water, land cover, trees and transport details) are drawn from those committed assets over the global map. Their rendered local geometry takes precedence wherever it exists; Libre remains visible for features not represented by local assets.
- The game also filters matching OpenMapTiles vector features by their OSM feature IDs in the corresponding source layers (buildings, transportation/name lines, water, waterways, landcover/landuse, parks and mapped POIs). This removes the duplicate Libre feature beneath a local replacement while leaving unmatched features in those same layers visible.
- The local renderer must not fetch Overpass/OSM geometry to fill missing tiles or to manufacture a second city layer. If a local manifest/tile is absent or unreadable, that sector is Libre-only until committed local data is available. This avoids substituting incomplete live OSM data for the permanent world source.
- Outside local coverage, the local geometry set is empty and the player continues over the OpenFreeMap Liberty world. Libre supplies roads, water, buildings, labels and all other style layers there.
- Local coverage is the actual committed tile footprint, not the full municipal boundary. The current city windows are initial imported coverage; expand them by committing additional primary-source/local context tiles, not by broadening a runtime Overpass query.
- Attribution remains visible: © OpenFreeMap, © OpenMapTiles and © OpenStreetMap contributors (underlying source data).


## Native Android Amman streaming (2026-11-02)

- The destination row keeps the existing online Amman entry and adds a separate **Amman Local** entry.
- Both Amman entries use the same Roman-Theatre-area origin: latitude `31.9522646`, longitude `35.9389907`. The local entry uses the committed Overture full-city source extracts in `data/full-city-imports/amman/`.
- Before publishing an Android release, `tools/build_amman_stream_shards.py` converts the Amman building, building-part, road-segment, water, land-cover and land-use extracts into independently downloadable 1 km spatial cells under `data/offline-city-stream/amman/`. Cells are compressed NDJSON; the source GeoJSON files are not packaged into the game or downloaded by the device.
- On first entry to local Amman, the app downloads a small manifest and only the 1 km cells needed by its nearby 5-by-5 game-tile neighborhood. As the player moves, new cells are downloaded on demand. Downloaded cells are cached on disk; at most ten parsed source cells are retained in the in-memory LRU cache.
- Rendered game tiles outside the keep radius are released from memory. Offline Amman never falls back to Overpass/OSM when a local cell is absent; missing coverage remains empty. The existing online destinations and online Amman loader remain separate and unchanged.
- The first visit to an uncached area requires internet access to fetch its cell. Once cached, that area can be reused without downloading the full 2 GB source directory.


## Amman offline source-property rendering (2026-11-02)

- Amman Local continues to use only `data/full-city-imports/amman/`; no GLB conversion or secondary city source is used.
- Building shard conversion retains mapped total height, above-ground and underground floor counts, underground height, raised base/min-height, plinth height, roof height/shape/material/colour, facade material/colour, building use/class, names and available address fields. The renderer uses mapped height/levels, base offsets, roof material/colour, gabled/skillion roof profiles and mapped roof rise. When underground depth is mapped, the building wall geometry continues below terrain; it is not a visible underground cutaway in the current camera/terrain renderer.
- Road conversion retains the road class, source width, surface, bridge/tunnel flags and any mapped bridge/tunnel height or layer. The renderer uses source width instead of the class-width default when a valid width is present, and changes road surface appearance by mapped surface type. Bridge/tunnel vertical offsets are applied only when a source height/layer is available; no fixed assumed bridge or tunnel elevation is substituted.
- Missing source measurements remain missing. Existing conservative building-height/class fallbacks and class-based road widths are only fallbacks, not source measurements. Building plinth and underground values are retained; the current renderer does not yet provide an interactive underground cutaway or a separately modelled plinth band.
- This is native procedural geometry, not GLB. A successful signed Android build and visual inspection on device are required before these changes are considered released or fully verified.


## Amman offline architectural detail and basement cutaway (2026-11-02)

- The native procedural renderer adds separate horizontal facade joints at source storey elevations and a projecting plinth band only when `building:plinth:height` is present. It does not infer a plinth from `min_height`.
- Underground storey depth uses `adam:underground:height`; when absent, the renderer estimates depth from `building:levels:underground` at 3 m per level. Basement walls, floor joints and concrete floor slabs are generated below terrain.
- The camera control row includes **كشف تحت الأرض**. It hides the terrain mesh while leaving the below-grade building geometry visible; use **إظهار الأرض** to restore the terrain. This is a global terrain cutaway, not a localized per-building section plane.
- Source road widths, road surfaces and available bridge/tunnel height or layer attributes are used by the procedural renderer. Bridge/tunnel elevations with no source height/layer remain unresolved rather than being assigned a fabricated engineering elevation.
- These changes affect only the native offline renderer and source-shard mapping; no GLB assets are created or loaded. Android release build and on-device visual QA are still required before claiming the APK is verified.


## Source boundary correction (2026-11-02)

The native offline Amman stream is generated exclusively from the already imported directory `data/full-city-imports/amman/`. The shard builder must not read `data/facades`, `data/offline-world`, `data/offline-city`, external APIs, or any other city/source folder. It does not modify or replace those existing datasets. The generated compressed spatial cache is a separate derived output under `data/offline-city-stream/amman/`; it is not a second source dataset.


## Tabarbour single-folder storage rule (2026-11-03)

- All Tabarbour-owned files must live under `data/offline-world/tabarbour/`: city configuration, building footprints/details/parts, addresses, roads and road names, OSM context, transit routes, aerial and Panoramax catalogues, facade review queues, elevation source grids, terrain tiles, spatial stream tiles, manifests and future verified facade assets.
- Do not write Tabarbour data to `data/facades/cities/tabarbour/` or `app/src/main/assets/cities/tabarbour/`. The importer workflow migrates existing files into the single offline-world folder and removes the obsolete locations after a successful publish.
- The global city catalog may remain at `data/facades/cities/catalog.json`; it is registry metadata, not a Tabarbour dataset.
- `TileLoader` reads the remote manifest and cells from `https://raw.githubusercontent.com/saeedhub101/Adam-World/main/data/offline-world/tabarbour/stream`. The APK contains only the loader/runtime code, not Tabarbour city content.


## Tabarbour additional footprint sources (2026-11-03)

- The footprint supplement importer is `tools/facades/import_tabarbour_footprint_supplements.py`. It runs only for Tabarbour and stores source files, source status and reports under `data/offline-world/tabarbour/sources/{microsoft,jordan}/`.
- Microsoft Global ML Building Footprints is checked against the official `dataset-links.csv` manifest and only Jordan quadkey tiles intersecting the configured Tabarbour bounding box are downloaded. No worldwide archive is downloaded. The source is licensed CDLA Permissive 2.0; source tile URL, quadkey and upload date are retained.
- HDX is queried for the exact public dataset title **Buildings of Jordan**. A supported resource is clipped to Tabarbour before saving. If the exact package/resource is absent or cannot be read, a status report is written and the workflow continues; no substitute dataset is silently selected. The actual dataset licence is retained from HDX metadata and must be reviewed before redistribution.
- Both sources are added to the existing provenance-preserving merge. Explicit OSM identity is preferred; spatial reconciliation requires high overlap and close centroids. Unmatched valid polygons can be added, while uncertain overlaps remain separate with source provenance for QA. Building parts remain separate from parent footprints.
- Footprints alone do not provide verified names, house numbers, heights, storeys, materials or facade appearance. Those fields are retained only when a source explicitly supplies them; no values are fabricated. Existing footprints and source files are never deleted by the importer.


## Tabarbour stream integrity and cache (2026-11-03)

- The stream builder must use the canonical merged multi-source building manifest; it must fail rather than publish OSM context-only data as a complete city.
- Tile files are built in a staging directory. The old complete manifest remains published until all replacement cells validate; the new complete manifest is published last. Stale cells not referenced by the new manifest are removed.
- Each complete manifest carries a content-derived `dataset_version` and building count. Android caches cells by that version and never redownloads an unchanged cell merely because Home was pressed.
- Home refreshes the small manifest only. Existing immutable versioned cell files remain available, and a changed dataset version naturally selects a new cache namespace.
