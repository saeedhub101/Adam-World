# World Explorer — Architecture decisions

## Native-first decision

The product is an Android-native application. Kotlin is the primary language, Jetpack Compose is the UI toolkit, and Java interoperability remains available. Flutter and React Native are not embedded in the first release because they are alternative application frameworks and would add complexity without a defined cross-platform requirement.

## Module boundaries

The initial Gradle project starts as a single app module to keep the first build verifiable. Code is organized so future modules can be extracted:

- core: shared models, configuration, and utilities
- map-engine: map SDK integration and camera state
- rendering-3d: terrain, 3D Tiles, glTF/GLB rendering
- search: geocoding and place search
- buildings: building details and feature selection
- offline: region downloads, cache policy, and storage
- account: authentication and user preferences

Modules will be added when there is working functionality to place in them, not as empty scaffolding.

## Geographic rendering

The map SDK and 3D renderer must be selected after a technical spike covering Android compatibility, licensing, attribution, offline rights, memory use, tile formats, and device performance. The UI must not represent mock content as live geographic data.

## Data provenance

Every dataset must carry source, license, attribution, timestamp, geographic coverage, and confidence metadata. User-facing building details must distinguish observed/source-provided values from estimates and unknown values.

## Performance

Use viewport-based loading, level of detail, bounded caches, cancellation of stale requests, and explicit memory budgets. Never load a whole city mesh into memory by default.

## Privacy

Request precise location only after the user invokes a location feature. Keep location processing on-device where practical and explain any server-side processing.
