# Manila Offline Data Pipeline

The Manila offline game mode uses the existing game renderer and loads nearby spatial shards only.

## Source priority

1. **Building footprints:** `data/facades/cities/manila/building-footprints.geojson` supplies the saved polygon geometry when the newer consolidated `buildings.geojson` is absent or has no usable features. When a matching feature exists in `buildings.geojson`, its geometry takes priority.
2. **Building details:** `building-details.geojson` is joined to footprint records by OSM feature ID. Its available names, addresses, building use, heights, levels, roof/facade materials, colours and `building:part` tags fill missing properties. Unmatched building polygons/parts and mapped building nodes are also retained. Duplicate IDs are merged rather than rendered twice.
3. **Consolidated building data:** `buildings.geojson` remains the preferred consolidated input when populated; an empty file must not stop the build or suppress the existing footprint/detail datasets.
4. **Roads and named streets:** OpenMapTiles PBF supplies road geometry and non-building POIs/address points. `transportation.geojson` supplies OpenFreeMap/OpenMapTiles road geometry and street-name lines.
5. **Other context:** saved public-services/accessibility CSVs and the Manila OSM context extract add non-building features. The context importer excludes building ways so it cannot create duplicate building meshes.

The preserved `building-footprints.geojson` contains 1,528 polygon features. `building-details.geojson` contains 1,533 features: 1,528 polygons and five mapped nodes; 222 polygon records carry a `building:part` tag. These are source counts, not a claim of full municipal Manila coverage.


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
