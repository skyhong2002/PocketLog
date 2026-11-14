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
