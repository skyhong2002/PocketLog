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

## Native replay recovery and media ownership (#38)

Astra's bounded read-only review found that resource404 used generic "update the
server" guidance and left a stale thumbnail row, while successful decoded bitmaps
were dropped without disposal after Activity destruction. Displayed dialogs also
lacked explicit Activity ownership. These were source findings; root then executed
the synthetic acceptance below. Dayflow's media-ready/fallback source remains the
reference described for #37, not an executed macOS/Android parity claim.

An unavailable native thumbnail now refreshes its list and explains expiry/deletion.
Replay dialogs are explicitly owned and dismissed on Activity destruction. Their
bitmap is released on dismissal and display failure; task results may provide a
disposal callback for successful results delivered after finishing/destruction.
Only replay opts into this callback. Image accessibility text now includes the
timestamp. No image disk cache, capture cadence, credentials, consent or server
API/retention changes.

The emulator-only ownership harness calls the actual production media presentation
method with a generated bitmap labelled synthetic/no personal data, checks secure
window flags and release after dismissal, then recreates the Activity while open
and verifies dismissal/release. A gated task returns a bitmap after finish; the
production task delivery path disposes it and never invokes its UI callback. This
is deterministic callback ownership verification, not a delayed real-network
decode or garbage-collector/heap measurement claim.

The separate connected harness uses real trusted HTTPS and two worker-validated
synthetic JPEGs. After loading the list it deletes one through a second API client,
taps the stale row, verifies accurate feedback/no upgrade instruction and exactly
one remaining row, then opens the surviving real image in a secure dialog and
deletes it. Its displayed bitmap is recycled and the empty state is shown. No
capture consent is enabled. Normal and2×font runs of both harnesses pass; their
View snapshots were inspected. FLAG_SECURE stays enabled throughout; these are
View captures rather than compositor/keyboard or TalkBack evidence.

Android37unit tests,assembleDebug,assembleDebugAndroidTest,lintDebug and full
connected eight-page/HTTPS/offline queue/cache/brief-consent regression pass.
Rustfmt/clippy-Dwarnings20unit/31integration,audit1509/185,web regression and
Docker smoke of the unchanged server image pass. Version14/0.4.9-dev retains the
existing signing certificate. Physical device/TalkBack/battery remain unverified.

- [Secure native viewer](screenshots/native-replay-viewer.png)
- [2×text viewer](screenshots/native-replay-viewer-large.png)
- [Unavailable image recovery](screenshots/native-replay-unavailable.png)
- [2×text recovery](screenshots/native-replay-unavailable-large.png)
- [Empty archive](screenshots/native-replay-empty.png)

## Draft classification preview (#39)

Web and native category editors now offer an explicit unsaved rule trial. The
private `POST /v1/workspace/preview` validates the proposed workspace with the
same structural validator used by Save and evaluates the production matcher.
It requires no database or provider access and does not store samples or change
journal cards. Normal authentication metadata still applies. Exact app matching
remains case-sensitive; title substrings ignore case; the first enabled AND rule
wins. Manually edited activities keep their category.

Official Dayflow `DayCategorySelectionEditor.swift` at source2c79e161 informed
the shared category editing reference. The supplied actual timeline screenshots
inform the surrounding hierarchy. This trial tool is a PocketLog extension,
not a claim that Dayflow ships this exact UI; macOS was not executed here.

Built-image browser checks used only the disposable synthetic fixture: disabled
first rule, case-insensitive title match, exact app match, uppercase app fallback,
empty app validity, and unsaved rule reordering. Settings reads remained unchanged.
Editing a sample invalidated the displayed result; a held response could not
replace newer input or populate a reopened modal. Injected offline and old-server
404 responses showed useful guidance and a successful retry. The top shortcut
focuses the sample field. At1440px and390px, the form/result are readable with no
document or modal horizontal overflow. These are Chromium checks, not screen
reader or mobile Safari verification.

Native connected normal and2×font checks use the real editor and trusted HTTPS
fixture endpoint, verifying matching, priority, case, invalid input, draft return,
and unchanged saved workspace/consent. Inspected View snapshots retain
FLAG_SECURE; they are not compositor, physical-device or TalkBack evidence.
Full connected eight-page/offline queue/cache/brief-consent regression passes.

Required checks: Rustfmt/clippy-Dwarnings,20unit+31existingAPI+6preview integration
tests, cargo audit1509advisories/185crates, web syntax/import/timeline regressions,
Android37unit/assembleDebug/assembleDebugAndroidTest/lintDebug, and final Docker
smoke. Audit reports its existing local crates.io-index warning. Preview API tests
cover authentication/scope, strict bounds, production matching parity, unchanged
table snapshots including expired rows, and operation without database tables.

- [Desktop draft result](screenshots/web-rule-preview-desktop.png)
- [Phone web result](screenshots/web-rule-preview-mobile.png)
- [Native result](screenshots/native-rule-preview.png)
- [Native2×text result](screenshots/native-rule-preview-large.png)

## Capture consent versus eligible apps (#40)

Astra's read-only review identified a misleading native state: a nonempty allowlist
could consist entirely of excluded packages while Settings/Support still presented
screen context as enabled. The capture service correctly rejected these events
before beginning diagnostics, leaving any past success unchanged.

Settings, Support and capture diagnostics now show actionable zero-eligible
guidance when context consent is on. The count uses the existing production
`contextAllowed` predicate, including custom exclusions, known sensitive packages,
name patterns and PocketLog itself. Support places the warning above the status
card. Positive counts describe package eligibility, not permission or capture
success. Diagnostics and its entry rows explicitly describe historical checks.
The app-list banner and setup/security documents now explain the finite sensitive
app matching coverage and the need to explicitly exclude private apps. No capture
gate, consent, permissions, sampling, queue or API behavior changes.

The dedicated emulator harness uses no credentials or network. It temporarily
sets only synthetic package lists/context consent on an initially consent-off
fixture, tests empty/explicitly excluded/built-in-blocked/own-package lists and
recovery to one eligible package, and restores original lists/consent in finally.
A synthetic in-memory past queued result remains explicitly historical. It opens
the actual recovery action and app-list page. Normal and2×font runs pass, including
an assertion that the recovery action is fully visible; final View captures were
inspected. The first screenshots exposed the Support warning below its long card;
it was moved above the card and both final runs repeated. FLAG_SECURE remains on.
These checks do not exercise OS capture, compositor screenshots or TalkBack.

Reference: official `ScreenRecordingPermissionView.swift` and privacy settings
view/model at2c79e161 separate permissions and blocked-app state; supplied actual
Dayflow screenshots inform overall shell hierarchy. Linux did not execute macOS.
Required Android37unit/build/lint and full connected eight-page/HTTPS/offline
queue/cache/brief-consent regression checks pass; unchanged server/web dependencies
retain #39's verified fmt/clippy/57tests/audit/web/Docker checks and successful CI.

- [Actionable capture warning](screenshots/native-capture-no-eligible.png)
- [2×text warning and visible action](screenshots/native-capture-no-eligible-large.png)
- [Accurate privacy guidance at2×text](screenshots/native-capture-privacy-large.png)

