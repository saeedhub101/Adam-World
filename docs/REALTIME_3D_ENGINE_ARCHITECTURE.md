# Adam World — Unified Real-Time 3D Runtime Architecture

**Status: target architecture / migration contract. This is not a claim that the runtime is implemented.**

The objective is a professional, integrated 3D game runtime for characters, weapons, vehicles, world interaction and streamed cities. A feature is complete only when its production asset is loaded and rendered by the Android runtime, its animation/physics/interaction works in gameplay, and it stays inside the device budgets.

## 1. Current baseline (verified from main)

- The application uses a custom Android OpenGL ES renderer in `CityRenderer`.
- The player and pedestrians are assembled from procedural meshes and transform-driven limb motion; there is no complete imported skinned-character animation pipeline.
- `AssetMeshLoader` reads AWM1–AWM4 static mesh data. AWM4 currently binds base-colour textures and scalar metallic/roughness values; it is not a complete glTF/PBR implementation and does not provide skinning, animation, IK, or full texture-channel support.
- `ProceduralVehicleModels` remains the fallback vehicle system. The external GLB inventory explicitly says those GLBs are not loaded directly by the game; build-time conversion is separate from proof of complete vehicle fidelity.
- `WeaponSystem` contains gameplay archetypes/balance data, while weapon presentation and handling still need a complete imported-asset and animation pipeline.
- `PlayerMotionSystem` is a locomotion controller, not a character animation/rig system.

These limitations are deliberate migration inputs, not features to relabel as complete.

## 2. Target runtime

### 2.1 Standard asset interchange

Use glTF 2.0 / GLB as the authoring and interchange format for all animated and static assets. Preserve:
- node hierarchy and transforms;
- skinned meshes, joint hierarchy, inverse bind matrices and vertex weights;
- animation clips, interpolation, events and morph targets where present;
- material factors and texture channels (base colour, normal, metallic-roughness, occlusion, emissive);
- alpha modes, double-sided state, UV sets and tangent basis;
- animation metadata, attachment sockets, collision proxies, LODs and source/licence metadata.

A build-time pipeline may optimize and package assets into runtime containers, but it must not discard rig, animation, material, texture or node semantics. AWM1–AWM4 remain legacy compatibility formats only; they are not the long-term animated asset format.

### 2.2 Rendering backend decision

The current GLES renderer cannot be assumed to provide a professional glTF runtime by adding a file parser. Before replacing the city renderer, make a minimal Android technical integration proving:
1. GLB loading with a skinned humanoid and at least two blended clips;
2. PBR base-colour, normal and metallic-roughness materials with correct colour space;
3. rigid animated nodes for wheels, doors and weapon mechanisms;
4. render-thread ownership, context-loss recovery and lifecycle correctness;
5. compatibility with the Android minimum API and target GPU tier.

Evaluate a maintained Android-capable glTF/PBR runtime (including Filament with gltfio) against extending the current renderer. Select one backend based on the working device prototype, not assumption. Do not run two independent full-screen renderers or introduce unsynchronized GL contexts as a shortcut. The selected design must provide one coordinated frame lifecycle and depth/lighting integration with the streamed city.

### 2.3 Character runtime

A character is a real skinned model with a named skeleton and retargetable animation library, not disconnected procedural body parts. Required systems:
- animation state graph: idle, walk, run, sprint, start/stop, turn-in-place, jump, fall, land, crouch, dodge, climb, enter/exit vehicle, aim, fire, reload, melee, hit reactions, knockdown and recovery;
- blend spaces driven by actual velocity and direction; additive upper-body layers for aiming, recoil and weapon handling;
- root-motion policy with collision/physics authority clearly defined (never apply root motion and motor displacement twice);
- two-bone IK for hands and feet, foot placement/ground alignment, hand-to-grip weapon sockets, and vehicle steering/door/seat targets;
- retargeting validation, animation events, transition rules and fallbacks for missing clips;
- optional facial rig/morph targets and expression/viseme hooks only when supplied by the asset.

The same skeleton/animation system must support the player and pedestrians, with different quality tiers and update rates.

### 2.4 Weapon runtime

Each weapon is an imported, correctly proportioned model with material assignments, attachment points and animated mechanisms. A weapon definition references:
- first/third-person model variants, muzzle, ejection, magazine, grip and sight sockets;
- fire/reload/inspect/equip/unequip animation clips and audio/particle event markers;
- magazine, chamber, reserve ammo, fire mode, recoil, spread, projectile/trace behavior and damage profile;
- collision/interaction bounds and optional attachment slots.

Weapon handling must be driven by the character rig: both hands reach their grip targets using IK; aim aligns the weapon socket with camera/target; recoil affects weapon and upper-body pose; reload events synchronize magazine/bolt motion and ammo state. Do not represent a weapon replacement as only a different primitive mesh or HUD icon.

