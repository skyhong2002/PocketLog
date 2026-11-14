# Web first-run and responsive UI: implementation notes

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