## First-run copy after mood-entry removal (#41)

The optional-AI onboarding step no longer advertises the removed manual mood
feature. It names timeline, activity classification, focus statistics and optional
reflection instead. Legacy-record export/deletion and AI-exclusion explanations
remain accurate and retained. This follows the owner's feature correction; it
adds no Dayflow parity claim or collection/provider behavior.

On the final built image, the disposable workspace was temporarily reset to
onboarding0. All four real steps were traversed. The fourth step was inspected at
1280px and390px with no horizontal overflow, then completed through its real
button; shared onboarding1, goals and rules were restored/preserved. No external
provider call or phone capture was made. Web syntax/import/timeline checks and
Docker smoke pass; Rust/native source and dependencies are unchanged from #39/#40.

- [Desktop final step](screenshots/web-onboarding-current-desktop.png)
- [Phone final step](screenshots/web-onboarding-current-mobile.png)

## Approved provider for connection tests (#42)

Astra's source review found that the fixed connection test loaded the latest
saved provider without the confirmed identity. Another client could change A to B
while A's confirmation remained visible, causing an unintended synthetic request
and possible charge to B. This did not leak journals or forward A's key to B.
Native confirmation also omitted the saved destination/model.

The API now requires a strict64-hex `provider_id`, compares it to the same loaded
Provider used for the fixed test, and rejects stale/disabled/unconfigured identities
before networking. Existing current-provider checks, limits and no-journal payload
remain. Clients capture saved settings for confirmation/request, explain that
unsaved edits are excluded, and offer explicit refresh/reconfirmation after409
without automatically testing a new provider. Native dialogs retain FLAG_SECURE.
Both clients require `probe_provider_binding` capability; old bodyless clients
receive400 and must update. API compatibility is documented.

Bounded Rust mock tests verify missing/malformed/unknown fields/authentication,
zero calls after stale or disabled identity, exactly one accepted synthetic call
using keyB, and absence of keyA/owner token/private card content. Native OpenAI,
Anthropic and Gemini transport tests use confirmed IDs. Requiredfmt/clippy-Dwarnings,
20unit+32API+6preview tests and audit1509advisories/185dependencies pass (existing
local crates.io-index warning). Web syntax/import/timeline checks and final image
Docker smoke pass.

Actual Chromium tests on the final image show savedA despite unsavedB fields,
Cancel retains the draft, A-to-B change rejects the old confirmation with no mock
call, and explicit reload updates both editor/background summary. Reconfirmation
succeeds with exactly one fixed test. Missing capability was injected only into
the disposable GET response and blocked confirmation/networking.390px consent and
1440px recovery were inspected with no horizontal overflow.

Real native HTTPS tests at normal/2×font exercise the same saved-versus-unsaved,
cancel, stale409, keep-editing, reload and fresh-confirmation path. Captures of the
secure dialog Views were inspected; they are not compositor/TalkBack evidence.
A production-form render with a synthetic missing-capability response verifies
that old-server testing is blocked. The initial test harness threw while polling
for an absent dialog on the main thread; that harness error was fixed and the
disposable emulator data cleared before successful reruns. No product crash claim.
Android37unit/assembleDebug/assembleDebugAndroidTest/lintDebug and full connected
eight-page/HTTPS/offline queue/cache/brief-consent regression pass. The synthetic
provider configuration was restored through the API before full regression.

Dayflow `TestConnectionView.swift`/provider helper source at2c79e161 informs the
selected-provider reference; macOS was not executed and its credential URL/fallback
behavior was not copied. Physical-device, screen-reader and battery limits remain.

- [Phone web confirmation](screenshots/web-probe-consent.png)
- [Desktop stale-provider recovery](screenshots/web-probe-changed.png)
- [Native saved-provider confirmation](screenshots/native-probe-consent.png)
- [Native2×text confirmation](screenshots/native-probe-consent-large.png)
- [Native2×text stale-provider recovery](screenshots/native-probe-changed-large.png)


## Activity-edit draft preservation (#43)

Native activity editing now checks a nonblank title, Unicode-scalar limits of
200/1000 and NUL rejection before queueing. It displays live counts and retains
all text instead of silently truncating. Choice labels/IDs are captured together.
Busy and real enqueue failures keep the secure editor open with inline feedback;
fields/cancel are locked during persistence and it dismisses only on success.
The summary field has a bounded height. Feedback scrolls after layout; the first
2× capture exposed clipping, fixed before final evidence and verified with full
visible-bounds assertions at both font scales.

The actual emulator editor at normal/2× font rejects 201-character titles,
whitespace-only titles and 1001-character summaries without adding a queue item.
A busy-state check retains both fields. Missing credentials force the production
enqueue failure path; drafts remain and Retry persists exactly 200 emoji in the
title and 1000 in the summary through the real Keystore/encrypted queue without
UTF-16 truncation. Synthetic credentials target loopback port 1 and future worker
backoff prevents networking; original backoff and empty credential/queue state
are restored. Capture consent stays off. No claim about rotation persistence,
physical gestures or TalkBack. The screenshot remains blocked at OS level; these
are secure View bitmap captures.

Android37unit/assembleDebug/assembleDebugAndroidTest/lintDebug, full connected
HTTPS eight-page/offline queue/cache/brief-consent regression, Rust fmt/clippy
warnings-denied/20unit32API6preview and audit pass. Web import/timeline regressions
also pass; the server image and API contract are unchanged by this native fix.
Dayflow's reference screenshot and Swift DayCategorySelectionEditor at2c79e161
inform correction-in-context and explicit completion; this does not claim a
matching macOS text editor or full feature parity.

- [Title validation](screenshots/native-card-title-limit.png)
- [Persistence failure retains draft](screenshots/native-card-save-failure.png)
- [2× font complete recovery feedback](screenshots/native-card-save-failure-large.png)

## Keyboard focus survives confirmation and shell updates (#44)

Fable5.2's read-only synthetic review found that async buttons disable their
opener during native-dialog restoration, and unkeyed controls disappear on a
shell rebuild. Root reproduced export cancellation and a real no-op goals save
on the original built image: both landed on BODY. The fix restores surviving
async openers only when focus remains on BODY, records keyed modal return targets
for asynchronous device/schedule loads, and restores them synchronously before a
Save handler rebuilds the shell as well as after native close/Escape. Stable
Settings/Support/summary keys and explicit week-to-day/filter-reset targets cover
the affected re-renders. Existing intentional focus is respected.

Additional actual checks exposed async device/schedule cancellation losing focus
and, at390px, a correctly focused goals button below the viewport after a save
(top1043px, viewport844px). These were fixed before final acceptance. Restored
controls now scroll into view with phone bottom-bar/safe-area clearance.

