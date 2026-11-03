# Manila Offline Data Pipeline

The Manila offline game mode uses the existing game renderer and loads nearby spatial shards only.

## Source priority

1. **Primary geometry:** the existing `saeedhub101/Adam-City/Manila/vector-tiles/` OpenMapTiles PBF tiles. Zoom-14 building polygons and transportation geometry are converted into local game elements.
2. **Primary street names and transport geometry:** OpenMapTiles `transportation` and `transportation_name` layers. The existing OpenFreeMap transportation GeoJSON is a fallback if the PBF tiles contain no road geometry.
3. **Canonical Manila building details and footprints:** `data/facades/cities/manila/building-details.geojson` and `building-footprints.geojson`. These are populated from the preserved 1,533-feature OSM detail extract (1,528 polygon footprints) and are consumed by the shard builder. The audited `Adam-City/building-data/buildings.csv` adds matching attributes. Supplemental fields fill blank primary fields; they do not replace primary footprints.
4. **Building facade attributes and parts:** Overture Maps Manila's existing `data/full-city-imports/manila/02_buildings_overture/building/` (107,912 building features) and `building_part/` (758 parts) supply available facade/roof material and colour, floors, heights and building-part geometry. The shard builder merges these into the local Manila stream. The separate photo-texture review file currently has zero human-verified assignments, so no unverified street photo is painted onto a building.
5. **Places and accessibility:** OpenMapTiles `poi` and `housenumber` layers, OSM public-services and accessibility CSVs, and the saved Manila OSM context extract. These add available hospitals, hotels, named facilities, address numbers, crossings, signals, entrances, pedestrian links, transit and utility features.

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
