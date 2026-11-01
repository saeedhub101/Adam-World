# Real-world buildings, roads and ground surfaces

## Current implementation checkpoint — 2026-11-01

The Android game reads its geometry from repository-owned offline city tiles. It
does not fetch building or road geometry from Overpass at runtime.

The renderer now separates mapped OSM surface classes instead of colouring every
park/wood/scrub polygon with one green material. The classes include:

- grass, parks, gardens and meadows;
- woodland and mapped tree cover;
- scrub, heath and moor;
- farmland, orchards, vineyards and plant nurseries;
- sand, beaches and desert;
- bare rock, scree, shingle and exposed ground;
- wetlands, marshes and mud;
- mapped water, reservoirs, basins and riverbank polygons.

Unclassified ground uses a restrained neutral fallback only. It is not described
as satellite imagery or as verified real-world soil. No procedural terrain texture
is used to pretend that a surface has been observed.

## Expanded offline source import

The offline OSM importer now uses profile version 4. It expands the stored query
to include the land-use, land-cover and natural-feature tags listed above, plus
additional water polygons. The importer stores each city's completed source data
in the repository and checkpoints it to main independently. Existing city data
with the previous profile is refreshed once; completed cities are skipped on
subsequent reruns when their profile version and element count validate.

The Android packager consumes the expanded local extract and generates the
spatial tiles. The game continues to load nearby local tiles only.

## Buildings and real facade evidence

Building footprints, IDs, heights/levels and OSM material/colour tags are retained
where supplied. Missing heights still use conservative estimated massing; this is
not a measured building height. The facade image manifest remains the strict gate:
only an exact, human-verified building/facade/photo match with camera direction,
quality and redistribution licence checks may be applied to the corresponding
building surface.

Nearby images, aerial images, catalogue matches and camera-cone candidates are
not proof of identity. Aerial imagery may inform roofs/terrain only when the exact
coverage, date, resolution and licence are checked. Street photos must be
rectified/projected onto the actual facade plane, then stored locally with their
provenance. Detached photo cards and arbitrary image-to-building matches are not
accepted as finished work.

## Remaining work (not yet completed)

1. Inspect the imported v4 terrain/water elements and confirm coverage per city.
2. Acquire licensed, high-resolution georeferenced aerial imagery where available;
   store useful local tiles/checkpoints in the repository and use them as
   orthographic terrain/roof evidence, not as invented colour overlays.
3. Improve elevation/terrain mesh generation from verified local DEM/DSM sources.
4. Review actual street photographs against exact OSM footprints and facade edges.
5. Build independent building meshes/face IDs, bake verified facade projections,
   export textured GLBs and include them in the local game asset package.
6. Validate the result visually against source imagery, checking coordinates,
   orientation, scale, occlusion, material colour and texture stretching.

Do not mark a phase complete merely because metadata was downloaded, a candidate
was found, or a generic material was rendered. Report verified counts and failed
coverage explicitly.
