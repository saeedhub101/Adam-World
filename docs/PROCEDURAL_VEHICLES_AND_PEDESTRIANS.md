# Procedural vehicles and pedestrians — native Android renderer

All vehicles and pedestrian meshes currently used by the active Android game are generated in Java. The native renderer does not load the GLB inventory under `vehicle-assets/` or the legacy model manifest under `old_app/`.

## Vehicles

`ProceduralVehicleModels.java` generates ten selectable mesh categories:

1. Sedan
2. SUV
3. Pickup
4. Bus
5. Police vehicle
6. Motorcycle
7. Bicycle
8. Tank
9. Helicopter
10. Airplane

These are procedural silhouettes/materials, not licensed city-specific photoreal vehicle models. Their presence in the selector does not imply real route, flight or rail simulation.

## Pedestrians

`ProceduralHumanRig.java` generates six mesh variants from 16 body parts. `Game.java` manages pedestrian agents and walking phases; `CityRenderer.java` applies the limb transforms.

## Imported vehicle inventory is separate

`vehicle-assets/manifest.json` records two external GLB candidates with CC BY-NC-SA declarations. That manifest is an asset/provenance inventory only; the current native renderer does not load or download those GLBs. The rights notes must be checked before commercial redistribution.

## Remaining work

- Integrate licensed local vehicle models only after verifying redistribution rights, scale, orientation and performance.
- Add city-specific vehicles only where their form is supported by local evidence.
- Implement route-following behavior separately from merely rendering a vehicle mesh.
- Test pedestrian/vehicle collision, traffic response and frame time on device.
