# Player character and pedestrians — native implementation

## Player

The player is rendered by the native OpenGL ES renderer in `CityRenderer.java`. The head mesh is loaded from `app/src/main/assets/kid_head.bin` by `KidHead.java`. The body and articulated parts are generated as procedural meshes using `ProceduralHumanRig.java` and the character draw/update methods in `CityRenderer`.

This is not the old Three.js character page. The current native rig is assembled from separate meshes and animated through model transforms; it is not a Three.js skeleton with morph-target blendshapes.

## Pedestrians

`ProceduralHumanRig` builds six visual variants with separate body-part meshes and different skin/clothing palettes. `Game` updates pedestrian agents and their walking phase; `CityRenderer` applies limb transforms during drawing.

## Current limitations

- The character is a procedural game model, not a photoreal or likeness-accurate scanned person.
- The current native code does not expose the historical JavaScript expression/viseme API described by the legacy HTML client.
- Do not claim facial blendshapes, a full skeletal animation system, or imported animated GLB characters in the native app unless those features are added and verified in native code.
- The head asset is a local binary mesh; its presence does not imply a full externally rigged character asset.

## Relevant source files

- `KidHead.java`
- `ProceduralHumanRig.java`
- `ProceduralHumanModels.java`
- `CityRenderer.java`
- `Game.java`
