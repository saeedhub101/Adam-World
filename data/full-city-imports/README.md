# Full-city source imports

This dataset is isolated from the existing ten playable-city datasets. Nothing in this import may write into `data/offline-world/` or `data/facades/cities/`.

## Scope
The 15 requested cities are listed in `catalog.json`. Each city has its own folder and source/layer folders. Resolve the authoritative administrative city polygon before clipping or reporting completion; a search bounding box is not a city boundary.

## Sources and layers
- **Overture Maps:** buildings, building parts, roads/transport, places, addresses, land, land cover, land use, water, infrastructure and divisions. Public cloud GeoParquet/GeoJSON downloads; no paid key.
- **Google Open Buildings:** building footprints and confidence/source attributes where provided. Not facade photographs, doors, windows or guaranteed true heights.
- **OpenAerialMap:** downloadable orthophotos/drone imagery only where catalogued. Preserve date, resolution, footprint and per-item licence.
- **LiDAR:** only directly downloadable, openly licensed point-cloud/elevation datasets covering the city. Record native resolution and vertical datum; never label a coarse DEM as LiDAR.
- **Panoramax:** street-photo metadata and downloadable image assets only where present and individually licensed for redistribution. Preserve camera position, azimuth, date and attribution. Nearby images are not automatically verified facades.
- **HydroSHEDS:** river networks, drainage basins, flow direction/accumulation and related hydrology layers available for the area.
- **JRC Global Surface Water:** occurrence, seasonality, recurrence and transition products where direct downloads cover the area.

## Import rules
1. Resolve and save the city polygon first, including source and administrative level.
2. Import all available layers for the full polygon, not a handful of central tiles. Clip vector/raster products to that polygon with any edge buffer documented.
3. Keep source datasets separate; store normalized cross-source layers in `derived/`.
4. Preserve source IDs, timestamps, original properties, CRS, vertical datums, licences, attribution and checksums.
5. Every layer must be marked `downloaded`, `not_available`, `license_restricted`, `failed_retryable`, or `not_applicable`. Never fabricate missing coverage.
6. Chunk large rasters and point clouds by spatial tile. Save each completed chunk and checkpoint during the workflow. Resume from valid checksums, import only missing/invalid chunks, and continue other sources after a failure.
7. Do not commit imagery or point clouds whose licence forbids redistribution.
8. GitHub has per-file and repository storage limits. Keep chunks below the per-file limit; if total licensed data exceeds repository capacity, report the exact remainder rather than claiming a complete import.

## Current state
This initial commit establishes the isolated registry and per-city layer contract only. No city source data has been downloaded yet. Completion requires verified files, geographic coverage, licences, checksums and validation.
