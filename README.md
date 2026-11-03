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
