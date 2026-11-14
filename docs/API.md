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

## Private browser and shared product setup

`GET /` serves the same-origin responsive browser UI. `POST /session` accepts
`{"token":"owner token"}` with `X-PocketLog: 1`, creating an eight-hour Secure,
HttpOnly, SameSite=Strict host cookie. `DELETE /session` invalidates that cookie's
session. No CORS; cross-site requests are rejected. Cookie-authenticated writes
also require `X-PocketLog: 1`. Native bearer authentication remains unchanged.
No tokens belong in URLs or localStorage. All API routes below require owner auth.

- `GET /v1/workspace`: `revision`, `onboarding_version` (0/1), `focus_minutes`,
  `distraction_minutes` (0–1440), and ten category styles `{id,name,color,role}`.
  Names max40 characters; color #RRGGBB; role focus/neutral/distraction.
- `PUT /v1/workspace`: full strict schema, including current revision; 409 on
  stale revision. Returns incremented revision. Completing onboarding never
  enables collection or authorizes AI. These preferences survive journal deletion.
- `GET /v1/ai`: redacted provider config, `available`, `enabled`, `has_key`,
  `provider_id`; API key never returned.
- `PUT /v1/ai`: `{endpoint,model,api_key?,image_support?,response_format?}`;
  response_format json_schema(default)/json_object; each save rotates provider_id.
  Omitted key retains it only on same enabled endpoint; empty explicitly clears.
- `DELETE /v1/ai`: removes key, writes disabled tombstone, invalidates analysis.
- `POST /v1/ai/test`: fixed synthetic prompt only; no journal; one concurrent
  test/chat request, eight-second model timeout; returns `{ok,journal_data_sent:false}`.
- `POST /v1/ask`: `{question,from_ms,to_ms,provider_id,consent:true}`; question
  max1000 chars, range max7days within retention. Sends up to100 derived cards,
  max24,000 bytes of evidence. Returns `{answer,scope,stored:false}`. No history,
  raw context, screenshots, feelings, reflection or model tools. Refuses stale
  identity and invalidates output if journal deletion/provider epoch changes.
- `POST /v1/reviews`: strict `{id,at_ms,kind,text,end_ms,rating,source}`; optional
  fields may be null. kind review/flow/agent. Flow requires end_ms (at most24h,
  not future); review requires rating focused/neutral/distracted; agent requires
  source codex/claude. Text max4000 chars; at most500 stored records, 30-day TTL.
  Stable IDs replace existing records, making retries safe.
- `GET /v1/reviews?from_ms=...&to_ms=...`: private range up to30days; returns
  `{records,truncated}`, at most100 records/470KiB of records. Journal deletion
  removes all of these records. No raw observations are readable here.

Observation ingestion additionally accepts optional `provider_id` (64 hex),
binding per-observation model consent to saved provider configuration. Omitted
IDs preserve legacy receipt hashes but cannot authorize a dynamically configured
provider. Changing model settings invalidates pending model jobs/epoch; it does
not erase source journal or retract requests already sent to the provider.
