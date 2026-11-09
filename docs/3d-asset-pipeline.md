# Adam World 3D Asset Pipeline

This document is the implementation contract for the shared 3D asset pipeline used by vehicles, characters, props, and buildings. It distinguishes implemented features from planned work; a format version must never be described as supporting a feature until the Android renderer consumes it.

## Goals
- One source-to-runtime pipeline for all 3D asset classes.
- Build-time conversion: source GLB/glTF files and authoring tools are not shipped in the APK.
- Runtime assets are streamable, bounded in memory, and compatible with Android GLES 2.0 devices.
- Preserve geometry, UVs, material assignments, textures, transforms, and (where supported) animation.
- Keep a backwards-compatible reader for existing AWM1/AWM2 assets during migration.
- Never silently replace missing source data with invented visual detail; log omissions and retain a safe fallback.

## Format compatibility
| Format | Payload | Status |
|---|---|---|
| AWM1 | Position, normal, vertex RGB | Legacy reader supported |
| AWM2 | AWM1 fields plus UV coordinates | Current vehicle build output; UV is zero-filled if absent in source |
| AWM3 | Expanded triangle geometry, submesh ranges and scalar material factors | Backward-compatible reader retained for existing assets |\n| AWM4 | AWM3 geometry plus embedded PNG base-color texture table and per-material texture indices | Converter extraction and GLES2 runtime binding implemented for vehicle base-color maps |

AWM4 layout is little-endian: 24-byte header (`AWM4`, vertex count, submesh count, material count, stride=11, texture count), expanded 11-float vertices, 12-byte submesh records (first vertex, vertex count, material index), 52-byte material records (base RGBA, emissive RGB, metallic, roughness, alpha cutoff, alpha mode, flags, base-color texture index), then texture records (width, height, codec=PNG, byte length, PNG bytes). AWM3 remains readable. The Android reader validates counts, ranges, stride, finite vertex data and exact file length before creating the GPU mesh. The renderer draws each submesh with its own metallic/roughness factors and binds its referenced base-color PNG texture on texture unit 0, falling back to a neutral white texture when none is present. In this converter revision, source base-color RGB is baked into expanded vertex colors; the vehicle paint tint remains a separate multiplier. Alpha-mode metadata is retained but transparent/masked material rendering is not yet active.\n\nAWM2 layout is little-endian: 4-byte magic, uint32 vertex count, then 11 float32 values per expanded triangle vertex: position(3), normal(3), UV(2), RGB(3). AWM1 remains readable with 9 float32 values per vertex.

AWM3 must use a length-delimited header and independently addressable sections. It must include bounds and section offsets/sizes, explicit limits, and checksums so malformed or truncated assets can be rejected before GPU allocation. Geometry sections must retain material/submesh boundaries. Material records must carry base color, metallic, roughness, emissive, alpha mode/cutoff, double-sided state, and optional texture indices for base color, normal, metallic-roughness, occlusion, and emissive maps. Texture records must include MIME/codec, dimensions, mip count, byte range, and color-space intent.

## Rendering requirements
- Draw each submesh with its own material state; do not flatten material identity into a single RGB vertex color.
- Decode texture assets off the GL thread, upload on the GL thread, and release CPU bitmaps after upload.
- Prefer KTX2/Basis Universal for GPU-friendly texture streaming; PNG/JPEG are acceptable as an initial compatibility path. Never assume KTX2 support without a decoder.
- Use UVs for imported assets. World-space triplanar mapping remains a fallback for procedural terrain/building surfaces, not a substitute for source UV textures.
- Apply normal maps only with a valid tangent basis. If tangents are unavailable, generate them at build time or use a documented fallback.
- Handle alpha blend/mask separately from opaque materials and sort transparent draws.
- Preserve GL context-loss recovery: CPU-side asset descriptors must allow GPU resources to be recreated.

## Performance and lifecycle
- Build-time: normalize transforms, validate finite coordinates, generate normals/tangents, deduplicate where safe, and create LODs.
- Runtime: stream assets by distance and visibility; maintain explicit CPU/GPU byte budgets, evict far assets, and never cache unboundedly.
- Keep collision geometry separate and simpler than render geometry.
- Keep the APK free of source GLBs and bulk city datasets. Only optimized runtime assets needed by the app belong in APK assets.
- Avoid retaining both imported and procedural GPU copies when the imported asset loaded.

## LOD, collision, and animation
- LOD0: full detail near the player; LOD1/2: progressively simplified geometry and material variants; far objects may use impostors only when visually acceptable.
- Collision meshes are low-poly and independent of render meshes. Vehicle collision must support wheels, body, ramps/curbs, and reliable ground contact.
- Animation clips use named skeletons, stable joint indices, inverse bind matrices, and sampled keyframes. Vehicle parts (wheels/doors) may use rigid-node animation.
- Animation data must be optional; static assets remain loadable on devices that cannot afford skeletal animation.

## Asset-class adoption
1. Vehicles: imported exterior/interior submeshes, glass, tires, lights, trim, UV textures, wheel/door animation, and simplified collision.
2. Characters: skinned meshes, skeletons, walk/run/idle clips, retargeting rules, and LOD.
3. Buildings: retain geographic footprint and orientation; use per-surface materials, facade/roof submeshes, doors/windows/balconies/garage geometry, and source-linked detail layers. A facade must never be assigned to a neighboring building.
4. Props and street furniture: signs, traffic lights, benches, trees, hydrants, parking barriers, and road markings with shared material atlases and instancing where possible.

## Current implementation status (2026-11-09)
- [x] AWM1 backward-compatible reader.
- [x] AWM2 reader and build-time writer for position, normals, UV, and RGB.
- [x] Vehicle source conversion runs during the Android release build; source GLBs are not runtime dependencies.
- [x] Imported vehicle meshes replace procedural GPU meshes when available, and the draw path accepts imported-only slots without requiring a procedural mesh.
- [x] Preserve vehicle paint tint while applying per-submesh metallic/roughness values.
- [x] Procedural GPU copies are released after successful imported mesh upload.
- [ ] Verify per-source UV coverage; zero UV fallback must be visible in build logs.
- [ ] AWM3 binary container and material/submesh table.
- [ ] Extract and package source texture images and PBR material properties.
- [ ] Runtime material binding and multi-submesh drawing.
- [ ] Tangent generation and normal-map support for imported meshes.
- [ ] LOD generation/selection and byte-budgeted asset streaming.
- [ ] Collision mesh pipeline.
- [ ] Rigid-part and skeletal animation pipeline.
- [ ] Adopt the same pipeline for characters, buildings, and props.

## Acceptance rules
A phase is complete only when its build output is consumed by the Android runtime and visibly changes the relevant asset. A converter log or a format declaration alone is not proof of rendering support. Keep each phase backwards-compatible and preserve the existing fallback path so a missing optional asset cannot prevent the game from starting.
