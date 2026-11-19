# Native Android UI implementation (issues #9, #8 first run)

## Current state: simplified app (2026-11-17)

Implements the Android parts of [SIMPLIFIED_IA.md](SIMPLIFIED_IA.md). The sections
below this one are the original 2026-11-15 implementation log and describe pages that
no longer exist (每日, 每週, Chat, Flow, Agents, 支援, goal meters, reflection,
check-ins, daily rating, copy timeline, the hourly day canvas and the week canvas).
Server APIs and stored data for those features are unchanged.

- **Navigation**: exactly three bottom tabs, 時間軸 / 回顧 / 設定, equal-width cells.
- **時間軸**: previous / date picker / next / 今天; one tappable status line chosen by
  `StatusLine.select` (key rejected → paused → not started → usage access →
  accessibility → AI not set → `等待充電時上傳截圖（N 張）` → all good); day summary
  (`已記錄 …` + one stacked category bar labelled by its top categories); a filter
  menu; chronological card rows with AI title (or app name), one-line summary,
  category chip, source label (`AI 整理` / `畫面文字` / `只有 app` / `你改過`) and badges
  `AI 整理中` / `受保護畫面（無截圖）`. Empty today shows the promise and one action.
  Card detail is a dialog: full summary, one-tap category chips that save immediately
  through the existing encrypted `/v1/cards` queue, 修改文字 for title/summary, and
  看截圖 only when replay consent exists. The two-minute update check is kept.
- **回顧**: 日 / 週 segmented control and date navigation; AI 回顧 card (saved brief or
  產生 AI 回顧 with `會把這段時間的活動卡送到 <host>`; 先設定 AI otherwise); 時間分配
  bars (week adds a seven-day bar row); 常用 app top 5; 主題 chips from `/v1/overview`;
  問 AI with three example chips, `/v1/ask` with one in-memory conversation ID per
  period and retry-stable request IDs; answers stack below.
- **設定**: 記錄 (master 自動記錄 switch → guided explain → 使用情況存取 → 輔助使用 →
  choose apps with installed suggestions → done; 截圖 switch; app lists; independent
  畫面文字 / replay / suspected-secret / pause-all rows; Wi‑Fi only; battery line;
  last capture), AI (state line, 自動整理新截圖, 每天自動產生回顧), 分類, 連線, 資料
  (storage, `防止截圖這個 app`, export a day, clear/delete), 說明 (condensed support).
- **AI form**: provider presets from `AiProviders` derive the endpoint (only 自訂 shows a
  URL field), the model is prefilled and editable, 讓 AI 看截圖 defaults on (off for
  DeepSeek), and 測試並儲存 saves (`PUT /v1/ai`) then runs the bound probe
  (`POST /v1/ai/test` with the returned `provider_id`). A 409 is reported, never
  retried with a new identity; automatic summaries stay off until switched on.
- **FLAG_SECURE**: a persisted setting, off by default; when on it applies to the
  activity and every dialog/picker as before.
- **Onboarding**: three skippable steps (promise → AI → start recording).
- **UX round 1 (2026-11-17)**: follows SIMPLIFIED_IA "Cross-platform consistency":
  shared status vocabulary and order (`StatusLine`), app names never shown as package IDs
  (device label → the website's known-name map → readable last segment, `LocalAppNames`),
  `不到 1 分`, a `隔天凌晨` divider, zh-TW dates (`11月16日（週一）`, `11月16日 – 22日`),
  exception-only source badges on one wrapping meta line, card detail as a bottom sheet
  with wrapping category chips (current first), single-line category bars sized by share,
  a `這週每天` card, period-matched 問 AI examples, a single master 自動記錄 switch with one
  next-step row and an 進階 page, `上次同步 HH:mm`, the AI page with a visible provider
  list, saved-key hint, `取得 API 金鑰` links, a visible test result and AI switches only
  there. Secondary text colour was darkened for ≥ 4.5:1 contrast.
- **UX round 2 + networks (2026-11-17)**: SIMPLIFIED_IA "Round 2 rules" and "Networks and
  self-hosted AI": at most one card badge (`AI 整理中` wins), `只知道使用了這個 app`, ranges under a
  minute show only the start, non-breaking legend items with dots, outlined filter, labelled
  同步 action, sticky date/period in the header, stacked week bars (`週一`, `4.1 時`, 平均每天),
  time split `1 小時 · 25%`, topics only for model keywords, inline 問 AI send with the spec's
  examples, a shorter 設定 › 記錄 (Wi‑Fi and 暫停所有裝置 in 進階), destructive actions last and
  quiet, AI page order with 載入模型清單 (`POST /v1/ai/models`) and an 自動化 section, and
  private-network `http://` connections (`EndpointPolicy.privateNetworkHost`, same rule as the
  server) with the VPN warning.
- **UX round 3 + updater (2026-11-17)**: separate timeline cards with a 4 dp stripe and soft
  badges (accent `AI 整理中`, amber `受保護畫面`, neutral others; pending cards show no app-only
  line), icon-only 重新整理, compact 今天, header shadow, sheet in spec order with a 修改文字 icon
  button and small inset chips, soft 日/週 segment, time split with a ≥ 40% bar, scrollable ask
  chips and a send icon inside the input, a full-width 160 dp week chart with `—` and
  `平均每天 X（已過 N 天）`, outlined/filled switches, 開始記錄 button and battery line in 記錄,
  http warning row with an icon, and `AppUpdater`/`Updates` (GitHub releases, 12 h throttle,
  SHA-256 check, PackageInstaller) behind `BuildConfig.UPDATE_CHECK`.
- **Instrumentation**: checks for removed pages were deleted; `UiSnapshotInstrumentation`
  renders 設定, a synthetic 時間軸 and a synthetic 回顧. No device or emulator run was
  made for this change; the new UI has unit-tested logic only.


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
  Back uses `OnBackInvokedDispatcher` only; the manifest sets
  `enableOnBackInvokedCallback` so Android 14/15 route button and gesture Back there too.
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

## AI brief consent follow-up (0.4.4-dev)

Issue #24 adds a readable confirmation page with provider endpoint/model, exact
range/timezone, bounds and exclusions. Cancellation makes no model call; one
request identity is retained for retries. Schedule consent identifies its
provider and cannot be enabled without one. See ACCEPTANCE.md for synthetic checks.

## Owner scope correction (#25)

Manual mood/energy entry and its historical card were removed from Timeline and
Daily. This supersedes the check-in work described above. Existing API/export
data is preserved; shared reflection editing and focus review remain available.
Prioritize consented screenshot capture, classification, timeline and API next.

## Category visibility follow-up (0.4.5-dev)

Issue #26 adds Dayflow-inspired category visibility controls shared by the native
timeline and list, filtered counts, all-hidden recovery, and rotation retention.
Full-day statistics remain clearly labeled and unchanged. See ACCEPTANCE.md.

## Capture diagnostics follow-up (0.4.6-dev)

Issue #28 adds content-free process-memory diagnostics wired to context capture
gates/callbacks and exposed in Settings/Support. Outcomes and last local queue
time do not imply successful sync/classification. Consent, exclusion and sampling
policy are unchanged. Acceptance distinguishes injected-state UI tests from OS
capture verification.