Final rebuilt-image browser acceptance at1280×800 and390×844 verifies focus
identity and fully visible bounds for export/pause cancellation, week→day,
empty-category reset, actual no-op goals/category saves, async schedule/device
close and Support connection refresh. Phone setup device/AI close also pass.
Desktop provider confirmation was dismissed with a real Escape key; real
Tab/Shift-Tab remain in the provider dialog with a visible2px focus ring. Most
other actions use scripted focus/click; this is not a full keyboard-only or
screen-reader audit. An intentional date-field focus claim after cancellation is
not overwritten. No horizontal overflow, zero model-test calls, no schedule or
pause changes, and workspace content identical apart from normal revisions.

The two final screenshots were inspected. Web syntax/import/timeline regressions
and final Docker smoke pass. Native649a840 and Rust checks from the same wave
remain valid: no native, API, collection or consent change in this web fix.
Reduced-motion handling was reviewed in source only; no media emulation claim.
Dayflow's actual supplied screenshot and Swift TimelineFeedbackModal's explicit
editor focus at2c79e161 inform retaining context across dialogs; this is browser
accessibility work, not a claim about matching macOS focus behavior.

- [Provider test focus after Escape](screenshots/web-focus-provider-cancel.png)
- [Phone category-save focus above bottom navigation](screenshots/web-focus-phone-category-save.png)


## Pending activity corrections remain visible (#45)

Native timeline reads now overlay pending encrypted card edits on a copy of the
returned cards. Existing IDs only: no missing/deleted/truncated activity is
invented, and intervals/order/count/pending-context remain unchanged. A single
bounded4096-entry read supplies card/reflection overlays with existing destination,
expiry and quarantine filtering; filename order matches the uploader. Strict
edit fields, title/summary Unicode bounds, category syntax and NUL checks apply.
Custom membership can have changed remotely, so every overlaid card explicitly
remains pending. Source is user, with a separate pending marker and page banner;
Timeline/List/Daily/Week totals use the local category. Encrypted server snapshots
remain untouched. Server overview/AI evidence still uses uploaded values, and an
offline snapshot cannot prove a later remote deletion.

Four focused tests bring Android to41: immutable snapshots/intervals, no card
resurrection or overview overlay, latest-valid filename order/emoji bounds, and
full retained queue destination/rejection/expiry/acknowledgement isolation beyond
the256-item upload read. Astra's bounded read-only contract review informed these
constraints. Dayflow's supplied timeline/category reference and DayCategorySelectionEditor
at2c79e161 inform correction-in-context; this is an Android sync behavior improvement.

Actual secure emulator tests at normal/2× font enable airplane mode and disable Wi-Fi,
use the real editor to save a new title/Rest category, and verify immediate offline
Timeline/List/Daily data plus intact original snapshot. Restoring an unmetered
network and syncing preserves the correction on the server, removes pending flags
and removes the banner on refresh. Capture consent remains off. An initial harness
checked acknowledgement before unmetered connectivity was ready; its wait was
fixed. Initial screenshot selection matched an earlier same-title fixture card;
unique titles and a whole-row pending-provenance/visible-bounds assertion now
prevent that evidence mistake. Final screenshots were inspected.

Android41unit/assembleDebug/assembleDebugAndroidTest/lintDebug, full connected
HTTPS/eight-page/offline reflection-cache regression, and the prior activity-editor
validation/failure/Unicode encryption regression pass. Rust58/fmt/clippy/audit,
web regressions and Docker checks from this wave remain valid; server code/image
is unchanged by this native fix. Physical-device/TalkBack/battery not claimed.

- [Local correction awaiting upload](screenshots/native-pending-card-list.png)
- [2× text pending provenance and complete card](screenshots/native-pending-card-list-large.png)

## Short native cards remain distinct (#46)

The native day canvas now reserves each card's rendered vertical size when
assigning lanes. Adjacent short activities can share the same few pixels of
actual time without covering each other's minimum-height rectangles. Minimum
height grows with text size; end-of-day rectangles stay inside the canvas and
dense lane gaps shrink to preserve positive width. This only changes display
geometry: original intervals, order, callback indices and API values are intact.
Narrow cards keep a title/ellipsis cue when it fits; the List view provides the
complete text and accessible actions for short/dense clusters.

Actual secure Android View tests at normal/2× font check 320/360/800dp measured
widths, adjacent one-minute cards, a longer overlapping interval, a final-minute
card, an out-of-window card and100 adjacent one-second intervals. No pairwise
rectangle intersections, positive in-canvas bounds, all six original click targets,
movement cancellation and immutable source items pass. The inspected captures
are cropped native View bitmaps around08:00, not full device/compositor or
physical-tablet screenshots. Initial2× inspection found blank narrow cards:
adaptive padding plus an explicit ellipsis for Android's empty ellipsize result
fixed it; pixel-ink checks now guard all four narrow fixture cards.

The supplied private Dayflow timeline and official CanvasTimelineDataView.swift
at2c79e161 (`TimelineActivityLoader.resolveDisplaySegments`) informed separating
visual overlap resolution from stored activity data. PocketLog reserves rendered
lanes rather than copying Dayflow's segment trimming. Android41unit/build/lint,
normal/2× instrumentation and full eight-page HTTPS/offline regression pass.
Rust fmt/clippy-Dwarnings/58tests and audit1509advisories/185dependencies pass
(audit retains the local crates-index warning); web timeline/import regressions
pass. No server code/image changed, so no deployment is needed for this fix.
Physical-device, TalkBack traversal and full-day battery remain unverified.

- [Short cards at normal font](screenshots/native-short-cards.png)
- [Short cards at2× font](screenshots/native-short-cards-large.png)

## Future-capture AI consent follows remote configuration (#47)

A phone now checks an existing future-capture grant against current enabled AI
configuration during the existing sync cadence and when opening capture/AI
settings. Confirmed identity changes or disablement invalidate only that future
AI grant and explain how to renew it. Failed/offline or incomplete reads show
unverified readiness and retain only the previously approved identity. A current
identity is the only green state; a paused local context recorder shows a saved
grant rather than claiming active capture. No automatic grant, new sampling,
provider rebinding or queue rewrite is introduced. Already sent requests cannot
be recalled. The worker's independent identity check remains authoritative.

Astra's read-only source review identified the missing remote refresh. Dayflow's
FlowView.swift at2c79e161 observes provider-routing changes to recompute readiness;
this informs reactive state, not copying its hosted Flow integration.

Three policy tests bring Android to44unit tests. Real secure synthetic HTTPS
emulator tests at normal/2× font rotate the server configuration through a second
client, reconcile it via native sync, inspect the recovery state, cancel renewal,
and explicitly bind a new grant to the new identity. Unchanged identity does not
rewrite the capture revision. A real encrypted queued old-provider payload remains
byte-for-byte unchanged through reconciliation; the intentionally incomplete test
payload is cleared before sync and never uploaded. Read failure, actual airplane
mode/Wi-Fi-off status and confirmed disabled configuration are covered. All test
capture consents are cleared afterward; no capture or model-test endpoint is used.
The synthetic provider model is restored after each run.

