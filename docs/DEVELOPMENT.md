# Reproducible development on a separate machine

Development can move independently of production. Do not copy journals, `.secrets`,
provider configuration, signing keys, local output/ledgers or personal device data.
Production remains on its existing host; none of the commands below deploy there.
Read [security boundaries](SECURITY.md) and the scoped [acceptance evidence](ux/ACCEPTANCE.md).

## Toolchains and bounded checks

Use a JDK with `javac` (17 or newer), Android SDK platform 36 and build-tools
35.0.0. A JRE alone is insufficient. Install missing packages into your own SDK:

```sh
export JAVA_HOME=/path/to/jdk
export ANDROID_HOME="$HOME/Android/Sdk"
"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
  'platforms;android-36' 'build-tools;35.0.0'
./scripts/dev-check.sh android
./scripts/dev-check.sh web
```

Android uses the checksum-pinned Gradle wrapper, two workers, no persistent Gradle
daemon, no parallel projects and the existing 2 GiB Gradle heap. The Android check
runs unit tests, `assembleDebug`, `lintDebug`, and the isolated sandbox build/lint.
Dependencies and generated products stay ignored; never stage them.

On SteamOS, use rootless Podman rather than changing the read-only OS or installing
system compilers. The development image pins the same Rust image digest as CI,
adds rustfmt/clippy and cargo-audit 0.23.0, and has no production credentials:

```sh
./scripts/dev-check.sh rust
```

This builds the development toolchain, then runs fmt, clippy with warnings denied,
locked tests and the dependency audit. Compilation uses two jobs, at most two CPUs
and 4 GiB container memory. Source is mounted read-only; Cargo registry/target
caches live in `${XDG_CACHE_HOME:-$HOME/.cache}/pocketlog-dev`. The first run needs
network and several minutes. Audit fetches current advisories: an unavailable
advisory database or failed check is a failure, not an accepted offline result.
The pinned image currently supplies Rust 1.100.0; the application minimum is 1.90.

## Physical phone without replacing the owner app

```sh
./android/gradlew -p android --no-daemon --max-workers=2 :app:assembleSandbox
./scripts/install-sandbox.sh DEVICE_SERIAL
```

The sandbox package is `tw.skyhong.pocketlog.sandbox`, labelled **PocketLog Dev**,
with separate Android storage, Keystore namespace, credentials and permissions.
It retains HTTPS verification, backup restrictions and `FLAG_SECURE`. No permission
or capture/model consent is granted by building/installing. A new machine generates
its own ordinary debug signing key; do not import an owner signing key, install
the regular debug APK over the owner app, clear it or uninstall it. The install
helper checks the APK's actual package ID and requires an explicit device serial;
it neither launches the app nor changes grants.

Use T3 `device_list` / `device_open` and its returned bound `agent-device` command
for all device UI. A host ID from another T3 environment may appear as `local`
here: rediscover the serial rather than assuming host IDs are portable. If the
driver says `DEVICE_IN_USE`, the owning session must release its own connection;
do not reuse/close another session or bypass it with ADB UI commands. ADB remains
appropriate for builds, installation, logs and authorized test runners.

Begin with empty first-run fields and synthetic validation errors. Do not enter
production keys. Capture permissions in the owner app require separate consent.
Black compositor screenshots are expected for secure windows; never disable the
flag. Existing invasive emulator acceptance harnesses explicitly refuse physical
devices and must not be repurposed without new isolation/guarding. See
[capture acceptance](ux/CAPTURE_ACCEPTANCE.md) for its separate requirements.

### Physical capture with the shared device driver

For an isolated tailnet HTTPS service, install/connect Tailscale on the phone
before pairing. A working Wi-Fi connection alone does not provide tailnet DNS.
The user completes sign-in; never weaken certificate verification to work around
an unreachable private origin. Use disposable service volumes and an expiring
journal device key, not production credentials.

The sandbox's application ID differs from its Kotlin namespace. To return from
Android settings, the bound driver can open the **full component** with CLEAR_TOP:

```sh
"$pixel_driver" open tw.skyhong.pocketlog.sandbox \
  --activity tw.skyhong.pocketlog.sandbox/tw.skyhong.pocketlog.MainActivity \
  --launch-args=-f --launch-args=0x14000000
```

