# Adam World — city realism rebuild checkpoint

Last updated: 2026-11-01
Branch: `main`

This document records what is implemented, what is running, and what must not be
described as complete yet. The game must use repository-owned local assets at
runtime; source discovery/build workflows may use free public datasets, but no
paid API or runtime remote map/model dependency is introduced.

## Confirmed repository baseline

- Ten playable city destinations are registered in `data/facades/cities/catalog.json`:
  Amman, Manila, Bangkok, New York, Paris, Dubai, Tokyo, London, Riyadh and Cairo.
- All ten have committed full OSM extracts under
  `data/offline-world/<city>/elements.json`.
- Amman already has a Copernicus DEM GLO-30 grid at
  `data/facades/cities/amman/elevation-grid.json`.
- The other nine cities initially lacked elevation grids; all nine were generated and committed during this rebuild.
- The verified facade manifest is intentionally empty. The existing facade
  workflow may discover candidate photographs, but proximity is not proof that a
  photograph depicts a particular building.
- The Android runtime contains procedural OSM-derived building architecture,
  including window openings/glazing, sills, trim and conditional balconies. These
  are generated from each city's OSM building footprints; they are not verified
  photographic facade reconstructions.

## Changes committed in this rebuild

| Area | Implementation | State |
|---|---|---|
| Terrain sampling | DEM triangle sampling matches the rendered terrain triangles; missing DEM samples use a bounded nearest-valid fallback. | Implemented |
| Terrain outside DEM | Clamp sampling to the nearest DEM edge rather than returning zero elevation. This prevents objects from snapping to sea level outside the raster bounds. | Implemented |
| Building placement | Building facade/roof vertices sample terrain at their own horizontal coordinates. | Existing implementation retained |
| Roads and paths | Road ribbons sample terrain at every generated vertex. | Existing implementation retained |
| Agents | Player, NPCs and vehicles sample local terrain height each frame. | Existing implementation retained |
| Street names | Increased bold label size and width; labels sit at the sampled local terrain elevation. | Implemented |
| Roman Theatre | Added a generated limestone block/grain texture and UVs to the existing cavea, seating rows, diazomata, stair aisles and stage masonry. | Improved; not survey/photo exact |
| Rivers and streams | OSM linear waterway features are rendered as terrain-following water ribbons and recorded for water interaction. | Implemented |
| Water polygons | OSM water bodies are rendered and recorded for water interaction. | Implemented |
| Water appearance | Added a tiled moving surface pattern to the water material. | Implemented |
| Swimming interaction | Player movement slows in mapped water and the rig enters a simple paddling pose. | Basic interaction implemented |
| Waterfalls | OSM features explicitly tagged `waterway=waterfall` or `natural=waterfall` produce a vertical cascade and pool. No waterfall is fabricated where OSM has no feature. | Implemented for mapped nodes |
| Traffic lights | Mapped signal nodes now cycle green/amber/red. | Implemented |
| Vehicle response | NPC vehicles brake when an active signal is ahead on their road approach and resume on green. | Basic signal response implemented |

Relevant implementation commits:
- `5f0b8e7625cb0795ea12431867f405f419c1da9c` — DEM sampling and larger terrain-aware street labels.
- `fb58f6141d5370c476662810556a53371f834664` — linear waterway rendering.
- `e09e4f608ffd5856eae097aa27bb1792434d1c20` — water polygon ring handling.
- `2de98a1cd15358784affac53685b5fe8a6305700` — limestone theatre texture, water interaction, traffic signal cycles and mapped waterfalls.
- `7a845c8bbc15ec8b57941029502112ee92398773` — car road-width data for signal approach detection.

## All-city elevation import — completed

A free, no-key Copernicus DEM importer and GitHub Actions workflow have been added:

- `tools/import_copernicus_terrain.py`
- `.github/workflows/copernicus-terrain-import.yml`

The workflow generates a 201 × 201 grid per city, samples a 30 m DEM over a
3 km radius, rejects grids below 98% coverage, commits each city independently,
and skips valid existing checkpoints on rerun. It uses public Copernicus COG
objects, not a paid API.

Run started by commit:
`b42abff9be044824f5b202bdceaab730c282b7ca`

GitHub Actions run:
https://github.com/saeedhub101/Adam-World/actions/runs/41344282260

**Do not mark the nine missing grids complete until that run has committed them
and the validation step reports all ten cities.** If a tile/source fails, the
workflow continues to the next city and a rerun resumes from valid saved grids.

## Work still required before claiming the requested final realism

1. **Roman Theatre exact match:** the current improvement is procedural limestone
   masonry, not a photographically matched stone texture or a surveyed exact
   reconstruction. The final pass still needs verified reference imagery and
   dimensional comparison. The model must preserve the documented cavea, three
   seating levels, orchestra, stage, side entrances and access aisles.
2. **Photographic facades for all cities:** keep the strict building-identity,
   camera-direction, image-quality and redistribution-licence checks in
   `docs/FACADE_RECONSTRUCTION_PIPELINE.md`. No candidate may be attached to a
   building just because it is nearby. Until verified evidence is available, the
   game shows procedural OSM architecture, not real matching facade photographs.
3. **Vehicles and pedestrians:** current NPCs are still procedural Three.js
   geometry. Traffic signal behaviour is basic, not a full road-rule simulation.
   Replace these with multiple licensed local 3D models and animation clips,
   verify their scale/orientation, and keep all runtime assets inside the APK.
4. **Water completeness:** the game now renders and interacts with mapped OSM
   water features. It cannot display a real sea, lake, river or waterfall where
   the committed OSM extract contains no corresponding geometry. Expand/verify
   source coverage for the actual playable city windows before claiming all water
   bodies exist.
5. **Visual and release validation:** run the Android build after the terrain
   checkpoints, inspect Amman slopes and the Roman Theatre in-game, test red/amber
   signal stopping and swimming, then publish and verify the resulting APK. A
   source-code commit or an in-progress Actions run is not proof of a successful
   playable build.

## Roman Theatre reference facts

The Jordan Department of Antiquities identifies the Amman Roman Theatre as a
2nd-century Roman structure for about 6,000 spectators, with cavea, semicircular
orchestra, stage platform, aditus maximus and scaenae. It records three seating
levels and restoration beginning in 1957. Use the official record as the
architectural checklist, not as proof that the current procedural mesh is exact:

- https://www.doa.gov.jo/EN/ListDetails/Archeological_Sites/1157/2
- https://www.mota.gov.jo/EN/Pages/Amman

## Acceptance checks

- [ ] All ten cities have valid committed elevation grids and appear in APK city manifests.
- [ ] On sloped Amman roads, road surfaces, building bases, player, NPCs and vehicles remain on the DEM surface with no y=0 drop.
- [ ] The Roman Theatre is visually compared with official reference photos from front, side and upper seating views.
- [ ] Facade photos are assigned only to the exact verified OSM building/facade and baked into that building's GLB/local mesh.
- [ ] Water surfaces are only shown where mapped source geometry exists; player swimming works in mapped water.
- [ ] Traffic lights change state and vehicles stop at red/amber approaches and resume at green.
- [ ] Multiple locally bundled vehicle and pedestrian models have distinct silhouettes, textures and animation.
- [ ] Android APK build, archive validation and signature verification succeed.
