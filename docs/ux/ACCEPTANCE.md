# UI acceptance evidence

## Native first iteration, 2026-11-15

The screenshots below render the actual Activity View hierarchy on an Android34
AOSP emulator, using the production Kotlin Views and synthetic data. The test
runner draws Views to PNG; these are **not compositor/system-bar screenshots**.
`FLAG_SECURE` remained enabled and is asserted by the runner before every capture.
No owner credentials, real journals, cloud provider or phone capture were used.

| Configuration | Evidence | Observed |
| --- | --- | --- |
| 360dp phone, font1.0 | [First run](screenshots/native-first-run.png) | Connection form visible before the expandable detailed help; key remains a password field |
| 360dp phone, font1.0 | [Timeline](screenshots/native-timeline.png) | Separate overlap lanes, proportional cards, labelled navigation and explicit recording-off state |
| 360dp phone, font2.0 | [Large text](screenshots/native-large-text.png) | Compact date avoids header wrapping; text wraps and page scrolls; navigation scrolls horizontally |
| 1067dp tablet, font1.0 | [Tablet](screenshots/native-tablet.png) | Readable centered column; all eight nav destinations fit |

The first captures exposed overly long setup instructions and a large-text date
that occupied most of the header. Both were changed and these captures repeated.
A total of30 captures were made locally, including settings, list mode and all
other navigation pages in their unconnected/recovery state. Connected API actions were subsequently checked below.
TalkBack speech/gestures, device permission behavior and nested-scroll feel have
not yet been validated. Do not interpret an error-state capture as
proof of the successful data path.

`UiSnapshotInstrumentation` is androidTest-only and is not part of the shipped APK.
It requires `synthetic=yes`, refuses any installation with saved credentials and
uses no server. Run only on a disposable emulator:

```sh
# Set JAVA_HOME/ANDROID_HOME to your local toolchains first.
./android/gradlew -p android --max-workers=2 assembleDebug assembleDebugAndroidTest
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb install -r android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e synthetic yes -e variant phone \
  tw.skyhong.pocketlog.test/tw.skyhong.pocketlog.UiSnapshotInstrumentation
adb pull /sdcard/Android/data/tw.skyhong.pocketlog/files/ux-snapshots
```

Unit tests/build/lint passed after integration (22 tests). Additional arithmetic
coverage verifies complete23/25-hour04:00 windows. Flow UI refresh stops onStop and
recomputes onStart; it performs no added network polling. Physical battery and
permission acceptance remain separate work.

## Web

Root verified the embedded files in a rebuilt `pocketlog:ux-check` container,
using an isolated synthetic journal on loopback18751 and a local fake model on18752.
No production data or external model was involved.

| Configuration / flow | Evidence / observed result |
| --- | --- |
| Desktop1440×1000 first run | [Welcome and key help](screenshots/web-first-run.png), [shared onboarding](screenshots/web-onboarding.png) |
| Desktop1440×1000 populated day | [Timeline](screenshots/web-desktop-timeline.png): overlapping cards remain separate; merged total120min, focus75min; category attribution145min is labelled |
| Mobile390×844 populated day | [Timeline](screenshots/web-mobile-timeline.png): labelled bottom navigation, separate overlap lanes, no document overflow |
| Explicit AI brief consent | [Destination and scope](screenshots/web-ai-consent.png); local fake model answer saved after confirmation |

Root walked the four onboarding steps, skipped AI, saved a first check-in, and
confirmed that manual journaling completes setup without a phone. Reflection save
and reload, successful Chat and saved daily brief using the local fake model,
session expiry after server restart and re-login were exercised. Flow start, leave/return, live countdown and finish/save were exercised. All eight pages
loaded at390px without document overflow. The fake model checks UI/request flow,
not real model quality or vendor interoperability.