### 2.5 Vehicle runtime

Vehicles use imported exterior and interior GLB assets, with named wheel, steering, door, seat, glass, light and attachment nodes. Required:
- wheel rotation and suspension travel, steering angle, brake/reverse lights, doors and entry/exit animation;
- a stable vehicle physics body with wheel contact/suspension, center of mass, friction, braking, handbrake, collision and curb/ramp handling;
- character seat/hand/foot IK and camera transitions;
- aircraft-specific lift, throttle, control surfaces, landing gear, ground contact and flight-state model;
- simplified low-poly collision proxies independent from high-detail render meshes.

Vehicle gameplay must not silently fall back to a primitive vehicle after an imported production asset fails. Show a clear asset error in development builds and use a deliberate low-detail LOD fallback in release.

### 2.6 Shared interaction and physics

Use one interaction graph with typed entities, stable IDs, sockets and collision layers. Character, weapon, vehicle, door, seat, pickup and world props must share:
- query/overlap and raycast interfaces;
- physical material/friction/restitution and collision masks;
- attachment and detachment rules;
- interaction state transitions and cancellation/error recovery;
- event routing for animation, audio, particles, UI and gameplay.

Rendering geometry, animation skeletons and physics proxies are separate data, linked by stable node/socket IDs.

## 3. City streaming and performance contract

The city renderer and dynamic-actor runtime must share a frame budget but have separate, explicit memory budgets.

- Never load the full offline world at startup. Keep the existing tile/ring streaming model and load only visible tiles plus a bounded prefetch ring.
- Stream character/vehicle/weapon assets asynchronously by proximity and gameplay state. Decode on worker threads; create/update GPU resources only on the owning render thread.
- Maintain hard CPU and GPU byte budgets for city tiles, actor meshes, animation data and textures independently. Every cache must have an LRU/visibility eviction path and a hard cap.
- Use LODs, mesh simplification, texture mip streaming, instancing and animation update throttling for distant agents.
- Use a small number of shared skeleton/animation sets for pedestrians; avoid one duplicate texture/material/mesh allocation per agent.
- Pool transient effects and interaction objects; avoid per-frame allocations and unbounded collections.
- On memory pressure, reduce far LODs and evict distant assets before touching the active player/vehicle or visible near-field city.
- Context loss, city switch, failed download, corrupt asset and interrupted stream must be recoverable without freezing the game or leaving stale GPU handles.
- No bulk city data, source GLBs, authoring files or high-resolution source textures in the APK. Deliver optional asset packs separately and cache them under explicit quotas.

Initial device targets must be measured on low-, mid- and high-tier Android hardware. Do not promise a frame rate or memory ceiling until profiled. Frame-time, CPU/GPU memory, draw calls, visible bones, animation cost and tile-stream latency must be instrumented.

## 4. Migration sequence (one integrated product, not isolated demos)

1. **Runtime foundation:** choose and prove the renderer/backend with a skinned GLB, PBR maps, animation blending, rigid nodes, lifecycle and city depth integration.
2. **Asset pipeline:** validate/licence assets, optimize GLB, generate LODs/tangents/collision proxies, preserve animation/material metadata, produce versioned runtime packs and manifests.
3. **Character foundation:** replace player procedural body with a production skinned character; integrate locomotion, state graph, IK, sockets, hit reactions and gameplay events. Then migrate pedestrians using shared assets.
4. **Weapon foundation:** migrate every weapon slot to actual assets and synchronized character/weapon animation, attachments, effects and audio.
5. **Vehicle foundation:** migrate every vehicle class to actual assets, wheel/door/seat nodes, collision and physics; then aircraft and airport flows.
6. **World integration:** align city interaction, doors, ramps, sidewalks, pickups, traffic, lighting and occlusion with the same physics/query model.
7. **Retire legacy paths:** remove procedural player/weapon/vehicle presentation only after all replacement assets load and work; retain only explicit low-tier fallback assets, not the old default system.

Each migration step must be merged directly to `main`, preserve release signing identity and produce the normal release APK/AAB. No parallel branches or separate test-only workflows.

## 5. Definition of done

A subsystem is not complete because a document, converter, asset file, class or successful build exists. It is complete only when:
- the Android runtime actually loads the production asset;
- the expected material, skeleton, animation or rigid-node data is consumed;
- gameplay interactions and collisions work end-to-end;
- asset failure and city switching are recoverable;
- memory remains bounded during long-distance movement and repeated city/vehicle/weapon changes;
- the feature is visually and functionally checked in the running app on representative Android devices.

Never label the whole game AAA/AAAA based on a successful compile or one imported model. Report implemented, build-verified and device-verified status separately.
