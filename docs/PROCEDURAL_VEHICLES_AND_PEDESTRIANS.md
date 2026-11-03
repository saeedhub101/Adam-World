# Procedural Vehicles and Pedestrians

All new vehicle and pedestrian meshes are generated in Java at runtime from parametric geometry. This system does not load GLB vehicle models or download model assets.

## Current implementation

- Vehicle mesh generator: `ProceduralVehicleModels.java`.
- Human mesh generator: `ProceduralHumanModels.java`.
- Renderer integration: `CityRenderer.java`.
- Player vehicle selection and aircraft lift controls: `MainActivity.java`.
- Vehicle-specific movement and flight altitude: `Game.java`.

Vehicle types currently generated: sedan, SUV, pickup, bus, police car, motorcycle, bicycle, tank, helicopter and airplane. Road traffic can spawn sedans, SUVs, pickups, buses, police cars, motorcycles and bicycles. The player can cycle through all ten types using **نوع المركبة**, then use **🚗** to summon/enter or exit. Aircraft use the joystick for forward speed and steering, and the up/down controls for lift.

The body and fuselage use longitudinal parametric rings with Bezier profile shaping. Vehicle details include separate colored glass surfaces, lights, bumpers, wheel/tire/rim geometry, tank tracks and turret, helicopter rotor/tail, and airplane wings. A vehicle-only microfacet-style lighting branch adds metallic highlights and a small analytic surface perturbation. Pedestrians use six generated body/clothing/skin/hair variants with smooth ellipsoid geometry and facial details.

## Known limitations — not release-verified

This is the first procedural foundation, not a claim of finished photorealism. The current renderer still needs:
- a real tangent-space normal-map pipeline and per-material roughness/metalness maps;
- transparent/reflection-aware glass and environment reflections;
- rotating/steering wheel meshes, articulated tank tracks, animated helicopter rotor and airplane control surfaces;
- more detailed aircraft/tank silhouettes and vehicle-specific suspension, braking and collision handling;
- articulated pedestrian rigs, walk-cycle animation, higher-detail facial geometry and clothing folds;
- Android build, on-device performance profiling and visual QA on low/mid-range devices.

Do not describe the current microfacet shader as full image-based PBR or the procedural surface perturbation as a baked normal map. Verify a successful Android build and inspect screenshots/device rendering before marking this feature complete.
