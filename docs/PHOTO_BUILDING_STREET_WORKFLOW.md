# Photo-to-building and street reconstruction workflow

Status: active execution; three existing Manila Panoramax images visually inspected on 2026-11-04.
Scope: only the three selected photographs and their exact visible buildings/streets. No unrelated imports, workflow edits, refactors, or city-wide processing.

## Non-negotiable rules
- Inspect the actual image, not just metadata or filenames.
- Every photo belongs only to its own photographed place. Never transfer windows, doors, balconies, entrances, signs, materials, or colors between targets.
- Use the Panoramax image UUID, GPS camera position, view azimuth, capture time, and the visible scene together.
- Proximity and camera direction are evidence, not proof of building identity.
- Do not invent OSM IDs, building footprints, street names, or facade dimensions.
- Use existing repository images and source geometry first. Do not initiate another broad import.
- A script may extract source geometry after the human visual review, but it must not guess image-to-building identity or synthesize facade details.

## Three images visually inspected
All three source JPEGs are already committed under `data/facades/cities/manila/facade-review/images/`. Their source metadata is in `data/facades/cities/manila/panoramax-items.geojson`. All are GoPro HERO9 Black captures from 2024-03-10 with CC-BY-SA-4.0.

1. `0cb04844-65a3-418b-beb8-7d6a65915903`
   - Camera: [120.9764015, 14.588054], azimuth 314°, altitude about 47.7 m, horizontal accuracy 4 m.
   - Visible scene: brick-paved narrow pedestrian street; pale pink wall and red-cross sign/gated frontage on the left; large white multi-storey building behind; weathered light-grey multi-storey frontage on the right; tree canopy down the street.
2. `134abd91-1ba0-43d5-b1e5-71bb394f71a7`
   - Camera: [120.9761578, 14.5907033], azimuth 53°, altitude about 47.8 m, horizontal accuracy 4 m.
   - Visible scene: broad paved street; white colonial-style facade with repeated arches and pilasters on the left; long yellow/tan two-storey arcade with repeated arches and dark shopfronts on the right; taller block in the distance.
3. `150e2cd0-7c24-42f3-83c7-71e8f551e1fc`
   - Camera: [120.9755908, 14.5885277], azimuth 38°, altitude about 43.4 m, horizontal accuracy 4 m.
   - Visible scene: long dark weathered masonry wall and old pale stone building on the left; tall white/grey facade on the right; red car parked on the right; covered passage/arcade ahead.

## GeoJSON currently produced
`data/facades/cities/manila/layers/photo-building-street-review.geojson` contains, for each photo:
- the exact Panoramax camera Point;
- a 45 m camera-view-direction LineString derived from the recorded azimuth;
- one short, exact LineString segment selected from the existing local transportation dataset nearest to the camera, with its source feature ID and measured offset;
- the visual observations above and explicit review status.

The road segment is a source-derived locator segment, not yet a confirmed named street centerline. The direction ray is not a road or building geometry.

## Remaining required work (do not mark complete yet)
- Extract the actual building polygon(s) from the canonical `layers/building-layer.geojson` for each visible frontage and retain the source OSM ID. Confirm the correct side/target using the photo, camera position, and azimuth. Do not substitute an unrelated nearby polygon.
- Inspect the local building properties and any existing source images for height, levels, material/color evidence and addresses.
- Add image-space annotations only where the facade element is visible and attributable to that exact building: windows, doors, entrances, balconies, signs, roof edge, facade material/color/texture.
- If the exact footprint cannot be established from the current source, keep the building unresolved and state the missing evidence; do not draw a guessed polygon.
- Validate geometry, coordinate order, feature/source IDs, and image-to-target consistency.

## Completion rule
The task is complete only when all three requested images have visually confirmed building footprints and their own street geometries, plus evidence-backed facade annotations. The current GeoJSON is an inspected evidence-and-street-locator stage, not a completed building reconstruction.
