# Facade evidence and reconstruction policy

## Goal

A facade photograph is useful only when it is verified to depict the exact building and facade side. The final texture must be applied to that building's own surface. A nearby photo, floating card, billboard or generic window overlay is not a reconstructed facade.

## Current native runtime limitation

The active Android renderer is Java/OpenGL ES 2.0 and builds buildings procedurally in `TileBuilder`. The current Manila runtime does not render generated `facade-windows-*.geojson` layers and does not apply verified Panoramax photos as building textures. Do not describe the current buildings as photo-textured or GLB-reconstructed.

## Evidence sources and gates

- Aerial imagery can support roof outline, building location and broad roof colour; it cannot establish detailed street facade appearance.
- Panoramax or other street-level imagery can provide facade candidates only when capture coordinates, camera direction, field of view, image quality and licence are available.
- Exact identity must be checked against footprint, facade orientation, visible architectural features and neighbouring geometry.
- Interior images, wrong buildings, wrong facade sides, people-only views, blurred/occluded photos and non-redistributable images must be rejected.
- Proximity or camera direction alone is never proof of identity.
- KartaView must not be used as a substitute evidence source when excluded by project policy.

## Required verified assignment record

Before any image is used, record the image ID, source URL, creator, licence, capture date, camera position/azimuth/pitch, image hash, target building source ID, facade edge/side, verification method and reviewer decision. Keep candidate and verified states distinct.

## Required final geometry stage

A future approved pipeline must:
1. Build the target building shell from its real footprint and supported height.
2. Calibrate the image camera against the exact facade plane.
3. Project/crop the facade image with perspective correction.
4. Bake the texture into the same building mesh/material.
5. Preserve doors, windows, balconies and signage only when visible evidence supports them.
6. Validate coordinates, orientation, scale, UVs, embedded textures and licensing.
7. Save the output locally and update the runtime asset manifest only after validation.

## Current status

Candidate/review tools and archived image data exist in the repository, but no evidence here establishes that a verified-photo facade layer is active in the native Android renderer. Treat photo-real facade reconstruction as unfinished until a native loader and verified on-mesh assets are demonstrably used by the game.
