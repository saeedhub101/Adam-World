# Adam World

## Building data source policy

For building footprints and building geometry, use **OpenFreeMap** and **OpenMapTiles** as the preferred data sources.

- Prefer building data obtained from OpenFreeMap and OpenMapTiles over raw OSM extracts or direct OSM imports.
- Do not use raw OSM building files as the fallback or replacement source for the preferred building datasets.
- Convert and consolidate the selected building data into the project's required **GeoJSON** format (for Manila, `buildings.geojson`), preserving available attributes such as footprint geometry, height, minimum height, and colour.
- Rebuild the offline/runtime building shards from the validated GeoJSON so the game displays the same imported building data.
- Validate coverage, feature count, geometry validity, coordinate placement, and available attributes before replacing existing working data. Do not overwrite a valid dataset with an empty or incomplete import.
- Keep the source and import provenance with the generated data.

This is a standing project rule and must be followed by future building-data import, conversion, and offline-city workflows unless the project owner explicitly changes it.

**Explicit Manila Local exception (2026-11-03):** the project owner has now directed the offline Manila renderer to use the already-committed `building-footprints.geojson` and `building-details.geojson` datasets as well as `buildings.geojson`. For this Manila-specific path only, the saved detail extract may enrich matching footprints and add unmatched building/building-part features. Preserve source IDs and attribution, merge rather than duplicate matching features, and keep the OpenFreeMap/OpenMapTiles consolidated building data as the preferred primary geometry whenever it is populated. This exception does not change the source policy for other cities.


## Manila complete vector-tile import

- Import every published vector-tile layer and every exposed feature attribute from OpenFreeMap and OpenMapTiles at zoom levels **1 through 16 inclusive**.
- Preserve each layer, feature properties, feature ID where supplied, source, source zoom, and tile coordinates.
- Save each source layer as GeoJSON under `data/facades/cities/manila/layers/`; name the imported building layer `building-layer.geojson` to keep it distinct from the canonical `data/facades/cities/manila/buildings.geojson` consumed by the offline building-shard builder. Never create `layers/building.geojson`.
- Commit a checkpoint for every provider/zoom before advancing. Resume completed stages on rerun. Record empty responses; never fabricate data or misrepresent overzoom as native higher-zoom data.
- Rebuild local Manila building shards from the canonical GeoJSON after import.