A CSS200% zoom stress check at390px exposed fixed date-control/chart widths (#15).
The repair was checked through individual DOM style properties, preserving CSP;
rebuilt-image confirmation passed for all eight pages with no document overflow. CSS zoom is not browser text zoom.
The screenshots predate that responsive repair. Layout arithmetic and overlap/DST
regressions run in CI. Docker smoke passed again on the final rebuilt image.

Still pending: real browser zoom, screen readers, Firefox/Safari, reduced motion,
clipboard/download actions,
and broader keyboard/reduced-motion acceptance. Goals and a custom category/rule
were saved and confirmed through API reads. A device key was created with64
characters, one-time guidance and cleared dialog contents on close. Worker dev-proxy
checks are recorded separately in WEB_IMPLEMENTATION.md. The first-answer Chat picker issue (#16) was fixed in c318cf3 and verified against
the rebuilt image: the new conversation is selected, deletion is available and
focus returns to the question. No external model was used.

## Native connected and offline acceptance, 0.4.2-dev

A disposable Android34 emulator connected over system-verified tailnet HTTPS to an
isolated synthetic server. The fixture uses a deliberately fixed synthetic key;
production credentials/data were never loaded. `ConnectedUiChecks` invokes actual
View actions and renders Views with `FLAG_SECURE` asserted. This checks View/API
behavior, not physical gestures, system screenshots, real TalkBack speech or battery.

- Invalid short key, rejected 64-character key, corrected connection and all eight
  destinations passed. Shared setup remained complete; all capture/model consents
  remained off. [Connected timeline](screenshots/native-connected-timeline.png).
- Unsaved reflection survives tab navigation and Activity recreation. Intentionally
  clearing it stays empty after a server read. Chat drafts survive navigation and
  stay separate between day/week scopes. Drafts are bounded in memory, not written
  into Android saved-state Bundles, and clear on reset/deletion or Activity finish.
- A daily rating saves, remains selected after leaving/returning, and retains its
  previous selection when a replacement fails authentication.
  [Saved rating](screenshots/native-saved-rating.png). Selected state also has an
  accessibility description; screen-reader speech itself was not tested.
- Flow starts, remains active across navigation, and saves/ends.
  [Running Flow](screenshots/native-connected-flow.png). Check-in encrypted queue
  and manual sync passed.
- After an online read, the test enables airplane mode and disables emulator Wi-Fi,
  verifies there is no active network, then reopens a later current-day range.
  The encrypted snapshot remains readable. Directly queued reflection replacement
  and blank deletion override the old snapshot in sequence. Connectivity is restored
  in `finally`; these writes are cleared locally, not uploaded.

Run the connected runner only on a disposable emulator with an isolated fixture
on its explicitly restricted HTTPS port18447, passing `synthetic=yes`,
`connected=yes`, `offline=yes` and `endpoint=<fixture origin>`. It refuses an
already connected installation and accepts only its hardcoded synthetic key.
The runner clears local test credentials/queue in `finally`.

Android unit tests:33 passed; `assembleDebug`, `assembleDebugAndroidTest`,
`lintDebug` passed (no lint errors). Rust fmt, clippy with warnings denied, tests
and dependency audit passed against the unchanged service source. The audit loaded
1509 advisories/185 crates; the standalone audit tool warned that its crates.io
index update was unavailable. No new Docker/service deployment was needed for this
native-only runtime change; previous built-image smoke still applies.

Pending reflection precedence follows the existing queue filename/upload order,
including UUID ordering for writes in the same millisecond; it does not promise
insertion ordering within that millisecond. Cache identities now share exact native
04:00 day scopes, including DST, while arbitrary/overview queries remain separate.
Old exact-key current-day snapshots are not migrated: an online read populates the
new identity before the improved offline reopening works.

## Web dialog and import follow-up

Issue #21: with the final rebuilt image, an invalid03:59 schedule time now produces
a persistent, focused alert inside the modal and preserves the entered value.
A server-rejected timezone also stays in the dialog. Correcting to09:15 and
explicitly consenting saved a schedule; API read confirmed enabled/minute555 and
Asia/Taipei. The test disabled it again.
[Dialog feedback screenshot](screenshots/web-dialog-feedback.png). A deliberately
held synthetic request was failed after Escape and opening the goals dialog; its
late error did not enter the new dialog or global notice. This used a temporary
fixture-only browser fetch stub, restored immediately. Native Escape worked.

Agent import was exercised through the browser file-upload tool with a synthetic
Codex JSONL containing two text turns, metadata, a tool output and an image. Both
preview checkboxes began unchecked. Approving only one turn stored exactly that
turn; the unselected text and excluded payloads were absent from the API record.
Replay opened successfully. No real session files or external model were used.

Web syntax, timeline/import regressions, Rust fmt/clippy/tests and final-image
Docker smoke passed; unchanged Android checks remain the33-test build above.

## Web reflection save race (#22)

Reproduced before the fix: a held save of A completed after typing B, incorrectly
marked B saved and erased it after navigation. With the rebuilt image at390px,
A saves while B stays marked unsaved, then B survives leaving and reopening Daily.
A second held save was completed after leaving/rebuilding the page and deliberately
clearing its editor; the empty newer draft stayed empty after another navigation.
The server retained the submitted version, not the unsent newer edit.
[Unsaved newer draft](screenshots/web-newer-reflection-draft.png). The temporary
fixture-only fetch delay was restored before subsequent actions. No external AI
or browser journal storage was used. Web regressions, Rust fmt/clippy/tests and
final-image Docker smoke passed; native code/APK were unchanged.

## Native Daily reading/writing loop (#23), 0.4.3-dev

Daily now uses the same reflection editor, day-keyed in-memory draft and encrypted
queue as Timeline. Current-day check-ins can be recorded there; historical dates
show recorded feelings plus a return-to-today action instead of an entry form.
This follows the official Daily source's editable draft workflow; PocketLog keeps
its explicit save and never generates or sends reflection text to AI.

Connected synthetic emulator checks passed at normal and2× font scale:
Daily→Timeline draft continuity, Daily save→Timeline→Daily reload, historical
reflection editing, absence of the current check-in form on past days, and return
to today preserving the correct day's editor. Prior pairing/rating/Chat/Flow and
queue checks also passed. The normal-font run additionally repeated real offline
cache/queued update/deletion checks. All capture/model consents stayed off.

- [Current Daily editor](screenshots/native-daily-editor.png)
- [Historical-day guidance](screenshots/native-daily-historical.png)
- [2× text, scrolled save action](screenshots/native-daily-large-actions.png)

These are secure emulator View snapshots, not compositor captures. The runner
uses View actions and programmatic scrolling, not physical touch or TalkBack.
Astra independently reviewed the native diff and found no concrete issue; that
review was source-only. Android33 unit tests, assembleDebug, instrumentation build
and lintDebug passed (0 lint errors/8 existing warnings). Service source/APK
network/collection logic are unchanged; Rust/web/Docker checks passed earlier in
this wave. Physical device/keyboard/permission/battery validation remains pending.

## Native AI brief destination consent (#24), 0.4.4-dev

The separate confirmation page names endpoint, model, exact half-open date range
and timezone, evidence limits and exclusions before any model request. Schedule
consent also identifies the endpoint/model. No provider means disabled consent.
Connected synthetic normal/2× font checks verified cancel leaves brief count
unchanged, explicit consent creates one brief through a local fake model, and
schedule destination is readable. Android33 unit tests, assembleDebug,
assembleDebugAndroidTest and lintDebug passed. The test runner now accepts empty
new days after the04:00 rollover rather than requiring populated-day text.

- [Destination and scope](screenshots/native-brief-consent.png)
- [Schedule destination](screenshots/native-brief-schedule.png)
- [2× text consent actions](screenshots/native-brief-large-actions.png)

Secure emulator View snapshots; physical touch and TalkBack remain unverified.

## Owner scope correction: remove manual mood entry (#25)

Supersedes the check-in portions of #23 and earlier acceptance: Android Timeline
and Daily no longer render mood/energy forms or historical feeling cards. Web
Daily, empty states, welcome and checklist no longer ask for a mood entry. Setup
now tracks a journal device key and first activity, with capture consent separate.
Existing check-ins remain in storage and private export/API for compatibility;
no journal migration or deletion was performed. Optional reflection and Dayflow's
focus/distraction review remain separate from mood entry.

Android33 unit tests/build/instrumentation build/lint passed. Connected synthetic
normal and2× font runs passed all pages, absent current/historical mood actions,
shared reflection drafts/save, queue sync, brief consent, Chat and Flow. Normal
run repeated actual offline queued update/delete/cache checks. The first run
stopped because an old test still clicked the deleted mood button; corrected to
sync a reflection and reran on the disposable emulator.

- [Daily without mood form](screenshots/native-daily-without-mood.png)
- [2× text reflection action](screenshots/native-daily-without-mood-large.png)

Secure View snapshots only; no physical gesture/TalkBack validation claimed.

Final rebuilt-image web verification: 390px Daily has no mood/energy entry or
feeling-record prompt and no document overflow. Desktop1440px Support checklist
tracks phone pairing/first activity, with no mood step and no overflow.
[Daily](screenshots/web-daily-without-mood.png) ·
[Activity setup](screenshots/web-activity-setup-checklist.png).
The built JS hash matches source. Rust fmt/clippy-Dwarnings/tests, cargo audit
(1509 advisories/185 dependencies; local index warning), web regressions and
final Docker smoke passed. No external model or real journal used for captures.

## Native category visibility (#26), 0.4.5-dev

Official Dayflow TabFilterBar.swift and the supplied actual screenshots show
category visibility chips. Native now provides horizontally scrollable48dp
category actions with visible/hidden state, reset and filtered-card count.
Timeline/list share the same selection, retained with the day and view mode on
Activity recreation. All-hidden state offers reset; goals and totals explicitly
remain full-day statistics. This does not change stored categories or collection.

Connected synthetic normal/2× text runs passed hide→list exclusion, retained
selection/list on recreation, hide-all and reset, then timeline mode. Existing
all-page/brief/Chat/Flow/reflection checks passed; normal also repeated actual
offline/cache/queue checks. Android33 unit tests/build/instrumentation build/lint
passed. A test selector initially used the wrong segmented-control accessibility
label; corrected to the real label before final successful runs.

- [Filtered list](screenshots/native-category-filter-list.png)
- [All hidden](screenshots/native-category-filter-empty.png)
- [2× text timeline controls](screenshots/native-category-filter-large.png)

Secure emulator View snapshots and programmatic actions; physical horizontal
swipe/TalkBack focus remain unverified. The web/server UI is unchanged.

## Stable summaries after late context (#27)

Source audit found a late local-only observation could replace an existing AI
summary/category after earlier raw evidence was consumed. The atomic notes UPSERT
now protects AI notes from local fallback, while allowing valid model refreshes,
local-to-local refreshes and preserving user edits. No schema, consent or retention
change; processed raw observations and jobs are still removed.

In-memory synthetic pipeline tests cover existing local/user/AI notes with missing
consent or mismatched provider identity, cleanup/receipt preservation and repeat
processing. A local stub verifies valid model output can replace an older AI note.
Rust fmt/clippy-Dwarnings and all14 unit/31 integration tests passed. Standalone
cargo audit passed1509 advisories/185 dependencies with the local index warning;
no external model or real journal used. This is classification consistency work,
not proof of Android screenshot capture on a physical device.

## Content-free capture diagnostics (#28), 0.4.6-dev

Settings and Support now link to a native capture status page with a manual
refresh action. It distinguishes privacy/system/encoding/processing failures from
successful local queue writes; queue success never means server receipt. One
in-memory result and last queue time contain no app names, content or exception
strings, are never uploaded/persisted, and reset on process/connection reset.
Attempt identities ignore stale callbacks. All existing capture gates and the
five-minute event-driven cap remain unchanged. The page explicitly describes
whole-window input-field exclusion and the lack of periodic same-app capture.

Four new unit tests cover failure versus queue success, preserving last success
when a later attempt fails, stale/reset callbacks and partial-success states.
Android37 unit tests, build/instrumentation build and lint passed. Connected
normal/2× text checks verified Settings/Support navigation, manual refresh,
whole-window guidance and failure preserving previous queue time. Normal also
repeated actual offline/cache/queue checks and previous category/brief/Chat/Flow
checks; recording consents remained off. Diagnostic outcomes were injected
synthetic fixtures: this verifies presentation/state, not actual OS screenshot
callbacks or physical capture. Those still require a separate capture fixture.

- [Whole-window exclusion](screenshots/native-capture-exclusion-status.png)
- [Failure with prior local queue success](screenshots/native-capture-failure-status.png)
- [2× text failure explanation](screenshots/native-capture-failure-large.png)

Secure emulator View snapshots; no physical gestures/TalkBack/battery claim.

## Actual synthetic OS capture and classification

The separate [capture acceptance](CAPTURE_ACCEPTANCE.md) verifies the actual OS
screenshot path, input exclusion, journal-device upload/revocation, isolated
worker/local excerpt/private replay and explicitly consented loopback-model
classification. This adds runtime evidence beyond injected diagnostic states in
#28. Only fixed synthetic content was used; no production data or external AI.
Physical-device and full-day battery checks remain open.

## Web content provenance (#29)

Dayflow's actual supplied timeline screenshots and official `ActivityCard.swift`
(at reference2c79e161) establish the selected-card review/edit hierarchy. PocketLog
also needs to distinguish its local excerpts and app-only fallback from model
output: the web previously omitted source, unlike native. Timeline accessible
labels/tooltips, list/Daily metadata and the inspector now show fixed readable
source labels. The inspector explains AI uncertainty, excerpt context limits,
app-only limits and user precedence. Missing/unrecognized source stays unknown.
No source text is interpreted as markup and no processing request is added.

Built-image390px list and inspector, and1440px desktop list checked with actual
synthetic capture/local+AI cards plus synthetic app-only/user cards. All four
source explanations were opened and cancelled; labels include the source for
assistive technology. No document/dialog horizontal overflow. Rust fmt/clippy
with warnings denied,14unit/31integration tests, web regressions, cargo audit
(1509advisories/185dependencies, local-index warning) and final Docker smoke passed.
Android37unit/build/lint+fixture checks passed for the preceding test-only change;
no native production source changed in this web fix.

- [Phone source labels](screenshots/web-content-source-mobile.png)
- [AI inspector](screenshots/web-content-source-inspector.png)
- [Desktop source labels](screenshots/web-content-source-desktop.png)

## Pending server context (#30), 0.4.7-dev

Official Dayflow `CanvasTimelineDataView.swift` and `ActivityCard.swift` expose
generation/failure visibility (source observations at2c79e161). PocketLog now
exposes its own narrower fact: `pending_context` means matching raw context remains
on the server. It is separate from provenance and does not promise active AI.
The indexed query matches full stored activity bounds even when the displayed
card is clipped. No schema, collection, consent, retry controls or polling changes.
Native list/canvas metadata, aggregate count and secure inspector, plus web
list/Daily/timeline labels and inspector, explain waiting and manual refresh.
Older servers without the field continue to render normally. README no longer
introduces the product as remembering feelings; the focus review label now says
focus level. Historical records/API/export are preserved.

Sol backend tests: matching app, inclusive start/exclusive end, missing/mismatched
evidence, clipped windows, late evidence on AI/user/local notes, partial12-of13
consumption and final clearing. Rustfmt/clippy-Dwarnings,17unit/31integration pass;
standalone audit1509advisories/185dependencies passes with local-index warning.
Android37unit/build/instrumentationbuild/lint, normal+2× connected checks pass;
normal also checks actual offline cache/queued reflection. A targeted secure
inspector check reads the synthetic accessibility tree; screenshots remain View
snapshots, not compositor/TalkBack/physical gesture verification. Screenshot
scrolling initially exposed only the heading; runner now reveals the entire row.

Built image390px list/inspector and1440px list were visually checked; no horizontal
overflow. All content is synthetic. API first showed app_only+pending=true; the
isolated worker consumed the local-only observation, producing local_excerpt
and pending=false. Web manual refresh removed both card and aggregate markers.
Missing/false web fields show no marker. Final Docker smoke and web regressions pass.

- [Native pending card](screenshots/native-pending-context.png)
- [Native2× text](screenshots/native-pending-context-large.png)
- [Web pending card](screenshots/web-pending-context.png)
- [Web explanation](screenshots/web-pending-context-inspector.png)
- [Desktop](screenshots/web-pending-context-desktop.png)

No external AI or real journal used. False does not prove capture or processing
success: phone-local queues/exclusions/expired evidence remain outside this flag.

## Device pairing readiness (#31)

Dayflow official source at2c79e161 separates onboarding progress
(`OnboardingFlow.swift`) from screen permission rows
(`ScreenRecordingPermissionView.swift:214–235`). This is a source observation;
macOS Dayflow cannot run on this Linux host. PocketLog separates issued journal
keys, a past permitted authentication and first activity arrival. Past validation
never means online now, uploaded successfully or recording consent granted.
The manual refresh action introduces no polling or automatic collection.

Owner-only device reads expose nullable minute-coarsened `last_authenticated_ms`.
Absent older-server fields remain unknown. Collector/expired keys do not establish
journal pairing. The diagnostic write is best-effort and cannot deny an otherwise
permitted request. No IP, request path, content or event history is retained.
The separate metadata table keeps the released six-column credential schema;
a DELETE trigger cleans it even when a rollback binary revokes/prunes a key.
An actual previous production image created/authenticated/revoked keys on the
migrated disposable DB, and metadata cleanup was verified. No production schema
was changed for this test, and no journal restore is needed for binary rollback.

Rust fmt/clippy-Dwarnings,20unit/31integration and cargo audit pass (1509
advisories/185dependencies; local registry-index warning). Web regression tests
cover absent/null/valid metadata, expiry, scope and newest timestamp. Final built
Docker smoke passes. Android37unit/build/lint/instrumentationbuild pass; the
emulator-only pairing runner then issued a journal key, recovered from malformed
and rejected keys, connected the actual MainActivity over HTTPS, verified the
minute timestamp and empty timeline, and asserted all collection consents off.
It cleared local credentials/queue afterward. Native image is a secure View
snapshot, not a compositor capture or physical-device acceptance.

Built-image390px and1440px web captures show validated connection with first
activity still incomplete. The initial mobile completed-row CSS hid the new
status explanation; corrected and recaptured with timestamp and limits visible.
No document overflow. Issuing a masked once-only key was also checked: no token
in URL/browser storage and dialog secret removed from DOM after closing. All
fixtures and images are synthetic; the successful test key is revoked afterward.

- [Issued, not yet validated](screenshots/web-pairing-issued.png)
- [Validated mobile, waiting for activity](screenshots/web-pairing-validated.png)
- [Desktop checklist](screenshots/web-pairing-desktop.png)
- [Per-key status](screenshots/web-pairing-device-status.png)
- [Actual native empty journal after pairing](screenshots/native-pairing-empty.png)

No Android production source change or new APK required for this issue. Physical
phone, TalkBack and full-day battery validation remain outstanding.

## Keyboard classification and date boundaries (#32)

Read-only Astra source review identified focus loss after saving a card and at
date-navigation boundaries. Reproduced on the previous built image: focus a real
synthetic timeline card, Enter, save, and `document.activeElement` becomes BODY.
PocketLog's Dayflow-style timeline needs browser keyboard support as well as its
pointer-based card inspector; this change does not claim Dayflow runtime parity.

Stable card keys now include ID and clipped start time, avoiding duplicate keys
for a card spanning multiple week days. Save restores the originating key only
after the new cards render. A card removed by the category filter/top-five list
falls back to the focusable main region. When Previous/Next/Today becomes disabled,
focus moves to the date input. Restored cards scroll into view on both axes.
Actual week screenshot verification caught a horizontally hidden focused card;
the final build now reveals it rather than only restoring an invisible focus.

Shared browser acceptance used synthetic fixture only: actual Enter activation
and save passed day timeline, list, Daily and week; Escape returns to the card.
Changing a learning card to work while filtering learning removed the card and
focused main. Next→today and Today→today focused the date input. Final390px list
and1440px week focus rings were inspected; focused week card bounds are inside
the viewport, with no document overflow or duplicate data keys. Screen-reader
acceptance is still outstanding. Existing22px short grid cards remain small touch
targets; the larger list view is available and further mobile refinement remains.

Rust fmt/clippy-Dwarnings,20unit/31integration, cargo audit and final rebuilt Docker
smoke pass; JS syntax and web regressions pass. Android production/test sources
are unchanged from #31's37unit/build/lint and connected pairing acceptance.

- [Mobile focus after save](screenshots/web-classification-focus-mobile.png)
- [Week focus scrolled into view](screenshots/web-classification-focus-week.png)

## Initial web presentation on phones (#33)

The previous grid opens with22px-high short-event targets; dense week columns
can make them narrower than20px. This was observed in the synthetic browser
captures for #32, consistent with Astra's source review. Dayflow's desktop
proportional grid remains the hierarchy reference, but those dense targets are
not a good initial phone reading experience.

At initial viewport widths860px or less, PocketLog now selects the existing
activity list. Desktop defaults to the grid. No resize listener, browser storage,
automatic collection or additional API calls are added. A user's explicit view
selection persists through page navigation and viewport resizing within the tab.
A new page load chooses its initial view from the current width again.

Final rebuilt-image shared browser checks: fresh390px→list,8synthetic cards,
minimum102px height/328px width and no document overflow; week retained list with
12cards; explicit grid survived Support→Timeline and resizing to1440px; fresh
1440px→grid; explicit list survived resizing back to390px. Both initial layouts
were visually inspected. Classification/focus uses the same cards already
verified in #32. Existing opt-in dense grid targets remain small, and this change
does not claim to resize or solve them. Physical-device/screen-reader checks remain.

JS syntax/web regressions and rebuilt Docker smoke pass. Rust fmt/clippy-Dwarnings,
20unit/31integration and audit passed in this wave with unchanged Rust/dependencies;
Android unchanged from #31's37unit/build/lint and connected acceptance.

- [Phone initial list](screenshots/web-phone-default-list.png)
- [Desktop initial grid](screenshots/web-desktop-default-grid.png)

## Native capture app selection (#34)

Official Dayflow source2c79e161's `SettingsRecordingPrivacyTabView.swift` implements
installed-app search, visible count, bounded grid and no-match guidance;
`RecordingPrivacySettingsViewModel.swift:85–93` matches app names/package IDs.
The separate permission rows remain in `ScreenRecordingPermissionView.swift`.
These are source observations, not a macOS runtime claim. The supplied actual
Dayflow screenshots show timeline hierarchy, not this settings interaction.

PocketLog's previous native picker was an unsearchable multi-choice list. Sol6.1
implemented local label/package search, selected/visible counts, scrollable empty
and no-match states, wrapping recycled rows and a secure bounded dialog. Manual
package entries outside launcher results survive. Invalid manual input now marks
the field before any background lookup; Views are read only on the UI thread.
Limits remain2000launcher results/80-character labels; query is capped160chars.
No permission, installed-app upload, logging, storage, sampling or consent changes.
Fill modifies the form only; Save remains explicit and separate.

Root's emulator-only instrumentation opens the actual MainActivity app-list page
and actual picker. It selects Settings from the disposable AOSP image, filters to
no match and back with whitespace/uppercase, checks retained selection, fills
while preserving synthetic `org.example.manual`, cancels a changed selection and
verifies invalid input feedback. Persisted lists and all collection consents stay
unchanged. An explicitly empty app result list separately exercises the same
production dialog's no-app explanation. No real phone/app inventory was used.

Normal and2×font runs pass. Actual IME visibility was verified from window insets;
both action buttons remained fully visible in the keyboard-adjusted display frame.
Captures use public `WindowInspector` dialog Views with FLAG_SECURE asserted. They
are View snapshots, including the shrunken keyboard-state dialog, not compositor
screenshots of the keyboard or TalkBack verification. Final large-font pictures
were inspected; search/count/results/actions remain readable and scrollable.

Android37unit tests,assembleDebug,assembleDebugAndroidTest,lintDebug pass; full
connected8page/realHTTPS/offlinequeue/cache/brief-consent regression passes after
integration. Rustfmt/clippy-Dwarnings20unit/31integration,audit1509/185,web regression
and Docker smoke of unchanged production image pass. Version13/0.4.8-dev uses the
same signing certificate as earlier APKs. No server source/deployment change is
needed; production remains7603f9a. Physical phone/TalkBack/battery remain gaps.

- [Searchable app list](screenshots/native-app-picker.png)
- [Selection retained across searches](screenshots/native-app-picker-selected.png)
- [No search match](screenshots/native-app-picker-no-match.png)
- [2×text](screenshots/native-app-picker-large.png)
- [Dialog adjusted for visible keyboard](screenshots/native-app-picker-keyboard.png)
- [Explicit empty app result](screenshots/native-app-picker-empty.png)

## Desktop activity inspector (#35)

Official Dayflow source SHA2c79e1619d7039c476e06e4b85b0246e50bc34fa,
`Dayflow/Views/UI/MainView/Layout+Panels.swift:327` selects activity details in the
right inspector and otherwise shows the summary. The owner's actual wide screenshot
establishes the timeline/right-summary hierarchy, not the selected-card state.
Linux cannot execute the macOS SwiftUI app; this is source-based interaction alignment.

At widths >=1100px, PocketLog Timeline now selects a read-only activity inspector
beside the timeline. Explicit Edit opens the existing validated dialog. Narrower
layouts and Daily keep their dialogs. Selection, full title, time, category, app,
source explanation and pending-context explanation are visible without editing.
Return restores focus to the originating card. The toolbar remains visible when
scrolling long content. No extra requests, polling, browser storage or AI consent.

Root verified the rebuilt image in the shared browser with synthetic data only:
1440px day grid, week list, selection changes, refresh retention with fresh saved
content, return-to-card focus, Save focus, filter/date/mode/page clearing, and
390px dialog/no document overflow. Desktop Edit -> resize390px -> Escape returns
the original card and removes desktop pressed semantics; Enter then opens the
mobile dialog. Long154-character title, long package and900-character summary
wrap without document overflow; toolbar and pending explanation remain reachable
inside the scrollable sidebar. Initial long-content capture revealed actions
below the fold; final implementation moves a sticky toolbar above the title.
A bounded independent Astra source review found resize-cancel focus and stale
pressed-state issues; both were corrected and verified against the final build.

Rustfmt/clippy-Dwarnings,20unit/31integration tests, cargo audit1509/185, Node
syntax/timeline regression and final Docker smoke pass. Android unchanged from
#34's37unit/build/lint/connected acceptance; no APK republish needed.
This is a read-only inspector plus edit dialog, not full Dayflow feature parity.
Screen-reader behavior beyond semantic DOM/focus checks remains unverified.

- [Desktop selected activity](screenshots/web-desktop-activity-inspector.png)
- [Long title and summary](screenshots/web-desktop-inspector-long.png)
- [Mobile retains activity dialog](screenshots/web-mobile-activity-dialog.png)

## Independent day and week request ranges (#36)

Built-image synthetic reproduction delayed the weekly reviews response400ms. An
empty selected day lost its empty explanation; Copy copied1260characters from
other days, and one-day JSON export contained27reviews from the week. This was
a shared mutable snapshot race, not absent phone data. The official Dayflow
`TimelineActivityLoader.swift:50–68` (SHA2c79e161) returns a selected-day payload
and separately loads week activities. The supplied screenshot's date and weekly
footer hierarchy is consistent with keeping these scopes separate; it cannot
prove asynchronous behavior.

PocketLog loaders now return independent `{days,cards,reviews}` snapshots.
Timeline copy/export/empty states/sidebar use the primary displayed range; the
weekly footer only uses its separate range. Daily and Weekly metrics/exports
receive their own snapshots. The unused shared journal globals are removed. No
API/schema/capture/AI consent/retention changes or additional requests.

Final built-image browser verification passed both delayed response orders:
empty day keeps its explanation, Copy reports no activity without writing, and
one-day export has zero out-of-day reviews. A populated day copied exactly the
matching API's9cards and exported its19reviews. Week exported7days/13cards with
matching27reviews; Daily exported1day with matching reviews; Weekly exported7days.
Rapid navigation to Support while delayed requests finished remained on Support.
390px empty state has no horizontal document overflow. Real download-confirmation
buttons were used; captured synthetic Blobs were inspected for range equality.
No owner journal was used.

The CI timeline script now exercises independent ranges in both completion orders
with explicit response gates and stale-render rejection. Rustfmt/clippy-Dwarnings,
20unit/31integration, audit1509/185, web regression and final Docker smoke pass.
Android production unchanged from #34's validated build; no APK republish.

- [Empty day beside independent weekly footer](screenshots/web-independent-day-empty.png)
- [390px empty day](screenshots/web-independent-day-mobile.png)

## Recoverable thumbnail replay (#37)

Old built image reproduced delayed replay results opening a modal after navigation
to Support. Official Dayflow `TimelineReviewMediaViews.swift:6–58` at SHA2c79e161
distinguishes ready media from preview/fallback content. These are source findings;
the supplied actual Timeline screenshots do not establish replay interaction and
Linux cannot run the macOS application. PocketLog retains bounded static thumbnail
review, not desktop video playback.

Replay now opens immediately with the selected date and a list-loading state.
List failures have a retry; failed image requests show an explanation plus retry
image/reload list actions. Loaded media is bounded to the viewport, with controls
above it and wrapped metadata. Responses belong to the originating render, modal
and load attempt. Closing/navigating prevents late display. Previous/Next remain
disabled at boundaries; explicit deletion names the confirmed timestamp/app, locks
navigation while pending and removes only the captured ID. Retry/boundary/delete
focus stays within the dialog. No new API, image retention, capture cadence, AI
consent, automatic playback or polling.

Root created two replay-consented observations using the existing synthetic capture
fixture JPEG (read-only Kotlin sample, no account/network/private journal). A third
image had analysis-only consent; real worker validation excluded it from replay.
Only the isolated fixture archive was reset for this test. No production content.

Final built-image shared-browser checks: immediate loading/date, close+Support
while list delayed700ms (no late modal); simulated network rejection and real
list retry; actual authenticated image loads; first/last control disabled state;
390px no horizontal document overflow; image request redirected to a nonexistent
synthetic ID yielded a real404 and visible recovery, then restoring the original
image URL + actual Retry loaded successfully. Retry and boundary actions retained
focus. Cancel deletion preserved both images. A delayed real DELETE locked all
controls, removed only the confirmed ID (GET404), retained the other, and deleting
the final image produced the empty state with hidden controls and focused status.
The prototype first exposed the shared button helper undoing boundary disabling;
replay now owns these controls' busy/boundary state and final-build checks passed.

Rustfmt/clippy-Dwarnings,20unit/31integration tests,cargo audit1509/185, web
regression and final Docker smoke pass. Android source unchanged from #34's
validated37unit/build/lint/connected checks; APK unchanged. Screen reader and
physical-device behavior remain unverified. Parity matrix updated to reflect
compact native week layout and the removed manual mood/energy UI.

- [Phone replay, final-item boundary](screenshots/web-replay-mobile.png)
- [Recoverable image failure](screenshots/web-replay-image-error.png)
- [Desktop synthetic thumbnail](screenshots/web-replay-desktop.png)
