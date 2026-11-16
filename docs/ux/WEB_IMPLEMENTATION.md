# Web first-run and responsive UI: implementation notes

## Current state: simplified website (2026-11-17)

The website now implements the web parts of [SIMPLIFIED_IA.md](SIMPLIFIED_IA.md).
The sections after this one are the earlier history of the eight-page website and
describe UI that no longer exists; keep them only as a record.

Files: `server/web/app.js`, `app.css`, `index.html`, the `/agent-import.js` route
in `server/src/web.rs` (removed with `server/web/agent-import.js` and its test),
`scripts/test-web-timeline.cjs`, the `scripts/check-web-*.js` browser checks,
`scripts/dev-check.sh` and the web CI job. No API, storage or Android change.

- **Navigation:** exactly 時間軸 / 回顧 / 設定 (left rail on desktop, three-item
  bottom bar at ≤ 860 px).
- **時間軸:** date navigation; one status line chosen by `captureStatus()` from
  `GET /v1/collection`, `/v1/devices` (journal keys, expiry, last authentication),
  `/v1/ai` and the day's `pending_context` cards, in this order: 已暫停 → 金鑰失效
  → 尚未開始記錄 (no key or never connected) → 手機超過一天沒有連線 → AI 未設定 →
  AI 自動整理已關閉 → AI 整理中（N 張） → 記錄中 · AI 自動整理. Unknown reads never
  produce a warning. The line opens the matching 設定 section (or the phone step of
  onboarding). Below it: 已記錄 time with one stacked category bar whose labels are
  the legend; category chips for categories present that day; 時間格/清單 and a
  search icon button (the existing bounded search). Desktop ≥ 1100 px shows the
  hourly grid with an inspector; narrower windows open cards in a dialog and start
  in the list. Cards show the AI title (app display name for app-only cards), a
  one-line summary, category, source label (AI 整理 / 畫面文字 / 只有 app / 你改過),
  `AI 整理中` and `受保護畫面（無截圖）`. Card detail: full summary, app, time, a
  one-tap category picker that saves immediately (`POST /v1/cards`), `修改文字` for
  title/summary (newer typing during a save stays unsaved and is reported), and
  replay thumbnails only when `/v1/replay` has images for that app in the last 24 h.
  Empty day: the promise sentence plus at most one action.
- **回顧:** 日/週 segmented control sharing the date navigation (week steps by
  seven days). AI 回顧 card first: the newest saved brief of that kind for the
  period, or `產生 AI 回顧` (one tap sends `POST /v1/briefs` with `provider_id` and
  `consent:true`; a 409 re-reads `/v1/ai` and asks again), with the line
  `會把這段時間的活動卡送到 <host>`; without AI, `先設定 AI`. 時間分配 (category
  bars with duration and percent from `/v1/overview`), 常用 app (top five), 主題
  chips, and for the week a seven-day bar row that opens a day. 問 AI: one input,
  three example chips that only fill it, answers stacked below; one conversation
  per period (`conversation_id`), the same `request_id` for a retried question, the
  saved `provider_id`, `consent:true` only on explicit send, and on 409 a new
  conversation with no automatic retry.
- **設定:** 記錄 (state, each phone key's last contact, pause/resume all devices,
  link to keys), AI (state `已連接：<Provider> · <model>`, switches `自動整理新截圖`
  = `PUT /v1/ai/auto` with confirmation and `每天自動產生回顧` = brief schedule
  enabled with the browser's IANA time zone, daily and weekly, after
  confirmation; 停用並刪除金鑰), 分類 (one row to the existing category/rule editor;
  the focus/neutral/distraction role select was removed, stored roles are kept),
  連線與裝置 (server address, device-key dialog, logout, short private API note),
  資料 (storage use and limit, JSON export of today / 7 / 30 days, delete all),
  說明 (condensed help and links).
- **AI setup:** provider list OpenAI · Anthropic · Google Gemini · OpenRouter ·
  DeepSeek · 自訂（OpenAI 相容，例如 Ollama）. `providerSettings()` fills the
  endpoint and protocol (Gemini puts the model in the path; 自訂 alone shows the
  endpoint field and accepts HTTPS or literal loopback HTTP). Then API key, model
  (editable suggestion), `讓 AI 看截圖` (on except DeepSeek) and `測試並儲存`:
  `PUT /v1/ai`, then `POST /v1/ai/test` bound to the returned `provider_id`. A
  failed probe keeps the dialog open with the saved-but-untested message; nothing is
  retried automatically. Saving still turns the automatic switch off server-side.
