# Manila Offline Data Pipeline

The Manila offline game mode uses the existing game renderer and loads nearby spatial shards only.

## Source priority

1. **Local building geometry and attributes:** `data/facades/cities/manila/buildings.geojson` is the sole canonical building source for the local Manila game renderer. Its Polygon/MultiPolygon geometry and available OSM attributes (including name, address, height, floor count, building parts, roof and facade tags) are preserved into spatial runtime shards without inventing missing values.
2. **Runtime delivery:** `tools/build_manila_stream_shards.py` partitions those saved buildings into compressed 1 km spatial cells under `data/offline-world/manila/runtime/`. The Android game reads only the nearby cell and filters it to the current render tile; it never parses the 48 MB source GeoJSON into one in-memory object.
3. **Roads and named streets:** `transportation.geojson` supplies OpenFreeMap/OpenMapTiles road geometry and street-name lines. Optional OpenMapTiles PBF data supplements roads and non-building POIs/address points.
4. **Other context:** the saved `data/offline-world/manila/elements.json` extract contributes non-building context only. Building ways and parts from that extract are excluded to prevent duplicate or competing building meshes.

The separate `building-footprints.geojson` and `building-details.geojson` files remain preserved source datasets, but they are not substituted for the explicitly selected canonical `buildings.geojson` building layer in this renderer path.

## Normalized building properties

The importer preserves available names, official/alternate names, address and house number, use/class, height, floor counts, underground levels, minimum height, roof shape/material/colour, facade material/colour, window and door material/colour, year, architect and heritage tags. Missing values remain missing. The importer does not invent facade signs, measurements, photographs or building numbers.

The existing PBF enrichment report explicitly distinguishes internal building identifiers from real street address numbers. Runtime display must not treat an internal identifier as an address.

## Progressive loading and cache

- Source features are partitioned into approximately 1 km spatial cells with a 240 m overlap halo.
- The game requests only the cell containing each nearby render tile from `data/offline-world/manila/runtime/shards/`; it does not load the whole Manila dataset at once.
- The Android APK packages the committed runtime manifest and compressed cells as local assets, so Manila building display does not require a first-run network download.
- The runtime manifest records `data/facades/cities/manila/buildings.geojson` as the only building geometry source; the raw `offline-world/manila/elements.json` file is supplemental context only.
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
