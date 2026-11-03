# Manila Local — exact data flow

## Runtime source

The native Android Manila Local mode reads:

- Building geometry and properties: `data/facades/cities/manila/layers/building-layer.geojson`, transformed by `tools/build_manila_stream_shards.py` into `data/offline-city-stream/manila/shards/*.jsonl.gz`.
- Road geometry and street names: `data/facades/cities/manila/transportation.geojson`.
- POIs and address-related features: available saved OpenMapTiles PBF layers, saved OSM non-building context and optional thematic CSVs when present in the workflow checkout.
- Tree inventory: the preserved Rizal Park inventory may be loaded from the packaged `old_app/src/main/assets/data` asset source.

The current Manila shard builder reads the dedicated `building-layer.geojson` only for buildings. It does **not** merge `buildings.geojson`, `building-footprints.geojson` and `building-details.geojson` into the active runtime building layer. Older notes claiming that three-file merge is active are obsolete.

## Building counts and height

The current Manila manifest reports:

| Metric | Count/value |
|---|---:|
| Source building features | 1,759 |
| Features with height | 1,748 |
| Features without recorded height | 11 |
| Features with mapped colour/material attributes | 201 |
| Features with `building:levels` | 0 |
| Polygon components in source geometries | 213,808 |

The source height property is `render_height`; this layer does not use `height` or `building:levels` as its height field. The shard builder maps the source value to `adam:render_height_m` when explicit height/level measurements are absent. `TileBuilder.bHeight()` uses `adam:render_height_m` as the approximate fallback. It does not read the original GeoJSON at runtime.

The positive source values range from 1 m to 247 m; the average positive value is 45.3 m and their sum is 79,011 m. These are cartographic render heights, not verified survey measurements. See `manila-building-layer-height.md`.

## Materials and geometry

- The builder preserves source building properties and uses mapped building/facade colour and material tags where available.
- Roof colour/material tags are handled separately where present.
- Missing colours use the renderer's neutral/material fallback; no photo-derived colour is invented.
- A feature's polygon components are geometry parts, not separate building counts.
- Street labels are built from the named transportation source; no address number is inferred from an internal OSM ID.

## Disabled facade-window output

Generated `facade-windows-*.geojson` files are archived outputs. The active Manila builder does not include them in the runtime shard set, the native renderer does not render those window polygons, and the release workflow does not package them as runtime windows. There is no verified photo-textured facade layer in the current Manila runtime.

## Streaming and ground

Manila uses 1 km GZIP JSONL shards and filters records to the current render tile. The manifest currently lists 77 source cells. Manila terrain is deliberately disabled and the renderer returns ground height zero for this mode.

## Workflow

`.github/workflows/android-release.yml` rebuilds Manila shards from `building-layer.geojson`, commits generated outputs to `main`, checks manifest/shard presence, then builds and signs the APK. Its validation does not prove visual correctness on a device.
