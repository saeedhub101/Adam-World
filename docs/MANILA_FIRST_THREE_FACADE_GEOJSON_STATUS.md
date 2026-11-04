# Manila — first three Panoramax scene reconstruction status

Date: 2026-11-04
Branch: `main`

## Correct scene model

The existing Manila building and road geometry remains the base scene. The requested new source is a **scene-wide override layer** that augments the existing elements: facade appearance/material/color, facade shape details and openings, doors, gates, entrances, arches, columns, balconies, signs, roof details, road surface, curbs and sidewalks.

Do not replace or duplicate the canonical buildings or road geometry. The overlay must bind edits to existing target IDs and add only attached detail geometry where supported.

## New committed annotation layer

- `data/facades/cities/manila/layers/panoramax-scene-overrides.geojson`
- `docs/MANILA_PANORAMAX_SCENE_OVERRIDES.md`

The new GeoJSON contains the first three saved Panoramax camera anchors and 19 photo-specific scene annotations. It records the full visible scene classes across the photos, including building facades, entrances/gates, openings/arches, shopfronts/awnings, signage, roof appearance, road surfaces, sidewalks, curbs, wall surfaces and street context.

Each annotation is tied to its exact Panoramax UUID. Its `image_region_px` is a manually traced polygon in the 500×375 source image coordinate system. These are image-space masks, not longitude/latitude polygons.

## Important limitations — not complete yet

- The image-space annotations have not yet been projected onto geographic facade/road/sidewalk planes.
- No candidate building ID is yet human-verified as the exact photographed frontage.
- Candidate IDs for photos 1 and 3 intersect the recorded view ray but remain candidates, not confirmed identities.
- Photo 2 candidate IDs remain unverified; candidate 113293070 has a centroid outside the camera's recorded horizontal field of view.
- The nearest road source segments are locators, not full street-surface or sidewalk polygons.
- The new layer is registered in `facade-sources.json`, but `runtime_enabled` is false. Manila Local does not yet load or render this GeoJSON.

## Next required implementation

1. Resolve the exact existing rendered building, road, curb and sidewalk targets using source IDs and image/camera geometry.
2. Calibrate each photo's perspective against the actual source footprints and scene geometry.
3. Convert image-space masks into geographic facade/surface polygons; retain per-element IDs and source image provenance.
4. Apply color/material/shape overrides to the existing base elements and attach opening/sign/arch/column geometry without duplicating footprints.
5. Add a spatially filtered loader and renderer path for the scene override GeoJSON.
6. Validate on-screen alignment against all three exact Panoramax images.

No new broad imports or GLB generation are required. Do not mark the scene reconstruction complete until the geographic geometry and runtime rendering steps pass validation.
