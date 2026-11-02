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
6. Chunk every large Overture GeoJSON layer into valid FeatureCollection files under 35 MiB (`part-00001.geojson`, etc.) with a per-layer `manifest.json`. Checkpoint each part immediately. On resume, retain and skip verified parts, continue from the next part, and skip a layer only when its manifest is complete and every recorded size matches. Temporary full downloads stay untracked and are deleted after processing.
7. Never commit a full-city GeoJSON layer as one file. Keep each committed part below 35 MiB (well below GitHub's 100 MB hard limit); compression is optional and is not relied on for compliance.
8. Do not commit imagery or point clouds whose licence forbids redistribution.
9. GitHub has per-file and repository storage limits. If total licensed data exceeds repository capacity, report the exact remainder rather than claiming a complete import.

## Current state
This initial commit establishes the isolated registry and per-city layer contract only. No city source data has been downloaded yet. Completion requires verified files, geographic coverage, licences, checksums and validation.
