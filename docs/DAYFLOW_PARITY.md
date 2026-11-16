# Dayflow reference and PocketLog implementation

Reference: official [JerryZLiu/Dayflow](https://github.com/JerryZLiu/Dayflow),
commit `2c79e1619d7039c476e06e4b85b0246e50bc34fa`, reviewed 2026-11-14.
The screenshots supplied by the owner were used as private visual references;
no real journal entries or screenshots are included in this repository.

PocketLog is independent Android + Rust software. The table records working
capabilities and gaps; adding a navigation tab does not establish feature parity.

Since 2026-11-17 the website follows the [simplified product](ux/SIMPLIFIED_IA.md):
only 時間軸, 回顧 and 設定. Rows below say "website" where the web differs from
Android, which keeps its older tabs until its own simplification. Server APIs and
stored data for removed web features (reflections, ratings, goals, Flow, Agents,
saved Chat threads) are unchanged.

| Official Dayflow area | PocketLog status |
| --- | --- |
| Onboarding: explain capture/privacy/provider | Shared server completion and category settings; website onboarding is three skippable steps (promise, connect AI, connect phone) with no goal step; OS permissions and capture/model consent remain device-specific |
| Day timeline, date navigation and calendar | Both clients show actual interval cards on an hourly 04:00–04:00 timeline; missing time stays blank; desktop web selection opens a read-only side inspector, with explicit editing; web activity corrections retain newer unsaved edits and ignore closed-editor responses; visible current-day native and web views check activity changes at most once every two minutes and offer an explicit update without silently rebuilding the reader |
| Week timeline | Website: a seven-day bar row in 回顧 › 週 (the seven-column week grid was removed); native compact seven-column overview with per-day rows; any past journal date (the journal is retained permanently) |
| Category filtering and editing | Both clients filter categories and edit ten built-ins plus up to 30 custom categories; up to 32 ordered app/title rules, with manual corrections taking priority; both clients test unsaved rules through the private server classifier; Android can search local launcher apps for a rule, while web offers up to 20 recorded packages from the last 29 days. Selection fills an unsaved draft only |
| Targets, distraction budget, longest focus | Android daily review only; removed from the website (no goals or meters); category roles stay stored |
| Daily | Website 回顧 › 日: AI 回顧 first (saved or one-tap generate), category time with percentages, top five apps and topics; no reflection or rating on the website. Android keeps its reflection and focus review. Manual mood/energy entry removed earlier (legacy records/API retained) |
| Weekly | Website 回顧 › 週: the same cards plus a seven-day bar row; Android week timeline and category/app overview; both show model-labeled topic keywords (local fallback); opt-in daily/weekly recap schedule (website: one 每天自動產生回顧 switch); no generated artwork |
| BYO AI | Website: pick OpenAI, Anthropic, Google Gemini, OpenRouter, DeepSeek or a custom OpenAI-compatible endpoint (e.g. Ollama); the endpoint and protocol are filled in, then key, model, 讓 AI 看截圖 and 測試並儲存 (save + bound probe). Android configures URL/protocol/model/key directly. Both have the provider-bound automatic-summary switch; no CLI execution |
| Chat | Website: 問 AI inside 回顧 for the selected day/week, answers stacked as one conversation per period (no thread list). Android keeps its Chat tab with saved threads. Both send up to 100 derived cards only after an explicit send; no tools or desktop CLI |
| Flow | Android only; removed from the website (API kept). **Not** the official authenticated remote Flow web app, distraction agent or cloud account service |
| Agents | Android only; the website's JSONL import and replay were removed (API kept). No background filesystem scanning, Fable, or opening desktop tools |
| Privacy shield | Pre-capture exclusions/allowlist, password/editable-field/secure-window checks and default-on suspected-secret/long-number text gate before pixels on Android; **not** OCR/image redaction or a promise to remove all visual secrets |
| Pause | Local pause plus shared server stop switch, immediate rejection/clearing of pending context, phone propagation on existing sync cadence; offline phones cannot receive an immediate stop; saved local capture grants stay visible and revocable while paused, and upstream resume discloses retained downstream choices without granting OS permissions |
| Copy/export | Website JSON export in 設定 › 資料 (today / 7 / 30 days; the copy-timeline action was removed); Android clipboard + daily JSON document picker; raw observations never exported |
| Support/settings | First-run owner-key guidance, device/API credential creation and scope-specific once-only setup; website help is condensed into 設定 › 說明; exact-destination known credential rejection stops saved capture grants and offers explicit recovery while preserving cached journal/reflection until reset; no automatic support messages or telemetry |
| Recording and replay | Opt-in Android text with default-on screenshots every 10 seconds and on app switches (720 px), retained on the server under a 10 GiB default storage budget; separate opt-in sanitized thumbnail replay (24 hours/256 images), shown on the website only inside a card's detail (the replay page was removed); protected/DRM screens are recorded as text only and labelled 受保護畫面（無截圖）; no desktop continuous recording/audio/transcription |
| Storage limit | Owner-set server budget (1 GiB–1 TiB) in both clients; over the limit the oldest screenshots are removed and text is kept |

## Shared setup

`GET/PUT /v1/workspace` stores onboarding version, category presentation/roles and
daily goals. `revision` provides optimistic concurrency: an older editor gets
409 rather than silently replacing another device's changes. Completing setup
never grants collection or image consent or turns on automatic AI summaries. A newly paired phone can
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
