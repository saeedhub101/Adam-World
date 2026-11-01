# Real city transport and satellite-derived footprints

## Satellite-derived building footprints

Workflow: `.github/workflows/microsoft-footprints-import.yml`
Importer: `tools/import_microsoft_building_footprints.py`

The importer reads Microsoft's public `dataset-links.csv` and downloads only the LOD-9 quadkey shards intersecting each city's current 2.5 km minimum import radius. It uses direct public Azure Blob downloads; no API key is required. The source is Microsoft's Global ML Building Footprints, derived from Bing Maps imagery captured between 2014 and 2024, distributed under CDLA Permissive 2.0.

Imported polygons are merged into the existing offline OSM `elements.json`. Existing OSM buildings are retained; nearby matching Microsoft polygons are deduplicated by local centroid, bounds and area. Microsoft's `height` and `confidence` properties are preserved where supplied. Coverage is recorded per city; an unavailable tile is not replaced with a fabricated building.

Important: a Microsoft footprint is a roof/building outline, not a facade photograph. Height is present only for some detections. This import improves footprint coverage and available height estimates; it does not create real facade textures, doors, windows or balconies.

## Transport and infrastructure

Workflow: `.github/workflows/transport-infrastructure-import.yml`
Importer: `tools/import_city_transport_infrastructure.py`

Public OpenStreetMap data is re-queried through public Overpass endpoints without an API key, for the same 2.5 km minimum radius around each city centre. The importer checkpoints each city and merges tags/geometry into its existing offline extract.

Layers requested: rail/light-rail/subway/tram ways; station, halt, tram-stop and subway-entrance nodes; river/stream/canal/drain ways; waterfall nodes; bridges and tunnels; road width, lanes, surface, smoothness and traffic-calming tags; mapped potholes and bus stops.

Runtime changes:
- OSM road geometry remains the actual mapped street alignment; tagged width/lanes influence road width.
- Asphalt, concrete, paving-stone, gravel and dirt surfaces use separate materials when the corresponding OSM surface tag exists.
- Poor mapped smoothness reduces traffic speed. Only mapped potholes and traffic-calming points are rendered; no random holes or bumps are invented.
- Railway ways render sleepers and steel rails. A local metro-car GLB is placed on mapped rail paths.
- Mapped road/rail bridges receive an elevated deck and supports; tunnel-tagged routes receive entrance portals and are placed below the surface.
- River/stream/canal lines and mapped waterfall nodes feed the existing water renderer.

Limitations that remain explicit:
- The transport import radius is a local core-area radius, not an entire metropolitan boundary.
- OSM does not contain every pothole, bridge, tunnel, rail line, waterway or station. Missing mapped data is not synthesized.
- Bus route relations are not yet converted into bus paths; the bus GLB is imported but is not yet moving on verified bus routes.
- The metro GLB can only run where OSM provides rail geometry; it is not evidence that every city has a metro.
- The water-taxi GLB is imported but awaits a verified navigable-water route and dock.
- Bridge/tunnel structures are currently simplified geometry driven by OSM tags, not photogrammetric reconstructions.
- City-specific Jeepneys and Philippine sidecar tricycles, old worn Jordanian vehicles, and verified walking animations remain pending.
- Real matched facade imagery and photoreal building materials remain a separate unfinished stage.