- **Onboarding:** three skippable steps: promise with what is / is never captured;
  connect your AI (same form, `稍後再說`); install and connect the Android app
  (device-key dialog, server address). The goal step and the getting-started
  checklist (and its `localStorage` flag) are gone; the website stores nothing in
  browser storage.
- **Removed from the website:** 每日/每週 pages, Chat page and thread list, Flow,
  Agents and JSONL import, 支援 page, daily reflection, day rating, goals and
  meters, replay page, copy-timeline, the week timeline grid, the brief schedule
  dialog. Their APIs and stored data are untouched.

Verification done for this change (synthetic data only):

- `scripts/dev-check.sh web` (adds status-line, provider-preset and app-name tests)
  and `scripts/dev-check.sh rust` (fmt, clippy -D warnings, tests, audit) pass.
- The production image built with podman and `scripts/smoke-private.py` passed.
- In headless Chromium against a disposable container of that image (throw-away
  owner key, synthetic activities, AI pointed at a closed loopback port so nothing
  left the machine): every provider preset was accepted by `PUT /v1/ai` and mapped
  back to its preset; login, onboarding, timeline, one-tap category save, 回顧 day
  and week, brief/ask/probe failure messages and 設定 rendered at 1280 and 390 px
  with no horizontal overflow, console errors or CSP violations.
- The nine remaining `scripts/check-web-*.js` checks passed at 1280 and 390 px, but
  against a local mock of the API on the fixture port, not the real service; rerun
  them against the built image before treating them as accepted.
  `check-web-chat-reads.js`, `check-web-chat-writes.js`,
  `check-web-schedule-ownership.js` and `check-web-provider-completion.js` were
  deleted (their UI is gone or folded into `check-web-provider-ownership.js`);
  ACCEPTANCE.md still mentions them as history.

Not done: screen-reader, 200 % zoom, Firefox/Safari and real-phone review; no new
acceptance screenshots were saved; the Android app is unchanged and still shows its
older tabs; there is no dark theme (the site never had one).

### UX round 1 fixes (2026-11-17)

Follows "Cross-platform consistency (UX round 1)" in SIMPLIFIED_IA.md:

- Status line uses the shared vocabulary (`記錄中`, `AI 整理中（N 張）`, `AI 未設定，只顯示
  app 名稱`, `AI 自動整理已關閉`, …). Compact units (`56 分`, `不到 1 分`), zero-length
  cards show `01:26 · 不到 1 分`, a `隔天凌晨` divider after midnight, and only
  exception source labels (no `AI 整理`).
- Dates are zh-TW everywhere: the date control is a button (`今天 · 11月16日（週一）`,
  week `11月16日 – 22日`) that opens a hidden native picker; stamps read `11月16日 09:00`.
  `今天` appears only away from today; refresh is hidden on narrow screens and absent on 設定.
- Narrow windows default to the card list (until the owner picks 時間表/清單); the bottom
  nav has 13 px labels and content keeps clear of it. The hourly table (renamed 時間表)
  splits columns only for true overlaps and shows the time range in every block; category
  chips became one filter select.
- Card detail: read-only title, category picker right below it, × close button, no
  duplicate category row.
- 回顧: header aligned with the content column; 這週每天 directly after the AI recap with
  visible empty days; bars are the share of total time; 常用 app and topic chips wrap on
  phones; full-width 產生 AI 回顧 on phones; 重新產生 is a button and 刪除 a low-emphasis text
  action with confirmation; period-specific example questions with a neutral placeholder.
- 設定: stronger section titles, a section index (so 說明 is one tap away), one-line hints,
  server URL in normal type with 複製, 管理裝置金鑰 only in 連線與裝置, the private API note in
  說明. The AI section is a one-line summary plus an `AI 設定 ›` row; the dialog holds the
  two switches, 更換 AI and 停用並刪除金鑰.
- AI form: `自訂` with a small second line, saved key shown as the field placeholder
  `已儲存金鑰 ••••，貼上新的會取代`, a `取得 API 金鑰` link per provider, larger
  `測試並儲存` with a smaller cost note. All checkboxes render as switches.
