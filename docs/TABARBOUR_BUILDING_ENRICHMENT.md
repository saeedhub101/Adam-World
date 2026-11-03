# Tabarbour building heights, floors and entrance addresses

The Tabarbour import enriches each real building footprint from independent
sources. The Android runtime receives only the compact spatial tiles; source
GeoJSON/GeoPackage files remain in the repository and are not packaged in the APK.

## Sources queried

1. **OpenStreetMap / Overpass** — explicit `height`, `building:levels`,
   `building:part`, names and `addr:*` tags when contributors mapped them.
2. **OpenFreeMap / OpenMapTiles** — primary building footprints and rendering
   attributes. Its `render_height` is not treated as a surveyed height because
   it can be a generic cartographic fallback.
3. **Overture Maps** — building and building-part geometry, explicit height,
   floor count, names, address attributes and per-feature source provenance.
   Overture conflates upstream OpenStreetMap, Google Open Buildings and
   Microsoft Global ML Building Footprints data; its `sources` metadata is
   retained on normalized building tags.
4. **OpenBuildingMap (GFZ)** — the level-6 Quadkey tile covering Tabarbour.
   It combines OSM, Google Open Buildings and Microsoft building footprints,
   and supplies the GEM `height` taxonomy enriched using GHSL 2023A.
   `HHT:x` is decoded as height in metres, `H:x` as storeys, and `HBET`
   ranges as estimated storeys. Source dataset identity (OSM/Google/Microsoft)
   and the original taxonomy string are retained.

Google Open Buildings 2.5D Temporal is not a Tabarbour/Jordan source: Google's
published country coverage does not include Jordan. Microsoft global ML building
footprints are useful for missing footprint detection, but are not themselves
a per-building floor survey. Overture and OpenBuildingMap provide the practical
multi-source conflation/height enrichment paths for this area.

## Matching and trust rules

- Exact OSM object identity is preferred when a source provides it.
- Otherwise, a conservative footprint-centroid/area match is used; ambiguous
  neighbours are not joined.
- Address points are attached when they fall inside a footprint. Entrance
  points just outside the footprint are attached only when one building is
  uniquely nearest within 4 metres.
- Source attributes fill missing values; explicit OSM values are not silently
  overwritten by model estimates.
- A provider `render_height` is not treated as a real building height.
- A measured/source height is used for extrusion. A source floor count is shown
  separately. When no source supplies either value, the renderer retains a
  visibly approximate fallback rather than claiming a survey measurement.
- House numbers from `addr:housenumber` / Overture address `number` and
  available names are rendered on the building label. A missing official
  municipal entrance number is left blank; no number is invented.

## Jordan municipal building numbers

Greater Amman Municipality's Amman Explorer service has been publicly described
as supporting address lookup by street name and building number. That confirms
the municipal address system exists, but it does not establish an open bulk
download/API for the Tabarbour building-number register. The current importer
therefore uses published OSM and Overture address records only. An official
GAM feed can be added if a reusable public endpoint or licensed export is made
available; cadastral parcel numbers must not be mistaken for building entrance
numbers.

## Outputs

- `overture-building-details.geojson`
- `overture-building-parts.geojson`
- `overture-address-points.geojson`
- `openbuildingmap-buildings.geojson`
- Final spatial tiles under `app/src/main/assets/cities/tabarbour/`, fetched
  online by the app rather than embedded in the APK.

Every source stage is independently retryable. Overture/OpenBuildingMap failure
does not block the existing OSM/OpenFreeMap stages, and the final tile build
uses whichever verified source files were successfully saved.
