# World Explorer — Android

A standalone native Android application for exploring the world, cities, streets, places, and 3D geographic content.

> This application lives in its own `World-Explorer/` directory so the existing **Beyond the Stereotype** film project at the repository root remains untouched.

## Product direction

- Native Android application built with Kotlin and Jetpack Compose.
- Java interoperability is available for Android libraries and future modules.
- Map and 3D rendering are isolated behind dedicated modules/interfaces so the rendering engine can evolve without coupling the entire app to one provider.
- Flutter and React Native are intentionally not included in the native Android runtime. They are alternative cross-platform frameworks, not prerequisites for a native Android app.
- Initial pilot city: Amman, Jordan. City data coverage will depend on available, licensed sources.

## Current milestone

**Milestone 0 — Native Android foundation**

- [x] Project structure and Gradle configuration
- [x] Compose-based application shell
- [x] Initial World Explorer home screen
- [x] GitHub Actions debug APK build
- [ ] Real map SDK integration and map rendering
- [ ] 3D terrain / buildings integration
- [ ] Search, place details, accounts, offline areas, and navigation

The first build is a foundation, not a finished map product. The interface must not imply that live maps, 3D buildings, navigation, or offline maps are available until their providers and implementations are integrated and tested.

## Build

The workflow **World Explorer — Android CI** builds the debug APK on pushes affecting this directory and can also be started manually.

The APK is uploaded as a GitHub Actions artifact. It is not committed to the source repository.

## Data, licensing, and privacy

Every map, imagery, building, and terrain source must be reviewed for attribution, redistribution, caching, and offline-use permissions before integration. Location access must be requested only when a user invokes a location-dependent feature.

## Package

`com.worldexplorer.app`