- Secondary text colour darkened to about 6:1 on the lightest surfaces; icon buttons and
  switches have 44 px targets.

### UX round 2 and self-hosted AI (2026-11-17)

Follows "Round 2 rules" and "Networks and self-hosted AI" in SIMPLIFIED_IA.md:

- Cards share one structure (`時間 · 時長` + category + at most one exception badge /
  title / one-line summary) on a neutral background with a category stripe; app-only cards
  read `只知道使用了這個 app`; `AI 整理中` replaces `只有 app`. Under a minute is `不到 1 分`.
- The desktop 時間表 collapses runs of two or more empty hours into a thin `沒有記錄` gap
  (`timeAxis()`), shows `時間 · 時長` in every block at 14 px, marks `隔天凌晨`, and rings
  only the selected block. The inspector has a source/state line, `應用程式` and a text-style
  `修改文字`.
- Focus is a thin `:focus-visible` ring; selected segments, chips and AI services use soft
  pills. The header is sticky with the short date.
- 回顧 uses the timeline width with two balanced columns (one column in reading order on
  phones), bold titles, `日/週` before the date navigation, stacked week bars with `4.1 時`
  values, `平均每天`, the spec's example questions, send inside the input, and 主題 only for
  `keyword_mode` `model`/`mixed`.
- 設定: `暫停所有裝置` is a switch; the phone-address hint for loopback and plain-http
  origins; a horizontally scrolling section index with the current section marked; `匯出`
  range plus button; storage in MB below 1 GB; delete grouped last with a `無法復原`
  confirmation; `記錄` everywhere.
- AI page: AI 服務 → 金鑰 → 模型 + `載入模型清單` (`POST /v1/ai/models`, pickable list, free
  text fallback, short 502/429 errors) → 讓 AI 看截圖 → 測試並儲存 → 自動化 (two switches) →
  停用. `自訂（OpenAI 相容：CLIProxyAPI、LiteLLM、Ollama…）` takes a base `API 網址`
  (placeholder `http://127.0.0.1:8317/v1`, http allowed for private-network hosts by the
  same rule as the server) and an optional key.
- Wording names any VPN／private network instead of Tailscale only; plain-http logins on a
  private host show `這個連線沒有 HTTPS，資料保護依賴你的 VPN／私人網路`.

Checked: dev-check web/rust, podman image build and smoke, the nine `check-web-*.js` against
the local mock, and a headless browser run against a disposable container (all presets
accepted, the models request reaches the server and reports the short error for an
unreachable endpoint). Not checked: a real CLIProxyAPI/LiteLLM/Ollama model list, Safari,
screen readers.

### UX round 3 (2026-11-17)

Follows "Round 3 rules" in SIMPLIFIED_IA.md:

- List cards are separate neutral cards and desktop grid blocks are neutral too, each with a
  4 px category stripe; tall blocks repeat the shared row 1 (時間 · 時長, soft category chip,
  one badge: AI 整理中 accent, 受保護畫面 amber, others neutral). Same-minute and zero-length
  ranges show only the start time. Every empty hour collapses into a `沒有記錄` gap.
- Focus restoration passes `focusVisible: false`, and pointer-modality tracking hides rings
  after pointer actions (keyboard users keep the thin ring).
- Card detail order: title → app · time · length → summary → `AI 整理，可能有誤，請核對。`
  (ai/local_excerpt only) → category chips → icon + text `修改文字`; no top colour border.
- 回顧 on phones: 日/週 on row 1, `‹ 日期 › 今天` on row 2; time split is one line per
  category with aligned bars; ask chips scroll in one row; send is an icon inside the input.
  The week chart uses seven equal columns (~160 px), values above bars, `—` for empty days
  and `平均每天 X（已過 N 天）`.
- Copy: `AI 產生，可能有誤，請對照時間軸。`, `會把問題和這段時間的活動卡送到 <host>（不含截圖）。`,
  dialog title `AI`, `API 金鑰`, `檔案含私人內容，不含截圖；請自行保管。`, `已用 0 MB / 10.0 GB`.
- AI dialog: 自訂 tile shows only `自訂` with its description below once selected;
  `載入模型清單` is a secondary button with a loading label, styled like the `取得 API 金鑰`
  link; all switches share one size with a visible outlined off state.
