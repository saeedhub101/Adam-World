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

## Runtime status

The generated `facade-windows-*.geojson` files are retained as archived source outputs only. The Android runtime no longer downloads their `window-shards`, `TileBuilder` does not render their polygons, and the APK workflow does not check out or validate those window files. Building geometry, mapped building colours, and street geometry continue to come from the normal Manila building/transport datasets. No window GeoJSON is included in the APK.
