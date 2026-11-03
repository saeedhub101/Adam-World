# Real-world surface and geometry pipeline

## Active renderer

The Android game builds native OpenGL ES meshes from OSM-style features in `TileBuilder.java`. It does not render MapLibre/Three.js layers. The geometry visible in a local mode depends on that mode's committed source cells and the tags supported by the native builder.

## Buildings

- Footprints are converted from polygon ways into local mesh geometry.
- Height precedence is documented in `GAME_RUNTIME.md`.
- Explicit metric heights and mapped floor counts take priority over approximate render heights.
- Missing source values use a class-based visual fallback; that fallback is not a measured building height.
- Available building/facade and roof colour/material tags are used where the native builder supports them.
- Building parts and balconies are rendered only when their mapped tags and geometry are present.

## Roads and transport

Mapped highway ways are converted into road ribbons using class-based widths and available width/surface/bridge/tunnel/layer tags. Names are displayed only when a source name/ref is available. The game does not infer a real street name from an unrelated nearby feature.

Manila's local road geometry/names come from the saved `transportation.geojson`. Amman's local transport/building/context features come from its Overture-derived shards. Tabarbour's active stream is currently context/transit data and must not be confused with the separate merged building chunks.

## Land cover and water

The renderer supports selected mapped natural, landuse, leisure, water and waterway features. It can only display what exists in the loaded tile source. A renderer branch for a feature class does not prove that the relevant city extract contains that feature.

## Terrain

Terrain is enabled for Amman and Tabarbour and disabled for Manila. The native sampler uses zoom-14 Terrarium RGB elevation tiles, relative to the selected start point. It is a DEM surface, not LiDAR and not a photogrammetric ground mesh. Missing/unavailable samples use the renderer's fallback behavior.

## Accuracy boundary

No procedural material, generic building massing, fallback height, or generated detail should be described as a verified photograph, survey, cadastral record or exact architectural measurement. Preserve missing values as missing in source metadata and label approximations as such.
