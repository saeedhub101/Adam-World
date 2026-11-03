# Manila — Google Open Buildings 2.5D Temporal

This is a complete ingestion of the Google Open Buildings 2.5D Temporal source for Manila, stored separately from and without replacing existing Manila building, street, facade, or PBF data.

## Source contents imported

The public Google Research GeoTIFF distribution has exactly three original raster bands, for annual snapshots 2016–2023:

- `building_presence`: model confidence that a pixel belongs to a building.
- `building_height`: estimated building height above local ground, metres.
- `building_fractional_count`: fractional building-count signal used for estimating counts over areas.

All three original bands are retained in their source GeoTIFF tiles for every year. The importer scans the public Philippines URL lists, checks remote GeoTIFF bounds against the existing Manila boundary, and downloads only intersecting tiles. Annual inventory, scan errors, tile bounds, source URLs and download results are written to `source-inventory.json`. No band is intentionally omitted.

## Spatial resolution and interpretation

The importer preserves the original 0.5 m GeoTIFF files without resampling. Google states that the effective information resolution is approximately 4 m; 0.5 m output pixels must not be interpreted as independent 0.5 m observations.

The presence band is an uncalibrated model-confidence score, not a probability. Height must be interpreted together with presence and is an estimate, not a surveyed measurement. Height accuracy was not quantitatively evaluated in the Global South; buildings are capped at 100 m. Annual results can shift spatially and temporally, and small adjacent buildings may merge. Therefore these rasters are supplemental evidence, not replacements for verified building footprints, official heights, or floor counts.

## Output layout

`data/full-city-imports/manila/03_buildings_google_temporal/`

- `2016/` … `2023/`: tiled three-band GeoTIFF rasters.
- `source-inventory.json`: years, bands, licence, boundary source, tile checks, and per-year summary statistics.

The importer resumes by validating existing tile files. It does not delete existing Manila datasets. A refresh is explicit.

## Reproducibility and licence

- Earth Engine collection: `GOOGLE/Research/open-buildings-temporal/v1`
- Licences: CC BY 4.0 and ODbL 1.0 (choose and comply with the applicable licence).
- Input imagery: Copernicus Sentinel-2.
- Attribution: Google Research Open Buildings; Copernicus Sentinel-2 / ESA.

## Download and checkpoints

The workflow uses Google's publicly hosted GeoTIFF URL lists mirrored in the DEPRIMAP bulk-download index. It does not require an Earth Engine account or API key. For each year, the importer scans the Philippines tile list, reads remote GeoTIFF metadata through HTTP range requests, downloads only tiles intersecting Manila, validates the three bands, and commits each downloaded tile and its provenance sidecar to `main` immediately. Missing, unreadable, or failed source tiles are recorded and prevent the year from being marked complete.