- 設定: the recording line reads `最後連線 11/17 03:17` (or `手機超過一天沒有連線`) instead of
  `記錄中`; no divider under the last switch; the phone chip row fades at its edge; http
  origins show the no-HTTPS warning row in addition to the loopback hint; desktop secondary
  text is 14 px.

## History (before 2026-11-17)

Date: 2026-11-15 (UTC 2026-11-14 evening). Scope: issues
[#8](https://github.com/skyhong2002/PocketLog/issues/8) (deployment → first
connected phone, website half) and
[#10](https://github.com/skyhong2002/PocketLog/issues/10) (responsive web
timeline, navigation, summary). Owner: web UI worker (Claude Fable 5.2).

Files changed: `server/web/app.js`, `server/web/app.css`, `server/web/index.html`
and this document. No Rust, script, Android, logo or font file was edited. No new
asset or route is required: icons are inline SVG built with DOM calls, so
`server/src/web.rs` needs no change. The pre-existing branding edits (sage
palette, self-hosted Noto Sans/Noto Sans TC, `.brand-lockup`, `logo.png`) are
kept. Nothing was committed, pushed or deployed.

This is a status record, not a roadmap. Read the limits before treating anything
as accepted.

## What now exists

### Sign-in (first contact)

- A two-column welcome on desktop, stacked on phones: what PocketLog is, three
  privacy points, then the sign-in card. The field is labelled **Owner 金鑰**
  with a hint that it is the 64-character key created at deployment.
- "Owner 金鑰在哪裡？" has three situations:
  - **我自己架設的** — run `./scripts/setup-status.sh production` (or
    `development`), key file `.secrets/production_owner_token` /
    `.secrets/owner_token`, `./scripts/init-secret.sh` if missing, carry it
    privately, link to `docs/SETUP.md`.
  - **別人請我來用** — no signup, no reset, no invitations; ask the deployer
    privately; the owner key opens the same single journal; a device key cannot
    sign in here.
  - **我要連接手機** — sign in, issue a journal device key in Settings, enter
    the same HTTPS address plus that key in the app.
- Inline, field-attached errors (`role="alert"`, `aria-invalid`, focus returns
  to the field): empty, wrong format (checked locally, nothing is sent), wrong
  key (401), session capacity (429), cross-site rejection (403), unreachable
  server. Pasted whitespace/newlines are trimmed.
- State banners: session expired/restarted, signed out, "key accepted but the
  browser kept no session" (non-HTTPS origin or blocked cookie), plus a warning
  when the page is not a secure context.
- If the first request cannot reach the server, a recovery card replaces the
  blank page (network/VPN hint, `setup-status.sh`, retry).

### Shared onboarding

Same four steps and the same `PUT /v1/workspace` as before (value → privacy →
goals → optional AI), now with a labelled progress element, step names, focus
moved to each step heading, and wording that finishing setup is not consent to
any capture. The last button reads "先不用 AI，開始使用" when no provider is set.

### Getting-started checklist

Shown at the top of Timeline until complete or hidden, and always on Support.
Every row is derived from real server state; nothing is simulated:

| Step | Done when | Action |
| --- | --- | --- |
| 登入並完成初始設定 | always, once visible | — |
| 記下第一筆感受 | `GET /v1/overview` (last 29 days) `checkin_count > 0` | check-in dialog → `POST /v1/checkins` |
| 為手機建立裝置金鑰 | `GET /v1/devices` has a `journal` key, or activity exists | device-key dialog; shows the address to type on the phone (`location.origin`) |
| 收到第一筆手機活動 | overview has any category total | "重新檢查"; explains separate phone consent, Usage Access and Android scheduling delay; may be skipped |
| 接上自己的 AI（可略過） | `GET /v1/ai` `enabled` | provider dialog |

If either read fails the card says the state is unknown instead of guessing.

### Shell and navigation

- All eight destinations stay visible with icon + text. Desktop: 88 px left
  rail. ≤ 860 px: bottom bar with eight equal 48 px targets at 390 px (it scrolls
  horizontally only below that). Active item has `aria-current="page"`.
- Header: page title, a readable date line ("今天 · 11月14日週六", or the week
  range), previous/next/date/today controls only on dated pages, refresh.
