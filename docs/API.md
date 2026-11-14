# PocketLog API v1

All `/v1/*` routes require `Authorization: Bearer <64-hex-owner-token>` before
JSON/query parsing. `/healthz` returns only process health. No CORS or public
projection. Responses use `Cache-Control: no-store`; bodies are capped at 64 KiB.

| Method | Path | Behavior |
|---|---|---|
| POST | `/v1/activities` | `{activities:[{id,start_ms,end_ms,app,category}]}`, max 100; stable IDs, conflicting replay 409 and atomic rollback |
| POST | `/v1/checkins` | `{id,at_ms,mood,energy}`, mood/energy 1–5; same ID replaces |
| POST | `/v1/observations` | `{id,at_ms,app,text,analysis_consent,external_ai_consent?,jpeg_base64?,image_consent?}`; private processing consent required; model/image consent independently default false |
| POST | `/v1/cards` | `{id,title,summary,category}`; existing activity required; title 1–200 characters, summary ≤1,000; user edit wins over worker |
| POST | `/v1/reflections` | `{id,at_ms,text}`, ≤2,000 characters; same ID replaces, blank text deletes |
| GET | `/v1/timeline?from_ms=…&to_ms=…` | Up to 26 hours, clipped intervals; activities, checkins, derived cards, reflections and `truncated`; never raw context |
| GET | `/v1/overview?from_ms=…&to_ms=…` | Up to 30 days; complete clipped category totals, top 20 apps, self-reported averages; title keywords sample latest 1,000 cards |
| GET | `/v1/status` | Schema/capabilities, retention and pending-context count, no secrets or content |
| DELETE | `/v1/journal` | All owner activities, check-ins, notes, reflections, raw context, dedup receipts and jobs; invalidates in-flight analysis |

Timestamps are Unix milliseconds. Android uses local 04:00 boundaries, including
DST. Categories: `unsorted`, `work`, `learning`, `communication`, `social_media`,
`entertainment`, `news_reading`, `life_admin`, `rest`, `idle`.
`app` is a package identifier (≤200 ASCII identifier characters). Unknown fields
are rejected; raw screenshots do not belong on `/v1/activities`.

Context: one observation per request, ID ≤80 ASCII alphanumeric/dash/underscore,
text ≤2,000 Unicode characters and 8,000 UTF-8 bytes. Optional base64 JPEG ≤32,768
characters; image consent required. The API bounds opaque input; JPEG decoding
belongs to the isolated worker. Raw timestamps must fall within 24 hours and no
more than 60 seconds in the future. Same-ID same-content replay succeeds without
reintroducing consumed raw content; conflicting payload returns 409. Receipt hashes
remain 30 days, including when raw context has already been consumed.

Storage quotas: 100,000 activities, 10,000 check-ins, 1,000 reflections, 2,000 raw
observations and 60,000 receipts. Capacity exhaustion refuses new input. Timeline
queries return at most 1,000 of each record type and cap serialized output at
480 KiB; `truncated` is true if anything is omitted. Exports use this same contract
and are not guaranteed complete when truncated. Pagination/import remain planned.
Cards identify `source` as `app_only`, `local_excerpt`, `ai` or `user`. AI output
must be reviewed and never infers feelings. See [analysis](ANALYSIS.md).

SQLite is plaintext inside the private volume. Use encrypted host storage and
backups. SQL deletion cannot erase provider copies, exports, SSD history or
snapshots. See [security boundaries](SECURITY.md).