The first harness cleanup raced DNS after network restoration; it now waits for a
validated unmetered network. Initial2× captures showed recovery text squeezed beside
a large badge; final full-width guidance below the action is substantially easier
to read. Final captures were inspected and the complete consent block must fit in
the visible content area. These are Android View/dialog snapshots with FLAG_SECURE
retained, not compositor or physical-device screenshots.

Android44unit/build/lint and final normal/2× HTTPS checks pass. Full eight-page
HTTPS/offline reflection/cache/brief-consent regression also passes. Required
Rust58/fmt/clippy-Dwarnings/audit1509/185 and web regressions passed this same wave;
server sources/image remain unchanged. Physical capture/TalkBack/battery and full
Dayflow parity remain unverified.

- [Remote provider change recovery](screenshots/native-capture-ai-renewal.png)
- [2× recovery guidance](screenshots/native-capture-ai-renewal-large.png)
- [2× offline unverified state with revoke action](screenshots/native-capture-ai-unverified-large.png)

## Local recording survives private-server outages (#48)

Native sync now treats transient collection-control failures as offline for local
UsageStats collection, under the existing consent and last-known pause state.
Server backoff also preserves this local cadence. Neither path uploads before a
successful control read. Status explains that encrypted records remain local.
Confirmed401 still stops and clears capture; confirmed remote pause still clears
queued capture. Permanent control rejection is not treated as an offline capture
permission. No retention, sampling rate or endpoint-auth policy changed.

Astra's read-only audit identified the earlier control GET aborting before the
local scan. Dayflow's FrameStore.swift and StorageManager+Screenshots.swift
at2c79e161 persist capture locally, separate from analysis; this informs local
capture continuity rather than copying its desktop sampling frequency.

Actual secure emulator checks at normal/2× font use a disposable HTTPS failure
proxy:503, dropped transport, future retry deadline, last-known pause,403,401,
and restored forwarding. An aged21-minute encrypted usage checkpoint advances
through actual UsageStats scans without drop-count changes during transient
failures and backoff. Queued data stays destination-bound with zero upload
requests on failed/backoff paths. With last-known pause the checkpoint stays
unchanged. Restoring the endpoint drains the bounded queue and the unique
synthetic activity is read back from the API. Successful server pause and401
still clear captured records and stop as specified. Test permission/consent is
restored/cleared. Full eight-page HTTPS/offline reflection/cache/brief regression
passes. No full-day outage/battery or physical-device claim.

Reproduction helper: `scripts/fixture-control-outage.py` binds loopback18754 and
forwards only to the separate synthetic API on18751 using its public dummy owner
key. Temporarily map HTTPS18448 to18754 and the existing fixture18447 to18751;
run instrumentation with `synthetic=yes connected=yes controlFailureOnly=yes`
and the18447 fixture endpoint. Stop the helper and remove both temporary mappings
when done. No such test routes exist in the production Rust service.

Android44unit/build/instrumentationbuild/lint pass. Final normal/2× captures were
inspected, with full-row visible-bounds assertions. Required Rust58/fmt/clippy/
audit1509/185 and web checks passed this same wave; server/image unchanged.

- [Private server unavailable](screenshots/native-server-outage.png)
- [2× text recovery status](screenshots/native-server-outage-large.png)

## Classification saves preserve freshly saved native goals (#49)

The native shared-settings screen now replaces its local workspace snapshot with
the complete successful goal-save response. Previously it changed only revision;
opening the category editor afterward cloned old goal values under the new
revision, and a classification save silently reverted those goals.

Real synthetic HTTPS emulator tests at normal/2× font use the visible goal form,
save two new targets, open the classification editor, rename a category and save.
API readback and reloaded native fields retain both new targets and the category
change. Original synthetic workspace is restored after each run. Inspected secure
View snapshots show the saved goals and renamed category; no real journals.
The supplied Dayflow targets/category reference and separate DayGoalPlan persistence
in StorageManager+DayGoals.swift at2c79e161 inform maintaining independent intent.
Android44unit/build/lint and full eight-page HTTPS/offline regression pass, as do
Rust58/fmt/clippy-Dwarnings/audit1509/185 and web regressions. Server unchanged;
physical-device/TalkBack/battery validation remains separate.

- [Goals retained after category save](screenshots/native-goals-after-classification.png)
- [2× text goals and category](screenshots/native-goals-after-classification-large.png)

## Web classification draft recovery (#50)

Built embedded web assets, synthetic loopback fixture, 1280×800 and390×844.
Old image reproduced repeated409 plus refresh advice. New dialog retains category
and rule drafts, distinguishes goals-only versus classification changes, and
requires explicit retry/replacement. Actual second-client goal update, draft edit
after metadata read, classification/rule update, replacement cancel and confirm,
and another remote update during confirmation all pass API readback. No automatic
write follows a conflict. Used-category409 was exercised with a newly uploaded
synthetic activity: restoring the removed category retains other edits and saves.
Simulated fetch rejection on metadata refresh preserves the form and retry works.
Held refresh and held successful-save responses cannot alter/dismiss a newer modal.
Focused recovery heading and all actions fit at390px; screenshot inspected below.
The final embedded image passed retry/readback and Docker private smoke. Node
policy tests run in existing web CI. Same-wave required Rust58/fmt/clippy/audit and
Android44unit/build/lint/full HTTPS/offline regression passed; native unchanged.
Official Dayflow category/goal separation at2c79e161 plus supplied private screenshot
informs this editor; conflict recovery is PocketLog-specific. CSSzoom200% exposed
pre-existing category-row/modal overflow, tracked separately in #51; do not treat
this as a pass for browser zoom, screen readers, or full Dayflow parity.

- [390px classification recovery](screenshots/web-classification-conflict-mobile.png)

## Scaled classification editor and dialogs (#51)

Final embedded Docker image, synthetic browser fixture. At390×844, normal and
CSSzoom2, category roles stay inside the modal and can receive focus fully inside
the viewport. Category controls stack the role below colour/name at narrow widths;
the modal height uses its containing viewport percentage rather than an unscaled
vh limit. All modal edges remain visible and internal horizontal overflow is zero.
Actual goals-conflict recovery at2× retains its retry action; focusing it scrolls
it fully into view. Goals, provider and confirmation dialogs also pass bounds and
horizontal-overflow checks. At1280×800 normal/2×, classification dialog bounds pass
and normal desktop rows retain three columns. Synthetic workspace restored; no
provider test or inference was called. Screenshots below were inspected. CSS zoom
is not native browser zoom or a screen-reader test. Node regressions and final
Docker private smoke pass; unchanged Rust/native checks are recorded in #50 above.

- [Scaled category role](screenshots/web-classification-scaled-role.png)
- [Scaled conflict retry](screenshots/web-classification-scaled-recovery.png)

## Native classification draft recovery (#52)

