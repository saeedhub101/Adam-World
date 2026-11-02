# Original per-city building source imports

The import pipeline is designed for the ten registered Adam World destination areas:
Amman, Manila, Bangkok, New York, Paris, Dubai, Tokyo, London, Riyadh and Cairo.
Each destination is bounded by its own `data/facades/cities/<slug>/city.json`
centre and `discovery_radius_m`; these are playable discovery windows, not complete
municipal boundaries.

## Independent source files

For every city, the source importer writes:

- `building-footprints.geojson` — building polygons and the published render
  height/min-height/colour fields from OpenFreeMap's OpenMapTiles vector tiles
  (building layer, zoom 14). Geometry is decoded from PBF vector tiles, converted
  from tile coordinates to WGS84, and fragments sharing a source feature ID are
  merged. Source attribution: OpenFreeMap © OpenMapTiles; data from OpenStreetMap.
- `building-details.geojson` — OSM building and building-part geometries with
  their original available tags, fetched directly from public Overpass instances.
  This includes tags such as `height`, `building:levels`, `building:material`,
  `building:colour`, `roof:*`, `addr:*`, `name`, `building:part` and other
  tags only where OSM contributors have mapped them. Missing tags are not guessed.
- `buildings.geojson` — the existing OSM footprint file used by the current
  photo-to-building review tooling. It is retained for compatibility and is not
  a replacement for either source file above.

## Facade evidence versus a finished facade

There is no universal ready-made GeoJSON layer that supplies the exact facade,
windows, doors, balconies, materials and signs for every building. The original
facade evidence source used by this project is Panoramax street-level photography.
The existing catalogue importer saves its per-city records in
`panoramax-items.geojson`; OpenAerialMap records are saved separately in
`aerial-items.geojson` and may help with roof shape/colour, not facade texture.

The review queue links candidate photographs to nearby OSM building edges using
camera location and azimuth. That is discovery only. A photo becomes facade
geometry/texture only after exact building identity, facade direction, image
quality and redistribution licence are visually verified. The verified manifest
then drives local GLB texture baking. The procedural window/door/balcony geometry
from the old Ermita script is an approximation, not imported real facade data,
and must never be labelled as surveyed detail.

## Running and resume behaviour

Workflow: `.github/workflows/city-building-sources-import.yml`.

- Runs on `main`; can be manually refreshed for all cities or one city slug.
- Writes each city's two source files atomically.
- Commits and pushes a completed city before moving to the next.
- Keeps a valid file when its source bounds have not changed; `refresh` requests
  new source data.
- A failed source or city does not stop later stages/cities; the final step reports
  failures rather than silently claiming full success.
- No API key or paid service is used. Runtime uses only repository-owned assets.

## Licences and attribution

OpenStreetMap-derived data is ODbL 1.0 and requires attribution; OpenFreeMap/
OpenMapTiles attribution is retained with the footprint dataset. Panoramax image
licences are evaluated per image; non-redistributable or unclear licences must be
rejected. Do not bake a photo into a redistributable GLB unless its licence allows
that reuse and the required attribution is preserved.
