# Tabarbour — building sources, merge and active runtime

## Canonical merged source

Tabarbour source material is kept under `data/offline-world/tabarbour/`. The merged building checkpoint is:

- Manifest: `merged/manifest.json`
- Output: 12 chunks named `merged/buildings_000.geojson` through `buildings_011.geojson`
- Reported merged building count: **29,159**
- Input source counts: OSM 7,223; OpenFreeMap 124; Overture 28,333; OpenBuildingMap 28,753.
- Attached addresses: 858; attached entrances: 3; attached building parts: 0; Panoramax photos: 0.

The merge policy prefers explicit OSM identity and uses conservative spatial matching. It preserves source provenance and does not treat a nearby Panoramax candidate as a verified facade photo.

## Active Android stream — verified and connected

The native Android runtime reads only:

`data/offline-world/tabarbour/stream/manifest.json`

It validates the manifest, selects nearby 250 m cells from its `tiles` array, downloads only those cells, and passes their OSM-style `elements` into `TileBuilder`. It does not read the merged GeoJSON chunks directly at runtime; the stream builder has already converted the canonical merged source and required context into the active cells.

Latest verified active stream:

- **1,866** listed cells, all present and validated.
- **107,121** elements across the stream.
- **59,266** published building features.
- **785** buildings and **299** road elements in the start-neighborhood validation window.
- Start coordinate: **31.997579, 35.940358**.
- `runtime_validation.status: passed`; all 1,866 cells were parsed and their per-cell counts checked.
- The Shafa Badran Street label is present in repaired cell `tile_-9_-22.json.gz`.

The validator run is [42121546729](https://github.com/saeedhub101/Adam-World/actions/runs/42121546729). This confirms repository data integrity and manifest completeness. It does **not** claim that a physical Android device has completed a visual/gameplay test. The Java loader integration is documented in `docs/GAME_RUNTIME.md` and `docs/OFFLINE_CITY_STREAMING.md`.

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


## Runtime geometry corrections (2026-11-06)

The native Android renderer now applies this priority for building height:

1. Explicit metric `height`, `building:height`, and `height:meters`.
2. Mapped `building:levels` / `num_floors` / `levels` multiplied by the storey height, plus mapped roof height.
3. The cartographic `adam:render_height_m` only as a fallback, with a class-based minimum where the feature is a known multi-storey class. The minimum is explicitly an estimate, not a surveyed height.

For Tabarbour only, an unmeasured ordinary building receives a two-storey visual minimum; apartments/dormitories/residential blocks receive five storeys, and hotels/hospitals/offices/commercial/retail/university buildings receive four. Garages, sheds and huts are excluded from the generic minimum. These are provisional visual estimates and must never be reported as verified real-world measurements. Where OSM, Overture, or OpenBuildingMap measurements exist, those take precedence.

Tabarbour ground-floor commercial footprints now receive full-height glazed shopfront bays with mullions. The apartment entrance treatment now includes stone side returns, a recessed dark entry bay, and a door plane set behind the outer facade; the two flanking ground-floor areas receive low terrace slabs. The ground-floor wall mesh now omits the central entrance rectangle, leaving an actual opening in the facade mesh; stone side returns, a dark recessed back plane and the door sit behind the facade. Two flanking terrace slabs and low rails model the ground-floor apartment bays. The source currently has only three attached entrance points, so unmapped entrances still use the longest-footprint-edge fallback and are not verified door positions.

Mapped sports pitches now receive field markings and goal geometry. Mapped playground polygons receive a sand-coloured surface and basic low-profile play equipment. Park/garden polygons use an earth-toned base with tree placement, while mapped forest, grass, sand, and wetland classes retain separate surface colours.

Hospital and hotel categories are rendered from their OSM building/amenity/tourism tags and receive category signage where the source includes those tags. Missing source classification is not guessed from building shape.

## Important verification boundary

The changes above are renderer-side and do not enlarge the APK with city geometry. Tabarbour cells remain hosted on GitHub and are fetched/cached around the player. A successful APK build proves compilation/signing only; visual proportions, recessed entry appearance, shop glazing, field equipment, and the actual height distribution still require an Android emulator/device inspection. The class-based minimums are an interim visual correction, not a substitute for per-building surveyed heights.
