# Imported Adam-City reference datasets — provenance and limits

This directory preserves selected source-backed assets from `saeedhub101/Adam-City`. It is a reference archive, not an instruction to load Manila data into Amman or to treat candidate evidence as verified.

## Manila / Ermita building reference data

- `ermita-building-details.geojson` and `ermita-building-details.csv`: OpenStreetMap contributor data. The GeoJSON contains OSM element IDs, raw tags and geometry (points as well as polygons); the CSV is a tabular export of OSM tags including building levels, explicitly mapped heights, roof shape/material/colour, building material/colour and addresses. Source: https://www.openstreetmap.org/copyright . Licence: ODbL 1.0; preserve attribution and comply with share-alike obligations.
- `building-evidence-links.csv`: candidate image/source links and observed appearance notes. Some source photos are Wikimedia Commons under CC BY-SA 4.0; each row retains its photo URL/licence. Match status is provisional, not a verified texture assignment.
- `landmark-facade-study.json`: named landmark research and architectural descriptions linked to official/institutional/Wikimedia references. Its declared status is `architectural_reference_study_complete_geometry_match_pending`; do not attach a facade profile to a footprint until identity, position and orientation are checked.
- `facade-details-report.json` and `facade-signage-index.csv`: retained only as an audit/example of the old procedural output. The report explicitly says facade colours, windows, frames, sills, balconies, doors and signs are approximations/placeholders, not surveyed or photo-matched features. The 89–91 MB procedural GLB and its generator were deliberately excluded.

These Manila datasets are not active inputs for Amman/Jordan. Their value is source schema, OSM attribute examples and the documented evidence-linking workflow.

## Rizal Park / Luneta source data

Official source pages:
- NPDC downloads: https://npdc.gov.ph/downloads/
- NPDC Rizal Park: https://npdc.gov.ph/rizal-park/
- NPDC tree inventory: https://npdc.gov.ph/inventory-of-trees/
- NPDC tree species list: https://npdc.gov.ph/inventory-of-trees-2/
- 2024 NPDC technical map: https://npdc.gov.ph/wp-content/uploads/2024/05/Rizal-Park-Technical-Map-scaled.jpg
- NPDC tree inventory sheet: https://docs.google.com/spreadsheets/d/1WfMFbHG9Tam6MtL6X1YYRAQ0zvMP2Upn/edit
- 2024 tree distribution map: https://npdc.gov.ph/wp-content/uploads/2025/02/Inventory-Of-Trees.pdf
- 2025 GIS distribution/thematic report: https://npdc.gov.ph/wp-content/uploads/2025/02/GIS-Group_Distribution-and-Thematic-Mapping-Report.pdf

The 2024 CSV export currently preserved here has only 30 data rows; it is a partial export, not a complete park-wide inventory. The coordinate-normalization file preserves raw DMS strings and source-row JSON alongside decimal-degree conversions. The park feature index transcribes labels from the NPDC technical map; most entries do not yet have extracted coordinates. The tree-name index is a common-name/category transcription, not a complete taxonomic authority; scientific names remain blank unless independently verified.

## Exclusions and use policy

- No unverified KartaView candidate photo is being treated as a confirmed facade.
- No procedural windows, doors, balconies, signage or colours are being presented as real architectural measurements.
- No old/ambiguous Microsoft footprint layer or overwritten OSM/Basemap file was imported as canonical footprint geometry.
- Keep the Manila/Rizal Park datasets in their regional folder; do not merge their coordinates into other cities.
- Preserve source IDs, dates, URLs, licences and transformation notes. Validate geometry and coordinates before runtime use.