Old0.4.18 APK reproduced repeated409 after another client updates goals; server
classification remains unchanged, draft has no recovery action. New0.4.19 actual
HTTPS emulator/View tests pass at normal and2× system font scale: add-category
recursive editor, rule-preview return, goals-only conflict, return to edit with
original draft, explicit retry preserving new category and remote goals, remote
category change, replacement cancel, another remote update during confirmation,
guarded refusal/reload and explicit successful replacement. Real synthetic activity
makes category removal return used409; restore retains other edits and saves.
Actual airplane/WiFi-off metadata refresh fails; returning to edit retains draft,
reconnect and retry succeed. Synthetic workspace restored after each run, capture
consent off. Four policy unit tests include independent snapshots, current unrelated
fields, category/rule changes, revision guard and null/key-order normalization.
48native unit tests, assembleDebug, instrumentation build, lint and full eight-page
HTTPS/offline regression pass. Rust58/fmt/clippy-Dwarnings/audit1509/185 and web
regressions pass. Audit retains the local crates.io-index update warning; advisory
scan succeeds. Secure View and secure-dialog captures below were inspected; these
are not compositor, physical-device, gesture or TalkBack evidence. Draft recovery
is scoped to the current editing flow, not process-death persistence.
Official Dayflow target/category separation and DayCategorySelectionEditor.swift
at2c79e161 inform explicit category intent; multi-client recovery is PocketLog-specific.

- [Retained draft and current goals](screenshots/native-classification-conflict.png)
- [2× retry action](screenshots/native-classification-conflict-large.png)
- [2× explicit replacement confirmation](screenshots/native-classification-replace-large.png)

## Native retained goal drafts (#53)

Old0.4.19 APK reproduced repeated goal409 after a second client renamed a category;
entered minutes remain in the form but cannot recover without reloading. Sol6.1
implementation reviewed and exercised against the synthetic HTTPS fixture at
normal/2× system font: preserve entered focus/budget, return to edit and retry,
retain remote classifications on successful goal save, remote-goal cancel/confirm,
remote classification update during confirmation guarded by revision, and actual
airplane/WiFi-off refresh failure followed by return/edit/reconnect/save. API
readback and original workspace restoration pass after each run. Policy tests
also cover remote rules/other fields, independent nested snapshots, either goal
changing and valid request bounds. Drafts are scoped to the current editing flow.
52unit tests, assembleDebug, instrumentation build and lint pass. Final full
8-page HTTPS/offline regression plus goal-save→classification-save regression pass.
Same-wave Rust58/fmt/clippy/audit1509/185 and web regressions pass; server unchanged.
Private backup restore, prior-row preservation and production assets/auth checks pass.
The actual supplied Dayflow target/category hierarchy and DayGoalPlan persistence
at2c79e161 inform preserving separate edit intent. Captures below are inspected
secure View/dialog renders, not compositor/physical-device/TalkBack evidence.

- [Retained goals and explicit retry](screenshots/native-goal-conflict.png)
- [2× goal retry](screenshots/native-goal-conflict-large.png)
- [2× goal replacement confirmation](screenshots/native-goal-replace-large.png)

## Native readable local app names (#54)

Old0.4.20 APK reproduced package identifiers in usage-only list cards. Final
0.4.21 normal/2× system-font checks exercise real synthetic HTTPS activities:
installed Settings label in list/day card models and overview; unknown packages
retain their identifier, user titles remain unchanged. The editor shows local
name plus original package while its editable title stays canonical. Loaded JSON
and API readback remain unchanged; actual airplane/WiFi-off cached cards retain
labels. Only packages present in the viewed journal are resolved locally, off the
UI thread, with 256 lookup/cache bounds; no enumeration, upload or persistence.
Android package visibility can prevent resolution; unresolved apps fall back.
This changes native presentation, not server/API/export titles or web labels.

Four policy tests cover substitution, no JSON mutation, failures, dedup/work/cache
bounds, normalization and surrogate-safe length. 56 native unit tests, debug APK,
instrumentation build and lint pass; exact Unicode card edit/queue and full
eight-page HTTPS/offline regressions pass. Same-wave unchanged Rust58/fmt/clippy
with warnings denied/audit1509/185 and web regressions pass. Audit retains the
local crates.io-index update warning; advisory scan succeeds. Fresh private
backup restore, prior-row preservation and production HTTPS assets/auth pass.
Official ActivityCard.swift title hierarchy at2c79e161 and the supplied actual
Dayflow screenshots inform readable cards; local naming is not semantic AI
summary parity. Inspected synthetic secure View/dialog renders below are not
compositor, physical-device or TalkBack evidence.

- [Local identity and canonical editable title](screenshots/native-local-app-identity.png)
- [2× readable usage card with unknown-package fallback](screenshots/native-local-app-list-large.png)

## Native deliberate thumbnail deletion (#55)

Old0.4.21 actual HTTPS replay check reproduced immediate deletion on a single
viewer action. The final viewer first opens a secure confirmation showing app,
time and exact deletion scope. Opening/cancelling preserves the same viewer and
bitmap and leaves the API image list unchanged; keyboard-mode cancellation
returns focus to the delete action. Confirmation removes only that thumbnail;
API readback, empty list and bitmap disposal pass at normal/2× system font.
The lifecycle harness opens both dialogs, recreates the Activity and confirms
both windows close and media is recycled. Existing dismissal and undelivered
bitmap disposal checks also pass. Synthetic images were validated by the real
worker; analysis-only images remain excluded. Capture/AI consent stays off in
the phone; no capture cadence, retention, server API or permissions changed.

Initial harness selection read the underlying dialog instead of the topmost one;
that selector was corrected. The focus test explicitly enters keyboard mode
before activation; touch mode alone does not establish keyboard focus. This is
scripted View focus evidence, not physical-keyboard or TalkBack verification.
56 native unit tests, assembleDebug, instrumentation build, lint and full
eight-page HTTPS/offline regression pass. The unchanged Rust/web required checks
passed in this wave; server remains9928655.
Fresh private backup/restore, prior-row preservation and production HTTPS
assets/private authentication pass. Dayflow TimelineRateSummaryView.swift
(at2c79e161) uses a confirming state for destructive activity actions. That is
a source reference; supplied actual screenshots do not establish replay deletion
interaction. The inspected synthetic captures are secure View/dialog renders,
not compositor, physical-device or macOS runtime evidence.

- [Deletion identity and scope](screenshots/native-replay-delete-confirm.png)
- [2× confirmation and actions](screenshots/native-replay-delete-confirm-large.png)

## Native transparent weekly response limits (#56)

Old0.4.22 actual HTTPS fixture reproduction submitted 1,001 idempotent synthetic
activities on the prior day. The real bounded timeline response returned1,000
cards and truncated=true; native Weekly displayed totals without any warning.
Final normal/2× font checks verify one affected day, a warning before the totals,
a loaded-records label and partial-data text in that day's row. Actual airplane/
WiFi-off cached week retains those distinctions; switching to an ordinary
complete earlier week removes them. All requested warning/row text fits its
visible rectangle after scrolling. At2× the duration value wraps, without loss
of its units. Default touch mode is established for these phone captures.

