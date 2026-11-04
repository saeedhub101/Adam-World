# Photo-to-building and street reconstruction workflow

Status: policy and execution specification. Last updated: 2026-11-04.
Scope: Manila photo evidence and the exact buildings/streets represented by that evidence.

## Non-negotiable rules
- Do not start unrelated imports, rebuilds, refactors, workflow changes, or data collection. Work only on the photo/building/street task explicitly requested.
- Do not claim that a photo belongs to a building merely because it is nearby. Inspect the image itself and verify its subject using the image's OSM identifier, coordinates, capture position, camera direction, visible surroundings, and address/street context where available.
- Do not create a script whose only purpose is to guess or automatically associate photos with buildings. Human visual inspection determines the target; GeoJSON records the reviewed result.
- Never invent an OSM ID, footprint, road geometry, address, facade detail, or third location. Unknown values stay null and are marked unresolved.
- Preserve source data and provenance. Do not overwrite source-derived values with guesses or model estimates.
- Use existing repository images and datasets first; do not initiate another broad image/data import.

## Required review for each image
1. Open and visually inspect the actual image.
2. Record its source identifier, original asset reference/path, capture coordinates, capture position/heading if available, and any OSM ID supplied by the source.
3. Identify the visible building or street using distinctive facade/signage/roof/entrance/window/balcony details and surrounding geometry.
4. Match the image to the exact building footprint or road feature. Verify the camera-to-subject direction and ensure the image is not an interior, neighboring building, unrelated street, or wrong viewpoint.
5. If identity is ambiguous, retain the image as unresolved; do not attach it to a guessed feature.

## GeoJSON output
- Produce a FeatureCollection with one feature per verified building and one feature per verified street segment.
- Use the real footprint polygon for a building and the actual road LineString/MultiLineString for a street, sourced from existing repository geometry or explicitly verified source geometry. A Point may be used only as a clearly labelled evidence/locator anchor, never represented as a building footprint.
- Preserve source IDs and add review metadata: `review_status`, `image_ids`, `osm_id`, `street_name`, `building_name`, `evidence`, `facade_observations`, `confidence`, and `unresolved_fields`.
- Do not fabricate polygon or line geometry from a point coordinate. Keep unavailable geometry null and explain why.
- Windows, doors, entrances, balconies, signs, roof, facade material, color and texture must be recorded only when visible and attributable to the exact target. Each image applies only to its own verified target.

## Building reconstruction
- Aerial imagery may establish roof position, roof shape and broad color only.
- Street-level facade imagery may establish facade materials, color, windows, doors, entrances, balconies, signage and texture only after exact-building and camera-direction verification.
- Apply the resulting facade details to the surfaces of that building's own mesh; do not add detached/random overlay geometry or transfer details between buildings.
- Keep building footprint, height and floors source-derived where possible. Record estimates separately with method and confidence; never silently replace measured/source values.

## Validation and completion
- Validate GeoJSON syntax, geometry type, coordinate order (longitude, latitude), feature identity, source provenance and duplicate targets.
- Compare each output feature against the source image and source geometry. Report verified, ambiguous and missing targets separately.
- Mark the task complete only when each requested image has a reviewed target, each target has correct geometry, and the resulting facade/building data passes visual inspection.
- Record exact files changed, feature counts, unresolved items and next action here or in the task log. Resume from the last completed review; do not repeat completed work.

## Known targets recovered from prior project context (not a claim that three images are currently available)
1. Alkhaleej International Services Inc. — commercial two-storey building; Malate; Roxas Boulevard corner Quirino Street; saved anchor coordinate longitude 120.9818584, latitude 14.5728234; prior footprint reference `footprints-feature-000342`.
2. Commercial two-storey building — HXGP Vasquez St, Barangay 696, Malate, Manila; saved anchor coordinate longitude 120.9871317, latitude 14.5750948; prior footprint reference `footprints-feature-000343`.
3. Third target — no verified image, location, OSM ID or coordinates recovered in the available prior context. Must remain unresolved until the actual third image/identifier is available.

The two saved coordinates above are evidence anchors only. They are not substitutes for footprint polygons or road centerlines. No OSM IDs are confirmed in the recovered context.
