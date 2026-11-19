# Development plan

## Milestone 0 — historical foundation

Public MIT repository, Kotlin app identity, HTTPS connection setup, Keystore-backed
credentials, daily history reader, Rust authenticated API,
SQLite persistence, idempotent ingest, limits/retention/delete, hardened Compose,
and tests. This records the original foundation, not the current release. Manual
mood/energy entry has since been removed; historical records/API remain. Current
capture and interface status is recorded in the implementation updates below.

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

Native Kotlin day/week timeline and offline cache, activity details/corrections,
accessible themes, optional text reflection and user-chosen reminders. Manual
mood/energy entry is outside the current product scope.
Surface correlations as tentative and allow dismissal. Do not optimize a focus score.

## Milestone 4 — ownership and integration

Encrypted backups with restore tests, JSON export/import and verified deletion
through the pipeline. Lightweight local-only mode. Optional infovore connector
with dedicated scopes and explicit, previewable public projections. No raw content
or full timeline exposed by default. Migration is separately reviewed.

## Implementation update — 2026-11-14

The infovore phone feature port now implements opt-in app sessions, separate
allowlisted text/image capture, pre-capture exclusions, pause, encrypted bounded
queue/cache, WorkManager sync/retries/quarantine, isolated server processing,
optional structured-output AI with per-capture consent, editable cards, daily
reflection, category overview and daily JSON export. See
[exact coverage and limitations](INFOVORE_PORT.md).

Still pending: physical-device permission/capture/Keystore/Doze tests and full-day
battery measurements; physical-device pairing/revocation acceptance; encrypted backup/import/restore;
local-only mode; reminders; independent security review; owner-approved infovore
publishing connector. Earlier milestones are product sequencing, not a claim that
all of these remaining requirements shipped in this port.

## infovore capture parity (2026-11-16)

By owner decision PocketLog now follows infovore's phone capture model, sampling
more densely: context every 10 seconds (infovore: 60 s) while an allowed app is in
front of an unlocked screen and on app switches, 720 px / ≤300 KB screenshots, owner-enabled automatic
summaries (replacing per-capture consent), model-labeled topic keywords, a permanent
server journal and a 10 GiB default storage budget that removes the oldest
screenshots first. Battery, Doze and physical-device acceptance of this cadence are
not yet measured. See [INFOVORE_PORT.md](INFOVORE_PORT.md).

## Private API contract (2026-11-17)

`/v1` now has a written additive compatibility policy with `GET /v1/status`
capability flags, uniform JSON errors with `Retry-After` on 429/503, complete
keyset-paged reads with bounded search (`GET /v1/activities`), a per-route
credential scope matrix test and a standard-library example client exercised by
the Docker smoke. Android's native timeline now exposes the same bounded card
search with 1/7/30-day ranges, optional categories and cursor continuation.
Import/restore, local-only mode and reminders remain pending.
See [API.md](API.md).

## Website and official Dayflow alignment (2026-11-14)

Shared onboarding, native/web day/week timelines, custom categories and ordered
classification rules, goals, native provider protocols, saved multi-turn Chat and
category charts, provider-bound daily/weekly brief schedules, cross-device focus
timers, per-turn native agent JSONL import/replay, shared collection pause, separate
short-term image replay consent and expiring/revocable device keys are implemented.
The native client uses Kotlin platform Views. Manual mood/energy entry is removed;
legacy records and API remain, while optional text reflection and focus review
are separate features.
See [the exact parity matrix](DAYFLOW_PARITY.md). Desktop continuous recording/audio,
OCR pixel redaction, CLI inference, background desktop session scanning, generated
artwork and Dayflow's separately hosted Flow backend are not implemented. Physical
capture/battery acceptance is still required; no additional high-frequency work has
been introduced on Android.
