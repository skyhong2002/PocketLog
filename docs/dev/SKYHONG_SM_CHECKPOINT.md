# skyhong-SM development checkpoint — 2026-11-16 (Taipei)

This is a new sanitized development record. Historical private ledgers/output,
journals, provider configuration, production secrets and signing keys were not
copied. No old 12-hour schedule was restarted. Production remains **sky-server**,
with the supplied runtime baseline **36f9fd9**; production was not accessed or
deployed during this work. Manual mood input remains removed; historical data/API
are unchanged. Broader physical acceptance remains open in
[#11](https://github.com/skyhong2002/PocketLog/issues/11).

## Checkout and machine

- Working directory: `/home/deck/Projects/PocketLog`, cloned from public MIT-licensed
  `skyhong2002/PocketLog`, clean initial main `b5e83ea`.
- Implementation branch: `dev/skyhong-sm-pixel-bootstrap`.
- Machine: SteamOS 3.9.5, x86_64, Linux 7.2.7, 12 logical CPUs, 15 GiB RAM,
  8.7 GiB swap; initial available RAM about 11 GiB and disk space 320 GiB.
- Existing machine projects/files were preserved; no system package replacement.
- T3 project already registered here; added manual Android, Rust and web check
  actions. No automatic/scheduled action was registered.

## Actual development environment

| Component | Result |
| --- | --- |
| JDK | Existing Java was a JRE without `javac`. Installed Temurin JDK 21.0.13+9 under `/home/deck/.local/share/pocketlog-dev/jdk-21.0.13+9`; existing Java link unchanged |
| JDK integrity | Official Adoptium Linux x64 archive SHA-256 `d7f5d5cf865a2902b70ff549b97586a7d3f234e016f041ab447ea740c7d66d1c`, verified before extraction |
| Android SDK | `/home/deck/Android/Sdk`; installed platform 36 and build-tools 35.0.0 using existing command-line tools |
| ADB | Existing platform-tools 37.0.1; physical Pixel 7 / Android 16 detected |
| Rust | Host Rust/C compiler absent. Rootless Podman development image uses CI's pinned Rust digest, Rust/Cargo 1.100.0, rustfmt/clippy and cargo-audit 0.23.0 |
| Build limits | Rust image/checks: 2 CPUs / 4 GiB / 2 compilation jobs; check container 512 PIDs. Android: 2 workers, existing 2 GiB Gradle heap, no persistent Gradle daemon or parallel projects |
| Containers | Existing Docker CLI 29.6.2, no Docker daemon; existing rootless Podman 6.0.2 works. Started user Podman API socket; selected it with per-command DOCKER_HOST, without changing Docker context |
| Caches | Rust registry/target under `/home/deck/.cache/pocketlog-dev` (about 1.9 GiB after checks); normal Android/Gradle caches remain ignored |

The JDK archive is the official `OpenJDK21U-jdk_x64_linux_hotspot_21.0.13_9.tar.gz`
from Adoptium's `temurin21-binaries` release `jdk-21.0.13+9`.
See [portable commands](../DEVELOPMENT.md).

## Verified checks

- `scripts/dev-check.sh android`: **passed**, 65 unit tests, zero failures/errors/
  skips; `assembleDebug`, `lintDebug`, `assembleSandbox`, `lintSandbox` successful.
  Lint: zero errors, 16 warnings per variant (AGP/dependency recommendations,
  DrawAllocation, ViewConstructor, and SetTextI18n). No warning-free claim.
- `scripts/dev-check.sh rust`: **passed**, fmt, locked clippy all-targets with
  warnings denied, 20 unit + 32 API + 6 classifier-preview tests. Audit fetched
  1,509 advisories and scanned 185 dependencies successfully; no index warning.
  Final script was also executed with only Cargo manifests/source/tests/web
  mounted read-only, excluding unrelated workspace files.
- `scripts/dev-check.sh web`: **passed**, JS syntax, agent import privacy/bounds,
  timeline/immutable ranges/DST/density/export and ownership policy regressions.
- Production Dockerfile built locally with Podman (2 CPUs / 4 GiB / 2 Cargo
  jobs); existing disposable Docker smoke **passed**
  through Podman's Docker API: authentication, persistence, API + worker,
  idempotent replay, correction, deletion and no private input in container logs.
  Only synthetic temporary keys/data and randomly named disposable volumes used;
  smoke resources removed by its cleanup.
- Shell syntax and `git diff --check`: **passed**.

Fresh machine-local build/check logs are retained at
`/home/deck/.local/state/pocketlog-dev/2026-11-16-bootstrap-cgov_xak/`.
They contain this bootstrap's Android, final Rust, bounded container build and
final disposable smoke output; no historical ledger or device content was copied.

## Installed isolated phone build

Development isolation is tracked in
[#93](https://github.com/skyhong2002/PocketLog/issues/93).
The regular debug build previously shared the owner application's package ID.
A dedicated `sandbox` build now provides:

- Package `tw.skyhong.pocketlog.sandbox`, label **PocketLog Dev**,
  versionCode 36 / `0.4.31-dev-sandbox`.
- Independent Android UID/storage, Keystore namespace and permissions; normal
  HTTPS verification, backup restrictions and `FLAG_SECURE` retained.
- A guarded install helper that checks the APK's actual package ID and requires
  an explicit device serial. It installs only; no launch or permission grants.

Actual build output:
`android/app/build/outputs/apk/sandbox/app-sandbox.apk` (ignored, not committed).
SHA-256: `875515d11e83497880f1aab0f7cd38d77797f0d94b8322dccd84aec4a34ddf89`.
Direct ADB install and the guarded helper both returned **Success**.
Package metadata confirms sandbox36; the owner package remains36 / `0.4.31-dev`.
The new workstation uses its own debug identity. No owner APK update, uninstall,
data clear, signing-key import, credential entry or capture/model grant occurred.

## Initial physical UI blocker (resolved below)

The supplied remote T3 host ID is not registered in this environment; local
discovery found the same Pixel as host `local`. `device_open` succeeded and
returned a bound driver. Its first `open tw.skyhong.pocketlog` failed with
`DEVICE_IN_USE`, identifying the previous root session in the old checkout as
the owner. Following its suggested status action returned that remote device
claims cannot be inspected through this command. Device actions stopped; the
other session was neither closed nor reused and ADB UI was not used as a bypass.

`device_screenshot` returned the expected black secure-window image. It was not
published or used to claim layout/readability acceptance. The reported empty
URL/key and expanded help state comes from the root handoff, not a successful
new accessibility-tree inspection here.

**Original next step:** the owning root session must release its own device connection.
Then open **PocketLog Dev** with the newly returned bound driver and verify empty
submit, malformed origin/key, password semantics, help, actual keyboard bounds
and large-text scrolling using synthetic values. Do not connect production or
grant capture permissions. No new product UI defect was established while the
driver was blocked; no first-run UI fix or full physical-debugging completion is
claimed. Physical capture/exclusions/Doze/TalkBack/full-day battery and trusted
HTTPS synthetic-server pairing remain unverified in this migration.

## Physical first-run continuation — 2026-11-16

After the root released its own connection, local device discovery and a **new**
bound driver session successfully opened `tw.skyhong.pocketlog.sandbox` on the
physical Pixel 7 / Android 16. All UI actions used that session's `agent-device`
command. No old session was reused and no ADB/SSH UI bypass was used.

### Observed checks

| Check | Physical result |
| --- | --- |
| Fresh setup | Empty URL/key fields, zero-character counter, connect/help controls present in the accessibility tree |
| Empty submit | `請填寫伺服器網址。` appeared |
| HTTP origin | `http://example.invalid` rejected with the HTTPS requirement |
| Origin with path | `https://example.invalid/path` rejected with the no-path requirement |
| Empty key | `https://example.invalid` with no key produced `請貼上裝置金鑰。` |
| Short key | Synthetic `1234` produced the specific 4-of-64 length error |
| Non-hex key | 64 synthetic `g` characters passed the length counter and were rejected with `金鑰只能包含 0–9 與 a–f。` |
| Password semantics | Key node reported `password=true`, masked bullets; keyboard exposed Done and disabled voice input for the field |
| Keyboard, font scale 1.0 | Keyboard frame `(0,1352,1080,1048)`; scroll viewport ended at y=1352. After scrolling, key bounds were `(84,505,912,124)` and submit bounds `(84,874,912,126)`; submit worked with the keyboard open |
| Large text, font scale 2.0 | After relaunch and scrolling with the keyboard open, key bounds `(84,572,912,169)` and submit bounds `(84,884,912,162)` remained above the same keyboard top; submit showed the missing-origin error |
| Help and scrolling | Help expanded at scales 1.0 and 2.0. Its origin/key instructions were exposed by the tree; scrolling reached the final separate-consent explanation at scale 2.0 |
| Secure screenshot | `device_screenshot` returned black protected app pixels. `FLAG_SECURE` was retained; this is **not** visual readability/color/clipping acceptance |
| Cleanup | Restored and read back original font scale 1.0; relaunched sandbox showed empty URL/key and zero counter. Both synthetic password-manager save prompts were declined. Bound driver close succeeded; Device panel close used `shutdown=false` |

The driver briefly exercised scale 3.2 (header and first card tree only) and 1.75
(scrolling only). Those are exploratory observations, not complete acceptance.
Two argument/reference errors were corrected using a current snapshot/reference;
neither was `DEVICE_IN_USE` or an application failure. No raw device tree, screenshot,
private content or key is checked into the repository or attached to an issue.

No first-run product defect was established. The connection attempts above all
failed local validation: no valid credentials were entered, no successful pairing
or authenticated API/model call was attempted, and shared onboarding after
successful pairing was not reached. Password-manager save prompts are observed
third-party/system behavior, not evidence of a PocketLog regression. No capture,
model or Android permission grants were made. The owner application was not
launched, installed over, reset or otherwise modified.

### Bootstrap review and small fix

Reviewed the sandbox build identity, manifest label, guarded installer, development
Containerfile, check script and documented commands. The installer checks actual
APK package metadata before installation and requires an explicit serial; the
Rust check mounts only specified source/manifests/tests/web read-only, and build
limits remain explicit. No safety defect was established in those scripts.

One concrete coverage gap was found in `.github/workflows/check.yml`: CI ran only
the regular debug build/lint, allowing the new sandbox variant to break without a
CI failure. CI now also runs `:app:assembleSandbox :app:lintSandbox`, matching the
existing local Android check. This is a build-coverage fix under #93, not a claim
of a physical UI bug. No production/container deployment configuration changed.

### Continuation checks

- `scripts/dev-check.sh android`: **passed**, including `testDebugUnitTest`,
  `assembleDebug`, `lintDebug`, `assembleSandbox`, `lintSandbox`. Gradle reported
  89 tasks, 2 executed / 87 up-to-date; unit-test/build/lint outputs were reused
  because application sources did not change. The earlier 65-test result and
  zero-error / 16-warning lint reports remain the baseline, not a fresh test run.
- `scripts/dev-check.sh rust`: **passed**, fmt, locked all-target clippy with
  warnings denied, 20 unit + 32 API + 6 classifier-preview tests; cargo-audit
  loaded 1,509 advisories and scanned 185 dependencies without a reported finding.
- `scripts/dev-check.sh web`, shell syntax for both bootstrap scripts and
  `git diff --check`: **passed**. No Docker smoke rerun: deployment inputs did not
  change; the preceding bootstrap smoke result remains the recorded evidence.
- Check logs: `/home/deck/.local/state/pocketlog-dev/2026-11-16-physical-acceptance/`.
  These contain build/check output only; device evidence above is sanitized prose.

**Still open:** visual layout/readability, TalkBack navigation, trusted-HTTPS
synthetic pairing and the four shared onboarding steps, automatic capture and
exclusions, classification/display/API integration, AndroidKeyStore recovery,
revocation, Doze and measured full-day battery acceptance. No Dayflow parity or
routine personal-use readiness claim. Production and old 12-hour schedules were
not accessed or resumed.
