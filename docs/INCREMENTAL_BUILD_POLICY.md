# Incremental build and persistence policy

This policy applies to every data-import, extraction, conversion, enrichment, geometry, packaging, and release workflow in Adam World.

## Mandatory execution contract

1. **Persist while work is running.** Each completed independent stage and each bounded output batch must be committed to `main` during the run. Do not wait for the entire workflow to finish before saving its outputs.
2. **Checkpoint contents.** A checkpoint includes the produced files, a machine-readable state/manifest, source fingerprint, completed item or batch identifiers, and enough metadata to validate the saved output.
3. **Resume, do not restart.** On retry, inspect the committed checkpoint and repository contents first. Reuse valid outputs whose source fingerprint and validation still match. Recompute only missing, invalid, or source-changed items.
4. **Atomic publication.** Write each file to a temporary sibling and rename it into place. Publish checkpoint state only after its referenced files are durable. Keep the last complete runtime manifest active until a new complete dataset passes validation.
5. **Bounded commits.** Commit small batches to `main`; retry transient push failures with fetch/rebase and verify whether GitHub accepted a timed-out push before retrying.
6. **Failure isolation.** A failed source or independent stage must be recorded and must not prevent unrelated stages from completing. A failed batch is retried alone.
7. **No false completion.** A partial checkpoint must be explicitly marked `building` / `complete: false`. Mark a dataset complete only after all files, counts, integrity checks, and runtime requirements pass.
8. **No duplicate imports.** Inventory existing files and manifests before importing. Do not overwrite valid data unnecessarily or create millions of tiny files.
9. **Logs are not persistence.** Progress messages and workflow artifacts do not satisfy this policy. Outputs and checkpoint state must be committed to the repository during the run.
10. **Document every pipeline.** Each workflow must state its checkpoint format, resume key/fingerprint, batch size, validation, and failure-recovery behavior.

## Tabarbour stream implementation

`tools/build_offline_city_tiles.py --city tabarbour --checkpoint-every 20 --publish-checkpoints` writes each compressed cell directly into the stream directory and commits checkpoint state after each batch. The state file is `.build-state.json`; it is separate from the runtime `manifest.json`, so the game continues using the last complete manifest until final validation succeeds.

A retry compares the source fingerprint and tile inventory. It reuses valid completed cells and rebuilds only missing or invalid cells. The source assembly is cached in bounded compressed chunks under `.build-cache/`, so a later interruption does not require reassembling all source layers. Each tile batch is published with `.build-state.json`; the final runtime manifest is switched only after validation.

Before doing any expensive rebuild, the script checks the current complete manifest, its successful runtime-validation record, the source tree's latest Git commit, and every referenced cell's existence and size. If the source tree has not changed since the published manifest and all 1,866 cells remain present and valid, it records the source fingerprint and skips the rebuild entirely. This avoids reprocessing an already complete city.

## Other implemented pipeline checkpoints

- `tools/ci/publish-checkpoint.sh` is the shared retry-safe commit/push helper.
- The Manila facade workflow publishes the review queue, visual-verification report, local-photo gallery, and verified GLB outputs after each completed stage.
- Manila review-queue matching stores compressed `review_queue/part_*.json.gz` shards and a small `.review_queue_checkpoint.json` index every 500 buildings; retries load completed shards and continue from the next building. The final `review_queue.json` is a small manifest, not a 128 MB monolithic JSON file.
- Manila visual screening writes and publishes `visual_verification.json` every 100 photo candidates. It resumes completed candidates when the review-queue fingerprint matches and fails the workflow if checkpoint publication fails.
- The first-three Panoramax GeoJSON workflow publishes its validated GeoJSON and audit report immediately after validation.

The Android APK release is a packaging workflow, not a source-data extraction pipeline; it publishes the APK/release artifact after the build and verification stage.

## Acceptance checks

- At least one committed checkpoint appears before the long-running build stage completes.
- A forced interruption after a checkpoint leaves its cells and state on `main`.
- A retry reports reused cells and processes only missing/invalid cells.
- A source change invalidates only affected output according to the pipeline's fingerprinting granularity.
- The final manifest references existing, readable files and is marked complete only after validation.
