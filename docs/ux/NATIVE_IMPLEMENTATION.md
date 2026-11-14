# Native Android UI implementation (issues #9, #8 first run)

Owner: native UI worker (Claude Opus 5.5). Date: 2026-11-15. Scope: `MainActivity.kt`,
new native helpers `NativeUi.kt`, `TimelineViews.kt`, `TimelineMath.kt`, nav/control
vector icons in `res/drawable/ic_nav_*.xml`, `ic_back.xml`, `ic_chevron.xml`,
`ic_sync.xml`, and `TimelineMathTest.kt`. No change to `build.gradle.kts`,
`AndroidManifest.xml`, `styles.xml`, branding assets or dependencies. Existing dirty
branding/theme/version edits were left untouched.

## References inspected

- Private Dayflow timeline screenshot (inspected locally only; not copied, published or
  described beyond layout). Observed structure: narrow left icon rail (Timeline, Daily,
  Weekly, Chat, Flow, Agents, Support, Settings); header with prev/next, calendar,
  Day/Week toggle and date title; category chips; dashed hour lines with time labels;
  interval cards with a colored left accent, title and right-aligned time range; right
  panel with targets (focus progress, distraction budget), category donut with totals,
  review (distracted/neutral/focused) and focus tiles; footer copy-timeline action.
- Official source `/tmp/pocketlog-dayflow-reference` @ `2c79e16`:
  `CanvasTimelineDataView.swift` (168 pt/hour, cards `max(10, raw − 2)` tall, 3 pt
  radius, category accent, scroll anchor near "now"), `CanvasTimelineTypes.swift`.
- `docs/UI_GUIDELINES.md` (sage canvas `#F2F5EF`, paper `#FCFDF9`, accent `#52765F`,
  ink `#303A32`; horizontally scrollable bottom rail on small screens; 04:00 day
  boundary; never invent activity; real empty/offline/failed states).

No Dayflow artwork, copy or icons were reused. Icons are new 24 dp stroke vectors.

## Shell and navigation

- One Activity, platform Views only (no WebView, AppCompat, Material or Compose).
  Layout: header (back button or brand mark, title, subtitle, optional action) →
  scrollable page → status strip → bottom navigation rail.
- Bottom rail: eight destinations from the guidelines (時間軸, 每日, 每週, Chat, Flow,
  Agents, 支援, 設定), 72×64 dp cells, icon + label, selected pill, horizontally
  scrollable, auto-scrolls the selection into view, centered when it fits (tablets).
  Each cell announces "…，目前頁面" when selected.
- Sub-pages (Bring your own AI, 目標與分類, 分類與自動規則, AI 回顧/排程, app 清單,
  工作線, 對話回放, JSONL 核准, 縮圖回放) show a back arrow; the rail keeps the parent
  tab highlighted. Back from a root tab returns to 時間軸; from 時間軸 it leaves the app.
  Back uses `OnBackInvokedDispatcher` (required for targetSdk 36 on Android 16) plus
  the legacy `onBackPressed` path for Android 14/15 without the manifest opt-in.
  The category editor asks before discarding unsaved edits.
- targetSdk 36 is edge-to-edge: the shell applies system-bar, cutout and IME insets;
  the rail hides while the keyboard is open. Content is limited to a 720 dp column
  on wide screens; phones keep a 16 dp gutter.
- Selected tab and day survive recreation (rotation) via `onSaveInstanceState`;
  no journal content or credentials are saved there.

## States: busy, error, empty, disabled

- Page reads (`load`) render an inline spinner in their section, then the content or
  an error card with a specific reason and **重試**. A generation counter drops
  results from pages the user already left, so slow reads never render into the
  wrong page and navigation is never blocked by reads.
- Mutations (`task`) remain one-at-a-time as before, show a progress status strip
  (live region, so TalkBack announces it), dim the rail and block navigation/back
  until they finish, then show success (auto-hides) or a persistent error.
- Error text maps causes without leaking bodies/tokens: 401 (wrong/expired/revoked
  key), 403 (collector-only key), 404 (server too old), 409, 413, 423, 429, 5xx,
  unknown host (with Tailscale hint), TLS verification, timeout, refused, generic IO,
  malformed JSON. Previously every failure said the same thing.