56 unit tests, debug APK, instrumentation build, lint and full eight-page
HTTPS/offline regression pass. The navigation regression waits for the loaded
week grid rather than a totals label that intentionally changes for partial data.
Same-wave unchanged Rust58/fmt/clippy with warnings denied/audit1509/185 and web regressions pass.
Fresh private backup restore, prior-row preservation and HTTPS assets/private
authentication checks pass; no server deployment/API/quota change. Synthetic
fixture activity remains in the isolated instance only, with existing retention.
The supplied actual Dayflow screenshot and official WeekTimelineGridView.swift
hierarchy at2c79e161 inform clear scope of factual summaries; neither establishes
Dayflow's response-limit behavior. These inspected synthetic secure View renders
are not compositor, physical-device or TalkBack evidence. Initial extended test
waited for the optional category chart on an empty week; it now waits for the
always-present week grid. No product failure was inferred from that test error.

- [Partial-week warning before totals](screenshots/native-week-partial-warning.png)
- [2× warning and loaded totals](screenshots/native-week-partial-warning-large.png)
- [2× affected-day detail](screenshots/native-week-partial-day-large.png)

## Native export snapshot ownership and recovery (#57)

Old0.4.23 lifecycle harness reproduced a pending export snapshot being wiped on
actual Activity recreation, with no bytes in the new instance. Final normal/2×
system-font harness verifies transfer of the same exact Unicode byte snapshot,
then invokes the production result callback and ContentResolver file-URI writer:
output bytes match, and the consumed buffer is zeroed. Cancellation, busy-result
rejection, reset and final Activity destruction also wipe pending bytes. A real
write to a missing parent directory fails with export-specific retry feedback;
a successful result with no retained snapshot writes nothing and explains retry.
No journal bytes enter saved-state Bundles or new disk caches. Content lost on
process restart is intentionally not reconstructed from changed journal data.

This is deterministic lifecycle/callback/file-output evidence using injected
synthetic pending state, not interaction with the real document picker or an
actual OS process kill. The unconnected emulator displays recovery feedback on
its setup screen in the captures; normal exports originate on Timeline. Initial
destruction assertion ran before onDestroy completed; it now waits for actual
destruction. One2× command had a runner-name typo before launch, corrected before
the successful run. 56 unit tests, assembleDebug, instrumentation build, lint and
full eight-page trusted-HTTPS/offline regression pass. Same-wave unchanged Rust
fmt/clippy-Dwarnings/tests/audit and web checks pass. Fresh private backup restore,
prior-row preservation and production HTTPS assets/auth pass; server unchanged.
Official SettingsDataTabView.swift at2c79e161 provides deliberate export range/
actions; this Android ownership fix is not a macOS lifecycle/parity claim.
The inspected captures are synthetic secure View renders, not compositor,
physical-device or TalkBack evidence.

- [Lost snapshot retry guidance](screenshots/native-export-lost-state.png)
- [2× lost snapshot guidance](screenshots/native-export-lost-state-large.png)
- [2× failed write guidance](screenshots/native-export-write-failure-large.png)


## Week entry anchor (#58)

The rebuilt synthetic fixture now opens the selected week column fully in view
and scrolls to its first matching activity with leading context. At1280×800
selected Saturday is fully visible (scrollLeft245, scrollTop464); at390×844
it is fully visible (643,464), with the dense synthetic activity band below the
sticky date header. No document overflow. Switching to Sunday keeps that column
visible; manually changing both scroll offsets stays unchanged while idle.
Scripted DOM focus/click navigation preserves the week control and the disabled
today button falls back to the date input. Cancelling a mobile card dialog
returns focus to the visible originating card. These are Chromium scripted-focus
checks, not real Tab/TalkBack or physical-device evidence.

Official WeekTimelineGridView.swift at2c79e161 calls scrollToRelevantHour and
prefers selected-day content; PocketLog uses actual elapsed day bounds. Empty
past selected days retain04:00 and today without matching content uses now.
The separate missing hour-axis finding remains #59.
Node syntax/timeline/agent-import checks, Rust fmt/clippy-Dwarnings/58tests/audit
and disposable Docker privacy/API/worker smoke pass. Android unchanged from
#57's passing56unit/build/lint and connected/offline checks.

- [Desktop selected Saturday](screenshots/web-week-anchor-desktop.png)
- [Phone selected Saturday](screenshots/web-week-anchor-phone.png)


## Visible week clocks (#59)

Normal weeks now have one44px sticky hour gutter. Date headers and the clock
share60px headers, keeping all track origins aligned. Selected-column entry
accounts for the gutter. The scroller isolates its stacking context so the gutter
cannot paint over the phone navigation, and scroll padding keeps restored cards
clear of sticky dates/hours. Official WeekTimelineGridView.swift at2c79e161
renders a dedicated timeColumn; no copied artwork or macOS runtime claim.

Final rebuilt-image Chromium synthetic checks at1280×800 and390×844 verify
24clock labels, matching track origins, selected Saturday fully visible, no
document overflow, horizontal gutter stickiness and unobscured phone navigation.
A Sunday card restored after shell reload remains focused and visible below the
header and beyond the gutter (phone top526px, header bottom510px). Captures below
were saved and inspected. The first capture iteration exposed gutter paint over
the bottom bar; final isolation fixes it. Initial automation waited for a grid
while the narrow-screen default was list; explicitly selecting grid fixed the
harness, not product code.

DST weeks use individual day clocks rather than a misleading shared clock over
unequal elapsed-day lengths, and explain this in the UI. Node regressions verify
the branch policy for New York23/25h and Lord Howe23.5h transitions; existing
tick/offset tests retain repeated-hour distinction and04:00 boundaries. The
shared preview has no timezone override, so transition-week browser layout is
not visually verified; these captures use the normal Taipei week. No real Tab,
TalkBack or physical-device claim. Web regressions and final-image Docker smoke
pass. Same-wave unchanged Rust fmt/clippy-Dwarnings/58tests/audit and Android
56unit/build/lint/connected-offline checks remain valid.

- [Desktop clock gutter](screenshots/web-week-axis-desktop.png)
- [Phone clock gutter and navigation](screenshots/web-week-axis-phone.png)


## Dense timeline groups (#60)

The1000one-second synthetic activities exceeded the grid's useful target width:
114cards painted outside a130px week track, and padding forced15px cards over
near-zero lane widths. Groups over4week lanes or8day lanes now show one neutral
count/list action, using the group's true first/last activity times. Ordinary
groups keep individual cards. This is view-only aggregation, not journal merging
or classification, and does not raise query quotas or fill empty time.

Final-image Chromium at1280×800 and390×844 shows one1000-record action wholly
inside its day,24px high, with the short visible label fitting in full; no
document overflow. Activating it produces1000unique original list cards and
focuses the first, retaining selected date/week and learning filter. Day view
also aggregates the dense range; clicking through opens the original editor and
Cancel returns focus to its list card. Captures below were saved and inspected.
Pure regressions verify complete record coverage, true bounds, immutability,
separated dense/ordinary groups and ordinary overlaps. Astra6 independently
reviewed the diff and found no substantive issue (no browser/API actions).

