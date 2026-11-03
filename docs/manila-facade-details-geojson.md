# Manila facade-detail GeoJSON

The generator adapts the procedural facade-detail method from Adam-City's Ermita script to Manila.

## Canonical input

Reads only: `data/facades/cities/manila/buildings.geojson`

It does not read `building-details.geojson`, `building-footprints.geojson`, `layers/building-layer.geojson`, PBF files, remote APIs, or any other building source. The canonical input is never modified.

## Outputs

Generated layers are split into numbered, independently valid GeoJSON FeatureCollection chunks:

- `data/facades/cities/manila/layers/facade-windows-0001.geojson` and subsequent numbered chunks
- `data/facades/cities/manila/layers/facade-doors-0001.geojson` and subsequent numbered chunks
- `data/facades/cities/manila/layers/facade-balconies-0001.geojson` and subsequent numbered chunks
- `data/facades/cities/manila/facade-details-report.json`

Each chunk is capped at 35,000,000 bytes, safely below GitHub's 100 MB per-file limit. Large source features are divided into smaller GeoJSON features while retaining the source building ID in `building_source_id`. No GLB is produced. Polygon positions are 3D WGS84 coordinates [longitude, latitude, height_m]; the third ordinate is height above the building ground, not absolute terrain elevation.

## Method and limitations

Retains the Ermita rules: height from height, otherwise levels times 3.2 m, otherwise 9.6 m; windows at approximately 3 m spacing on exterior edges of at least 4.5 m; estimated floor placement; doors centered on edges of at least 7 m (0.95 m wide and 1.9 m high); and deterministic occasional balconies on taller buildings. Generated features are procedural placeholders, not photogrammetric, surveyed, or photo-verified details.

The workflow validates every chunk and commits the GeoJSON files to `main`. It does not build game shards or modify the game runtime.
