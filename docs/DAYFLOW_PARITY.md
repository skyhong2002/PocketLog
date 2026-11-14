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
| Category filtering and editing | Website filters; both clients edit names/colors/focus roles of ten stable categories; arbitrary new categories/rules are not implemented |
| Targets, distraction budget, longest focus | Website selected-day sidebar and Android daily review; user-defined focus/distraction roles; intervals are merged for longest-focus metric |
| Daily | Activity totals, top activities, explicit check-ins, editable reflection and review; website can request AI recap with separate per-request consent |
| Weekly | Website seven-day activity summaries/reflections, totals, AI recap; Android week timeline and existing category/app/keyword overview; no scheduled AI brief or generated artwork |
| BYO AI | Both clients configure OpenAI-compatible Chat Completions URL, model, key, JSON Schema/JSON Object and image capability; no native Gemini/Anthropic protocol or CLI inference |
| Chat | Both clients can explicitly send up to 100 derived cards to selected provider; website day/week scope; independent single-turn questions, no persistent conversations, tools, charts or desktop CLI |
| Flow | Private user-started focus sessions and results, with browser countdown; **not** the official authenticated remote Flow web app, distraction agent or cloud account service |
| Agents | Explicit bounded JSON import/manual Codex/Claude summaries, visible on both clients; **not** automatic filesystem scanning, headless Fable recaps, session replay or opening desktop tools |
| Privacy shield | Pre-capture exclusions/allowlist, password/editable-field/secure-window checks on Android; **not** OCR/image redaction or a promise to remove all visual secrets |
| Pause | Existing Android capture pause; website cannot change Android OS permissions or immediately pause an offline phone |
| Copy/export | Website clipboard + JSON; Android clipboard + daily JSON document picker; raw observations never exported |
| Support/settings | Local guidance, source/documentation links and private configuration; no automatic support messages or telemetry |
| Recording and replay | Opt-in bounded Android app-switch metadata/text/thumbnails; no desktop continuous screen recording, audio transcription or timelapse playback |

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

## Boundaries still requiring work

Full official Dayflow parity is **not shipped**. Remaining substantial areas are
arbitrary category/rule creation, native provider protocols/CLI, persistent rich
Chat, automatic agent-session integrations, continuous desktop recording/replay,
image redaction, daily brief automation/artwork, and the separate Flow cloud
service. Mobile battery/capture validation also remains required. The independent
private functions above do not rely on the Dayflow account or service.
