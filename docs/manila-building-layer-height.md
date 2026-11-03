# Manila `building-layer.geojson` — building height data

## Source

- Repository: `saeedhub101/Adam-World`
- File: `data/facades/cities/manila/layers/building-layer.geojson`
- Original height property: `render_height`
- Runtime-normalized property: `adam:render_height_m`

## Dataset statistics

| Metric | Value |
|---|---:|
| Buildings | 1,759 |
| Buildings with a recorded positive height | 1,748 |
| Buildings without a recorded positive height | 11 |
| Actual polygon components | 213,808 |
| Lowest positive height | 1 m |
| Highest height | 247 m |
| Average positive height | 45.3 m |
| Sum of positive heights | 79,011 m |

## Exact runtime handling

The source layer does not provide `height` or `building:levels` as its height field. Its height values are in `render_height`.

The current pipeline is:

1. `tools/build_manila_stream_shards.py` reads each building feature's properties.
2. When explicit `height`/floor-count tags are absent, it copies the source `render_height` value into `adam:render_height_m`.
3. The generated feature is stored in the Manila compressed JSONL/GZIP spatial shards.
4. `TileBuilder.bHeight()` reads `adam:render_height_m` as an approximate fallback after explicit metric height and floor-count values.

Thus the native app does not open the original GeoJSON file at runtime, and the Java renderer currently reads the normalized tag rather than the raw `render_height` key. The normalized value preserves the source render-height measurement; it is not a newly surveyed height.

## Interpretation

- The highest recorded value is **247 m**; zero is not the maximum.
- The lowest positive recorded value is **1 m**.
- The average and sum above include positive recorded values only.
- The 213,808 figure counts polygon components, not 213,808 separate buildings.
- The 11 features without a positive recorded value require the renderer's missing-height fallback; absence must not be treated as a measured zero.
- Render heights are cartographic extrusion values and must be labelled approximate, not verified building surveys.

## Required preservation

Keep the source `render_height` value, its normalized runtime representation, the source feature ID and the source attribution. Do not silently replace it with `height` or `building:levels` when those fields are absent.
