# PocketLog API v1

All `/v1/*` routes require `Authorization: Bearer <64-hex-owner-token>` before
JSON/query parsing. `/healthz` returns only process health. No CORS or public
projection. Responses use `Cache-Control: no-store`; bodies are capped at 64 KiB.

| Method | Path | Behavior |
|---|---|---|
| POST | `/v1/activities` | `{activities:[{id,start_ms,end_ms,app,category}]}`, max 100; stable IDs, conflicting replay 409 and atomic rollback |
| POST | `/v1/checkins` | `{id,at_ms,mood,energy}`, mood/energy 1–5; same ID replaces |
| POST | `/v1/workspace/preview` | `{workspace,sample:{app,title,category,edited?}}`; test an unsaved draft with the server classifier, no persistence/model call |
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
must be reviewed and never infers feelings. Each card also includes
`pending_context`: true when the server still has matching raw observations for
that stored activity (same app, start inclusive/end exclusive). This is separate
from provenance: existing AI/user notes can have late evidence pending. It does
not mean active generation, AI consent or a successful phone capture. False only
means no matching server-pending evidence; excluded/offline/expired context is
not inferred. Older servers may omit the field. Timeline clipping does not change
this association. No extra collection or polling is introduced. See [analysis](ANALYSIS.md).

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
  Omitted key retains it only on same enabled endpoint and protocol; empty explicitly clears.
- `DELETE /v1/ai`: removes key, writes disabled tombstone, invalidates analysis.
- `POST /v1/ai/test`: fixed synthetic prompt only; no journal; one concurrent
  test/chat request, eight-second model timeout; returns `{ok,journal_data_sent:false}`.
- `POST /v1/ask`: `{question,from_ms,to_ms,provider_id,consent:true}`; question
  max1000 chars, range max169hours (seven local days across DST) within retention. Sends up to100 derived cards,
  max24,000 bytes of evidence. Optional conversation/request IDs save bounded history;
  returns `{answer,scope,stored}`. No
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

## Extended private APIs

All routes below require owner authentication or a journal device key, except device
management, which requires owner token/browser session. Body limit remains 64 KiB.

- `GET/PUT /v1/workspace`: ten built-ins plus up to 30 `custom_…` categories;
  optional `rules` array (32 max) of `{id,category,app,title_contains,enabled}`.
  First match wins; specified conditions must both match. Manual edits win.
  Unknown custom IDs are rejected; deleting a used category returns 409.
- `GET /v1/conversations`: 100 thread summaries. `GET /v1/conversations/{id}`:
  50 recent pairs / 460 KiB, oldest first, `truncated` and `next_cursor`.
  Cursor query uses `before_ms` **and** `before_id` to preserve tied timestamps.
  DELETE removes the thread and invalidates in-flight results. Optional ask fields
  `conversation_id` and `request_id` must both be present or absent; same payload ID
  replays the saved answer, conflicting reuse is 409. 500 pairs / 100 threads / 30d.
- `GET/POST/DELETE /v1/focus`: GET returns `{session:null|{id,at_ms,minutes,text}}`;
  POST starts one session (1–180 minutes, 300-char text, timestamp within one minute).
  DELETE body `{id}` discards only that session. `POST /v1/focus/finish` body `{id}`
  atomically creates a flow review and clears the timer. Finishing is idempotent,
  and an old ID cannot finish a newer timer. Timers expire after 30 days.
- `GET /v1/briefs?from_ms=…&to_ms=…`: bounded saved briefs (100 / 30d).
  `POST /v1/briefs`: `{id,from_ms,to_ms,kind:"daily"|"weekly",provider_id,consent:true}`,
  up to 26h / 169h respectively; stable IDs replay results without another call.
  `DELETE /v1/briefs/{id}` removes one. `GET/PUT /v1/briefs/schedule`:
  `{revision,enabled,provider_id,timezone,minute_of_day,daily,weekly}`. Time is
  04:00–23:59, timezone is IANA; enabling requires current provider identity and
  independent ongoing consent. Three durable attempts/period, two periods/pass;
  no historical catch-up. Owner deletion disables schedules.
