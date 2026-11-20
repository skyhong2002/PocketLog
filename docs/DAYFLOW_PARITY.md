# Dayflow reference and PocketLog implementation

Reference: official [JerryZLiu/Dayflow](https://github.com/JerryZLiu/Dayflow),
commit `2c79e1619d7039c476e06e4b85b0246e50bc34fa`, reviewed 2026-11-14.
The screenshots supplied by the owner were used as private visual references;
no real journal entries or screenshots are included in this repository.

PocketLog is independent Android + Rust software. The table records working
capabilities and gaps; adding a navigation tab does not establish feature parity.

Since 2026-11-17 both clients follow the [simplified product](ux/SIMPLIFIED_IA.md):
only 時間軸, 回顧 and 設定. Server APIs and stored data for removed features
(reflections, ratings, goals, Flow, Agents, saved Chat threads) are unchanged.

| Official Dayflow area | PocketLog status |
| --- | --- |
| Onboarding: explain capture/privacy/provider | Three skippable steps on both clients: what is and is never captured → connect AI (Mika-style form) → start recording through the guided 自動記錄 flow (phone) or connect the Android app (web). No goal step. OS permissions and capture consent remain device-specific |
| Day timeline, date navigation and calendar | Both: previous/next/今天 and a date picker, one tappable status line, a day summary (recorded time + one stacked category bar) and cards with AI title or app name, one-line summary, category chip, source label, `AI 整理中` and `受保護畫面（無截圖）`. Website desktop keeps an hourly 04:00–04:00 grid with a side inspector; phones show a chronological list. Missing time stays blank |
| Week timeline | 回顧 › 週 on both clients: a seven-day bar row (tap a day) with category/app/topic totals; the seven-column week grids were removed |
| Category filtering and editing | Both edit ten built-ins plus up to 30 custom categories and up to 32 ordered app/title rules, with manual corrections taking priority, and test unsaved rules through the private server classifier. A one-tap category picker in the card detail saves immediately; title/summary edits sit behind 修改文字 |
| Targets, distraction budget, longest focus | Removed from both clients on 2026-11-17; stored goals and category roles are kept |
| Daily | 回顧 › 日 on both clients: AI recap first (saved or one-tap generate with a one-line disclosure of the provider host), category time with percentages, top five apps, topics and 問 AI. Reflection, check-ins and the daily rating were removed from both UIs (APIs and stored data kept) |
| Weekly | 回顧 › 週: the same cards for the week plus the seven-day bar row; model-labeled topic keywords (local fallback); automatic daily/weekly recaps are one 每天自動產生回顧 switch in 設定 › AI; no generated artwork |
| BYO AI | Both clients: pick OpenAI, Anthropic, Google Gemini, OpenRouter, DeepSeek or a custom OpenAI-compatible endpoint (e.g. Ollama); endpoint and protocol are filled in, then key, prefilled editable model, 讓 AI 看截圖 and 測試並儲存 (save + provider-bound probe); then the automatic-summary switch. No CLI execution |
| Chat | 問 AI inside 回顧 for the selected day/week on both clients, one conversation per period, no thread list; up to 100 derived cards only after an explicit send; no tools or desktop CLI |
| Flow | Removed from both clients on 2026-11-17 (API kept). **Not** the official authenticated remote Flow web app |
| Agents | Removed from both clients on 2026-11-17 (API and stored sessions kept); no background filesystem scanning |
| Privacy shield | Pre-capture exclusions/allowlist, password/editable-field window exclusion, lock-screen exclusion and the default-on suspected-secret/long-number text gate on Android. Secure/DRM windows are recorded as app + visible text without a screenshot (`protected_screen`); black frames are never kept. PocketLog's own screen protection is an opt-in setting. **Not** OCR/image redaction or a promise to remove all visual secrets |
| Pause | Local pause plus shared server stop switch, immediate rejection/clearing of pending context, phone propagation on existing sync cadence; offline phones cannot receive an immediate stop; saved local capture grants stay visible and revocable while paused |
| Copy/export | JSON export in 設定 › 資料 on both clients; copy-timeline removed; raw observations never exported |
| Support/settings | 設定 has 記錄 / AI / 分類 / 連線（與裝置） / 資料 / 說明 on both clients; the old 支援 page is condensed into 說明; first-run owner-key guidance and scope-specific device keys; known credential rejection stops capture and offers explicit recovery; no automatic support messages or telemetry |
| Recording and replay | Opt-in Android text with default-on screenshots every 10 seconds and on app switches (native-resolution lossless WebP when it fits 300 KB, with JPEG fallback); unchanged screens skipped; captures upload while charging, or on an unmetered network once more than 256 MB wait, and stay queued up to 72 h; retained on the server under a 10 GiB default storage budget; separate opt-in sanitized screenshot replay (24 hours/256 images) shown only inside a card's detail; no desktop continuous recording/audio/transcription |
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
Keystore/Doze, charging-only upload behaviour and full-day battery acceptance remain unverified. No increased mobile
sampling or on-device inference has been introduced to approximate desktop recording.
