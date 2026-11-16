# infovore phone feature port

Reference reviewed: `/home/sky/Projects/infovore`, commit
`cd2bb4b7548ca3050ca65f65c24d6890b4e6a8e6` (2026-11-14).
PocketLog independently implements the relevant phone journal behavior in Kotlin
and Rust. It does not connect to infovore, copy its secrets, modify its services,
share its database, or migrate existing history. On 2026-11-16 the owner decided
PocketLog should match infovore's phone capture cadence, screenshot quality,
automatic summaries, topic keywords and server-side retention; this table reflects
that decision.

| infovore behavior | PocketLog implementation |
| --- | --- |
| Foreground app observations | Incremental UsageStats reader; only after explicit consent and OS access; background reads no more often than 15 minutes |
| App/session boundaries | Resume/pause, screen-off and shutdown events; completed intervals; deterministic IDs; segments capped at 20 minutes |
| Visible screen text | Separate consent and explicit package allowlist; every 10 s while an allowed app is in front of an unlocked, interactive screen, plus app switches ≥5 s apart (owner decision; denser than infovore's 60 s) |
| Screen JPEGs | On by default with screen text (Dayflow-style), can be turned off separately; same allowlist/exclusions; 720 px long edge, ≤300,000 bytes, mostly-black frames skipped (infovore bounds); retained on the server under the storage budget; no image read API except the separate replay opt-in |
| Sensitive exclusions | Own app, password managers, authenticators, payment/banking patterns, user exclusions; password/editable fields and incomplete window traversal reject the whole window before content capture |
| Secure/locked screen behavior | Locked/screen-off states reject capture; Android window screenshot failure rejects capture; no FLAG_SECURE bypass |
| Pause | Visible settings switch; reset incremental cursor and remove queued context; no backfill across pause |
| Durable uploads | Android Keystore AES-GCM AtomicFiles; endpoint+token destination binding; same IDs across crash/retry |
| Offline and quarantine | FIFO queue, 24 h / 1 GiB / 32,768 record limits; HTTP permanent failures quarantined; visible counts/drop counter; explicit retry/delete |
| Retry/backoff | WorkManager exponential backoff, bounded Retry-After, batches of 32 for up to 45 seconds per run (the sync lock is held meanwhile), default unmetered networking |
| Server context jobs | Separate Rust worker; bounded durable leases, four failed attempts then excerpt fallback; raw text retained permanently, screenshots until the owner storage budget (default 10 GiB) removes the oldest |
| Summaries | App-only cards or labeled local excerpts without a model; with a configured provider and the owner's automatic switch, every session is summarized from its text and up to six screenshots |
| Topic keywords | Model labels on cleaned card titles (infovore `quality.ts` equivalent), local word fallback |
| Summary safety | Treat text/images as untrusted evidence; no model tools; strict schema/category/length validation; no inferred emotions; user corrections win |
| Private daily timeline | Local 04:00 boundary; date navigation; card corrections and optional text reflection; manual mood/energy entry removed, legacy records/API retained |
| Overview | Daily, Monday-based week and 29-day category totals, top apps and locally extracted title keywords; cache/truncation warnings |
| Offline reading | Keystore-encrypted cache, at most 30 entries, about 2.5 MiB including newest response, max 30 days |
| Ownership | Daily JSON export through Android document picker; owner deletion covers source, notes, receipts, reflections and jobs; local erase clears queue/cache and disables capture |

## Remaining differences

There is no continuous screen recording, OCR on the phone, on-device inference,
public projection, merging with desktop Dayflow data, shared infovore owner token or
import of infovore history; the owner does not want these. Summaries group by
PocketLog's UsageStats app sessions rather than infovore's 20-minute cross-app
segments. Text capture also uses a transient OS window screenshot as a
secure-window gate; when image consent is off those pixels are closed without
encoding, saving or upload. Structural sensitive-field checks run before that
screenshot. Accessibility is conservative and can skip an entire browser window
containing an editable search field. Android may omit usage events or report
overlapping activity lifecycles; this is a best-effort activity journal, not a
billing-grade duration meter. Device shutdown without a final OS event can leave an
incomplete session; a gap over 24 hours resets the cursor.

Health Connect and infovore article browsing are not phone-journal features of
PocketLog. Expiring/revocable device keys are implemented; physical pairing
acceptance, encrypted backup/import and automatic reminders still require work. See
the newer [Dayflow matrix](DAYFLOW_PARITY.md) for shared timelines, provider
protocols, Chat, brief schedules and approved session replay.

## Validation and remaining real-device work

Rust tests cover private routes, idempotency/conflicts, consent, image bounds,
retention, corrections, model fallback and deletion during a model request. Model
responses are tested against a disposable loopback HTTP stub, not a live provider.
Android JVM tests cover session boundaries, exclusion precedence, Unicode limits,
queue destination isolation, quarantine, expiry, corrupt records and retry policy.
They use injected in-memory storage; this does not test AndroidKeyStore hardware
or an actual AccessibilityService. Android unit tests, assembleDebug and lintDebug
are required. A disposable Docker smoke test exercises API + worker before upgrade.

No physical phone is attached to this development environment. Installation,
Accessibility behavior across vendors/multi-window, AndroidKeyStore recovery,
work under Doze, and a full-day battery/network benchmark need an actual phone.
Capture stays off when upgrading. Do not label these checks as completed.

Battery protocol: compare three full-day runs on the same device (capture off,
metadata only, context enabled on a small allowlist), with similar screen-on time,
network and charging behavior. Record Android battery attribution, wakeups,
network bytes, queue/cache size, captured/failed observations and timeline delay.
Use `adb shell dumpsys batterystats --reset` before each run and save
`adb shell dumpsys batterystats tw.skyhong.pocketlog` afterward to private local
storage. Define a product battery budget from those measurements before increasing
frequency further or recommending routine image capture. The 10-second cadence
was adopted by owner decision before these runs; they have not been performed.