- `GET/PUT /v1/agent-sessions`, `GET/DELETE /v1/agent-sessions/{id}`. PUT body:
  `{id,revision,at_ms,source:"codex"|"claude",project,title,status,turns}`;
  status `active|waiting|completed`; 40 turns `{id,at_ms,role:"user"|"assistant",text}`,
  1000 chars/turn, ordered times, unique IDs, 100 sessions / 30d. List excludes text.
  Only locally previewed, explicitly approved turns should be uploaded. Never model evidence.
- `GET/PUT /v1/collection`: `{revision,paused,changed_ms}`; PUT checks revision,
  server assigns changed_ms. Paused ingest returns 423 and deletes pending raw
  context/jobs/unvalidated replay. Explicit check-ins/reflections remain available.
  Phones apply on existing sync cadence; offline stop is not immediate.
- Observation optional `replay_consent:true` additionally requires image consent
  and a JPEG. Independent archive, max256 / 24h; pending images are unreadable.
  Worker validates, resizes/re-encodes, strips metadata and bounds to 480px/24KiB.
  `GET /v1/replay?from_ms=…&to_ms=…` lists validated metadata; GET
  `/v1/replay/{id}` returns `{jpeg_base64}`; GET `/v1/replay/{id}/image` returns
  authenticated image/jpeg. DELETE `/v1/replay/{id}` removes the archive copy.
  Ordinary analysis-only images remain unreadable. No images in ordinary exports.
- `GET/POST /v1/devices`, `DELETE /v1/devices/{id}`: owner only. POST body
  `{label,scope:"journal"|"collector",days:1..90}`; returns one-time `{id,token,scope,expires_ms}`.
  List never returns token/hash. At most32 active credentials, SHA-256 digests only.
  Journal scope accesses private APIs except device management; collector scope
  only POSTs activities/observations and GETs collection/AI configuration. Neither
  can log into owner browser sessions. Revocation affects new requests; rotation
  of the owner token invalidates all device keys at service startup. Owner GET
  also returns nullable `last_authenticated_ms`: latest permitted device-key
  authentication, rounded down to a minute and updated at most once/minute/key
  on a best-effort basis. Null means no record since this metadata was introduced;
  absent means an older server. It does not imply request success, upload, current
  connectivity or capture consent. No request/path/IP/content history is stored.
  The timestamp is credential metadata, erased with revocation/expiry cleanup or
  owner rotation; ordinary journal deletion preserves device configuration.
  Metadata uses a separate table with deletion cleanup, keeping the credential
  schema compatible with older binaries during rollback.

AI configuration adds optional `protocol:"openai"|"anthropic"|"gemini"` (default
openai for compatibility). Missing keys are retained only for the same endpoint
**and protocol**. Native endpoint paths must match the protocol and Gemini model.

Journal deletion includes all new content tables, disables brief schedules and
invalidates results. Workspace/provider/device configuration remains; other trusted
devices can still upload until paused or revoked. Exports are private derived data,
not a complete backup of every new content type.

### Draft classification preview

`POST /v1/workspace/preview` accepts the same strict workspace object as PUT,
plus one `sample`: app1–200 ASCII letters/digits/`._-`, title0–200 Unicode
characters without controls, a category present in the proposed workspace, and
optional `edited` (defaultfalse). It returns `{category,matched_rule_id,manual_override}`.
The first enabled rule whose exact case-sensitive app and case-insensitive title
conditions both match wins. With no match, the sample category remains; an edited
sample always retains its category and returns null matched rule.

Owner/browser-session and journal-device access only; collector keys denied.
Normal authentication-before-parsing,64KiB body limit and no-store behavior apply.
The handler evaluates only supplied data, with no database/provider operations,
retention cleanup, saved preferences or journal mutations. Existing authentication
metadata for journal keys remains unchanged. Draft validation is shared with Save;
save-only revision/category-in-use conflicts are checked only when saving. A
successful preview does not reserve a revision or guarantee a subsequent Save.
