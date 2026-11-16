# Simplified product (2026-11-17)

Owner direction: PocketLog becomes one simple loop. **The phone captures what is on
screen; your AI understands it; you look back.** Nobody has to write a diary or rate a
mood. Everything below applies to both the Android app and the website unless marked.

One-sentence promise shown in onboarding and the empty timeline:
**「手機自動記下你看過的畫面，你的 AI 幫你整理成可回顧的一天。」**

## Navigation

Exactly three destinations on both clients, in this order:

| Tab | Purpose |
| --- | --- |
| **時間軸** | What happened on a day, as AI-understood activity cards |
| **回顧** | Day / week overview, AI recap and asking the AI about the period |
| **設定** | Capture, AI, categories, devices, data, help |

Removed from the UI (APIs and stored data stay for compatibility, nothing is deleted):
daily reflection, mood/energy check-ins, daily focused/neutral/distracted rating, Flow
focus timer, Agents (Codex/Claude import), the separate Chat page (now inside 回顧),
the separate replay page, focus/distraction goals and their meters, onboarding goal
step, Android local-only mode and the reflection reminder (PR #115, closed), the
"copy timeline" action. The 支援 tab becomes 設定 › 說明.

## Words

Plain Traditional Chinese (Taiwan). Avoid: observation, context, provider_id, epoch,
cursor, worker, queue, schema, "derived". Use: 截圖、畫面文字、AI、整理、記錄、活動卡.
Card source labels: `AI 整理` (ai), `畫面文字` (local_excerpt), `只有 app` (app_only),
`你改過` (user). Pending server evidence: `AI 整理中`.

## 時間軸

Top to bottom:

1. Date header with previous / next / 今天. (Web desktop may keep its date picker.)
2. **One status line** (tappable, goes to 設定 › 記錄): the single most important state:
   - `記錄中 · AI 自動整理` (all good)
   - `記錄中 · AI 未設定，只顯示 app 名稱` → 設定 › AI
   - `等待充電時上傳截圖（N 張）` (phone only, normal battery-saving state, not an error)
   - `需要權限：使用情況存取` / `需要權限：輔助使用` → the exact Android screen
   - `已暫停` / `金鑰失效，請重新連線`
   - `尚未開始記錄` → guided start
3. **Day summary**: `已記錄 5 小時 12 分` + one stacked category bar with the top
   categories (legend is the bar's labels, no donut).
4. **Activity cards**, newest day order (chronological). Each card: time range, AI title
   (falls back to the app's display name), one-line summary (ellipsized), category chip,
   small source label. `AI 整理中` badge when `pending_context`. Category filter chips may
   stay on the web; on the phone a single filter menu is enough.
   - Web keeps its hourly time grid on desktop (≥ 1100 px) with the inspector, list on
     narrow screens; Android shows the list by default and keeps the compact time
     ribbon only if it stays readable.
5. Empty day: the promise sentence + one button (start recording / set up AI / nothing
   if already recording: "手機會在背景記錄，AI 整理後會出現在這裡").

Card detail (sheet / dialog / inspector):
- AI title, full summary, app display name, time, source label.
- **Category** as a one-tap picker that saves immediately (manual edit wins).
- Title/summary editing behind a small `修改文字` action.
- Screenshot thumbnails only if replay consent produced them; never raw analysis images.

## 回顧

- Segmented **日 / 週** with the same date navigation.
- **AI 回顧** card first: the saved brief for the period, or a primary button
  `產生 AI 回顧`. Under the button, one line: `會把這段時間的活動卡送到 <provider host>`.
  If AI is not configured: `先設定 AI` → 設定 › AI.
- **時間分配**: category bars with durations and percentages; **常用 app** top 5;
  **主題** keyword chips. Week view adds a seven-day mini bar row.
- **問 AI**: one input `問問這段時間…` with up to three example chips
  ("我花最多時間在哪？", "這週學了什麼？", "有哪些分心的時段？"). Answers stack
  below as a simple conversation for the selected period (existing `/v1/ask` with
  conversation IDs). No separate thread list UI.
- Automatic daily/weekly recap scheduling lives in 設定 › AI as one switch.

## 設定

Short sections; each shows its current state in one line before any action:

1. **記錄** (phone; web shows each device's last-seen time instead)
   - One master switch `自動記錄`. Turning it on runs a short guided sequence: explain →
     Android 使用情況存取 → 輔助使用 (screen text + screenshots) → choose apps
     (allowlist, with sensible suggestions) → done. Each OS step is one screen with one
     button. Consents stay independent underneath, but the user sees one flow.
   - `截圖` switch (on by default with screen text), `排除的 app`, `暫停所有裝置`.
   - Battery line: `截圖在充電時上傳，平常只記錄、不耗流量`.
2. **AI** (Mika-style). Pick a provider from a list: OpenAI · Anthropic · Google Gemini ·
   OpenRouter · DeepSeek · 自訂（OpenAI 相容，例如 Ollama）. Then paste the API key,
   choose/enter a model (prefilled suggestion, editable), `讓 AI 看截圖` (on), and
   `測試並儲存`. Endpoint URLs are filled in from the provider and only shown for 自訂.
   After saving: one switch `自動整理新截圖` (the existing provider-bound auto switch)
   and one switch `每天自動產生回顧`. Show `已連接：Anthropic · <model>` as the state.
3. **分類**: categories (name/colour) and automatic rules. Existing editors, reached
   from one row. No focus/distraction goals.
4. **連線與裝置**: server address and this device (phone); device keys (web).
5. **資料**: storage use and limit, export, delete.
6. **說明**: short help and source links (the old 支援 page content, condensed).

## Onboarding

Three steps at most, skippable:
1. The promise sentence and what is captured / never captured.
2. Connect your AI (the same Mika-style form; `稍後再說` allowed).
3. Phone: start recording (the guided flow above). Web: "install the Android app and
   connect it" with the device-key step.

## Battery (Android)

- Capture cadence is unchanged (10 s while an allowed app is in front of an unlocked
  screen, plus app switches) by owner decision.
- **Skip unchanged screens**: if the normalised screen text and app are identical to the
  previous capture and the screenshot is not needed (no visual change detected via a
  small downscaled hash), skip encoding/storing/uploading it.
- **Upload screenshots while charging**: observations (screen text + screenshots)
  upload only while charging, or on an unmetered network when the queue exceeds
  256 MB, so storage cannot fill up. Small app-session uploads keep the normal
  15-minute cadence. AI runs on the server and costs the phone nothing.
- Phone queue retention for observations is 72 h; the server accepts context up to 72 h
  old and still analyses it.
- No new periodic wakeups on battery.

## Fewer restrictions (owner, 2026-11-17)

- **PocketLog's own screen protection** (`FLAG_SECURE`) becomes a setting,
  `防止截圖這個 app`, **off by default**, in 設定 › 資料. When on, the activity and its
  dialogs are protected as before.
- **Protected / DRM screens of other apps** (Netflix, banking apps, any window with
  `FLAG_SECURE`): Android returns no pixels for them and PocketLog never tries to bypass
  that. Instead of discarding the whole capture, record the app and its visible screen
  text **without a screenshot**, and mark the observation so the card can say
  `受保護畫面（無截圖）`. A black or blank frame is never stored or uploaded. The AI
  still summarises from app name + text.
- Capture is no longer rejected just because a window is secure. What stays:
  user exclusions/allowlist, password and editable-field windows (credentials), the
  lock screen, and the suspected-secret text shield (on by default, can be turned off).

## Cross-platform consistency (UX round 1, 2026-11-17)

Both clients must use exactly these rules:

- **Status line vocabulary** (first matching wins): `已暫停` · `金鑰失效，請重新連線` ·
  `尚未開始記錄` · `需要權限：使用情況存取` · `需要權限：輔助使用` (phone; web: `手機需要開啟輔助使用`
  when the device reports it is unknown, otherwise skip) · `手機超過一天沒有連線` (web) ·
  `AI 未設定，只顯示 app 名稱` · `AI 自動整理已關閉` · `等待充電時上傳截圖（N 張）` (phone) ·
  `AI 整理中（N 張）` · `記錄中`. Each maps to the one place that fixes it.
- **Units**: `1 小時 4 分`, `56 分`, `不到 1 分`; never `分鐘` in compact rows.
- **Category bars**: width = share of the total recorded time (percent), same on both.
- **App names**: never show a package ID. Use the device label (Android) or the shared known-name
  map, then a humanised last package segment (e.g. `Pocketlogfixture`).
- **Times**: a zero-length range shows `01:26 · 不到 1 分`; cards after midnight in a 04:00 day get a
  `隔天凌晨` divider.
- **Card source label**: show only exceptions (`只有 app`, `你改過`, `畫面文字`, `AI 整理中`,
  `受保護畫面`), never the normal `AI 整理`.
- **Dates**: zh-TW everywhere (`11月16日（週一）`, week `11月16日 – 22日`), never `11/16/2026`.
- **Toggles are switches** (no checkboxes); navigation rows have a chevron; destructive actions are
  low-emphasis text with a confirmation, never a large filled button.
- **AI setup**: provider choice is a visible list/grid on both clients, a saved key shows
  `已儲存金鑰 ••••，貼上新的會取代`, the Ollama hint only appears for 自訂, each provider has a
  `取得 API 金鑰` link to its official key page, and the two AI switches live only on the AI page
  (設定 shows a one-line summary).

## Round 2 rules (2026-11-17)

- **Counts** keep a space: `AI 整理中（1 張）`.
- **Durations under a minute** show `不到 1 分` (also when start and end round to different
  minutes); zero-length ranges show only the start time.
- **Card structure is identical on both clients**: row 1 `時間 · 時長` + category chip + at most
  one exception badge; row 2 title; row 3 one-line summary. Neutral card background with a
  left category stripe (no tinted cards). App-only cards use the summary `只知道使用了這個 app`.
  When `pending_context` is true show only `AI 整理中` (never together with `只有 app`).
- **Date navigation**: `‹ 日期 ›`; a `今天` button appears only when not viewing today (both).
  When scrolled, the sticky header keeps the short date/period (e.g. `11月16日（週一）`).
- **Time split rows**: `分類 | bar | 1 小時 · 25%` on both.
- **Week bars**: stacked by category on both, labels `週一 … 週日`, value `4.1 時` (one decimal);
  empty days show a faint baseline and `沒有記錄` in the accessible label.
- **Topics** appear only when the server returns model topics (`keyword_mode` `model`/`mixed`);
  with local-only keywords hide the section (local keywords are just card titles).
- **問 AI**: neutral placeholder `問問這段時間…`; examples per period — day: `今天花最多時間在哪？`,
  `今天學了什麼？`, `有哪些分心的時段？`; week: `這週花最多時間在哪？`, `這週學了什麼？`,
  `這週有哪些分心的時段？`. Send button sits inside/next to the input.
- **Storage**: under 1 GB show `MB` without decimals, otherwise `GB` with one decimal.
- **Words**: use `記錄` (not `紀錄`). Section and field labels in Chinese (`App` → `應用程式` or omit).
- **Bottom safe area**: the last item is never covered by the bottom navigation.
- **Focus styles**: keyboard focus uses a thin ring; selected states use the soft pill
  background, never a thick dark outline.
- **Destructive actions** are grouped at the end of 資料, low emphasis, with an explicit
  `無法復原` confirmation. Changing server/key is a normal row (its dialog explains effects).
- **Recording settings** (phone): one master switch; while a permission is missing the
  screenshot switch is disabled and its subtitle names the missing permission; Wi‑Fi-only moves to
  進階 with one line: `截圖只在充電時上傳；開啟後其他資料也只用 Wi‑Fi`.
- **Web settings**: `暫停所有裝置` is a switch; if the browser origin is loopback, the
  phone-address hint says `這是本機位址；手機請用 VPN／區網位址`.

## Networks and self-hosted AI (owner, 2026-11-17)

- Wording never assumes Tailscale: say `VPN／私人網路（例如 Tailscale、ZeroTier、Headscale、
  WireGuard、區網）`.
- **Phone connection**: HTTPS anywhere; plain `http://` only for private-network hosts (same rule
  as the server: loopback, 10/8, 172.16/12, 192.168/16, 100.64/10, IPv6 ULA/link-local,
  `localhost`, `*.local`, `*.lan`, `*.internal`, `*.home.arpa`, single-label names). Show
  `這個連線沒有 HTTPS，資料保護依賴你的 VPN／私人網路` when http is used. Public http is refused.
- **AI 自訂** is labelled `自訂（OpenAI 相容：CLIProxyAPI、LiteLLM、Ollama…）`; its field is
  `API 網址` with placeholder `http://127.0.0.1:8317/v1` (base URL; the server appends
  `/chat/completions`); the key may be empty (`代理不需要金鑰可留白`).
- **載入模型清單** on every provider: `POST /v1/ai/models {protocol, endpoint, api_key?}` returns
  `{models:[…]}`; show them as a pickable list, keep free text as fallback, show a short error on
  502/429. AI page order: AI 服務 → 金鑰 → 模型 (+ 載入模型清單) → 讓 AI 看截圖 → 測試並儲存 →
  section `自動化` with the two switches. Label `AI 服務` on both clients.

## Round 3 rules (2026-11-17)

- **Cards** are separate cards (not rows in one container) with a neutral background and a 4 px
  left category stripe, on both clients and in the web desktop grid blocks too. Row 1:
  `時間 · 時長` + category chip (soft, with dot) + at most one badge. Badge styles: `AI 整理中`
  accent soft fill, `受保護畫面` amber soft fill, `只有 app` / `你改過` / `畫面文字` neutral soft
  fill. `隔天凌晨` is a neutral grey divider.
- **Card detail order** (both): title → `App 名稱 · 時間 · 時長` → summary → one disclaimer line
  `AI 整理，可能有誤，請核對。` (only for ai/local_excerpt) → category chips → `修改文字` (icon +
  text button). No separate normal-source label.
- **Copy**: AI recap note `AI 產生，可能有誤，請對照時間軸。`; ask disclosure
  `會把問題和這段時間的活動卡送到 <host>（不含截圖）。`; use `…` never `...`; `已連接：`; field
  `API 金鑰`; page/dialog title `AI`; `防止截圖這個應用程式`; storage `已用 0 MB / 10.0 GB`.
- **Segmented 日/週**: soft style (selected = white pill on muted track), never a saturated block.
- **Week chart**: seven equal columns across the full card width, ~160 px tall, value above each
  bar (`4.1 時`), empty days a faint baseline with `—` and accessible `沒有記錄`; `平均每天 X（已過 N 天）`.
- **Time split**: one line per category on every width (`● 分類 | bar (flexible, ≥ 40% width) | 值`).
- **Ask AI**: example chips in one horizontally scrollable row (smaller than the input); send is an
  icon button inside the input.
- **Switches**: off state must be visible (outlined track, ≥ 3:1 contrast); on state filled.
- **Selection vs focus (web)**: programmatic focus restoration must not show a focus ring (use
  `focus({focusVisible:false})` or pointer-modality tracking); the current nav item uses the soft pill.
- **Web recording status** never claims `記錄中` from last-seen alone: say `最後連線 11/17 03:17`.
- **http origins**: whenever the site or phone uses `http://`, show the no-HTTPS warning row (in
  addition to the loopback hint where applicable).

## In-app updates (Android, owner 2026-11-17)

Release builds check `https://api.github.com/repos/skyhong2002/PocketLog/releases/latest` when the
app comes to the foreground, at most every 12 hours (setting `自動檢查更新`, on by default; a
manual `檢查更新` row in 設定 › 說明 shows the current version). A newer release has an asset
`PocketLog-<version>-<versionCode>.apk` with a higher versionCode. Dialog: `有新版本 <version>` +
short release notes + `稍後` / `略過這個版本` / `更新`. 更新 downloads the APK (HTTPS, GitHub
hosts only, size-capped), verifies its SHA-256 against the asset `digest` (or the `.sha256`
asset), then installs through `PackageInstaller`; Android shows its own confirmation, and the first
time asks to allow installs from PocketLog. Updates only succeed when the release is signed with
the same key as the installed app. Only GitHub is contacted; no journal data is sent. Sandbox and
debug builds never check.

## Round 4 rules (2026-11-17)

- **Selection** never uses a thick dark outline anywhere (category chips, grid blocks, tabs, nav):
  selected = tonal soft fill (+ optional ✓). Bottom-nav/rail selected item = the soft pill behind
  the icon on both clients.
- **Category order** is the workspace order on both clients; colour dots sit inside chip padding.
- **自訂 provider tile**: two lines everywhere — `自訂` and, smaller, `OpenAI 相容（CLIProxyAPI、
  LiteLLM、Ollama…）`. Provider choices are rounded-rectangle tiles (12 px radius) on both
  clients; `取得 API 金鑰` is a text link with an external-link icon on both.
- **Switch rows**: title regular, subtitle one step smaller and muted; helper text sits directly
  under its label. All switches one size.
- **Delete confirmations** state the scope and `無法復原` (journal deletion, local data reset, AI
  key removal).
- **Pending card** summary placeholder `正在整理這段活動…` so every card has three rows.
- **隔天凌晨** is a small soft neutral pill centred on a hairline.
- **Time split**: the bar flexes up to the value column (no fixed short track); compact rows
  (~48–56 px).
- **Ask AI**: chip side padding ≥ 12 px, full-width `？`, edge fade on the scroll row, the
  disclosure never breaks inside `（不含截圖）`, and an empty-state line `問題和回答會出現在這裡`.
  Send = filled circular accent icon button inside the input on both clients.
- **Week chart**: ~160 px/dp tall, bars ~60% of the column width, value directly above the bar,
  `—` on the baseline for empty days, today marked only by a bold weekday label (no boxes).
- **Headers**: all three tabs use the same header (title + optional single icon action), no logo.
- **Actionable rows** (`立即同步`, `檢查更新`) show a trailing icon; information shows once
  (no duplicate version footer / duplicate `最後連線`).
- **Recording (phone)**: the `自動記錄` switch alone starts the guided flow (no extra
  `開始記錄` button); off-state subtitle `尚未開始記錄`; battery caption directly under it;
  `暫停所有裝置` is a visible row in 記錄.
- **Web desktop 時間表**: blocks sit at their true times on a true hour scale (minimum height
  only, no reflow that misaligns hour labels); blocks ≥ 40 px tall show row 1 (time · duration,
  category chip, badge) and the title, taller ones also the summary; short blocks show at least the
  badge icon. Empty-hour gaps are faint text-only markers; `隔天凌晨` sits at 00:00.
- **Web controls**: native selects and date-nav buttons use the soft pill style; on narrow screens
  the 時間表/清單 toggle is hidden (list only); settings section tabs wrap to two rows on mobile
  instead of clipping.
