# Adam World playable child character

## Current in-game implementation

The player character is implemented in `app/src/main/assets/index.html` and is separate from lightweight pedestrian NPCs. The current low-poly/procedural model uses the supplied child photo as a broad visual reference only; it is not yet a likeness-accurate sculpt.

### Rig and body controls

- Hierarchical Three.js bones for hips, neck, head, arms, forearms, hands, five finger chains per hand, thighs, shins and feet.
- Named `jaw` bone added to the head rig.
- Walk animation drives the leg and arm chains.
- `window.AdamCharacter.setFingerCurl('left'|'right', amount)` provides a basic hand-curl control.
- Bone names are exposed through `window.AdamCharacter.bones` for future animation and attachment work.

### Facial blendshapes and performance controls

The head geometry now has actual Three.js position morph targets:
`smile`, `frown`, `mouthOpen`, `mouthWide`, `mouthRound`, `mouthPucker`, `browDownLeft`, `browDownRight`, `browInnerUp`, `browOuterUpLeft`, `browOuterUpRight`, `eyeWideLeft`, `eyeWideRight`, `eyeSquintLeft`, `eyeSquintRight`, and `cheekRaise`.

Available expression presets:
- `neutral`, `happy`, `joy`, `angry`, `sad`, `surprised`, `fear`, `disgust`, `confused`.

Available speech viseme presets:
- `sil`, `PP`, `FF`, `TH`, `DD`, `kk`, `CH`, `SS`, `nn`, `RR`, `aa`, `E`, `I`, `O`, `U`.

Runtime API:
```js
AdamCharacter.setExpression('happy');
AdamCharacter.setViseme('aa');
AdamCharacter.setJaw(0.5);
AdamCharacter.blink(1);
AdamCharacter.setLook(0.2, -0.1);
AdamCharacter.setFingerCurl('right', 0.8);
```

The controller blends morph weights over time and includes an automatic blink cycle. Mouth cavity and tooth geometry are present, with basic jaw opening control. These are initial game-ready controls, not a complete facial-animation rig: eyelid deformation, tongue articulation, phoneme timing from audio, robust jaw/teeth deformation, and a full ARKit-compatible set still need production work.

## Important remaining production work

1. Build a likeness-accurate head from the supplied child's reference photo. The current procedural sphere-based face is not an accurate reconstruction and must not be described as one.
2. Create a production-quality full-body mesh with clean topology, UVs and PBR materials.
3. Bind the body as weighted SkinnedMesh geometry to the skeleton. Current body parts are mostly rigid meshes parented to bones; this is not yet smooth weighted skinning.
4. Add eyelid meshes and full facial blendshapes (including jaw, lips, teeth/tongue, blink, gaze, cheek, brow and asymmetry), then validate expression combinations.
5. Export and validate a real `adam_player.glb` containing the skeleton, skin weights, morph targets and animation clips.
6. Add weapon/prop sockets to both hands, grip poses, foot IK, hand IK, interaction animations and motion-state transitions.
7. Test on Android for deformation, clipping, frame time and animation stability.

## Runtime constraints

The character is generated locally in the game scene and does not require external runtime model URLs or paid APIs. The current scene model is not yet an exported GLB. Do not claim GLB export, high-fidelity likeness, weighted skinning or final production rig until those assets and tests exist.
