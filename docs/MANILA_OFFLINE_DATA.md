# Manila Offline Data Pipeline

The Manila offline game mode uses the existing game renderer and loads nearby spatial shards only.

## Source priority

1. **Runtime building geometry and properties:** `tools/build_manila_stream_shards.py` merges `buildings.geojson`, `building-footprints.geojson`, and `building-details.geojson` by normalized OSM feature ID. The consolidated `buildings.geojson` keeps geometry priority and all populated primary tags; footprint/detail attributes fill missing values, and unmatched supplemental building polygons are added once. The merged polygons and properties are written to `data/offline-city-stream/manila/shards/*.jsonl.gz`; Android `TileLoader` reads only nearby compressed cells.
2. **Building colours and materials:** values are read from each building feature's properties/tags. `TileBuilder.buildingColor()` checks `building:colour`, `building:color`, then `colour`; if no explicit colour is available it maps known `building:material` values (brick, concrete, stone, wood, plaster/stucco/render) to a material colour, otherwise Manila uses a neutral fallback. Roof colour/material is handled separately from `roof:colour`, `roof:color`, and `roof:material`.
3. **Road geometry:** the OpenMapTiles vector-tile PBF `transportation` layer supplies road geometry and mapped bridge/tunnel/oneway/layer attributes. `data/facades/cities/manila/transportation.geojson` is also imported for road geometry and is the source for street-name labels; it avoids guessing names from the nearest road.
4. **POIs and house numbers:** OpenMapTiles PBF `poi` and `housenumber` layers supply point features. Saved Manila OSM context and thematic CSVs add supplementary non-building context; building ways are excluded from that supplement to avoid duplicate buildings.
5. **Generated window GeoJSON:** `facade-windows-*.geojson` remains in the repository as archived generated output only. The Android runtime no longer downloads or renders it, and the APK workflow no longer checks out or validates window shards. These files are not included in the APK.

Before this merge was restored, the saved runtime manifest reported 1,759 buildings and zero height/level tags because the shard builder had excluded both saved supplemental building files. The saved `building-footprints.geojson` contains 1,528 polygons: 118 with explicit `height` and 743 with `building:levels` (some features have both). `building-details.geojson` contains the corresponding OSM detail extract. The rebuilt manifest must report the merged source counts and the resulting buildings with height/levels; until that shard rebuild completes, the already-committed runtime shards still contain the old fallback-height data. Coverage is the saved Ermita extract, not complete municipal Manila.

## Normalized building properties

The importer preserves available names, official/alternate names, address and house number, use/class, height, floor counts, underground levels, minimum height, roof shape/material/colour, facade material/colour, window and door material/colour, year, architect and heritage tags. Missing values remain missing. The importer does not invent facade signs, measurements, photographs or building numbers.

The existing PBF enrichment report explicitly distinguishes internal building identifiers from real street address numbers. Runtime display must not treat an internal identifier as an address.

## Progressive loading and cache

- Source features are partitioned into approximately 1 km spatial cells with a 240 m overlap halo.
- The game requests only the cell containing each nearby render tile; it does not load the whole Manila dataset at once.
- Each 1 km shard is spatially filtered to the current render tile before TileBuilder processes it; this avoids rescanning every feature in a full shard for each game tile.
- Initial background prefetch is limited to a 5×5 tile neighborhood, with the nearest tile requested first.
- Compressed JSONL shards are downloaded independently and decoded on the background loader thread.
- At most four decoded offline shards are kept in memory; the pending GL upload queue is capped at six tiles.
- Disk cache is bounded by the same 384 MiB / three-day policy as offline Amman and evicts old shards.
- City changes clear the in-memory shard cache and reload the correct city's manifest.
- Far render tiles and their GL meshes are released as the player moves away.

## Ground

Manila uses a flat ground plane. Elevation/DEM terrain is disabled for Manila; no Manila terrain data is imported or requested.

## Limitations and provenance

The canonical footprint/detail files are stored directly in `data/facades/cities/manila/`; the build fails fast if either file is missing or empty. Coverage is limited to the committed source tiles and saved OSM/thematic extracts. Missing house numbers, measured heights, facade materials, signs, rail/tram routes, sewer/water networks or detailed sidewalk geometry cannot be fabricated. Every imported element retains a source marker where supported. OpenStreetMap attribution and OpenMapTiles/OpenFreeMap attribution must remain in the project.
