# Rizal Park (Luneta) — research and source inventory

This folder is a regional research archive for Rizal Park in Manila. It is not a standalone game scene and its files are not all consumed by the active Android renderer.

## Official source references

- NPDC downloads: https://npdc.gov.ph/downloads/
- NPDC Rizal Park: https://npdc.gov.ph/rizal-park/
- NPDC tree inventory: https://npdc.gov.ph/inventory-of-trees/
- NPDC tree species listings: https://npdc.gov.ph/inventory-of-trees-2/

## Feature groups

Keep mapped park boundaries, named sections, monuments, gardens, paths, water features, tree inventory and other points as separate source-backed feature groups. Preserve the original row/reference ID, coordinate text, scientific/common name and source URL.

## Validation

- Do not turn a park-wide count into exact point locations.
- Keep source coordinates separate from derived decimal-degree coordinates.
- Reject malformed/out-of-park coordinates for map placement, but preserve the original source row for review.
- A missing coordinate is unknown, not a guessed centroid.
- A partial inventory must remain labelled partial.

## Runtime boundary

The active game may load the packaged Rizal Park tree inventory through its explicit asset path. That does not make every research note, landmark list or source PDF an active runtime layer.
