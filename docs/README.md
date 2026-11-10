# Documentation index

This documentation describes the **current native Android application**, not the legacy HTML client.

- `GAME_RUNTIME.md`: active modules, city modes, data paths and build/release behavior.
- `OFFLINE_CITY_STREAMING.md`: tile selection, network access, caches and memory limits.
- `MANILA_OFFLINE_DATA.md`: exact Manila building, road, POI and height data flow.
- `TABARBOUR_BUILDING_ENRICHMENT.md`: merged source data versus the currently active stream.
- `REAL_WORLD_SURFACE_PIPELINE.md`: current procedural geometry and terrain behavior.
- `FACADE_RECONSTRUCTION_PIPELINE.md`: evidence requirements and what is not yet rendered.
- `REALTIME_3D_ENGINE_ARCHITECTURE.md`: target unified runtime for skinned characters, PBR assets, weapons, vehicles, IK, physics and bounded city streaming. This is the migration contract, not a statement of completed implementation.
- `3d-asset-pipeline.md`: current asset conversion/runtime capabilities and limitations.
- `PLAYER_CHARACTER.md` and `PROCEDURAL_VEHICLES_AND_PEDESTRIANS.md`: current native procedural models.
- `REALISM_REBUILD_STATUS.md`: verified implementation and outstanding work.

Source-import and research documents remain in their respective `data/` folders. Their manifests record acquisition/build outputs; they are not automatically Android runtime manifests.