Web syntax/timeline/import regressions and final-image Docker privacy/API/worker
smoke pass. Same-wave unchanged Rust fmt/clippy-Dwarnings/58tests/audit and
Android56unit/build/lint/connected-offline results remain valid. This is a
bounded stress fixture, not a statement about the owner's private data, physical
phone input or TalkBack. Official Dayflow's readable timeline hierarchy is the
reference; dense-group rendering is PocketLog's adaptation, not feature parity.

- [Desktop dense group](screenshots/web-density-desktop.png)
- [Phone dense group](screenshots/web-density-phone.png)


## Brief activity durations (#61)

Positive durations below30seconds now read「不到1分鐘」on the website, matching
the native formatter's distinction from zero. Zero stays0; minute/hour rounding
and stored interval/merged-total arithmetic are unchanged. Negative display input
is clamped to zero, as on Android. Official DailyWorkflowComputation.swift
at2c79e161 delegates duration presentation to LocalizedDuration; PocketLog's
wording is its own implementation, not an exact Dayflow format claim.

The rebuilt synthetic390px list shows the new duration in both visible text and
accessible name for a one-second card, without overflow. The1280px read-only
inspector also shows it and focuses its heading. Both captures were saved and
inspected. Node checks cover zero/negative,1ms/29999ms/30000ms, minute rounding
and exact/remainder-hour boundaries. Web regressions and final-image Docker
privacy/API/worker smoke pass. Same-wave unchanged Rust fmt/clippy-Dwarnings/
58tests/audit and native56unit/build/lint/HTTPSoffline checks remain valid.
No browser mutation, provider call or native production change was needed.

- [Phone brief durations](screenshots/web-short-duration-phone.png)
- [Desktop inspector duration](screenshots/web-short-duration-inspector.png)


## API first-read guidance (#62)

API.md now starts with the actual owner/session/journal/collector scope matrix,
separates64KiB request bodies from route-specific response limits, and provides
a read-only Bash/curl example. The64-hex key is entered without echo, sent via
curl config stdin rather than arguments/URL, and unset afterwards. HTTPS uses
normal certificate validation and follows no redirects. No auth implementation,
route, permission or runtime artifact changes.

Cross-checked lib.rs authenticate, web.rs authorized and devices.rs authorized.
A trusted-HTTPS disposable fixture verified journal status/timeline200, collector
status/timeline401, both device-management401, both collection/AI200, missing/
wrong/revoked key401, no-store and authorized schema rejection. The exact
documented snippet passed bash-n and executed successfully against that origin
with a temporary journal key, without the key in stdout/stderr. All temporary
credentials were revoked; no journal/provider writes. TLS18447 was removed.
Initial harness assumed schema400 and revoke200; actual extractor422 and
revoke204 were correctly accepted in the final successful run. No product change
was needed for these harness assertions. Documentation-only; prior required
checks remain valid and there is no new server/APK deployment.


## Native week axis across unequal days (#63)

WeekTimelineView previously positioned every day by its actual elapsed fraction
but retained one fixed04/08/12/16/20/00clock gutter. A25h journal day's midpoint
is12.5elapsed hours after04:00, not the16:00label shown at50%. Transition weeks
now explicitly show0/25/50/75/100%progress and explain the interpretation;
ordinary weeks retain clocks. Length badges have a separate header row, with
full23/25/23.5h-equivalent duration in readable daily rows. Compact date text
and selected circles fit their columns; day rows keep the full system font.
This is PocketLog's compact-mobile adaptation of the official Dayflow week
time-column/selection hierarchy at2c79e161, not a macOS runtime/parity claim.

Sol6.1 implemented the view/policy and four pure policy/quarter-geometry tests;
root integrated explanatory text, full day lengths, visual fixes and acceptance.
The old0.4.24APK with the new testAPK reproduced fixed clocks on unequal days.
Final actual Android Canvas/View tests cover NY23/25h, LordHowe23.5h and normal
Taipei/NewYork at320/360/800dp with normal/2×font sizes: correct labels, unclipped
percent gutter/bottom, true halfway activity geometry, bounded date text/circles
and all seven original day callbacks. A2×capture exposed circle/header crowding;
final column bounds fix it. Narrow length badges may ellipsize; readable rows
retain the full duration.

Actual trusted-HTTPS page checks with a fixed synthetic October31activity and
process-local NewYork timezone verify the explanation, full25h day row, and
removal of progress explanation on the next normal week, at both font sizes.
Timezone restored in finally; emulator consent stays off. Final eight-page
HTTPS/offline regression passes. Captures below were inspected; these are
synthetic secure View/Canvas renders, not compositor, physical-phone or TalkBack
evidence. Production FLAG_SECURE is unchanged.

60Android unit tests, assembleDebug/instrumentation build/lint pass. Fresh Rust
fmt/clippy-Dwarnings/58tests/audit1509advisories/185crates and web regressions
pass (audit has the existing local crates.io-index warning, scan succeeds).
Fresh private production backup/restore, prior rows and HTTPS assets/auth pass;
server remains51e9a1c. APK0.4.25-dev uses the existing signing certificate.

- [Normal-size progress axis](screenshots/native-week-progress-axis.png)
- [2× progress axis](screenshots/native-week-progress-axis-large.png)
- [Half-hour transition](screenshots/native-week-half-hour-axis.png)
- [2× readable full day length](screenshots/native-week-day-length-large.png)

## Classification editor focus (#64)

Built-image Chromium reproduced focus falling to BODY after moving the second
unsaved rule. Replacing its DOM removed the focused button. Rule moves now focus
the same rule's enabled checkbox at its new position; deletion chooses the next
or previous rule, falling back to Add rule for an empty list. New rules focus the
app condition. Category additions focus their name, and removals focus a neighbor.
Rule groups and action labels identify their position; a status region announces
moves/additions/deletions. First-rule Move up is disabled. Drafts remain local
until the explicit save.

Official Dayflow's supplied private screenshot and TimelineCardColorPicker.swift
at2c79e161 were reviewed for the category editing context. This is a correction
to PocketLog's own ordered-rule editor, not a claim that Dayflow uses this rule UI.

Final embedded-image checks at1280×800 and390×844 used synthetic data only:
add/move/delete, first-rule disabled, next/previous/empty focus fallback, preserved
app/title drafts, category neighbor focus, focused control within dialog bounds,
and no document/dialog horizontal overflow passed. Cancel left the complete
workspace response unchanged. Focus was driven by DOM focus/click and inspected
through activeElement; this is not screen-reader or physical-keyboard evidence.
Both saved captures below were inspected. No API/classifier/Android change.

Web syntax, timeline/import regression checks and final Docker auth/persistence/
worker/replay/correction/deletion smoke passed. The same-wave Rust fmt/clippy with
warnings denied/58tests/audit and Android60unit/build/lint/HTTPSoffline checks
remain valid for unchanged source. Fresh private production backup/restore passed.

- [Phone focused moved rule](screenshots/web-rule-focus-phone.png)
- [Desktop focused moved rule](screenshots/web-rule-focus-desktop.png)

