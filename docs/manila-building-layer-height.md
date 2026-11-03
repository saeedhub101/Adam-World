# Manila `building-layer.geojson` — Building Height Data

## Source

- Repository: `saeedhub101/Adam-World`
- File: `data/facades/cities/manila/layers/building-layer.geojson`
- Height attribute used by this dataset: `render_height`

## Dataset statistics

| Metric | Value |
|---|---:|
| Buildings | 1,759 |
| Buildings with a recorded height | 1,748 |
| Buildings without a recorded height | 11 |
| Actual polygons | 213,808 |
| Lowest positive height | 1 m |
| Highest height | 247 m |
| Average positive height | 45.3 m |
| Sum of positive heights | 79,011 m |

## Important interpretation

This file does **not** provide `height` or `building:levels` as the height source. The recorded building heights come from `render_height`.

Therefore, the game must read `render_height` directly when deriving the displayed building height. It must not assume that `height` or `building:levels` exists, and it must not treat their absence as a zero-height building.

For a feature with a valid positive `render_height`, use that value as the building's height in metres. If `render_height` is absent, null, invalid, or non-positive, handle the feature through an explicit missing-height fallback rather than silently interpreting it as a measured zero.

## Zero-height note

A reported zero height is not the maximum height in this dataset. The highest recorded height is **247 m**. The minimum positive recorded height is **1 m**; the positive-height average is **45.3 m**.

## Required game-side behavior

1. Read `properties.render_height` from each building feature.
2. Convert and validate the value as metres before using it for vertical scaling.
3. Preserve the distinction between a missing height and a measured height of zero.
4. Do not substitute `height` or `building:levels` as the primary source for this layer.
5. Keep the source file's building and polygon geometry intact; the polygon count is not the building count.

## Result

The dataset contains **1,759 buildings**, with height values recorded for **1,748** of them, and **213,808 actual polygons**. The maximum recorded height is **247 metres**.

*Statistics and source-field interpretation documented from the supplied inspection of `building-layer.geojson`.*
