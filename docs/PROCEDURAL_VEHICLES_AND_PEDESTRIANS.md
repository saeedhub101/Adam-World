# Procedural Vehicles and Pedestrians

All vehicle and pedestrian geometry is generated in Java at runtime. The renderer does not load vehicle GLB models or download character/vehicle assets.

## Procedural vehicles

`ProceduralVehicleModels.java` generates sedan, SUV, pickup, bus, police car, motorcycle, bicycle, tank, helicopter and airplane geometry. The player can cycle through all ten types with **نوع المركبة** and summon/enter using the vehicle control. Traffic uses road vehicles; aircraft and the tank remain player-selectable.

- Parametric ring bodies and Bezier-shaped profiles generate the main hulls/fuselages.
- Vehicle windows are separate transparent meshes with Fresnel-weighted cubemap reflections.
- Cars, buses, motorcycles and bicycles use separate wheel meshes. Their wheels rotate from travelled distance; front wheels steer with the steering input.
- Tank tread links are animated around a loop from vehicle speed.
- Helicopter rotor blades rotate continuously.
- Aircraft lift and steering remain connected to the existing flight controls.

## Materials and lighting

`ProceduralMaterialMaps.java` creates independent runtime textures for tangent-frame normal detail, roughness, and metalness, plus a cubemap environment used for reflective vehicle paint and glass.

The vehicle shader uses a microfacet BRDF, Fresnel response, independent material maps and the environment cubemap. The normal map is sampled through triplanar tangent frames. The current cubemap is a generated sky/horizon/ground environment; it is not yet a live capture of nearby buildings or a dynamic reflection probe. Material maps are generated procedurally and are not downloaded image assets.

## Articulated pedestrians

`ProceduralHumanRig.java` generates six pedestrian appearance variants as separate body-part meshes. `CityRenderer.java` animates shoulder, elbow, hip, knee and foot transforms with a phase-driven walk cycle. The meshes include skin, hair, eyes, nose, brows, mouth, collar, pocket/seam details, hands, shoes and varied clothing colours.

The player avatar still uses its existing dedicated character rendering path; the articulated rig currently drives pedestrian agents.

## Verification and remaining limits

This implementation is not yet certified as photorealistic. The normal map uses tangent frames from triplanar projections rather than authored per-mesh UV tangent attributes. The cubemap currently reflects a generated sky/horizon/ground, not the live city scene. Human faces and clothing remain procedural low-poly forms, not scanned or photorealistic people. Suspension, aircraft control-surface animation, skeletal skinning and collision refinements remain future work.

A successful Android release build and on-device visual/performance test are required before treating this feature as release-complete.
