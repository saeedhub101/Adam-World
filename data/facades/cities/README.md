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

## Mandatory evidence-to-GLB policy

Finding a photo is not a deliverable. A nearby photo, a camera-bearing match, a valid
JPEG, or a high quality score is only a discovery candidate. It is not proof that
the image depicts the target building or the intended facade.

1. Discover photo metadata for every configured city; do not privilege one country.
2. Before downloading an image, reject records without an explicit reusable licence,
   a real visual image asset, capture coordinates, camera azimuth, usable field of
   view, acceptable capture accuracy, and a camera ray that intersects the target
   footprint facade within the strict distance/angle limits.
3. Download candidate image bytes only to temporary workflow storage for image
   decoding, dimensions, blur/exposure checks and visual inspection. Do not commit
   candidate photos or bulk review galleries to the repository.
4. Verify exact building identity and facade side against the footprint, capture
   position, camera direction and visible architectural details. Reject interiors,
   unrelated objects, distant buildings, sky/ground-only views, wrong floors,
   wrong facades, duplicates and ambiguous matches. Proximity alone must never
   approve an image.
5. Only an explicitly verified, correctly licensed, high-resolution image may be
   copied into app assets. Record photo ID, source, licence, image hash, OSM way ID,
   facade edge, camera calibration, verification method and timestamp.
6. In the same workflow run, immediately rebuild the target building mesh and
   textured GLB from its footprint and verified image. Apply the image as a
   perspective-correct facade texture on the building surface itself, not as a
   separate floating image or overlay. Validate GLB bounds, orientation, scale,
   texture embedding and target coordinates before committing it.
7. If exact identity or camera calibration cannot be established, do not texture
   the building and do not store the image as a project asset. Keep only compact
   metadata/rejection reason so a later pass can retry.

The current GLB builder deliberately accepts only human_verified assignments in
app/src/main/assets/facades/verified_facades.json. Automated geometric and image
quality gates can reject bad candidates, but they cannot honestly prove exact visual
building identity by themselves. Until a visual identity verifier is implemented
and validated, ambiguous candidates must remain unapproved; never fabricate an
approval or label a neutral footprint shell as a photo-real building.

Keep compact metadata, source hashes, review decisions and final GLB provenance in
the repository. Never check in a photo if its licence does not permit redistribution.


## Free offline visual-verification stage

The workflow includes `tools/facades/visual_verify_facades.py`, a local
computer-vision screening stage built on **OpenCV (BSD-3-Clause), NumPy and Python**.
It requires no API key, paid service, hosted model, or external AI endpoint. It
downloads eligible Panoramax preview bytes to memory for analysis and does not
store unverified photographs in the repository.

For each candidate it checks image decoding and resolution, blur/sharpness,
exposure, contrast, entropy, edge density and vertical facade-line cues. It also
rechecks camera-to-footprint distance and bearing gates, then writes a per-city
`visual_verification.json` report with metrics, image hash, license, camera
metadata and explicit rejection reasons.

**Identity boundary:** generic computer vision cannot prove from one street
photograph plus an OSM footprint that the pictured object is exactly a specific
building or exact floor. Even a passing candidate is labelled
`candidate_for_human_identity_review`, retains
`identity_status=unverified_requires_human_confirmation`, and has
`texture_assignment_allowed=false`. It cannot enter the verified manifest or
produce a photo-textured GLB automatically. Human confirmation of exact
building, facade side, level and license remains the final identity gate.
The GLB builder then projects the approved image onto the corresponding wall.

The workflow installs only free open-source packages and runs this stage
without any API key or paid endpoint. Install locally with
`python -m pip install opencv-python-headless numpy`.
