# Offline real 3D assets — Adam World

## Imported source assets

The repository now contains local GLB files under `app/src/main/assets/models/`. The Android package vendors Three.js r128's GLTFLoader and reads these models from the APK; no model is streamed from a third-party URL at runtime.

The initial imported set is from Innerscene's Free Parts Library. Each selected model page declares CC0-1.0 and provides a GLB download:

| Asset | Local file | Use / limitation |
|---|---|---|
| Adult woman | `people/adult-woman.glb` | Textured standing figure; static |
| Adult man | `people/adult-man.glb` | Textured standing figure; static |
| Child boy | `people/child-boy.glb` | Textured standing figure; static |
| Child girl | `people/child-girl.glb` | Textured standing figure; static |
| Animated adult man | `people/adult-man-walk.glb` | Rigged, seven looping locomotion clips (idle/walk/run etc.) |
| Animated adult woman | `people/adult-woman-walk.glb` | Rigged, seven looping locomotion clips (idle/walk/run etc.) |
| Dog | `animals/dog.glb` | Textured static dog |
| Sedan | `vehicles/sedan.glb` | Unbranded sedan exterior |
| Pickup | `vehicles/pickup.glb` | Unbranded pickup exterior |
| City bus | `vehicles/city-bus.glb` | Exterior bus asset; route integration still required |
| Metro car | `vehicles/metro-car.glb` | Rail vehicle asset; mapped rail geometry and route integration still required |
| Motorcycle | `vehicles/motorcycle.glb` | Detailed sport motorcycle |
| Water taxi | `vehicles/water-taxi.glb` | Boat asset; mapped navigable water and dock integration still required |

Source pages:
- [Adult woman](https://www.innerscene.com/tools/library/3d-parts/amara-3d-person-standing-office-2a16c294)
- [Adult man](https://www.innerscene.com/tools/library/3d-parts/folded-arms-3d-person-standing-b4b904f5)
- [Boy](https://www.innerscene.com/tools/library/3d-parts/leo-3d-person-child-standing-88a69d44)
- [Girl](https://www.innerscene.com/tools/library/3d-parts/maya-3d-person-child-standing-bace98fd)
- [Dog](https://www.innerscene.com/tools/library/3d-parts/standing-dog-bf156da2)
- [Sedan](https://www.innerscene.com/tools/library/3d-parts/sedan-car-42109739)
- [Pickup](https://www.innerscene.com/tools/library/3d-parts/pickup-truck-da45aa3f)
- [Bus](https://www.innerscene.com/tools/library/3d-parts/low-floor-city-bus-9e7ce899)
- [Metro car](https://www.innerscene.com/tools/library/3d-parts/metro-car-22a0c19c)
- [Motorcycle](https://www.innerscene.com/tools/library/3d-parts/race-replica-superbike-fully-faired-badbd83b)
- [Water taxi](https://www.innerscene.com/tools/library/3d-parts/water-taxi-boat-925a8345)

## Runtime status

- Road traffic now loads sedan, pickup and motorcycle GLBs from local assets.
- The player vehicle uses the local sedan GLB.
- Rigged adult man and woman GLBs use embedded looping walk clips; instances are independently cloned with SkeletonUtils and advanced by AnimationMixer.
- Static adult and child GLB figures and static dogs are also placed beside mapped roads.
- GLB geometry and textures are reused between instances; city-stream cleanup does not dispose shared source geometry.
- Adult male/female walking animations are imported from CC0 models and play in place while the character follows mapped pedestrian paths.
- Child boy/girl models remain static standing figures because a suitable free, redistributable animated child GLB has not yet been verified.
- Bus, metro and water-taxi files are imported, but they are not yet spawned as moving public transport. That requires matching mapped bus/rail/water routes.

## City-specific work still required

`app/src/main/assets/models/manifest.json` records the per-city profile and pending assets. Profiles currently select common road vehicle classes, but do not claim that all cities have a complete locally representative fleet.

- **Amman:** old, worn local sedans and taxis have not yet been sourced; current sedan/pickup/motorcycle assets are generic.
- **Manila:** jeepney and Philippine sidecar tricycle are not yet imported. A candidate Jeepney page was found, but the downloadable model and redistribution package were not verified. Paid/editorial tricycle models and workshop-only assets were rejected.
- **Bangkok:** a verified local tuk-tuk asset has not yet been imported.
- All cities: add more real, licensed vehicle variants, women/men/children in varied everyday clothing, and more than the current two adult locomotion models.

## Resumable import

Run `.github/workflows/free-3d-asset-import.yml` or re-run it after an interruption. `tools/import_free_3d_assets.py` validates the GLB v2 header and exact file length, skips existing valid files, continues after an individual download failure, and pushes each completed category to `main`. Missing assets are retried on the next run.
