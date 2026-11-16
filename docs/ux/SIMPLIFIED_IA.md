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
