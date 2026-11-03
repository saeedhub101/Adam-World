# Full-city source import archive

This is a separate multi-city source-import project. Its city/layer folders and per-layer `manifest.json` files are acquisition and validation records. They are not the active Android city registry and do not, by themselves, make those cities playable.

## Scope and separation

The requested source-import cities are listed in `catalog.json`. Keep this archive separate from the current local runtime datasets under `data/offline-city-stream/` and `data/offline-world/`. Do not overwrite a working runtime dataset as a side effect of an unrelated full-city import.

## Source families

The import project may retain Overture buildings/building parts, transport, places, addresses, land, land cover, land use, water and infrastructure; Google/Microsoft footprint sources; aerial imagery metadata; Panoramax metadata; LiDAR/elevation products; hydrology and surface-water products where available and redistributable.

## Manifest meaning

A per-layer manifest records the state of that source/layer import, such as downloaded, unavailable, licence-restricted, retryable failure or not applicable. It may also record chunks, byte sizes, checksums, source URLs, attribution and licence. It is not a runtime tile manifest and must not be substituted for the Amman, Manila or Tabarbour runtime manifests.

## Import rules

1. Resolve the authoritative area boundary before claiming full-city coverage.
2. Preserve source IDs, timestamps, original properties, CRS, vertical datum, licences, attribution and checksums.
3. Keep source datasets separate; put normalized cross-source products in `derived/`.
4. Chunk large GeoJSON outputs below 35 MiB and checkpoint each part.
5. Resume verified parts rather than downloading them again.
6. Never fabricate coverage, height, facade evidence or licence permission.
7. Keep imagery/point clouds out of the repository when redistribution is not allowed.

Check each city's current manifest before describing its import as complete. Repository presence alone is not evidence that all requested layers are available.