- Skip link, `main` landmark, labelled segmented controls with `aria-pressed`.
- Rendering: each navigation gets a sequence number; a slow response for an
  older page/day is discarded instead of overwriting the new one. Content is
  built off-screen and swapped in, so the loading placeholder never shows half a
  page. Keyboard focus returns to the control that triggered the re-render.
- Page-level failure shows a recovery panel (retry, go to Support) while the
  navigation stays usable. Optional sections that fail to load say so in place.
- A sticky offline banner follows the browser's `online`/`offline` events.
- `confirm()` was replaced by an in-page confirmation dialog that states the
  exact scope; destructive actions use a red action and default focus on Cancel.
  Dialogs are named by their heading and receive focus on that heading.

### Timeline

- Hierarchy follows the supplied real Dayflow layout without copying its
  artwork, dark theme or serif type: wide timeline, ~340 px day summary, category
  chips above the grid, footer with copy/export/replay and "本週已記錄".
- 64 px per hour, 04:00 start, hour rules, a "現在" line on today, empty hours
  left empty. Cards show title, exact start–end, and (when tall enough) app ·
  category and summary. `aria-label` includes time, title, category, duration.
- Overlapping/nested/identical intervals are placed in side-by-side columns
  (`layoutLanes`), so no card covers another. Short cards have a 24 px minimum
  and are treated as that tall when columns are assigned.
- Day/Week and 時間軸/清單 toggles. The list view is a plain ordered list of
  every activity (also the practical view for dense days on a phone). Week view
  has sticky day headers with totals, "沒有資料"/"尚未到來", and opens a day.
- Distinct empty states: no records today (actions: check in, write reflection,
  phone help), no records on a past day, filter with no matches (clear filter),
  truncated data notice.
