# Amman and Tabarbour Road Geometry Implementation

This note records the code changes made in the runtime and Amman stream builder. It is an implementation record, not a claim that every mapped road has surveyed measurements.

## Runtime changes

- Replaced the former single wide sidewalk-colored ribbon around selected roads with separate left/right sidewalk meshes.
- Sidewalk widths are read from `sidewalk:left:width`, `sidewalk:right:width`, and `sidewalk:width`. Explicit `sidewalk=no/none/separate` suppresses the corresponding generated sidewalk.
- When source sidewalk metadata is absent, urban street classes use a conservative 1.5 m fallback; this is an estimate, not a measured width.
- Sidewalk surfaces sample the terrain at each mesh edge, include a raised 0.30 m kerb strip and visible kerb face, and register their footprint in the existing vehicle collision grid.
- Traffic-island polygons are now registered in the same collision grid.
- Mapped crossing nodes are projected to the nearest vehicle road path and rendered as zebra stripes across the carriageway.
- Narrow vehicle roads (4.5–6 m) now render into a road buffer rather than the sidewalk buffer.
- Address-only OSM nodes with `addr:housenumber` now receive a visible numbered marker.
- Existing lane, turn-arrow, median, roundabout, signal, road-name, and terrain-sampled road mesh code remains active.

## Amman data builder changes

- The Amman stream builder now includes the already-imported Overture `place` layer from `data/full-city-imports/amman/11_places_addresses/place`.
- School, kindergarten, education, university, college, and academy categories are normalized to school POI tags.
- Existing OSM context filtering now retains school/education/address features and crossing/traffic-signal nodes.
- Road source tags preserve sidewalk widths, kerb/curb information, crossing markings, and crossing-island metadata.

## Data and accuracy rules

- Existing imported datasets are reused; no external data re-download is part of this change.
- Source-measured `width`, `width:carriageway`, `est_width`, and lane-width tags take precedence over estimates.
- If a road has no measured width or lane count, the runtime still needs a fallback. That fallback must never be described as survey-accurate.
- Sidewalk defaults are visual/gameplay fallbacks where source tags are missing; they do not prove a sidewalk exists in the real street.
- Schools and POIs can only be named when the imported source contains their names. Missing source names cannot be invented.
- Road elevation follows the existing terrain height sampler at each mesh vertex. This does not create surveyed retaining walls or cut/fill earthworks where no engineering source geometry exists.
- Crosswalk rendering requires a mapped crossing node; unmapped crossings cannot be inferred reliably from an intersection alone.

## Build and publication

The Android release workflow builds the signed APK and AAB from `main` and uses the existing signing secrets. The Amman stream rebuild workflow reprocesses only the already-committed Amman import and OSM-context files, publishes the regenerated local stream, then requests the Android release workflow.

No test-only workflow is introduced by this implementation.