Set `pixel_driver` to the exact command returned by `device_open`. A class name
without the package/component slash can be expanded incorrectly. Verify the
actual screen; an ordinary open/relaunch may leave a system settings activity
above the app. When operating Android settings, first bind the driver to that
app to avoid treating an expected system transition as an escaped-app error.

The driver's UI Automation helper can temporarily **suppress other accessibility
services**, including PocketLog. An enabled service is not proof it is bound.
Do not inspect the accessibility tree during a capture attempt. The no-network
fixture has a launcher entry and an optional ten-second deferred window for a
clean capture interval:

```sh
"$pixel_driver" open org.example.pocketlogfixture --relaunch \
  --activity org.example.pocketlogfixture/.FixtureActivity \
  --launch-args=--ez --launch-args=deferred --launch-args=true
"$pixel_driver" close com.callstack.agentdevice.snapshothelper
```

This closes only this driver's own helper. The fixture then opens a fresh fixed
window after the helper has released UI Automation. Do not reuse/close another
session. Reopen the app through the bound driver before further UI inspection;
inspection can suppress the service again. Use API results and service binding
diagnostics to distinguish a real capture failure from test interference. The
fixture never grants consent, changes credentials or relaxes capture rules.
Use `--relaunch` for each fixture attempt so extras reach a new activity rather
than an existing task. Add `--launch-args=--es --launch-args=variant
--launch-args=editable` for the editable-field exclusion, or replace `editable`
with `secure` for the Android secure-window gate. Release the helper immediately
after launch, before the ten-second deferred window opens.

Do not use `run-as` as a prerequisite: the tested custom Android 16 system rejects
its SELinux context. Use supported UI diagnostics and authenticated API evidence;
never disable SELinux or root the phone for acceptance.

After a physical reboot, wait for Android boot completion and the user's first
unlock before reopening credential-encrypted apps. On the tested custom ROM,
launching the sandbox before first unlock reported a missing activity despite
the same component working before reboot. Do not treat this as proof of APK
loss, reinstall/clear data, or bypass the lock screen. Follow device-tool failure
recovery limits; ask for the user's unlock when needed, then verify permission
revocations, saved credentials and recovery separately.
Also verify the VPN after first unlock: on the tested Pixel, Wi-Fi returned but
Tailscale did not reconnect until its app was opened. PocketLog retained its
encrypted offline timeline and showed the private-server/Tailscale guidance.
Open Tailscale through the device driver, confirm Connected, then retry PocketLog
with the existing key. Do not clear/re-pair an otherwise valid connection merely
because the private origin is temporarily unreachable.

`open --relaunch` is a deliberate force-stop test, not evidence of recovery from
low-memory process reclamation. On the tested Pixel, accessibility was disabled
after the sandbox relaunch even though queued images survived and retried. Check
both enabled and bound service state after every lifecycle transition; restore
through Android settings when needed. A no-capture interval with the service
disabled does not verify the lock-screen privacy gate.

## Disposable container smoke

With an available Docker daemon, build the existing production Dockerfile and run
the synthetic-only smoke helper. On SteamOS, rootless Podman builds directly with
resource limits; its Docker-compatible API lets the existing smoke helper use the
Docker CLI without changing the default context:

```sh
systemctl --user start podman.socket
export DOCKER_HOST="unix://$XDG_RUNTIME_DIR/podman/podman.sock"
podman build --jobs=1 --memory=4g --cpu-period=100000 --cpu-quota=200000 \
  -t pocketlog:implementation-check -f Dockerfile .
POCKETLOG_SMOKE_IMAGE=localhost/pocketlog:implementation-check python3 scripts/smoke-private.py
```

This is local test infrastructure, not a production deployment. The smoke creates
randomly named disposable volumes, a temporary random key, loopback-only API and
worker containers, then removes those resources. It checks authentication,
persistence, processing, idempotency, corrections, deletion and private-log bounds.
Never substitute an existing journal volume or production secret.

The sanitized [remote checkpoint](dev/SKYHONG_SM_CHECKPOINT.md) records actual
results and limits. It is a new public development record, not a copy of any
historical private ledger. No old timed development schedule is resumed.
