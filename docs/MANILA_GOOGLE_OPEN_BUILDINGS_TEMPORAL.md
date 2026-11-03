# Manila — Google Open Buildings 2.5D Temporal

This is a complete ingestion of the Google Open Buildings 2.5D Temporal source for Manila, stored separately from and without replacing existing Manila building, street, facade, or PBF data.

## Source contents imported

The Earth Engine collection `GOOGLE/Research/open-buildings-temporal/v1` has exactly three original raster bands, for annual snapshots 2016–2023:

- `building_presence`: model confidence that a pixel belongs to a building.
- `building_height`: estimated building height above local ground, metres.
- `building_fractional_count`: fractional building-count signal used for estimating counts over areas.

All three bands are retained together in GeoTIFF tiles for every year. Annual inventory and derived statistics are written to `source-inventory.json`. No band is intentionally omitted.

## Spatial resolution and interpretation

Exports use 4 m pixel spacing, matching the dataset's stated effective information resolution. Google publishes the rasters on a 0.5 m grid, but explicitly says the effective resolution is approximately 4 m; 0.5 m output pixels must not be interpreted as independent 0.5 m observations.

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

## Workflow authentication

GitHub Actions must have repository secrets `GEE_SERVICE_ACCOUNT_JSON` (service-account key JSON) and `GEE_PROJECT_ID` (Earth Engine-enabled Google Cloud project ID). The workflow fails clearly if these are absent; it does not report an import as successful without authentication and validated rasters.