- Collection status pill (from `GET /v1/collection`) links to Settings.
- Summary: goals with progress (over-budget distraction is marked in text, not
  only colour), donut with a text legend and percentages, day rating as a
  pressed-state group (bound to that day's record), focus totals.

### Daily and Weekly

- Daily order: factual totals → category bars → longest activities → check-ins
  (new check-in only on today; past days say why) → reflection → optional AI
  brief → export. An unsaved reflection survives navigation within the tab and
  shows "有尚未儲存的修改" / "已儲存".
- Weekly: totals, category bars, seven day rows (recorded/focus, stacked
  category bar, check-in count, "查看這天"), missing and future days stay
  visibly empty, the week's reflections, optional weekly brief.
- AI brief consent is a dialog listing provider host, model, exact time range,
  what is sent, what is not, and retention, before `POST /v1/briefs`. Without AI
  the page says nothing is sent and everything above still works.

### Chat, Flow, Agents

- Chat: saved conversations load and can be deleted even when AI is disabled;
  the composer is replaced by a setup notice. With AI: scope toggle, exact range
  / provider / caps, suggestion chips that only fill the box, per-request
  consent, an in-transcript pending state, and on failure the question returns
  to the box with an inline alert (the unchanged request reuses its
  `request_id`). Sending no longer rebuilds the whole page. The draft survives
  navigation.
- Flow: live countdown from the stored end time (paused while the tab is
  hidden), one status announcement at zero instead of per-second speech,
  "expired but not saved" wording, discard only offered while a session exists
  and confirmed, explicit empty state for today's finished sessions.
- Agents: approved sessions are labelled as all retained dates; the selected
  day only scopes the manual summaries. Empty states and an import call to
  action. The import dialog shows "匯入勾選的 N／M 則文字". The JSON summary
  import moved under a collapsed "進階" section. Copy no longer describes the
  Dayflow source-only workstream flow as its Agents tab; it says the desktop
  dashboard is not ported.

### Settings and Support

- Settings is grouped: 連線與裝置, 記錄與隱私, AI（選用）, 分類與目標, 資料,
  這個瀏覽器. Each card states the current state before its action. Service
  status is human-readable; the raw JSON sits under "技術診斷".
- Device keys: list with scope/expiry, validation, a one-time key screen with
  numbered phone steps and the server address, refresh of the page behind it.
- Support: the checklist, then task-oriented sections (no phone activity, phone
  cannot connect / key invalid, lost or rotating the owner key, AI failure,
  retention/export/deletion) with direct actions, accurate Agents/Flow
  boundaries, and links to source, setup and security documents.

## Security and privacy posture (unchanged or stricter)

- The owner key is read from the password field, sent once in the JSON body of
  `POST /session`, and the field is cleared before the request. It is never put
  in a URL, `localStorage`, `sessionStorage`, a cookie readable by script, the
  console, or an error message. No authentication rule was relaxed; the
  `X-PocketLog` header, `credentials: same-origin`, `redirect: error` and
  `cache: no-store` are retained on every request.
- No `innerHTML`, `eval`, inline script/style attributes or remote resources;
  the existing CSP (`script-src 'self'; style-src 'self'; img-src 'self'`) was
  served during testing and produced no violations. Journal and model text is
  inserted as text.
- No consent is implied or pre-checked. Onboarding, the checklist and Settings
  repeat that phone permissions and capture/AI consent are separate.
- No demo journal, analytics or logging was added.
- **One addition to flag for review:** hiding the checklist stores the single
  value `pocketlog.checklist = "hidden"` in `localStorage` (wrapped in
  try/catch). It holds no credential or journal data. If the project prefers
  zero browser storage, delete `checklistHidden`/`setChecklistHidden` and use
  the server field proposed below.

## Verification actually performed

Environment limits: the host has no `cargo`; the Rust binary embeds the web
files at compile time, and building the image belongs to integration. So the
browser checks ran against a **disposable container from the existing
`pocketlog:sage` image** (tmpfs data, random throw-away owner key, loopback
only) behind a small scratch dev proxy that served the working-tree
`index.html`/`app.js`/`app.css` with the production CSP header and forwarded
every other request. Only invented fixture data was used (`com.example.*` apps,
synthetic titles). The container, key and proxy were removed afterwards. The
final embedded binary has not been built or exercised with these files.

Checks run and passing:

- `node --check server/web/app.js`, `node --check server/web/agent-import.js`,
  `node scripts/test-agent-import.cjs`.
- `app.js` can be `require`d in Node (DOM use is guarded); an ad-hoc assertion
  script covered `layoutLanes` (nested, identical, adjacent-short, reuse of a
  freed column, empty input) and `duration`/`shift`. It is not yet in
  `scripts/`/CI (see below).
- In the in-app preview browser (Chromium), 1280–1440 px and 390 × 844:
  - Sign-in: empty, malformed, wrong key, correct key with surrounding
    whitespace; storage and URL stayed empty of the key.
  - Onboarding four steps → empty first-run Timeline with checklist; check-in
    from the checklist updates the row.
  - All eight pages at 390 px: `scrollWidth == innerWidth`, no element outside
    the viewport except inside intended scrollers, all buttons ≥ 44 px except
    timeline cards, eight 48 px nav targets, `aria-current` and focus restored.
  - Timeline with 15 synthetic cards including three nested intervals, two
    identical intervals, 3–4 minute events, a long Traditional Chinese title and
    an activity after midnight: zero overlapping card rectangles; week view (7
    columns, empty and future day labelled); list view; filter and
    filter-with-no-matches; card edit and save; day rating.
  - Daily draft retention across navigation, reflection save, check-in;
    Weekly rows; Flow start → live countdown → confirmed discard; Chat without
    AI; Agents empty state, manual summary, import dialog opening.
  - Settings cards; device key create (one-time screen) and revoke with
    confirmation; pause and resume collection with confirmation and the
    Timeline pill; provider saved with an unreachable synthetic endpoint;
    brief-consent dialog contents and cancel; Chat failure path (question
    restored, consent cleared, inline alert).
  - Container stopped: page recovery panel with focus on Retry, Support still
    renders with "state unknown", reload shows the unreachable card; container
    restarted: retry leads to sign-in. Server-side session removal: next action
    shows the "session ended" sign-in. Logout: banner, API returns 401.
  - No uncaught script errors during these runs.
- Contrast computed from the palette (not measured on rendered pixels): body
  text 11.6:1, muted text 5.1–5.8:1 on the three surfaces, white on accent
  5.1:1, danger 6.8:1; control borders were darkened to 3.7:1.

Not verified — do not report as done:

- Rust fmt/clippy/tests/audit, Docker smoke, the embedded production binary,
  Android checks. None were run by this task.
- Screen readers (VoiceOver/NVDA/TalkBack), 200 % text zoom, Firefox, Safari,
  and an emulated `prefers-reduced-motion` session. Reduced motion is by
  construction: every transition/animation is inside
  `@media (prefers-reduced-motion: no-preference)`.
- A successful model answer or brief (no provider was available; only setup,
  consent and failure paths ran), the schedule dialog save, replay with real
  thumbnails, a JSONL file import through the browser file picker (the parser
  has its Node test), goals/categories dialog saves (code largely unchanged),
  clipboard copy and JSON download results.
- DST transition days and a 04:00 rollover during use.
- Screenshots were viewed in-session only; none were saved to the repository.
  Acceptance captures for #10/#11 (desktop and 390 px, synthetic data) still
  need to be taken by integration against the built image.

## Remaining gaps

- Selecting a card still opens the edit dialog; there is no in-place inspector
  replacing the day summary as in Dayflow.
- No "generating" placeholder on the timeline: the server exposes only a global
  `pending_context` count (shown in Settings), not per-interval job state, and
  the UI does not fake progress.
- Hour labels assume a 24-hour local day; a DST day is positioned by real
  duration but labelled by index.
- The week grid needs horizontal scrolling on a phone; more than four
  simultaneous activities produce narrow cards (the list view is the fallback).
- No focus heatmap, treemap or Sankey; Chat charts remain a simple dialog.
- Copy is Traditional Chinese only; no language switch.
- "First record" counts check-ins and activities; a reflection alone does not
  tick the checklist because no bounded endpoint reports it.
- A proxy 502 and a model failure share one status code; the UI distinguishes
  them by endpoint path.

## Server-side support needed for onboarding (#8)

These would let the website state facts it currently cannot. None are
implemented; they are requests to the server owner.

1. **Device last-seen.** Add `last_used_ms` (and ideally first-use) to
   `GET /v1/devices`. Today the checklist can only say a key was issued; it
   infers "phone connected" from the first activity, which also requires
   recording consent. With last-seen, "手機已連線" becomes its own verified
   step and Settings can show stale keys.
2. **A bounded setup summary**, e.g. `GET /v1/setup` returning
   `{journal_device_keys, last_device_contact_ms, has_activity, checkin_count,
   has_reflection, collection_paused, ai_enabled}`. It replaces two calls
   (including a 29-day overview aggregation) on each first-run Timeline render
   and lets a reflection count as a first record.
3. **Persisted checklist dismissal** in workspace preferences (the struct is
   `deny_unknown_fields`), e.g. `getting_started_dismissed: bool`, shared with
   Android. Then remove the `localStorage` flag.
4. **Distinct model-failure status or error code** for `/v1/ask`, `/v1/briefs`
   and `/v1/ai/test`, so a reverse-proxy 502 is never worded as a model error.
5. **Date-bounded agent sessions** (`from_ms`/`to_ms` on
   `GET /v1/agent-sessions`) if the Agents page should follow the selected day.
6. **Per-interval analysis state** if a real "generating" placeholder is
   wanted on the timeline.
7. **Brief caps in a response** (card and byte limits) so the consent dialog
   quotes server values rather than documented constants.
8. **CI:** add a Node check that `require`s `server/web/app.js` and asserts
   `layoutLanes` (nested, identical, short-adjacent, column reuse). The exports
   are already in place; `scripts/` and the workflow are not owned by this task.
9. **`scripts/setup-status.sh`** could end by naming the website's "Owner
   金鑰在哪裡？" panel and Support checklist, so both entry points use the same
   words (owner key, journal device key).

## Integrator follow-up

Root added scripts/test-web-timeline.cjs to CI, including overlap/immutability and 04:00 DST/half-hour-transition coverage. Timeline tick labels/scroll anchoring now follow actual local-day duration, with offset labels on transition days. Manual-journal setup can complete without enabling phone capture; only unexpired journal device keys count as ready. Final built-image browser verification is tracked in ACCEPTANCE.md.

## Owner scope correction (#25)

Manual mood/energy entry, Daily feeling cards and check-in onboarding actions were
removed. The checklist tracks phone pairing and first captured activity instead;
existing check-ins do not count as capture setup. Welcome and empty states lead
to phone recording/classification. Existing API/export data remains intact.
