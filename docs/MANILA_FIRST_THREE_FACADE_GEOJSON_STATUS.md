# Manila — first three Panoramax facade GeoJSON work log

Date: 2026-11-04
Branch: `main`
Scope: only the first three already-saved Manila Panoramax photographs.
Restrictions followed: no script was executed and no GLB was created.

## Committed output

- GeoJSON: `data/facades/cities/manila/first_three_manila_facade_observations.geojson`
- This is a photo-specific evidence layer. It contains 13 features:
  - 3 Panoramax camera/observation Points;
  - 3 camera view-direction LineStrings;
  - 3 saved source-road LineStrings;
  - 4 isolated building polygon components retained as **unverified candidates**.
- It records visible facade/street observations separately for each image. It does not transfer any facade detail from one photo to another.

## Photo identities and observations

| Sequence | Panoramax ID | Camera [lon, lat] | Azimuth | Visual evidence recorded |
|---:|---|---|---:|---|
| 1 | `0cb04844-65a3-418b-beb8-7d6a65915903` | [120.9764015, 14.588054] | 314° | Pale-pink wall, dark metal gate, red medical-cross sign, weathered light-grey masonry and repeated partly obscured openings; reddish interlocking brick street |
| 2 | `134abd91-1ba0-43d5-b1e5-71bb394f71a7` | [120.9761578, 14.5907033] | 53° | Pale ornate facade and tall arched entrance with turquoise/green trim on left; yellow/cream arcade with repeated arches, columns and dark awnings on right; grey paved carriageway and yellow/white curb |
| 3 | `150e2cd0-7c24-42f3-83c7-71e8f551e1fc` | [120.9755908, 14.5885277] | 38° | Dark rough-stone wall, pale historic stone building and curved/parapet-like roofline; central arched facade with reddish tiled roof; light-grey panelled facade on right; grey paved road |

All coordinates are GeoJSON order `[longitude, latitude]`. Camera accuracy recorded in the review source is 4 m. Azimuth is compass heading, not camera pitch.

## Geometry provenance and selection

1. Camera points, azimuths and view-direction rays were taken from `data/facades/cities/manila/layers/photo-building-street-review.geojson`.
2. The road geometries and their source IDs were taken from the same review file. They are nearest saved road segments, not confirmed named street centerlines.
3. Candidate building geometry was isolated from the already-committed photo-target GeoJSON:
   - Photo 1: source `OpenFreeMap|building|id:143300180`, MultiPolygon component index 22; the recorded view ray intersects this component at approximately 60.1 m, with approximately 0.8 m lateral offset.
   - Photo 2: source `OpenFreeMap|building|id:113293080`, component 26; nearest candidate, approximately 4.07 m lateral offset, but no view-ray intersection.
   - Photo 2: source `OpenFreeMap|building|id:113293070`, component 41; second candidate, approximately 6.58 m lateral offset, but no view-ray intersection.
   - Photo 3: source `OpenFreeMap|building|id:148859810`, component 140; the recorded view ray intersects this component at approximately 70.91 m, with approximately 0.16 m lateral offset.
4. These are isolated polygon components, not whole source MultiPolygons. Their identity as the exact photographed building is still **unverified**. The file marks each one as a candidate and warns against applying facade textures until visual matching is completed.

## Important source inconsistency found

- `data/facades/cities/manila/layers/building-layer.geojson` on `main` was fetched and its content was empty (blob SHA: `5987ad17386684654424b15921dfc241138f54d8`).
- In contrast, `data/offline-city-stream/manila/manifest.json` reports 1,759 building features and 1,748 with height, and lists the building-layer GeoJSON as its source.
- The compressed local runtime shards exist under `data/offline-city-stream/manila/shards/`; the manifest's shard `-1_1.jsonl` is relevant to this area. The game runtime documentation says Manila Local reads the offline-city-stream manifest and compressed shards.
- Therefore the canonical source path and runtime manifest disagree. The four polygons in the new evidence GeoJSON are retained as explicitly unverified candidates from the earlier saved photo-target geometry, not treated as proof that the empty canonical file is healthy.

## What is and is not complete

### Completed in this commit
- Photo-specific camera points and view directions retained.
- Three saved road locator segments retained.
- Visible scene, facade appearance, openings, signs and road-surface observations recorded per photo.
- Four isolated source polygon candidates added with provenance, component index, view-ray evidence and unverified status.
- Work log committed so the next session can resume without repeating the first inspection.

### Not completed — do not mark this task finished
- 0 building footprints are visually confirmed as the exact photographed frontage.
- 0 window, door, entrance, balcony, roof-edge or sign polygons have been digitized.
- No per-building facade texture/material map has been produced.
- No roof color is assigned except the observed reddish tiled roof in photo 3, whose exact building attribution remains unresolved.
- The new GeoJSON is **not yet wired into the Manila Local renderer**. The current game runtime reads compressed offline-city-stream shards; committing an evidence GeoJSON alone does not make the game display it.

## Required continuation order for the remaining images

1. Resolve the empty canonical building-layer versus populated runtime-shard discrepancy using the existing local shard data; do not start a broad import.
2. Compare each candidate polygon component with its exact Panoramax image, camera point, azimuth and street context. Replace candidates with confirmed individual building footprints only when the match is visually supported.
3. For each confirmed footprint, digitize only visible facade elements from that same image: facade boundary/surface, windows, doors, entrances, balconies, columns/arches, signs and roof edge. Keep obscured or unseen elements unknown.
4. Store colors/material appearance as evidence-backed properties. Do not invent RGB values, construction materials, roof colors or dimensions when the photograph does not establish them.
5. Add the observed street surface and roadside details to the correct street geometry, not to the building.
6. Integrate the GeoJSON into Manila Local's existing renderer only after confirming the supported data schema and spatial filtering. Do not create GLB files.
7. Validate feature counts, geometry validity, coordinates, OSM/source IDs, image-to-building matches and on-screen placement.
8. Repeat the same photo-specific process for the next image batch. Never reuse one image's facade details for another building.

## Source files

- `data/facades/cities/manila/layers/photo-building-street-review.geojson`
- `data/facades/cities/manila/first_three_manila_photo_targets.geojson`
- `data/facades/cities/manila/first_three_manila_facade_observations.geojson`
- `data/facades/cities/manila/layers/building-layer.geojson`
- `data/offline-city-stream/manila/manifest.json`
- `docs/PHOTO_BUILDING_STREET_WORKFLOW.md`
- `docs/GAME_RUNTIME.md`