Follow-up saved-state check on the same built synthetic fixture: two rules for
one synthetic package were reordered in the UI and saved. GET workspace retained
both selected categories in the new order; the authenticated classifier preview
selected the first rule and retained a manually edited sample's original category.
Save closed the dialog and returned focus to chip-edit. Fixture preferences were
restored with the current revision in finally; no activities/model calls were made.

## Discoverable private API setup (#66)

Settings now has a Private API card with the actual origin and an explicit copy
button, plus optional expanded setup steps. It explains journal versus collector
scope, keeps owner recovery separate, links the existing device manager and API
reference, and names the status/timeline routes, Unix milliseconds,26h range and
truncation. No key is created or displayed by the guide. Phone capture guidance
now matches the native Settings / phone recording / screen context sections.

The final embedded image was inspected at1280×800 and390×844 with expanded help:
no document/panel horizontal overflow, scope/range/partial-result text readable,
no64hex key in the guide, and API reference rel=noopener+noreferrer. The actual
copy button completed successfully; an observer wrapping the original clipboard
write confirmed its sole payload was location.origin. Browser clipboard reading
was denied (NotAllowedError), so clipboard readback is not claimed. Device-manager
open and Escape-close restored focus to settings-api-devices without writes.
No extra API/model requests, credential creation or collection consent are added.
Final web regressions and Docker smoke pass; unchanged source retains the prior
required Rust/native checks. Both synthetic captures below were inspected.

- [Desktop API setup](screenshots/web-api-guide-desktop.png)
- [Phone API setup](screenshots/web-api-guide-phone.png)

## Native classification continuation (#65)

On the installed0.4.25APK, moving the last of eight synthetic rules retained the
order/draft but reset the actual ScrollView to0, above five category forms. Each
organizing action rebuilt screen() without an editing continuation. The editor
now uses stable category/rule identities to focus and reveal the next action after
layout, guarded by render generation and attachment. Add opens the new app/name
field; moves follow the same rule; deletion chooses the nearest survivor or Add.
Existing first-match logic, remembered drafts, conflict baseline and save stay intact.

Sol6.1 reproduced the old APK and implemented/checked actual secure Android Views.
Normal and2×font tests passed: add/move/delete rule/category; unsaved field values
and order; visible focused bounds; second-to-first move; final-rule→Add fallback;
last-custom-category→built-in-name fallback. Root inspected the three curated
captures below and reran the checks on the integrated0.4.26APK. This is a bounded
PocketLog editor correction in the Dayflow category editing context, not a new
classifier or desktop-parity claim. No API, provider or production data was used
by the disconnected editing checks. Production FLAG_SECURE is preserved.

The integrated release passed60Android unit tests, debug/instrumentation builds
and lint. Fresh Rust fmt/clippy-Dwarnings/58tests/audit1509advisories/185crates
passed; the known local crates.io-index audit warning did not prevent its scan.
Physical-device, TalkBack and keyboard-open interaction remain unverified.

- [Focused moved rule](screenshots/native-classification-continuation.png)
- [2× focused moved rule](screenshots/native-classification-continuation-large.png)
- [2× new category name](screenshots/native-classification-new-name-large.png)

The final eight-page trusted-HTTPS/offline regression passed on0.4.26; its cleanup
cleared credentials/queue and kept all capture consent off. Fixture TLS18447 was
removed. Fresh production backup/restored integrity, prior rows and HTTPS assets/
auth checks passed. The native-only release leaves serverb74723f unchanged and
uses the existing APK signing certificate.

## Meaningful category labels and keyboard acceptance (#67)

The inspected2×#65new-category capture showed the internal custom UUID consuming
two lines above the name. Native editing now labels categories as built-in/custom;
IDs remain unchanged in drafts, rule targets and API payloads. The existing fixture
locator now finds its category by its synthetic name instead of requiring visible
implementation IDs. No classifier, collection, sync or auth behavior changes.

Actual0.4.27normal/2×disconnected-emulator organization/focus checks passed with
the new labels, including all existing rule/category add/move/delete/edge/draft
checks. This wave also resolves the emulator IME acceptance gap for new fields:
the actual keyboard was opened, WindowInsets confirmed it visible, and global
field bounds remained fully visible above the visible frame boundary for both
new-rule app and new-category name. Keyboard was hidden between checks; original
hardware-keyboard policy, font1and cleared local state restored in finally.
These secure View renders show the application layout, not compositor keyboard
pixels. Physical-device/TalkBack behavior remains unverified.

60unit tests, debug/instrumentation builds and lint passed. Same-wave unchanged
Rust58/fmt/clippy-Dwarnings/audit and preceding final eight-page HTTPS/offline
checks remain valid; only a category label changed in production code. Three
captures below were inspected. Existing signing certificate is retained.

- [Readable2×category label](screenshots/native-category-label-large.png)
- [2×name above actual IME boundary](screenshots/native-category-keyboard-large.png)
- [Rule input above actual IME boundary](screenshots/native-rule-keyboard.png)

## Conversation-read ownership (#68)

The built website reproduced a real UI race with controlled synthetic responses:
A's response was held, B selected/read, then late A replaced the transcript while
the picker still said B. Reads now capture the selected ID and request/render
ownership; stale successes/errors after another selection, new chat or navigation
are ignored. Older-history reads have the same guard. Switching shows a busy
status and blocks send/delete against the prior thread until it resolves; a latest
failure restores the prior selection and usable transcript for retry.

The guarded browser-only scripts/check-web-chat-reads.js exercises the actual
built UI, controlling conversation read responses only. It requires explicit
synthetic opt-in and localhost:18751; it restores fetch in finally and calls no
providers or conversation writes. Final1280/390checks passed: out-of-order A/B,
stale failure, new-chat/nav during a held read, pagination during selection,
latest-error recovery/retry and successful pagination without duplication. No
implicit ask calls occurred; loading disables prior-thread send/delete and names
its busy state. These are injected fixture responses and DOM actions, not claims
of real-provider latency or physical keyboard/screen-reader verification.

Web syntax/timeline/import regressions and final Docker smoke passed; unchanged
Rust/native sources retain this wave's required checks. Fresh private production
backup/restore passed. The two synthetic captures below were inspected.

- [Phone switching state](screenshots/web-chat-loading-phone.png)
- [Selected thread and matching transcript](screenshots/web-chat-selected-thread.png)

## First-run and private API key wording (#69)

The signed-out invited-user help now distinguishes website Owner login from
journal-scoped Android/private API access and collector upload access. The key
manager names phones and private API tools, with a tool-name example; scope
option labels stay consistent with docs/API.md. No permission behavior changed.

Actual rebuilt-image checks at 1280/390 confirmed readable invited-user help,
selected-tab focus and no document overflow. At 390 the API key manager opened
with heading focus, showed the new intro/name hint and existing scope options,
and closed without creating or revoking a key. The synthetic captures were
inspected. JS/timeline/import checks and disposable Docker smoke passed; unchanged
Rust/native code retains the preceding same-wave checks.

- [Invited-user help](screenshots/web-api-invited-help-phone.png)
- [Phone/API key manager](screenshots/web-api-key-manager-phone.png)
