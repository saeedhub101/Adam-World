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


## Amman terrain and named landmark geometry — 2026-11-01

- Amman now has a locally stored 201 × 201 elevation grid derived from the
  Copernicus DEM GLO-30 COG, sampled over a 6 km × 6 km window centred on the
  Roman Theatre start area. The source resolution is 30 m; the grid reports
  elevations from 705.75 m to 929.27 m in the sampled window. It is a terrain
  surface model, not a building-height model or street-level LiDAR.
- The Android packager copies the elevation grid into the Amman offline asset
  folder. The renderer interpolates elevation for the terrain mesh and conforms
  building bases, road ribbons and mapped land-cover polygons to the local
  height field. Other cities retain their flat local ground until they receive
  their own DEM.
- The Roman Theatre is constructed as a dedicated three-dimensional amphitheatre
  asset in the game scene: 32 curved seating rows, four stair aisles, orchestra,
  stage facade, columns and side retaining wings. Its location is tied to the
  Amman theatre start coordinate. This is a dimensioned procedural reconstruction,
  not a surveyed photogrammetry scan; final matching still requires comparison
  against licensed reference photographs and measured site dimensions.
- Burj Khalifa is handled as a named landmark rather than a generic OSM extrusion.
  Its Y-shaped three-wing massing, 163 floor bands, stepped taper and spire are
  built from its OSM footprint and mapped 828 m height.
- Robinsons Place Manila's OSM retail footprint (way 130532101, 7 levels / 25 m)
  remains the building anchor; the current game adds a mall sign and enhanced
  per-floor facade geometry. No verified street photograph has yet been assigned
  to this exact mall footprint, so its exterior colour/sign placement must not
  be represented as photo-verified.

The Amman offline OSM importer is re-triggered when the city registry radius is
updated to 2.5 km. It checkpoints completed city extracts to main; Android
packaging runs after the offline-city import completes.


## Landmark reference records

- Amman Roman Theatre front view: Wikimedia Commons file
  https://commons.wikimedia.org/wiki/File:Amman_Roman_Theater_-_Front_View.jpg
  by Mervat, photographed 27 September 2024, CC BY-SA 4.0. Used as a visual
  reference for the three seating tiers, stage frontage and stone character;
  it is not projected as a detached image or claimed to be a full 3D scan.
- Robinsons Place Manila from Pedro Gil Street: Wikimedia Commons file
  https://commons.wikimedia.org/wiki/File:Robinsons_Place_Manila_Pedro_Gil.jpg
  by Mike Gonzalez (TheCoffee), photographed 28 March 2010, CC BY-SA 3.0.
  Used as a reference for the mall's pale horizontal facade bands and roofline
  sign. Because it is a perspective photograph from 2010, it is not stretched
  across the current building mesh as a facade texture.
- Current mall location and seven-level complex description are cross-checked
  against the official Robinsons Malls listing:
  https://robinsonsmalls.com/mall-info/robinsons-place-manila
- Burj Khalifa's OSM footprint identifies the exact landmark outline and records
  828 m maximum height, 163 levels and glass construction material. The runtime
  model uses those mapped measurements and a stepped three-wing Y-shaped tower
  form; no unlicensed exterior photograph is embedded.

The above references guide geometry and proportions only. A source image becomes
a facade texture only after the exact building face, camera direction, current
appearance and redistribution licence have all been verified.