- Real empty states with recovery: no activity (recording off → 設定手機紀錄; today →
  立即同步 with the 15-minute WorkManager explanation; past day → "空白時段不會被補上
  虛構的活動"), no briefs, no replay images (consent vs. expired), no agent sessions,
  AI not configured (Chat → Bring your own AI), offline cache and truncation banners.
- Disabled prerequisites are visible: each consent row states what is required first
  ("需先開啟 App 使用紀錄", "需先在允許清單加入 app", "需先開啟畫面縮圖", …) and a tap
  explains instead of failing silently. Date arrows disable at the 29-day/today
  bounds; the date picker is bounded to the same window.
- Rendering exceptions inside callbacks are caught and reported rather than crashing.

## First run (#8)

Setup screen ("連接私人伺服器", rail hidden) explains, in order:
1. what PocketLog connects to (your own server; no default cloud AI/analytics),
2. where the **server URL** comes from (the HTTPS origin you open the website at;
   example Tailscale Serve origin; origin only; phone must reach it),
3. where the **device key** comes from (log in to the website with the owner token →
   設定 → 裝置金鑰與撤銷 → 權限「日誌（手機 app）」; 64 characters, shown once,
   revocable; owner token works but is discouraged on phones),
4. then a form with inline validation: missing scheme gets `https://`; specific
   messages for http, paths, malformed URLs, key length (live "n / 64" counter
   without revealing the key), non-hex characters; server failures are shown inline
   (e.g. 403 explains collector-only keys), and
5. "連線之後": shared setup, optional BYO AI, and that **phone capture is a separate,
   default-off consent** under 設定 → 手機紀錄 with Android permissions.

No credential file paths are shown. Onboarding (4 steps, progress bar, back) ends
with a card restating that capture is still off and where to enable it; the toast
after completion repeats this. Re-reading onboarding from settings has a way back.

## Pages

- **時間軸**: date title + relative day + "一天從 04:00 開始"; sync action; prev / date
  picker / next; capture callout (paused / not recording / usage permission missing,
  each with an action) or the sync status; activity card with tracked total,
  **時間軸 | 清單** toggle; targets (focus vs goal, distraction vs budget, longest
  focus, tracked); category distribution (stacked bar + legend with duration and %);
  mood/energy 1–5 segmented check-in; daily reflection (prefilled); "這一天" rows
  (每日回顧, 活動概覽, 縮圖回放, 匯出).
- **Day canvas** (`DayTimelineView`): 04:00–04:00, ≥64 dp per hour (grows with font
  scale), dashed hour lines, tinted cards with darker category accent stripe, title +
  time range/category on two lines when tall enough, inline range when wide, nothing
  drawn in empty hours, overlapping intervals split into aligned lanes
  (`TimelineMath.lanes`), pressed feedback, "now" line, viewport scrolled to now
  (today) or the first card. It sits in a bounded nested viewport (58% of screen
  height) so summaries stay reachable.
- **每日**: targets, distribution, my review (分心/平衡/專注), check-ins and reflections,
  daily AI briefs, copy timeline (clipboard marked `EXTRA_IS_SENSITIVE`).
- **每週**: week range nav, tracked total and active days, seven-column week canvas
  (tap a column to open that day), one row per day with total and top category or
  the reason it is unavailable (future, outside 29 days, offline without cache),
  weekly distribution, weekly briefs. Replaces seven 240 dp horizontally scrolled
  full-day columns.
- **Chat**: provider card, scope segmented (selected day / this week), data-scope
  note, category chart dialog, conversation bubbles ("AI 回答，請核對"), load older,
  composer with per-request consent checkbox; new/saved/delete conversation rows.
  The request-id reuse for retries is preserved.
- **Flow**: start form or running timer (large remaining minutes, progress, start
  time; refreshes every 30 s only while visible), finish / discard (now confirmed),
  the day's focus records.
- **Agents**: JSONL import (source picker → local parse → per-turn approval page),
  work-thread list grouped by project with status pills, replay with prev/next that
  disable at the ends, manual summary, JSON summary import, the day's summaries.
- **支援**: live diagnostics rows (server, recording, usage access, server pause,
  context allowlist, accessibility service when context is on, last sync, offline
  queue counts) each linking to the fix; troubleshooting checklist; limits; docs.
  Refreshes when returning from Android settings.
- **設定** grouped: 連線 (server origin only, change server/key), 手機紀錄 (usage
  recording with a consent dialog — previously a single tap enabled it — usage
  access, server-wide pause, app lists, unmetered-only switch), 畫面情境 (text,
  thumbnails, replay, privacy shield, accessibility settings), AI (BYO AI, model
  consent), 共用設定, 同步 (sync now, queue; retry/delete rejected appear only when
  needed, delete confirmed), 資料 (clear local, delete server journal), 關於 (version).
  Refreshes on return from Android permission screens.

## Feature coverage

Every previous action remains reachable: setup/reset recovery, onboarding, date
selection, sync/refresh, overview (week / 29 days), export JSON, check-in, reflection,
card edit, clear local data, delete server journal, remote pause, usage consent and
permission, exclusions/allowlist with installed-app picker, context/image/replay/
privacy-shield consents, accessibility settings, BYO AI save/test/consent/disable,
unmetered toggle, queue status/retry/delete, shared goals, categories and rules,
weekly timeline, daily review/ratings/copy, briefs and schedule, Chat (history,
saved, new, delete, chart, ask), Flow, Agents (JSONL preview/import, sessions,
replay/status/delete, manual and JSON summaries), support link, replay images.
Network paths, request bodies, locks, queue clearing, consent bit ordering and
provider-identity checks were carried over unchanged.

## Privacy and security retained

- `FLAG_SECURE` on the Activity unchanged; now also added to every dialog that can
  show journal content (card edit, overview, chart, saved conversations, consent and
  delete confirmations, app picker, replay). Dialog windows do not inherit it.
- Credentials still Keystore-encrypted, sent only in headers; the key field is a
  password field excluded from autofill; cleared after a successful connect. AI key
  field cleared before the network call and removed from the request body afterwards.
- Settings show only the server origin, never the token. No content logging, no
  analytics, no new permissions, no background work added (Flow's 30 s refresh is a
  UI handler cancelled when leaving the page).

## Accessibility and density

- dp/sp everywhere; text sizes follow the system font scale; canvas hour height,
  gutters and card text derive from sp metrics. Controls have ≥48 dp targets; rows
  ≥56 dp. Buttons wrap instead of truncating.
- Headings marked with `isAccessibilityHeading`; rows/stat tiles/legend lines are
  single focusable units with combined descriptions; segmented controls announce
  selection; status strip and onboarding errors are live regions; fields use
  `labelFor`.
- The day and week canvases are hidden from accessibility; the **清單** view (selected
  automatically when TalkBack/touch exploration is on) and the per-day rows expose
  the same actions as standard focusable rows.
- Contrast (WCAG): muted/paper 5.6, muted/canvas 5.2, white/accent 5.1, accent-dark/
  accent-soft 6.1, warn 7.0, danger 5.8, ink/canvas 10.7. Placeholder hints are 3.5
  (placeholder only).

## Evidence

Run with `JAVA_HOME=/home/sky/.local/android-toolchain/jdk`,
`ANDROID_HOME=/home/sky/.local/android-toolchain/sdk`, `--max-workers=2`:

- `testDebugUnitTest`: 21 tests, 0 failures (new `TimelineMathTest`: 04:00 boundary
  and now-clipping, Monday week start, 29-day window, lane assignment incl. caller
  order and touching intervals, union/longest totals, duration formatting).
- `assembleDebug`: success.
- `lintDebug`: 0 errors, 8 warnings — 3 `SetTextI18n` in `MainActivity.kt` (the app
  keeps Traditional Chinese literals in Kotlin, as before), the rest in the now
  unused `ActivityTimelineView.kt` and Gradle/AGP version notices.

**No screenshots.** The SDK has no system images or AVD and the host had ~3 GB RAM
free, so an emulator was not feasible; no physical phone was attached. Nothing here
has been visually verified on a device. Production `FLAG_SECURE` was not weakened.

## Known gaps / follow-ups for the integrator

- Capture synthetic-data screenshots on an emulator or device (light theme, 360 dp
  and tablet widths, font scale 1.0 and 2.0, TalkBack list mode) before closing #9.
- `ActivityTimelineView.kt` is no longer referenced; it can be deleted (outside this
  worker's file ownership, so left in place).
- Canvases expose no virtual accessibility nodes; the list view is the accessible
  equivalent. An `AccessibilityNodeProvider` would let TalkBack explore the canvas.
- Nested vertical scrolling (timeline viewport inside the page) relies on platform
  nested scrolling; needs a real-device feel check.
- Light theme only (matches current `Theme.PocketLog`); no dark palette yet.
- Strings remain Kotlin literals (no `strings.xml` localization).
- The daily review buttons do not show a previously saved rating (the API read for it
  was not previously used on Android either).

## Integrator follow-up

See [ACCEPTANCE.md](ACCEPTANCE.md) for emulator View snapshots, first-run and large-text corrections, DST handling and lifecycle-gated Flow callbacks. The original no-screenshot statement above describes the worker run; integration now has synthetic emulator View captures, with connected API and actual TalkBack limitations recorded separately.

## Connected follow-up (0.4.2-dev)

Issues #17–#20 add bounded in-memory editor drafts retained through configuration
changes, pending encrypted reflection overlays, persisted daily rating selection
and stable current-day encrypted cache identities. See ACCEPTANCE.md for actual
connected/offline emulator checks and their limits. Daily rating read failures show
a retry state without hiding the cached activity overview. A truncated rating list
without the matching record is treated as unknown, not as an unanswered review.

## Daily loop follow-up (0.4.3-dev)

Issue #23 replaces read-only Daily reflections with the existing shared editor and
adds current-moment check-ins. Historical dates in both Daily and Timeline show a
return-to-today action rather than a check-in form. Existing recorded feelings
remain readable, and historical reflection editing remains available. No capture
or model consent changes. Connected normal/2× text and Astra source review are
recorded in ACCEPTANCE.md.
