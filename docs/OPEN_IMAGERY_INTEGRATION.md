# Open imagery integration in Adam World

## Current map origin
The game starts in Amman at latitude 31.9516, longitude 35.9394. City buttons remain unchanged.

## Runtime flow
- OpenStreetMap Overpass remains the source of building footprints, OSM IDs, tags, roads and mapped heights.
- The app stores each loaded building's OSM way ID, center coordinate, name/address fallback, tags and estimated/rendered height in memory.
- After the OSM scene is ready, two keyless public catalogues are queried in the background for the current area (approximately 1.8 km square):
  - OpenAerialMap STAC search: aerial/drone image metadata and catalogue item links.
  - Panoramax federated STAC search: geotagged street photos and available thumbnail assets.
- Panoramax photos with point coordinates are compared to loaded building centers. Nearby candidates within 70 m are listed with OSM ID, distance, date when supplied, and camera azimuth compatibility when available.
- Copernicus Browser is linked at the active coordinates for broad-area Sentinel imagery.

## Accuracy and safeguards
- A nearby photo is only a candidate, not proof that it depicts that building. Direction is checked when the catalogue supplies azimuth; otherwise the UI explicitly marks that manual review is required.
- No photo is automatically painted onto a facade. A verified facade texture needs a clear view of that exact facade, suitable camera direction, image quality, and a compatible licence.
- OpenAerialMap imagery is treated as aerial context/orthophoto evidence, not street-level facade imagery.
- Sentinel-2 is suitable for broad land-surface context (four bands at 10 m; other bands at 20 m/60 m), not windows, doors or facade textures.
- Each image's own catalogue record must be checked for its licence and attribution before copying it into repository assets.
- The imagery lookup runs after city loading and does not block the 3D scene. The panel is opened with the 📷 الصور button.

## Network implementation
The Android WebView loads the app from the local assets directory, so source metadata requests use a native HTTPS bridge. The bridge permits only api.imagery.hotosm.org and api.panoramax.xyz, uses GET only, has timeouts, and limits responses to 4 MiB. No API key or account is configured.

## Known limitation / next validation
The public web retrieval available during implementation could not directly inspect the live STAC search responses for the Amman bbox. Therefore this commit does not claim that either catalogue has images at the exact start point. The app panel will show actual returned counts or the corresponding network/API error on-device. No facade image is declared verified until it has been inspected.