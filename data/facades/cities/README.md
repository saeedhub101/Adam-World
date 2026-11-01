# City facade evidence inputs

This folder is city-agnostic. Add one folder for every city/area being processed;
the pipeline does not hard-code Amman or a fixed city list.

Required per-city files:
- `buildings.geojson`: FeatureCollection of OSM building footprints. Each feature
  must carry a positive OSM way ID in `properties.osm_way_id`, `osm_id`,
  `properties.id`, or feature `id` (optionally prefixed `way/`).
- `panoramax-items.geojson`: FeatureCollection of Panoramax/STAC photo items.
  Keep the original photo ID, Point geometry, `view:azimuth`,
  `pers:interior_orientation.field_of_view`, pitch/roll, assets, self link and
  licence metadata.
- `aerial-items.geojson` (optional): OpenAerialMap/STAC items with georeferenced
  footprints, capture date, GSD and licence.

Run:
```sh
python3 tools/facades/build_review_queue.py --city amman
python3 tools/facades/build_review_queue.py
```

The second command processes every city subfolder, not just a selected city.
It writes `review_queue.json` beside each city's inputs. Outputs are deterministic
and unchanged queues are not rewritten. The queue is an evidence/review worklist,
not an approved texture manifest. Nearby photos and camera-cone matches are only
candidates; no photo is automatically attached to a building.

Keep large imagery files outside Git unless a reviewed, licence-compatible texture
is actually needed by a final local GLB. Store the compact metadata, source hashes,
review queue and final provenance in the repository. Never check in a photo if its
licence does not permit redistribution.
