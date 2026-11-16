# PocketLog API v1

All `/v1/*` routes authenticate before JSON/query parsing. Native/API clients
send `Authorization: Bearer <64-hex-key>`; use a separately revocable **journal
device key** for an integration. The same-origin website uses an owner session
cookie after login. `/healthz` is unauthenticated and returns only process health.
There is no CORS or public projection. Responses use `Cache-Control: no-store`.
**Request bodies** are capped at64KiB; response limits vary by endpoint (for
example, timeline output is capped at480KiB and reports omitted records).

| Credential | Permitted access |
| --- | --- |
| Owner bearer key or owner browser session | All private APIs, including device-key management |
| Journal device key | Private APIs except `/v1/devices` and its children; cannot log into the website |
| Collector device key | Only POST activities/observations and GET collection/AI metadata; cannot read journals (including GET `/v1/activities`), manage devices or log into the website |

`server/tests/contract.rs` checks this matrix for every `/v1` route and method
with owner, journal, collector, revoked, expired, unknown and malformed keys.

Cookie-authenticated mutations require `X-PocketLog: 1` and are rejected when
cross-site. Bearer clients do not need that browser header. Missing, expired,
revoked and insufficient-scope keys return401. Device-key validity does not grant
phone capture or turn on automatic AI summaries.

The website's once-only issued-key screen labels the selected scope before the
masked credential. Journal keys include separate Android and private API setup
guides; collector keys show uploader setup only. Save the key privately before
closing. It cannot be retrieved again; revoke and reissue if lost. Issuing a key
does not enable capture or model consent.

## First authenticated read

Create a **日誌（手機 app）** device key under website **設定 → 連線與裝置 →
管理裝置金鑰**. It is also suitable for your private API client. Keep the owner
key for website login and recovery. This Bash example reads only status, uses
normal certificate verification, follows no redirects and supplies the key through
curl's standard input rather than its command arguments. Replace the origin with
your own trusted HTTPS address; do not add a path, credentials or query to it.

```bash
pocketlog_api_origin='https://your-private-host:12446'
read -r -s -p 'Journal device key: ' pocketlog_api_key
printf '\n'
if [[ "$pocketlog_api_key" =~ ^[[:xdigit:]]{64}$ ]]; then
  printf 'header = "Authorization: Bearer %s"\n' "$pocketlog_api_key" |
    curl --config - --proto '=https' --fail --silent --show-error \
      --connect-timeout 5 --max-time 10 "$pocketlog_api_origin/v1/status"
else
  printf 'Expected a 64-character hexadecimal device key.\n' >&2
fi
unset pocketlog_api_key
```

