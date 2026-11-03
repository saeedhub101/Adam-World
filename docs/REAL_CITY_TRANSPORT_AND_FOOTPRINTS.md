# Transport and footprint source imports

This document describes source-import tooling. A source import is not automatically a native runtime layer; runtime use must be confirmed in `TileLoader` and the relevant city builder.

## Microsoft Global ML Building Footprints

Workflow: `.github/workflows/microsoft-footprints-import.yml`  
Importer: `tools/import_microsoft_building_footprints.py`

The importer uses Microsoft's public dataset link inventory and downloads intersecting quadkey shards. Preserve source URL, quadkey, upload date, attribution, licence, CRS and confidence/height attributes. A footprint is a building outline, not a facade photo. A model-estimated height is not a survey.

## Transport and infrastructure imports

Workflow: `.github/workflows/transport-infrastructure-import.yml`  
Importer: `tools/import_city_transport_infrastructure.py`

The import tooling can collect mapped rail, stations, waterways, bridges, tunnels, road attributes, bus stops and related OSM features. Public Overpass endpoints are used without a project API key. Imported coverage is limited to the requested query bounds and available OSM mapping.

## Runtime caveat

The native Android app renders only features delivered by the active city data path and supported by `TileBuilder`. A source file or importer output is not active merely because it exists in the repository. Manila uses its dedicated building/transport shard builder; Amman uses Overture-derived shards; Tabarbour's merged building source is not yet connected to its active stream manifest.

Do not claim complete citywide transport, all mapped bridges/tunnels, bus-route simulation or satellite-derived facade detail without verifying both source coverage and native runtime use.
