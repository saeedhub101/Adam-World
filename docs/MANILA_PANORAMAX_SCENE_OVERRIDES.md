# Manila Panoramax scene-wide override layer

Date: 2026-11-04
Branch: main
Scope: the first three existing Panoramax images only.

## Purpose

`data/facades/cities/manila/layers/panoramax-scene-overrides.geojson` is the new scene-wide authoring layer. It is intended to augment the existing Manila scene, not replace or duplicate its canonical buildings, footprints, building details, or road geometry.

It records visible scene content across:
- building facade surfaces, appearance, materials and panel/joint cues;
- doors, entrances, gates, arches, columns, openings, shopfronts and awnings;
- roof appearance where actually visible;
- street surface, curbs and sidewalks;
- signs, parked vehicles, trees and overhead street context.

## Source images inspected

All three exact repository JPEGs were opened and inspected at 500 × 375 pixels:

1. `0cb04844-65a3-418b-beb8-7d6a65915903.jpg` — narrow red-brown brick pedestrian street, pink perimeter wall and metal gate, red medical-cross sign, right-hand weathered grey facade, raised concrete sidewalk.
2. `134abd91-1ba0-43d5-b1e5-71bb394f71a7.jpg` — broad grey paved street, white ornate facade and turquoise-trimmed arched entrance on the left, yellow/tan two-storey arcade with repeated arches, columns, dark shopfronts and awnings on the right, yellow/white curb.
3. `150e2cd0-7c24-42f3-83c7-71e8f551e1fc.jpg` — dark rough-stone wall, pale historic masonry and high roofline, low central arched arcade with visible red-brown tiled roof, modern grey panelled facade on the right, grey road and parked red car.

## GeoJSON structure

- Camera anchors are georeferenced Point features with the recorded Panoramax coordinates and azimuth.
- Three existing transportation source segments are retained as georeferenced LineString locators, with their source feature IDs and observed road-surface appearance. These are not road-surface polygons.
- Scene annotations are individual GeoJSON Features with `geometry: null` and `image_region_px` polygons in the exact source image's pixel coordinate system (origin at top-left).
- Each annotation retains its Panoramax photo UUID, source image path, scene element type, visible appearance and any candidate source feature ID.
- The three road source IDs and their source LineStrings are retained as locators only; the nearest source segment is not claimed to be the full road surface polygon.
- Candidate building IDs are explicitly marked unverified. No candidate is authorised for final building modification yet.

## Critical spatial distinction

Pixel-region polygons are traced image annotations, **not geographic polygons**. They must not be interpreted as longitude/latitude, placed on the ground, or sent to the renderer as map geometry. A camera/ground-plane/facade calibration step must project them onto the correct building facade or street/sidewalk surface using the actual target geometry and depth. The source images do not provide camera pitch, and the camera GPS has 4 m horizontal accuracy.

This prevents a false or misplaced edit. In particular:
- Photo 1 candidate `OpenFreeMap|building|id:143300180` is only a ray-intersection candidate.
- Photo 2 candidates `113293080` and `113293070` remain unverified; the second candidate's centroid is outside the camera's recorded horizontal field of view, so it must not be assigned merely because it is nearby.
- Photo 3 candidate `OpenFreeMap|building|id:148859810` is only a ray-intersection candidate.

## Runtime status

The native Manila Local renderer reads compressed offline-city-stream shards. It does not currently load this new GeoJSON. The new file is committed as the scene-wide source annotation layer, but **it is not yet active in-game**. Do not report that the buildings, road, or sidewalks have already changed in the game.

The next implementation must:
1. resolve each annotation to the actual rendered building/road/sidewalk element using source IDs and spatial checks;
2. calibrate image pixels onto the corresponding facade plane or ground surface;
3. produce geographic polygon geometry for each editable surface/element;
4. merge appearance/material overrides into the existing rendered element without duplicating its footprint;
5. add openings, arches, doors, signs and curb/sidewalk details as attached geometry on the target surface;
6. load and spatially filter the override layer in Manila Local;
7. validate the result against all three source photographs and the existing base geometry.

No broad data import, GLB generation, or new building geometry is part of this layer's purpose.
