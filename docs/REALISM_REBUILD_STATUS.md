# Adam World — verified implementation status

Last reviewed against the current `main` source tree: 2026-11-04.

## What the Android app is

The active application is native Java/OpenGL ES 2.0 (`MainActivity`, `CityGLView`, `CityRenderer`, `TileLoader`, `TileBuilder`). It is not the old Three.js HTML client. It procedurally generates building, road, terrain, human and vehicle meshes from available data.

## City data status

| Area | Code path | Verified source status | Runtime caveat |
|---|---|---|---|
| Amman Local | `offline-city-stream/amman` | 1,778 manifest-listed 1 km shards; Overture-derived local tiles | Repository download/cache; terrain uses Terrarium sampling |
| Manila Local | `offline-city-stream/manila` | 1,759 building features; 1,748 with `render_height`; 77 listed cells | Uses `building-layer.geojson` only for building geometry; flat ground; window GeoJSON disabled |
| Tabarbour Local | `offline-world/tabarbour/stream` | 1,108 listed 250 m cells and 2,229 active-stream elements | The 29,159 merged buildings are not in the active stream manifest |
| Generic/search | Overpass + Nominatim | Nearby OSM query and place search | Network-dependent; not a global offline basemap |

## Implemented in native code

- Nearby tile loading in a background thread with request prioritization and bounded ready/cache queues.
- Compressed JSONL/GZIP streaming for Amman and Manila, with per-render-tile feature filtering.
- Tabarbour repository-hosted JSON cell loading from the active stream manifest.
- Building extrusion from explicit heights/floors, normalized render heights, and clearly approximate fallback massing.
- Mapped building/facade and roof colours/material tags where present.
- Roads, mapped names, selected land-cover/water features, POIs and traffic/transport elements supported by `TileBuilder`.
- Terrain sampling for Amman and Tabarbour; Manila remains flat.
- Procedural player/NPC meshes and ten procedural vehicle categories.\n- Robinsons Place Manila is now a dedicated native landmark mesh in `Landmarks.robinsonsPlace()`: curved four-level mall shell, glass ribbons, balcony slabs/rails, white colonnade, entrance canopy/glazing, roof plant, red rooftop Robinsons sign/emblem, retail fascia labels, planters, benches and outdoor chairs. The generic building footprints inside its fitted mall ellipse are suppressed to avoid double geometry.
- Mesh and texture cleanup as render tiles are evicted.

## Not implemented or not verified

- The current app does not load the legacy GLB model catalog or Three.js GLTFLoader.
- Robinsons Place Manila is a photo-guided procedural reconstruction, not a photogrammetric scan; the current model uses geometry and colour/material cues read from public exterior photos, not the original photo pixels as UV textures.\n- No other Manila building has a photo-verified facade texture applied to its own mesh.
- Generated Manila window GeoJSON is not rendered.
- The Tabarbour merged 29,159-building dataset is not connected to the active runtime stream.
- The ten-city asset catalog is not the native loader's city registry.
- Generic locations are not supplied by a permanent offline/global map base; the default loader uses Overpass.
- A successful signed APK workflow is not proof of on-device visual QA, frame-rate stability or complete geographic coverage.

## Release

The active workflow is `.github/workflows/android-release.yml`. It rebuilds Manila shards, validates Amman/Manila committed shard inventories, builds and signs an APK, verifies the signature and publishes a release. It does not run an emulator/device acceptance suite.

## Acceptance checklist

- [ ] Verify Robinsons Place Manila's fitted footprint, facade orientation, signage placement and collision in a device screenshot.\n- [ ] Verify building heights and colours visually in Amman and Manila.
- [ ] Connect the Tabarbour merged source to the active stream and verify central cells.
- [ ] Confirm roads and buildings appear at the Tabarbour start point.
- [ ] Verify terrain alignment and fallback behavior on device.
- [ ] Verify memory, frame time, tile eviction and city switching on a representative Android device.
- [ ] Only mark photo facade reconstruction complete after exact-building identity, camera calibration, licence and on-mesh texture validation.
