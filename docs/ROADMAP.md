# Development plan

## Milestone 0 — foundation (this commit)

Private repository, Kotlin app identity, HTTPS connection setup, Keystore-backed
credentials, daily history reader, mood/energy check-in, Rust authenticated API,
SQLite persistence, idempotent ingest, limits/retention/delete, hardened Compose,
and tests. No production migration or automatic capture is claimed.

## Milestone 1 — identity and low-power recording

Short-lived, one-use pairing; device-specific upload/read scopes; rotate/revoke;
versioned schema. Encrypted and bounded queue; stable retry IDs; WorkManager sync;
visible pause/backlog/exclusion state. Port relevant infovore collector code with
provenance and tests rather than the Health Connect/browse app as a whole. Start
with app sessions; add consented context separately. Measure baseline vs metadata
vs context capture over a full day, including network bytes, wakeups, storage,
and battery. Define budgets from measurements, not an invented drain percentage.

## Milestone 2 — private understanding

Isolated worker and durable bounded jobs. Raw-context TTL on success/failure;
restricted decoding; optional local model endpoint; external AI opt-in; no tools
for models. Segmentation, editable cards/categories and uncertainty. No automatic
inference of emotions or medical conclusions. Graceful operation when AI is down.

## Milestone 3 — daily reflection

Compose daily timeline and offline cache, activity details/corrections, mood/energy
annotations, accessible themes, gentle daily reflection and user-chosen reminders.
Surface correlations as tentative and allow dismissal. Do not optimize a focus score.

## Milestone 4 — ownership and integration

Encrypted backups with restore tests, JSON export/import and verified deletion
through the pipeline. Lightweight local-only mode. Optional infovore connector
with dedicated scopes and explicit, previewable public projections. No raw content
or full timeline exposed by default. Migration is separately reviewed.
