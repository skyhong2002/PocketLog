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
