# City facade and building source inputs

This directory stores city-scoped source datasets, vector-tile exports and facade-evidence metadata. It is not a universal runtime registry: each city's active game path must be confirmed in the native Android loader and its builder.

## Manila

The active Manila building pipeline reads `manila/layers/building-layer.geojson`. Road geometry and street names are read from `manila/transportation.geojson`. The current runtime builder does not merge the older `buildings.geojson`, `building-footprints.geojson` and `building-details.geojson` into the active Manila building shards. See `docs/MANILA_OFFLINE_DATA.md`.

## Facade evidence

Panoramax and aerial catalogue files are evidence candidates, not verified assignments. Preserve photo ID, capture position, camera direction, date, asset URL, licence, attribution and source hash. A nearby image is not proof of building identity.

No photo may be assigned to a facade until the exact building, facade side, camera calibration, image quality and redistribution rights are verified. The current native Android renderer does not yet consume a verified photo-texture manifest.

## General source rules

- Preserve original IDs and properties; do not fabricate names, addresses, heights or architectural details.
- Keep source data separate from derived/review outputs.
- Record unavailable or restricted sources rather than silently substituting another dataset.
- Chunk large generated outputs and checkpoint them during production.
- Do not claim that a city folder or evidence queue is active in gameplay merely because it exists.