A401 means the key is missing, wrong, expired, revoked or lacks the route's scope;
a collector key intentionally cannot read status or timeline. To read activities,
use the same authentication with `/v1/timeline?from_ms=…&to_ms=…`, supplying Unix
milliseconds and a range of at most26hours. Its response is private derived
journal data and may have `truncated:true`; it never contains raw observations.
For a **complete** read, use the paged `GET /v1/activities` described under
[complete reads](#complete-reads-and-search). [`examples/pocketlog_client.py`](examples/pocketlog_client.py)
is a standard-library client showing status, a complete paged read, search, a
category correction and safe retries; CI runs it against a disposable server.

## Routes

| Method | Path | Behavior |
|---|---|---|
| GET | `/v1/activities?from_ms=…&to_ms=…` | Complete keyset-paged derived cards with optional search; see [complete reads](#complete-reads-and-search) |
| POST | `/v1/activities` | `{activities:[{id,start_ms,end_ms,app,category}]}`, max 100; stable IDs, conflicting replay 409 and atomic rollback |
| POST | `/v1/checkins` | `{id,at_ms,mood,energy}`, mood/energy 1–5; same ID replaces |
| POST | `/v1/workspace/preview` | `{workspace,sample:{app,title,category,edited?}}`; test an unsaved draft with the server classifier, no persistence/model call |
| POST | `/v1/observations` | `{id,at_ms,app,text,analysis_consent,external_ai_consent?,jpeg_base64?,image_consent?}`; private processing consent required; image consent defaults false; current Android omits legacy `external_ai_consent`; body limit 512 KiB |
| POST | `/v1/cards` | `{id,title,summary,category}`; existing activity required; title 1–200 characters, summary ≤1,000; user edit wins over worker |
| POST | `/v1/reflections` | `{id,at_ms,text}`, ≤2,000 characters; same ID replaces, blank text deletes |
| GET | `/v1/timeline?from_ms=…&to_ms=…` | Any `from_ms` ≥ 0, up to 26 hours, clipped intervals; activities, checkins, derived cards, reflections and `truncated`; never raw context |
| GET | `/v1/overview?from_ms=…&to_ms=…` | Any `from_ms` ≥ 0, up to 30 days; complete clipped category totals, top 20 apps, self-reported averages; `title_keywords` from the latest 1,000 card titles (model topic labels where available, else local words) and `keyword_mode` `model`/`local`/`mixed` |
| GET | `/v1/status` | `schema_version:2`, `api:{version,capabilities}`, `analysis`, `raw_retention:"storage_budget"`, `journal_retention:"permanent"`, pending-context count and `storage` usage; no secrets or content |
| GET/PUT | `/v1/storage` | Owner storage budget; see below |
| DELETE | `/v1/journal` | All owner activities, check-ins, notes, reflections, raw context, dedup receipts and jobs; invalidates in-flight analysis |

Timestamps are Unix milliseconds. Android uses local 04:00 boundaries, including
DST. Categories: `unsorted`, `work`, `learning`, `communication`, `social_media`,
`entertainment`, `news_reading`, `life_admin`, `rest`, `idle`.
`app` is a package identifier (≤200 ASCII identifier characters). Unknown fields
are rejected; raw screenshots do not belong on `/v1/activities`.

Context: one observation per request, ID ≤80 ASCII alphanumeric/dash/underscore,
text ≤2,000 Unicode characters and 8,000 UTF-8 bytes. Optional base64 JPEG ≤400,000
characters (about 300 KB); image consent required. The API bounds opaque input; JPEG
decoding belongs to the isolated worker. Raw timestamps must fall within 24 hours and no
more than 60 seconds in the future. Same-ID same-content replay succeeds without
duplicating retained raw content; conflicting payload returns 409. Receipt hashes
are retained permanently.

Retention: activities, check-ins, cards/notes, reflections, receipts and raw
observation text are permanent. Screenshots are bounded by the storage budget.
Quotas: 2,000,000 activities, 10,000 check-ins, 100,000 reflections and 20,000
unanalyzed observations; receipts are not capped. Capacity exhaustion refuses new input.

`GET /v1/storage` returns `{revision,limit_bytes,used_bytes,image_bytes,image_count,
text_only_before_ms,min_limit_bytes,max_limit_bytes,default_limit_bytes}`; defaults
10 GiB, range 1 GiB–1 TiB. `used_bytes` is SQLite pages in use × page size.
`PUT /v1/storage` `{revision,limit_bytes}` returns the same shape; stale revision
409, out of range 400. When usage exceeds the limit the worker removes the oldest
analyzed screenshots (text kept) down to 95% and advances `text_only_before_ms`. Timeline
queries return at most 1,000 of each record type and cap serialized output at
480 KiB; `truncated` is true if anything is omitted. Exports use this same contract
and are not guaranteed complete when truncated; `GET /v1/activities` pages
the same cards completely. Import/restore remains planned.
Cards identify `source` as `app_only`, `local_excerpt`, `ai` or `user`. AI output
must be reviewed and never infers feelings. Each card also includes
`pending_context`: true when the server still has matching unanalyzed observations for
that stored activity (same app, start inclusive/end exclusive). This is separate
from provenance: existing AI/user notes can have late evidence pending. It does
not mean active generation, AI consent or a successful phone capture. False only
means no matching server-pending evidence; excluded/offline/expired context is
not inferred. Older servers may omit the field. Timeline clipping does not change
this association. No extra collection or polling is introduced. See [analysis](ANALYSIS.md).

SQLite is plaintext inside the private volume. Use encrypted host storage and
backups. SQL deletion cannot erase provider copies, exports, SSD history or
snapshots. See [security boundaries](SECURITY.md).

## Compatibility

- `/v1` only changes additively: new routes, new optional request fields and new
  response fields. Clients must ignore unknown response fields. Requests stay
  strict (unknown fields are rejected) so a typo cannot silently drop data.
- Removing or changing the meaning of a field, route, status code or bound needs
  a new version prefix; `/v1` would keep working alongside it for at least one
  documented release, with the deprecation recorded here first.
- Detect features with `GET /v1/status` → `api:{version:1,capabilities:[…]}`
  instead of probing routes. Older servers omit `api`; treat that as no
  capabilities. Current flags: `activity_pages` (GET `/v1/activities`),
  `activity_search` (`q`/`category` on it), `json_errors` (every `/v1` error is
  JSON) and `retry_after` (503/429 carry `Retry-After`). Older flags such as
  `GET /v1/ai` `probe_provider_binding` remain.
- An older Android client keeps using `/v1/timeline` unchanged.

## Errors and retries

Every `/v1` error response is `{"error":"<fixed message>"}` with
`Content-Type: application/json`, including framework rejections. Messages never
echo input, keys or content. Status codes are unchanged from earlier servers:

| Status | Meaning | Retry? |
| --- | --- | --- |
| 400 / 422 | Invalid query/body; 422 is malformed-but-JSON such as an unknown or mistyped field | No; fix the request |
| 401 | Missing, wrong, expired, revoked or out-of-scope key (indistinguishable on purpose) | No; reissue the key |
| 404 / 405 | Unknown `/v1` route or method (`Allow` lists valid methods) | No |
| 409 | Conflict: reused ID with different content, stale revision, changed provider, or `cursor expired` | No; re-read current state |
| 413 / 415 | Body over the route limit / not `application/json` | No |
| 423 | Collection paused; capture uploads refused | After the owner resumes |
| 429 | Model or test slot busy, browser session limit | Yes, after `Retry-After` |
| 503 | Server busy (bounded request slots) or storage unavailable | Yes, after `Retry-After` |
| 507 | Journal/context capacity reached | No; owner action needed |

Writes use stable client IDs (activities, observations, check-ins, reflections,
reviews, briefs) or full replacement (cards, workspace with revision), so retrying
after a timeout or 429/503 cannot duplicate data. A conflicting replay returns 409.

## Complete reads and search

`GET /v1/activities?from_ms=…&to_ms=…[&limit=][&q=][&category=][&after_start_ms=&after_id=&epoch=]`
(journal key or owner; collector keys refused). Range up to 30 days, any past
start. Returns `{activities,cards,next_cursor,journal_epoch,scanned}`: the same
clipped activities and derived cards as `/v1/timeline`, ordered by **stored**
`start_ms` then `id`. `limit` 1–500, default 200. `next_cursor` is
`{after_start_ms,after_id}` or null; pass it back with `epoch=journal_epoch`.

- **Completeness:** activities are immutable after ingest and removed only by
  `DELETE /v1/journal`. Paging until `next_cursor` is null returns every activity
  that existed for the whole read exactly once, including tied timestamps. An
  activity uploaded during the read appears if it sorts after the cursor; one that
  sorts before it is missed until a later re-read (late phone sync). Card titles,
  summaries and categories reflect the moment each page was read.
- **Invalidation:** journal deletion advances the epoch; a cursor from before it
  returns `409 {"error":"cursor expired"}`. Discard partial results and restart.
- **Bounds:** a request inspects at most 2,000 stored rows. A page over 480 KiB is
  shortened, never truncated: `next_cursor` then points at the last returned card.
- **Search:** `q` (1–100 characters, trimmed) matches app, card title or summary,
  case-insensitively; `category` matches the card's effective category after
  rules and manual edits. Filtering happens within the 2,000-row scan, so a page
  can contain few or zero cards while `next_cursor` is non-null; keep paging until
  it is null before concluding there are no matches. `scanned` reports rows
  inspected. Raw observation text and screenshots are not searched or returned.

## Private browser and shared product setup

`GET /v1/ai` includes `probe_provider_binding:true` on servers supporting bound
connection tests. `POST /v1/ai/test` now requires strict
`{"provider_id":"<64-hex identity from the approved saved settings>"}`.
Missing/malformed/unknown fields return400; a changed, disabled or unconfigured
provider returns409 before any test networking. The fixed synthetic text is sent
using the same loaded provider instance that was checked, never journal content.
Read current settings and obtain explicit confirmation again after409; do not
automatically retry with a new identity. Old clients posting `{}` must update.
New clients must require the capability flag before testing against older servers
that ignored request bodies. Existing request bounds, authentication and probe
concurrency/time limits remain. A request already sent cannot be recalled.

`GET /` serves the same-origin responsive browser UI. `POST /session` accepts
`{"token":"owner token"}` with `X-PocketLog: 1`, creating an eight-hour Secure,
HttpOnly, SameSite=Strict host cookie. `DELETE /session` invalidates that cookie's
session. No CORS; cross-site requests are rejected. Cookie-authenticated writes
also require `X-PocketLog: 1`. Native bearer authentication remains unchanged.
No tokens belong in URLs or localStorage. The scope matrix above applies to the
API routes below; creating a browser session itself requires the owner key.

- `GET /v1/workspace`: `revision`, `onboarding_version` (0/1), `focus_minutes`,
  `distraction_minutes` (0–1440), and ten category styles `{id,name,color,role}`.
  Names max40 characters; color #RRGGBB; role focus/neutral/distraction.
- `PUT /v1/workspace`: full strict schema, including current revision; 409 on
  stale revision. Returns incremented revision. Completing onboarding never
  enables collection or authorizes AI. These preferences survive journal deletion.
- `GET /v1/ai`: redacted provider config, `available`, `enabled`, `has_key`,
  `provider_id`, `auto_summarize`; API key never returned.
- `PUT /v1/ai/auto`: `{provider_id,enabled}`; owner/journal key. Turns automatic
  capture summaries and topic labeling on/off for that exact provider; 409 if the
  provider changed. Turning off invalidates jobs and in-flight results.
- `PUT /v1/ai`: `{endpoint,model,api_key?,image_support?,response_format?}`;
  response_format json_schema(default)/json_object; each save rotates provider_id
  and resets `auto_summarize` to false.
  Omitted key retains it only on same enabled endpoint and protocol; empty explicitly clears.
- `DELETE /v1/ai`: removes key, writes disabled tombstone, invalidates analysis.
- `POST /v1/ai/test`: fixed synthetic prompt only; no journal; one concurrent
  test/chat request, eight-second model timeout; returns `{ok,journal_data_sent:false}`.
- `POST /v1/ask`: `{question,from_ms,to_ms,provider_id,consent:true}`; question
  max1000 chars, range max169hours (seven local days across DST) with any past start (the journal is permanent). Sends up to100 derived cards,
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

Observation ingestion additionally accepts optional legacy `provider_id` (64 hex),
binding per-observation model consent to saved provider configuration; it is still
honored, but current Android omits it and relies on the owner's automatic switch. Changing model settings invalidates pending model jobs/epoch; it does
not erase source journal or retract requests already sent to the provider.

## Extended private APIs

All routes below require owner authentication or a journal device key, except device
management, which requires owner token/browser session. Body limit remains 64 KiB
(512 KiB only for `/v1/observations`).

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
  (unanalyzed) context/jobs/unvalidated replay; analyzed history is kept. Explicit check-ins/reflections remain available.
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

## Browser JSON export completeness

The website exports the data it has loaded, not a full database backup. Version1
keeps `days` and `reviews` and adds `truncated` (any activity/review limit reached)
and `reviews_truncated` (the reviews response was incomplete). Individual
`days[].truncated` markers are retained. Both markers are explicit booleans; a
false marker means no server read-limit was reported, not a guarantee against
unavailable devices, unsynced data or records outside the selected range. When
true, narrow the range or read smaller authenticated API windows; do not treat
the file as a complete backup. This includes retained legacy review data and
does not reintroduce manual mood entry. Raw observations/images are excluded.
