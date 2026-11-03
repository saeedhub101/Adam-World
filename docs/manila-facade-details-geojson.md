# Manila facade-detail GeoJSON

The generator adapts the procedural facade-detail method from Adam-City's Ermita script to Manila.

## Canonical input

Reads only: data/facades/cities/manila/buildings.geojson

It does not read building-details.geojson, building-footprints.geojson, layers/building-layer.geojson, PBF files, remote APIs, or any other building source. The canonical input is never modified.

## Outputs

Generated layers are split into numbered, independently valid GeoJSON FeatureCollection chunks:

- data/facades/cities/manila/layers/facade-windows-0001.geojson and subsequent numbered chunks
- data/facades/cities/manila/layers/facade-doors-0001.geojson and subsequent numbered chunks
- data/facades/cities/manila/layers/facade-balconies-0001.geojson and subsequent numbered chunks
- data/facades/cities/manila/facade-details-report.json

Each source chunk is capped at 35,000,000 bytes. Window coordinates are 3D WGS84 [longitude, latitude, height_m]; the third ordinate is height above the building ground, not absolute terrain elevation.

## Runtime integration

The Manila offline shard builder reads the saved facade-windows-*.geojson chunks and writes one compressed window-shards/{tileX}_{tileZ}.jsonl.gz file per game tile. It records the tile index and checksums in data/offline-city-stream/manila/manifest.json. This keeps the 7,037,436 saved window surfaces spatially partitioned; the Android loader fetches only the window shard for each requested Manila game tile and caches it locally.

TileBuilder recognizes these records as a separate facade-window layer and renders their saved polygon geometry in the glass mesh. It adds local terrain elevation to each building-ground-relative height. The building geometry remains sourced only from canonical buildings.geojson; windows are not merged into building footprints or building geometry.

The runtime build validates that the indexed surface count exactly matches facade-details-report.json before publishing. Doors and balconies remain disabled until separately integrated and verified.

## Method and limitations

Windows and doors are procedural placeholders, not photogrammetric, surveyed, or photo-verified details. The window layer uses the saved geometry as generated; it does not claim that every generated window matches a real-world opening. Balconies remain omitted until confirmed from suitable facade photographs.
