# Dayflow reference and PocketLog implementation

Reference: official [JerryZLiu/Dayflow](https://github.com/JerryZLiu/Dayflow),
commit `2c79e1619d7039c476e06e4b85b0246e50bc34fa`, reviewed 2026-11-14.
The screenshots supplied by the owner were used as private visual references;
no real journal entries or screenshots are included in this repository.

PocketLog is independent Android + Rust software. The table records working
capabilities and gaps; adding a navigation tab does not establish feature parity.

| Official Dayflow area | PocketLog status |
| --- | --- |
| Onboarding: explain capture/privacy/provider | Website and native Android onboarding share server completion, goals and category settings; OS permissions and capture/model consent remain device-specific |
| Day timeline, date navigation and calendar | Both clients show actual interval cards on an hourly 04:00–04:00 timeline; missing time stays blank |
| Week timeline | Website seven-column view and horizontally scrollable native Android day columns; 29 selectable journal dates |
| Category filtering and editing | Website filters; both clients edit ten built-ins plus up to 30 custom categories; up to 32 ordered app/title rules, with manual corrections taking priority |
| Targets, distraction budget, longest focus | Website selected-day sidebar and Android daily review; user-defined focus/distraction roles; intervals are merged for longest-focus metric |
| Daily | Activity totals, top activities, explicit check-ins, editable reflection and review; both clients generate, save and delete AI briefs with per-request consent |
| Weekly | Website seven-day activity summaries/reflections, totals, AI recap; Android week timeline and existing category/app/keyword overview; separate opt-in daily/weekly schedules with IANA timezone/DST support; no generated artwork |
| BYO AI | Both clients configure OpenAI-compatible, Anthropic Messages or Gemini generateContent URL, model, key and image capability; no CLI execution |
| Chat | Both clients can explicitly send up to 100 derived cards to selected provider; both clients support day/week scope, saved multi-turn conversations, same-provider bounded history, deletion and deterministic category charts; no tools or desktop CLI |
| Flow | Private user-started focus sessions and results, with a server-persisted cross-device timer and browser countdown; **not** the official authenticated remote Flow web app, distraction agent or cloud account service |
| Agents | Native Codex/Claude JSONL is parsed locally after file selection; preview/edit and explicitly choose text turns, then group by project, update status and replay approved text on both clients. Summary JSON/manual import remains supported; no background filesystem scanning, Fable, or opening desktop tools |
| Privacy shield | Pre-capture exclusions/allowlist, password/editable-field/secure-window checks and default-on suspected-secret/long-number text gate before pixels on Android; **not** OCR/image redaction or a promise to remove all visual secrets |
| Pause | Local pause plus shared server stop switch, immediate rejection/clearing of pending context, phone propagation on existing sync cadence; offline phones cannot receive an immediate stop, and resume never grants OS permissions |
| Copy/export | Website clipboard + JSON; Android clipboard + daily JSON document picker; raw observations never exported |
| Support/settings | Local guidance, source/documentation links and private configuration; no automatic support messages or telemetry |
| Recording and replay | Opt-in bounded Android app-switch metadata/text/thumbnails; separate opt-in sanitized thumbnail replay (24 hours/256 images); no desktop continuous recording/audio/transcription or high-frequency sampling |

## Shared setup

`GET/PUT /v1/workspace` stores onboarding version, category presentation/roles and
daily goals. `revision` provides optimistic concurrency: an older editor gets
409 rather than silently replacing another device's changes. Completing setup
never grants collection, image or external AI consent. A newly paired phone can
skip already completed product setup but must still grant its own OS permissions.
An offline launch can open the existing encrypted timeline without granting consent.

## Browser and storage

The Rust service serves the same-origin responsive interface at `/`, with no CDN,
remote fonts, trackers or JavaScript dependencies. Owner login creates a random
8-hour in-memory session in a Secure, HttpOnly, SameSite=Strict host cookie; the
owner token is not stored in localStorage/sessionStorage or put into URLs. All
browser mutations require a non-simple `X-PocketLog: 1` header. There is no CORS.
Logout invalidates the session; restart/token rotation invalidates all sessions.
At most 32 active sessions are accepted. Use trusted HTTPS (localhost only for
isolated development). Browser history and exports are outside Android encryption.

Focus/review/agent records are private, limited to 500 records and 4,000 characters
per record, and expire after 30 days. Read responses return at most 100 records and
470 KiB of records with a truncation marker. Owner journal deletion includes them.
Workspace/provider configuration survives journal deletion so accidental deletion
does not change permissions or resurrect an old provider configuration.

Agents import format (synthetic example):

```json
{"sessions":[{"id":"example-session","at_ms":1794600000000,"source":"codex","text":"Project: demo. Completed a review. Next: verify tests."}]}
```

Choose files explicitly. Each import is at most 48,000 bytes/20 summaries. Existing
IDs update records, so a retry cannot duplicate an accepted summary. Import is
sequential; a failure can leave earlier records saved, and retrying uses their IDs.
Do not import credentials or private conversations. These are selected summaries,
not automatic parsing of arbitrary native Codex/Claude session dumps.

## Added bounded private capabilities

Custom rules operate on current derived card titles and exact app package names;
first matching rule wins, while edited cards retain their selected category.
Used custom categories cannot be deleted until recategorized. Classification never
rewrites ingest payloads or breaks idempotent retries.

Chat retains 500 question/answer pairs across 100 conversations for 30 days. A request
can send at most the last six pairs/6,000 characters to the same provider identity,
plus 100 cards/24 KB. Client retry IDs avoid repeating completed paid requests.
History pagination includes timestamp and ID to preserve simultaneous turns.

AI briefs retain 100 records for 30 days. Daily/weekly schedules default off and
require separate provider-bound ongoing consent. At most two periods per worker
pass and three durable attempts per period; no historical backlog. Completed
04:00 periods follow the selected IANA timezone, including DST. Deleting the journal
disables scheduling; changing model configuration requires renewed consent.

A selected native JSONL file is limited to 512 KiB/2,000 lines; local preview shows
at most 40 recent user/assistant text messages, 1,000 characters each. Tool outputs,
thinking blocks, attachments and environment/path metadata are excluded. The owner
must select each turn and remove sensitive text before upload. Stored sessions have
revision conflicts, 100-session/30-day bounds and participate in journal deletion.
They are not sent to models by Chat or scheduled briefs.

## Boundaries still requiring work

Full desktop Dayflow parity is **not shipped**. Desktop-specific continuous recording,
audio, OCR pixel redaction, CLI model execution, automatic filesystem integrations,
generated brief artwork and Dayflow's separate authenticated Flow cloud service
remain outside these implemented private mobile/web equivalents. The public Dayflow
repository does not provide that cloud backend. Physical-device permission/capture,
Keystore/Doze and full-day battery acceptance remain unverified. No increased mobile
sampling or on-device inference has been introduced to approximate desktop recording.
