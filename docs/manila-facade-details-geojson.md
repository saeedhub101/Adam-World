# Manila facade-detail GeoJSON

The generator adapts the procedural facade-detail method from Adam-City's Ermita script to Manila.

## Canonical input

Reads only: data/facades/cities/manila/buildings.geojson

It does not read building-details.geojson, building-footprints.geojson, layers/building-layer.geojson, PBF files, remote APIs, or any other building source. The canonical input is never modified.

## Outputs

- data/facades/cities/manila/layers/facade-windows.geojson
- data/facades/cities/manila/layers/facade-doors.geojson
- data/facades/cities/manila/layers/facade-balconies.geojson
- data/facades/cities/manila/facade-details-report.json

No GLB is produced. Each output is a GeoJSON FeatureCollection with a MultiPolygon feature per source building record. Polygon positions are 3D WGS84 coordinates [longitude, latitude, height_m]; the third ordinate is height above the building ground, not absolute terrain elevation.

## Method and limitations

Retains the Ermita rules: height from height, otherwise levels times 3.2 m, otherwise 9.6 m; windows at approximately 3 m spacing on exterior edges of at least 4.5 m; estimated floor placement; doors centered on edges of at least 7 m (0.95 m wide and 1.9 m high); and deterministic occasional balconies on taller buildings. Generated features are placeholders, not photogrammetric, surveyed, or photo-verified details.

The GitHub Actions workflow validates the canonical source, runs the generator, validates the output collections, and commits the GeoJSON files to main. It does not build game shards or modify the game runtime.
