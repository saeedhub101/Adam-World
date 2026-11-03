# Manila — Google Open Buildings 2.5D Temporal source archive

This document describes the Google Open Buildings 2.5D Temporal raster source stored for Manila. It is a research/source dataset, not the active Manila Local building renderer input.

## Source meaning

The public 2.5D Temporal product provides annual raster bands for 2016–2023, including building presence, building height estimates and fractional building-count signals. The height band is model-derived and must not be described as a surveyed per-building height. Raster values are spatial estimates, not building IDs or facade evidence.

## Runtime status

The current native Manila Local builder reads `data/facades/cities/manila/layers/building-layer.geojson` for building polygons and uses `render_height` from that file via the normalized `adam:render_height_m` tag. The Android loader does not currently read the Google Open Buildings Temporal GeoTIFFs as its building geometry or per-building height source.

Do not merge raster estimates into an individual building unless there is a documented, reproducible spatial attribution and confidence policy. Do not overwrite source-mapped values with model estimates.

## Reproducibility and rights

Preserve the original band files, source URLs, year, tile bounds, CRS, pixel size, transformation history, attribution and licence. Record missing/failed tiles explicitly. A source archive being present does not imply it is packaged in the APK or used by the game.
