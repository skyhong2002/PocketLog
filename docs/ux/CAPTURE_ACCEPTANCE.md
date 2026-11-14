# Synthetic Android capture acceptance

This harness exercises the actual AccessibilityService screenshot callback.
Use only the disposable API34 AVD `pocketlog-ux-check` and an isolated API/worker
with disposable data, owner key `a` repeated64 times, and trusted tailnet HTTPS
on port18447. Never use a personal journal or real provider key.

Build with JDK17+ and SDK36:

```sh
cd android
./gradlew -PcaptureFixture=true --max-workers=2 \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:lintDebug :capture-fixture:assembleDebug :capture-fixture:lintDebug
```

Install app, app-debug-androidTest and capture-fixture debug APKs on that AVD.
The app must have no saved connection. Run from the repository root:

```sh
ANDROID_HOME=/path/to/sdk python3 scripts/check-native-capture.py \
  --endpoint https://your-host.your-tailnet.ts.net:18447 \
  --artifacts /tmp/pocketlog-synthetic-capture
```

The driver temporarily grants usage/accessibility access and meters emulator
Wi-Fi to hold automatic uploads, restoring original settings in `finally`.
Instrumentation kills the bound service, so the driver rebinds afterwards.
The harness refuses existing credentials, creates a journal device key, explicitly
allows only the no-network fixture, then clears local connection/queue/consents
and revokes the key. A401 response verifies revocation.

An editable window must produce no observation. A read-only window must produce
the fixed text marker and bounded JPEG in the encrypted queue. Only synthetic
timestamps move20minutes backwards to avoid settlement delays; production capture
sampling/flags stay unchanged. The observation is uploaded through the journal
key. Run the isolated worker and inspect `/v1/timeline` and `/v1/replay`: expect
`local_excerpt` and an authenticated, no-store replay image. The driver checks
capture/upload, not worker output.

## Optional model stage

`--with-model` requires a separately started local stub on loopback18753 reachable
from the fixture worker. It configures only the isolated fixture and uploads a
second copy of actual captured evidence with explicit provider-bound consent.
It leaves fixture model settings configured: finish worker verification and
restore/delete the fixture configuration yourself. Never use a real provider.
`result.json` includes redacted previous model metadata, not its key. Do not
publish that file or queue contents.

## Verified November14,2026

API34 emulator: editable exclusion, actual read-only JPEG/text, encrypted queue,
journal-key upload and revoked-key401 passed. The isolated worker produced a
local excerpt and private validated replay. A dedicated loopback stub received
exactly one request with the fixed marker and image for the separately consented
observation; that initially unsorted card became `learning`, source `ai`, with
the expected synthetic title. Unconsented capture made no model request. Fixture
model settings were restored. All37 Android unit tests, app/instrumentation builds,
app lint and fixture build/lint passed.

[Actual synthetic OS screenshot](screenshots/native-real-synthetic-capture.png).
This is emulator proof for this path, not physical-device, Doze, battery or
continuous same-window capture coverage. Production `FLAG_SECURE` stays enabled;
only the separate fixed-content fixture is capturable. It has no network permission
and is included only with `-PcaptureFixture=true`.
