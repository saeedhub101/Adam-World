# Manila facade-detail GeoJSON — archive status

## Generator input

`tools/facades/generate_manila_facade_details.py` reads the saved canonical `data/facades/cities/manila/buildings.geojson` file. It does not read `building-layer.geojson`, the footprints/details supplemental files, PBF tiles or remote APIs.

## Outputs

The generator writes chunked GeoJSON outputs for:
- `facade-windows-*.geojson`
- `facade-doors-*.geojson`
- `facade-balconies-*.geojson`
- `facade-details-report.json`

The report describes generated procedural details. It must not be interpreted as a verified photographic or surveyed facade inventory.

## Current Android runtime status

The active Manila Android shard builder reads `building-layer.geojson`, not these generated facade-detail files. The current native renderer does not download or render the generated window polygons, and the Android release workflow does not package them as active facade geometry.

These files are retained as archived generator outputs. They are not active runtime inputs. Any future reactivation requires an explicit native loader, bounded spatial streaming, duplicate prevention, and on-device memory/performance validation.
