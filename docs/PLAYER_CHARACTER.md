# Adam World playable character

## Current integration

The playable character in `app/src/main/assets/index.html` is now a dedicated Adam-style child character, separate from the lightweight pedestrian crowd models.

- The original child reference is the visual direction: short dark hair, large dark eyes, warm medium-light skin tone and a friendly expression.
- The player wears a blue Adam World hoodie, blue jeans and white/blue sneakers.
- The model is assembled from 3D meshes in the local Three.js scene. The face has separate eye, iris, pupil, brow/hair, ear, nose and mouth geometry.
- The player has a named hierarchical `THREE.Bone` rig for hips, neck, head, arms, forearms, hands, five fingers per hand, thighs, shins and feet. Body-part meshes are currently parented to their corresponding bones (rigid-part articulation); this is not yet a weighted SkinnedMesh or an exported GLB.
- The existing walk cycle drives the player limb bones. Eye meshes can blink independently. The named hand/finger bones provide attachment and animation points for later weapon grips, pointing, gestures and foot/hand actions.
- Crowd pedestrians continue to use the original lightweight `makePerson()` model to avoid increasing the cost of every NPC.

## Next production steps

1. Replace the procedural head/body meshes with a high-quality likeness-matched sculpt while preserving the current bone names and local coordinate convention.
2. Create proper UVs and PBR materials, then bind a weighted SkinnedMesh to the same skeleton.
3. Export and validate a real `adam_player.glb` with skeleton, materials, facial blendshapes (blink, smile, etc.) and animation clips.
4. Add weapon/prop sockets to both hands, foot contact/IK, facial expression controls and animation state transitions.
5. Test on Android devices for frame time, clipping, hand/finger deformation and camera scale.

## Runtime constraints

The character is built locally in the game scene and does not depend on external runtime model URLs or paid APIs. Do not claim that the current procedural mesh is already a final scanned/photorealistic character or a GLB asset; the weighted skinning and GLB export are follow-up work.
