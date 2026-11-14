# Dayflow screen audit and PocketLog priorities

Audit date: 2026-11-15. Reference: [official Dayflow source](https://github.com/JerryZLiu/Dayflow/tree/2c79e1619d7039c476e06e4b85b0246e50bc34fa), commit `2c79e1619d7039c476e06e4b85b0246e50bc34fa`. PocketLog inspected at HEAD `64dcfe29b4c0a3298287ee8b4e35069e7209cdfd` plus the existing uncommitted workspace edits. Concurrent implementation may supersede findings; verify the named functions before fixing.

## Evidence and limits

Two owner-supplied **actual Timeline screenshots** were inspected privately: wide 1728 × 1084 and narrow 900 × 1084. Neither image, its private attachment path, nor journal text is reproduced here. They establish visible layout and displayed states only, not successful interactions or the exact build revision. `docs/images/DashboardPreview.png`, `daily.png`, `weekly.png`, and other promotional assets in Dayflow are marketing/reference assets, not evidence that this checkout was run. No marketing image was substituted for actual runtime evidence.

Linux cannot run the macOS SwiftUI application. Daily, Weekly, Chat, Flow, Agents, Settings, Support and onboarding findings below are **source observations**, not screenshot or VoiceOver verification. A declaration existing in the tree does not establish that it is the current tab. Routing was checked in `Views/UI/MainView/Layout+Panels.swift`; all Swift paths below are relative to `Dayflow/Dayflow/`. PocketLog native/web inspection was read-only; this audit did not execute UI tests, capture PocketLog screenshots, create issues, or change application source.

Read alongside [UI guidelines](../UI_GUIDELINES.md), [security boundaries](../SECURITY.md), [existing parity matrix](../DAYFLOW_PARITY.md) and [climb ledger](UPHILL_CLIMB.md). Proposed work below is not a claim of implementation.

## Actual Timeline screenshot findings

The wide screenshot gives the main timeline most of the window, with an approximately 80 px navigation rail and a roughly 360 px day-summary inspector. Navigation exposes all eight destinations with text labels. The header groups previous/next/calendar, Day/Week, selected date, and Pause; category chips sit directly above the timeline. Hour lines, blank intervals, category edge colors, app icons, titles and exact start/end labels explain the day without opening every card. A visible generation placeholder is distinct from finished activity. The footer contains copy, weekly tracked time and privacy control. The inspector orders targets, category distribution, review and focus metrics.

The narrow screenshot preserves the summary width, compressing the timeline. The date title is absent, category chips clip, titles wrap or truncate, and footer text wraps vertically. This is useful evidence of a desktop compromise, **not a mobile template**. PocketLog should stack/collapse the summary and keep date context visible at phone widths. Preserve empty hours and actual interval geometry; do not copy the private journal, dark theme, artwork, or decorative serif typography into PocketLog's selected sage/Noto direction.

The screenshots cannot prove keyboard selection, contrast compliance, hit-target size, pause propagation, privacy protection or successful generation. Category text plus color is better evidence than color alone; the tiny legend and low-contrast metadata still need measured accessibility review.

## Screen-by-screen audit

### Timeline

**Official behavior and hierarchy.** `MainView/Layout+Panels.swift`, `Layout+TimelineHeader.swift`, `DateNavigationControls.swift`, `TimelineCalendarPopover.swift`, `TabFilterBar.swift`, `CanvasTimelineDataView.swift`, `WeekTimelineGridView.swift` and `MainView/ActivityCard.swift` implement day/week navigation, category muting, interval selection and an inspector. The day inspector replaces the summary with a selected activity, including title/category correction and feedback/delete actions. The week inspector is conditional on selection. `Components/DaySummaryView.swift` and related target/review/focus components explain the selected day. Screenshot evidence supports the unselected day layout only.

**States and accessibility.** `MainView/Layout.swift` has actionable provider/account failure notices and a separate recording-permission notice; `TimelineReviewOverlay.swift` and review media files support a distinct review workflow. Copy has copying/copied states and an explicit accessibility label. Selection and category states still require keyboard/VoiceOver testing; canvas geometry alone is not an accessible control tree. A generation placeholder must reflect a real job, never simulated progress.

**PocketLog comparison.** `server/web/app.js` functions `timeline`, `track`, `summary`, `editCard` and native `MainActivity.kt` `home`, `render`, `editCard` plus `ActivityTimelineView.kt` already implement real intervals and correction. Native canvas explicitly defers accessibility to the complete text list. Both overlap implementations offset later cards but extend them to the same right edge; nested intervals can obscure earlier cards. The web event label names time/title but omits category. Native canvas text uses density rather than scaled font size and clips to a single line. Preserve the accessible list when improving canvas appearance.

**Fixes.** Fold persistent navigation, readable cards, date/header priority and mobile summary stacking into existing #9/#10. Add synthetic nested/identical interval cases, short intervals and long Traditional Chinese titles to their acceptance evidence. Ensure every card remains selectable through a list even when geometry overlaps. Separate no records, no filter matches, loading, stale cache and failed refresh; retained content must identify its date and freshness.

### Daily

**Official behavior and hierarchy.** Routed `DailyView.swift` uses `DailyView+Workflow.swift` for date navigation, workflow overview/category totals, then editable standup highlights/tasks from `DailyView+Standup.swift`. Copy and regenerate actions have explicit labels; draft saves are scheduled. `DailyView+Access.swift`, `DailyAccessLockedViews.swift` and `Core/Access/FeatureAccessRequirements.swift` gate access using completed batches corresponding to five hours and provider/unlock setup. `DailyBrief/*` is a separate brief overlay, including saved/completed/page states; it should not be conflated with the Daily tab itself.

**States and accessibility.** Source distinguishes locked/unlocked, provider availability, regenerating/regenerated/no-data, disabled future navigation and generation failure. Layout currently sets `useSingleColumn = false`, illustrating why copying desktop columns onto a phone would be inappropriate. Source labels on copy/regenerate are positive evidence, not a full screen-reader pass.

**PocketLog comparison.** Web `review` combines deterministic category totals, top activities, check-ins, editable reflection and saved AI briefs. Native `dailyReview` shows targets/category totals, self-rating and existing feelings/reflections, while editing reflection returns to Timeline and briefs use another page. This fragments the daily reading/writing loop. Native daily read uses encrypted cache but does not surface `offline` in this screen's result path. Web AI-brief confirmation names `ai.model` but not the endpoint/provider host, despite the interface guideline requiring exact destination before consent.

**Fixes.** P1: one daily page ordered date → factual overview → reflection/check-in → optional AI brief; preserve the distinction between current-moment check-ins and a past selected day. Show cache freshness and incomplete-data flags beside totals. Before generating a brief, show provider host, model, exact date range and bounded outgoing scope together. Keep manual review usable without AI; do not import Dayflow's recorded-hours lock into ordinary journaling.

### Weekly

**Official behavior and hierarchy.** Routed `Weekly/WeeklyView.swift` uses a week header, overview/donut/context charts, workflow, focus heatmap, treemap and Sankey export wrappers. `Weekly/Sections/*` contains the individual models/views. The view has access gating and a selected-week requirement of at least 15 recorded hours. Several other chart/prototype files exist but are not proof that all are mounted by this route.

**States and accessibility.** Selected-week data gating differs from general feature access. Source refreshes on week/category/activity changes, disables forward navigation as appropriate and supports chart graphic export. Dense visual charts need text equivalents and meaningful labels; chart file presence does not establish accessibility. No actual weekly screenshot was supplied for this audit.

**PocketLog comparison.** Web reuses `review` for category bars and seven day blocks; native `weeklyTimeline` mainly presents seven scrollable timeline columns, with aggregate overview elsewhere. A week timeline and a weekly reflection report are different tasks. Native's labeled Weekly destination therefore underserves the second task even though week data is available.

**Fixes.** P1: a weekly overview with date range, per-day activity/focus totals, category breakdown and links into each day, followed by optional saved recap. Keep missing days visibly missing. P2: a focus heatmap with table/list equivalent, derived solely from existing intervals and explicit focus roles. Sankey, treemap and generated artwork should wait until the basic report is clear and reliable. Do not unlock insights by encouraging more capture.

### Chat

**Official behavior and hierarchy.** `ChatPanelView.swift` routes to `ChatView.swift`; `ChatView+Content.swift`, `ChatView+State.swift`, `ChatView+Actions.swift`, `ChatHistoryPanel.swift`, `ChatMessageViews.swift` and `ChatCharts.swift` cover new/history, welcome suggestions, transcript, work status, composer, provider selection, charts and editable memory. Beta access includes ten hours of data and a usable Gemini/CLI runtime. There are source paths for debug/tool work and memory; PocketLog need not inherit their execution or retention model.

**States and accessibility.** Empty welcome, locked runtime/data requirements, answering status and submission disabling are explicit. History and editable memory create a clear need to distinguish current question scope from persisted context. Transcript updates, composer focus, keyboard submission and chart text alternatives remain runtime verification items.

**PocketLog comparison.** Web `chatPage` and native `chatPage` already have bounded persistent dialogue, per-request consent, day/week selection and deterministic category charts. Web displays provider endpoint/model, retains a stable pending request body for retry, and resets consent after send. However disabling AI returns before loading saved conversations, making existing private history unavailable through that UI even though reading history should not require an active provider. Screen-wide rebuilding also risks losing composer/focus state when changing scope or navigating.

**Fixes.** P1: separate read-only history availability from ability to send; show exact range/provider/evidence cap beside the composer. Keep a failed question editable and retryable without duplicating a completed request. Add progress and errors beside the pending turn rather than only a global notice. P2: concise suggested questions using no sample journal and no implicit send. Do not add tools, automatic memory or Markdown execution to reproduce desktop capabilities.

### Flow

**Official behavior and hierarchy.** `Flow/FlowView.swift` hosts `FlowWebView.swift` after account restoration, entitlement/entry and provider capability checks. `FlowEnterView.swift` handles entry/provider-limited states. The main layout keeps Flow alive separately from the tab switch. The backend and loaded remote application's full screens are not established by the Swift wrapper.

**States and accessibility.** Loading, account-restoration failure, offline retry, provider limitation and web load failure are explicit. Retry recreates the web view; support can overlay it. The embedded remote UI's semantics cannot be assessed from this wrapper or Timeline screenshots.

**PocketLog comparison.** PocketLog intentionally offers a private user-started cross-device focus session, not Dayflow's cloud agent. Web `flowPage` has a visible countdown. Native `flowPage` computes remaining minutes once after the GET and requires manual refresh to change the display. A timer that visually freezes undermines trust even when its server timestamp remains correct.

**Fixes.** P1: native visible countdown derived from the stored end timestamp, updated only while the page is visible, refreshed on resume, with an explicit expired-but-unsaved state. No background capture, wake lock or frequent network polling. Test starting on one client, resuming on another and finish/cancel conflicts without duplicate records. Announce completion once; do not make screen readers speak every second. Keep product copy honest about the private timer's scope.

### Agents

**Important route correction.** `MainView/Layout+Panels.swift` routes the Agents tab to **`Agents/AgentPlaybackView.swift`**, not `AgentsView.swift`. The former introduces local Codex/Claude sessions, working/waiting, project history, token usage and estimated API cost; setup downloads AgentPlayback and requires Node.js/npm. It starts a local process and embeds a `WKWebView`. Its package is requested as `latest`, so the Swift SHA alone cannot pin the downloaded dashboard's exact UI. No claim of pixel or function parity with that dashboard is justified by this audit.

**Source-only alternative.** `AgentsView.swift`, `AgentsOverviewView.swift`, `AgentsBriefingView.swift` and `AgentsCardsView.swift` implement a Fable-generated workstream → briefing → thread review flow with manual generation, elapsed progress, failure/retry and explicit back labels. This is useful design research, but is not the inspected tab route. Existing parity descriptions that imply it is the current Agents tab should be corrected during integration.

**States and accessibility.** The routed wrapper has not-set-up, preparing, missing runtime/failure, ready/open, startup retry and embedded dashboard states. It permits switching tabs during setup and shuts down after time off-screen. Accessibility of the downloaded dashboard was not evaluated.

**PocketLog comparison.** Native `agentsPage`, `agentImportPreview`, `agentSessionsPage`, `agentSessionReplay` and web `agents`, `agentImportDialog`, `agentReplay` provide explicit local file parsing, per-turn review, project grouping, status and approved-text replay. The web session list has no explicit empty state. Its selected day header coexists with an unfiltered `/v1/agent-sessions` list, so users can mistake all retained sessions for the selected day's work. Support text still understates native JSONL support as summary-only.

**Fixes.** P1: label sessions as all retained dates or implement bounded date filtering; show project/status/turn count and a clear empty/import state. Make import preview's selected-turn count and upload action primary; preserve local preview, deselected defaults and editable text. P2: project overview → session detail with next/previous approved turn and position announcement. Do not scan private session directories, start shell tools, install desktop packages, import thinking/tool output, or send these records to models merely to imitate Dayflow.

### Settings

**Official behavior and hierarchy.** `SettingsView.swift` separates Account, Permissions, Storage, Privacy, Providers, MCP/CLI, Export and Other. `SettingsPermissionsTabView.swift` and `SystemPermissionRow.swift` describe system access; `SettingsRecordingPrivacyTabView.swift` has installed-app search/blocking; `SettingsProvidersTabView.swift` exposes provider setup/tests; `SettingsStorageTabView.swift` covers storage; `SettingsDataTabView.swift` covers date-range export and costly/destructive reprocessing confirmation; `SettingsAgentAccessTabView.swift` has checking/connected/not-found/retry states.

**States and accessibility.** The official structure keeps unrelated settings out of one continuous form. Export range inputs and copy commands have explicit accessibility labels. Drag-to-block privacy affordances require an equivalent non-drag action. Provider tests, failed writes and permission rechecks need feedback in their own section.

**PocketLog comparison.** Web `settings` groups several dialogs but exposes raw JSON in service status; native `settings`, `aiSettings`, `sharedSettings`, `categoryRulesEditor` provide broad controls in long screens. Shared server pause correctly documents offline propagation rather than promising instant remote control. Provider configuration is independent of future capture consent and must remain so.

**Fixes.** P1: group Connection/devices, Recording/privacy, AI, Categories/goals and Data; summarize each current state before its action. Present readable service health/queue/last-sync labels, with technical diagnostics secondary. Show revoked credentials and consent invalidation as concrete recovery paths. Preserve 409 conflict handling instead of overwriting another device's settings. Confirm exports and deletion with the actual scope; never simplify server pause into a misleading claim that all offline phones already stopped.

### Support

**Official behavior and hierarchy.** `BugReportView.swift` and `SupportChatWebView.swift` provide an embedded support chat, loading/ready/unavailable states, external contact options and debug-log copy. The failure state offers another contact route.

**PocketLog comparison.** Native `supportPage` and web `support` supply local help and source/documentation links. This is intentionally safer than automatic chat uploads. Their broad troubleshooting paragraphs require the owner to infer the failing stage; current capability copy is stale about approved JSONL imports.

**Fixes.** P1: task-oriented routes for no activities, offline sync, permissions, revoked key and AI failure, linking directly to the relevant local settings. Explain what is known and what to check next. Update support copy to match shipped import/replay boundaries. P2: owner-triggered copy of a strictly allowlisted diagnostic summary containing version/state/counts, with preview; never raw logs, endpoints with credentials, journal excerpts or screenshots. No automatic message delivery or telemetry.

### Onboarding and first run

**Official behavior and hierarchy.** `Views/Onboarding/OnboardingFlow.swift` actually invokes several files named `Prototype`: intro video → role/preset → referral → provider selection/setup → screen permission → personal goal → completion. Therefore the word Prototype in a filename does not itself establish non-production status. Progress and saved step are explicit. Provider routing save errors block progression. Completion creates a sample card and begins the getting-started checklist. `ScreenRecordingPermissionView.swift` and provider test views cover OS/provider setup separately.

**States and accessibility.** Back, saved progress, setup/test failures, granted/denied permission and completion deserve separate acceptance fixtures. Video, shrinking text, progress rings and animated transitions need reduced-motion and keyboard alternatives; these were not run. The source's referral/analytics calls and sample activity are not appropriate defaults for PocketLog.

**PocketLog comparison/fixes.** Existing #8 owns deployment → browser login → shared value/privacy/goals/optional AI setup → phone connection → explicit device consent → first real record. Existing #9 owns native presentation. Keep those issues rather than creating another onboarding umbrella. Completion must never imply Usage Access, accessibility, image capture or AI consent. Provide an empty-but-usable journal with reflection available and a specific next action; never populate a production account with demo activity. Verify server-unreachable, invalid/revoked device key, no AI, skipped capture and previously completed shared setup.

## Additional issue proposals

These are proposals for the coordinator, not filed issues. #8 already owns first run, #9 native shell/timeline, #10 responsive web timeline and #11 the acceptance ledger. Keep their layout/a11y work there. The following cuts have distinct behavior and testable outcomes:

| Priority | Proposed issue | Acceptance evidence |
| --- | --- | --- |
| P1 | Screen-local loading, stale data and retry states across journal tools | Synthetic empty/error/offline fixtures for Daily, Weekly, Chat, Agents and Settings; retained date/content clearly marked stale; retry works without losing an unsaved reflection/question; no raw error content or credentials. Include native Daily cache flag. |
| P1 | Identify the destination and range before every AI brief | Native/web confirmations show provider host, model, exact dates, bounded card scope, exclusions and retention; no request without explicit action; changed provider requires renewed consent. Existing chat/schedule boundaries remain intact. |
| P1 | Make native focus countdown live and recoverable | Visible countdown crosses zero without reload; background/resume derives correct time; cross-client finish/cancel and network failure are recoverable; no extra capture, wake lock or periodic network traffic. |
| P1 | Keep saved Chat readable when AI is disabled | Existing history remains readable/deletable; composer shows setup-required state; failed-send text persists; retry uses the same request identity for unchanged input. |
| P1 | Separate Weekly review from seven-day timeline | Seven factual daily totals, meaningful empty days, category totals, day drill-down and optional brief on native/web; accessible text equivalent; no AI required for factual overview. |
| P1 | Clarify Agents date scope and import states | All-retained vs selected-day scope is explicit; empty/loading/error states; approved-turn count visible before upload; source-only Fable flow no longer described as the routed Dayflow tab. |
| P2 | Task-oriented Settings/Support recovery | Concrete no-data/key/permission/provider paths; human-readable service status; accurate JSONL/replay copy; diagnostic copying, if added, is previewed and allowlisted. |

Prioritize trust and recoverability before adding chart types. Native/web source currently uses generic status handling (`MainActivity.task`, web `safe`/`notice`) that often loses the action-specific recovery path. Web `shell` rebuilds the root before data fetches complete, so preserve/loading/error behavior and focus restoration deserve deliberate implementation rather than cosmetic spinner additions.

## Required acceptance evidence for implementation

Use only synthetic journal content in an isolated instance. Capture desktop and 390 px web layouts, plus native emulator screenshots if feasible without weakening production `FLAG_SECURE`. Test keyboard traversal, visible focus, dialog naming/return focus, text zoom, TalkBack access to all activities, long Traditional Chinese titles, category names independent of color, overlapping intervals and short events. Mark active navigation/filters programmatically, not only with CSS. No claim of accessible operation until tested with the relevant assistive technology.

For each changed screen record normal, empty, loading, failure and applicable offline/disabled/permission states. Verify an old async response cannot populate a newly selected day/screen. Use fixed synthetic timestamps around the 04:00 boundary and a DST transition; calendar labels must agree with actual interval geometry rather than assuming every local day lasts exactly 24 hours. This is a proposed check, not an observed runtime defect.

Run repository-required Rust fmt/clippy with warnings denied/tests/audit and Android unit tests/assembleDebug/lintDebug for integrated changes; Docker smoke for deployment changes. This documentation-only audit did not run those checks. Physical permission behavior, battery impact and real-device accessibility remain unverified.

## Mobile boundary objections

Desktop screenshots, downloaded local dashboards and remote Flow are not a justification for continuous Android recording, on-device inference, high-frequency sampling, WebView, shell execution, implicit provider access or background session scanning. Battery work requires a measured budget and an explicit product decision. Pre-capture exclusions, separate collection/image/replay/model consent, provider binding, bounded retention, idempotency and revocation must survive UI simplification.

The useful transferable design is a clear path from evidence → review → deliberate action. PocketLog can deliver that through existing bounded records, readable summaries, explicit selection and honest states. It must not label inferred emotion as a fact, invent missing activity, imply OCR redaction is implemented, or claim parity with remote/downloaded surfaces that were neither available nor run.
