# Security boundaries

This development repository has not had an independent security audit. Please
report suspected vulnerabilities privately through the repository owner.

## Implemented

- No analytics, crash uploads, public journal endpoint, implicit external inference, shell
  execution, URL fetching, WebView or cross-origin API permission.
- One owner token, 32 random bytes encoded as 64 hex characters, supplied in a
  mounted secret file. The process retains a SHA-256 digest; comparison uses a
  constant-time primitive. Missing/weak-format configuration prevents startup.
  Format checks cannot measure entropy: use the supplied random initializer.
- All journal routes authenticate before JSON/query parsing. Token never belongs
  in a URL. No request/content logs. Generic storage failures avoid leaking SQL,
  filesystem paths and journal content. Health discloses process liveness only.
- Strict schemas, SQL parameters, 64 KiB input limit, per-batch and storage quotas,
  bounded query results, 10-second API timeout, eight outstanding DB operations.
  Blocking SQLite work stays off Tokio runtime threads. A timed-out write might
  still finish; stable request IDs make activity retries safe.
- Android uses HTTPS with system certificate verification anywhere; plain `http://` is
  accepted only for private-network hosts (the server's `private_network_host` rule:
  loopback, RFC 1918, 100.64/10, IPv6 ULA/link-local, `localhost`, `*.local`, `*.lan`,
  `*.internal`, `*.home.arpa`, single-label names), checked before every request so the
  device key is never sent over public HTTP. The network security config permits
  cleartext only so that this app check can decide; user-added CAs are not trusted.
  An http connection shows 「這個連線沒有 HTTPS，資料保護依賴你的 VPN／私人網路」. It refuses redirects,
  stores endpoint+token together using Android Keystore AES-GCM, excludes app
  data from backups/device transfer. Blocking screenshots of PocketLog itself
  (`FLAG_SECURE` on the activity and every dialog) is an explicit setting,
  「防止截圖這個 app」 in 設定 › 資料, **off by default** (owner decision 2026-11-17).
  Screen security flags do not protect against a rooted device or a camera.
- Container runs as UID/GID 10001, drops all capabilities, has a read-only root
  filesystem and bounded resources. The journal and dedicated AI settings volumes are writable/persistent for the API;
  the worker mounts AI settings read-only.
  The HTTP host port binds to loopback, for a TLS proxy (Caddy, nginx, Tailscale Serve…).
  Plain HTTP is supported only on private-network addresses for owners whose VPN
  (ZeroTier, WireGuard, a LAN) has no trusted certificate: the phone refuses public `http://`
  origins, the website needs the explicit `POCKETLOG_PRIVATE_HTTP=true` (non-`__Host-`,
  non-`Secure` session cookie), and confidentiality then depends on the VPN. AI endpoints
  follow the same rule so self-hosted OpenAI-compatible proxies (CLIProxyAPI, Ollama) work.

## Trust and known limitations

- Single owner only. Owner token grants all capabilities and browser login. Owner-issued
  device keys expire in 1–90 days and can be revoked individually. Journal keys have
  private journal/model access but cannot manage devices or create owner browser
  sessions; collector keys can only upload activities/context and read collection/model
  configuration. Only SHA-256 digests are stored, and keys are returned once. Owner
  rotation invalidates all device keys on service startup. Rotate the owner token
  by replacing the secret file with a fresh random value and recreating the
  container; update each client. Keep the generated token private. The owner-only
  device list includes one best-effort latest permitted-authentication timestamp,
  coarsened to a minute. It records no paths, IPs, request history or content and
  does not establish upload/capture success. It survives journal deletion with
  device configuration and is removed when the key is revoked/pruned or rotated.
- No built-in TLS termination or public-internet abuse protection. Keep behind
  VPN ACLs (Tailscale, Headscale, ZeroTier, WireGuard…) or an authenticated/private ingress. Do not expose raw
  container networking as a public service. Host firewall and proxy request/header,
  connection and rate limits remain necessary. Docker is not a VM security boundary.
- SQLite is plaintext inside the host volume. Use full-disk/volume encryption,
  restricted host accounts, encrypted backups and a tested restore process. The
  server administrator, a compromised host, or an opted-in AI provider can access
  whatever data is sent there. No claim of E2EE against server-side analysis.
- Deletion uses SQL secure_delete and a WAL checkpoint; backups, snapshots,
  flash wear leveling and copied exports may still retain data. Retention is
  enforced while the service runs, not while a host is powered off.
- Automatic collection defaults off. App usage consent and context consent are
  separate on the phone; granting context also turns on screenshots by default
  (Dayflow-style, disclosed in the same dialog) and screenshots can be turned off
  separately; model use is an owner server switch. Context requires an explicit app
  allowlist; exclusions override it. Built-in sensitive-app exclusions cover known
  packages and package-name patterns, not every sensitive app; explicitly exclude
  your financial, authentication and other private apps. Structural password/editable-field checks run
  before any screenshot/text capture; incomplete traversal fails closed. Locked
  screens are never captured. A window that Android refuses to screenshot because it
  is secure/DRM-protected (`ERROR_TAKE_SCREENSHOT_SECURE_WINDOW`) is no longer
  rejected outright: PocketLog never tries to bypass the protection, sends the app and
  its visible text with `protected_screen:true` and no image, and the card says
  受保護畫面（無截圖）. Mostly-black frames are never stored or uploaded. Existing
  context grants still deduplicate unchanged captures unless the owner explicitly
  enables continuous recording in Android settings. New context consent discloses
  continuous recording, which retains unchanged captures at the same cadence and
  may increase power, storage and upload costs. Quotas and privacy gates are unchanged. OS/AppOps behavior
  and permission revocation still require physical-device validation. UsageStats
  checkpoints retain observed permission state and restart their scan boundary
  when that state changes, including on app resume. A revoke-and-restore sequence
  entirely while the app is not executing is not detectable from the later
  current AppOps grant; do not treat this as continuous permission-history proof.
- Phone queues/checkpoints/cache use AndroidKeyStore AES-GCM and AtomicFile, with
  filename AAD. Queue max 1 GiB / 32,768 entries including quarantine; screen
  captures (`/v1/observations`) are kept up to 72 h because they upload only while
  charging (or on an unmetered network once more than 256 MB are waiting), and the
  server accepts context up to 72 h old; every other queued write keeps 24 h. Over
  the byte/record cap the oldest captures are dropped before app sessions or edits.
  Screenshot entries are stored separately so edit reads never decrypt them;
  timeline cache max 30 entries, roughly 2.5 MiB and 30 days. Expiry runs while the
  app/workers execute, not while powered off. Key loss can make local records
  unrecoverable; corrupt files are discarded with a visible drop count.
- Analysis-only raw context is private and retained on the server (text
  permanently; screenshots until the owner storage budget evicts the oldest), never
  readable/exportable by API. Context is sampled every 10 seconds and on app switches;
  screenshots are at most 4,096 px per edge, 8.5 MP and 300,000 bytes. Android
  prefers native-resolution lossless WebP and falls back to JPEG, reducing
  dimensions if needed.
  Independent per-image `replay_consent` can additionally create a separate private
  archive (the newest 256 images from the last 24 hours). An archive at capacity
  discards its oldest copy and does not reject the analysis upload. Only
  worker-decoded and re-encoded images
  become readable, with metadata stripped and a 300,000-byte image cap. Legacy
  uploads still produce 480 px / 24 KiB JPEGs. No implicit archive of old
  images. Replay requires owner/journal-device authentication, remains no-store,
  is excluded from ordinary exports and is erased with the journal. This is not OCR
  masking. Default-on local suspected-secret text checks reject the whole window
  before pixels, with false positives and no guarantee for secrets rendered only
  as pixels. Receipts
  retain payload hashes/IDs permanently for deduplication. Analyzed raw records are
  marked processed, not deleted. Budget enforcement runs each worker pass; a stopped
  worker delays screenshot eviction, and freed SQLite pages are reused rather than
  returned to the filesystem. The API never decodes
  images. Worker decoding bounds dimensions, pixel count, encoded bytes and allocation.
- Optional model requests require server configuration and the owner's
  provider-bound automatic switch (legacy per-observation consent bound to the
  current provider is still honored). Cleaned card titles are also sent for topic
  labels while the switch is on.
  Text/images are untrusted evidence; no tools, browsing, shell or API owner token
  are provided to a model. Output schema/length/categories are validated; user
  corrections win. No emotions are inferred. The worker has DB access and any
  explicitly mounted provider key; process separation is not a sandbox against a
  compromised host or decoder. Saving any model setting rotates its identity and
  turns the automatic switch off until the owner re-enables it; turning it off
  invalidates jobs and discards in-flight results. No provider-side deletion is implemented.
- Experimental video input is off by default and requires explicit owner approval
  before the operator enables it. It sends the usable screenshots from the batch
  to the already-configured provider instead of sampling six. The worker runs a
  fixed FFmpeg command without a shell, consuming only worker-encoded frames via
  stdin; its bounded MP4 stays in memory and is never written to the journal.
  The provider may retain its received copy according to its own policy.
- Pausing/removing allowlists or revoking consent clears pending local context,
  not already transmitted server/provider copies. Owner deletion invalidates jobs
  and in-flight results, but cannot retract requests already sent to a model. The
  Android delete action first disables capture and clears local queue/cache.
  Shared server pause rejects new captures and clears unanalyzed server context
  (analyzed journal history is retained);
  phones apply it on the existing sync cadence and clear queued capture data.
  Offline phones, including a failed private-server connection, use their last known state until reconnection, not instant remote
  control. Known invalid/revoked device credentials stop local capture after sync.
  Transient control-read failures and server backoff preserve the normal local
  UsageStats cadence under existing consent/pause and encrypted queue bounds;
  no uploads occur on those paths. Confirmed authentication failure still stops
  capture, and confirmed pause clears queued capture. Retention is not extended.
  Failed server deletion must be retried; other installations can still upload
  with the shared owner token. Rotate/revoke that token when necessary.
- In-app updates (release builds only, at most every 12 h or on request) contact only
  `api.github.com` and GitHub's download hosts over HTTPS (redirects are followed only to GitHub
  hosts, 150 MB cap), send no journal data or credentials, refuse a release without a SHA-256
  digest, verify the APK before installing through `PackageInstaller`, and rely on Android's
  same-signing-key rule; release notes are shown as plain text.
- Raw screen content never belongs in logs or infovore. Daily exports contain
  private derived content and are outside app encryption/retention after export.
- Dependency auditing is a development check, not proof of safety. SQLite/native
  libraries and transitive dependencies remain within the supply-chain boundary.

## Required acceptance checks before routine personal use

Physical-device pairing and revocation verification; physical-device verification of consent, exclusions,
AndroidKeyStore and Doze; full-day battery benchmarks; backup+restore and import;
independent capture/worker threat review; and private integration scopes. Automated
queue, retention, deletion-race and model/decoder tests do not replace these checks.
See [port coverage](INFOVORE_PORT.md) and the roadmap.

## Browser, workspace and BYO AI configuration

The same-origin browser UI uses random, hashed, in-memory sessions (eight hours,
32 maximum), Secure/HttpOnly/SameSite=Strict host cookies, no CORS and a required
non-simple header on browser writes. Browser login also requires this header;
cross-site Fetch Metadata is rejected. No owner token in URLs or browser storage.
Session state is lost on restart, and owner-token rotation requires recreating the
service. The owner token still grants all journal capabilities. Use a trusted
HTTPS origin; a non-TLS remote site cannot use the secure session cookie.

CSP restricts page scripts/styles/images/connections to the same origin and
disallows framing, external base URLs and external form targets. User/model
content is inserted as text, never interpreted as HTML, Markdown, links or tools.
The public shell contains no journal. Authenticated responses remain no-store.

BYO settings use a separate volume, directory 0700/file 0600 with atomic rename.
Keys are plaintext to the host administrator, outside SQLite and exports, never
read back by the API. Endpoint changes do not reuse a previous key; model changes
on the same endpoint may retain it. Tombstones disable legacy env fallback. A
failed/ambiguous save should be followed by a redacted settings read; filesystem
rename and SQLite commit are not a single cross-resource transaction. Provider
identity checks prevent old consent from authorizing a new configuration even
if the database commit fails. An already sent request cannot be recalled.

Chat requires consent on every request for the current provider identity. It
sends at most 100 derived activity cards/24,000 bytes of evidence and a 1,000-char
question, with no raw observations, images, reflections or check-ins. Optional
conversation/request IDs persist 500 pairs across 100 conversations for 30 days,
with at most six pairs/6,000 characters of same-provider dialogue per call. No
past dialogue is sent after provider identity changes. Completed request IDs replay
the saved answer without another model call. Model calls use an 8-second
request limit, a 64 KiB response bound and one concurrent explicit test/chat call.
Deletion/provider epoch changes invalidate in-flight answers.

Explicit model connection tests require the confirmed saved provider identity and
use that same loaded configuration. Changed identities are rejected before test
networking; clients require another confirmation after refreshing settings. Only
fixed synthetic text is sent, with no journal evidence. A request already sent to
the approved provider cannot be recalled. New clients check the advertised bound
probe capability before testing against older servers.

Shared onboarding is not collection consent. User-started focus, review and agent
summary records have quotas and 30-day retention and participate in journal
deletion. Agent files are chosen explicitly and parsed locally before per-turn approval.
Only selected text is uploaded; tool outputs, thinking, attachments and path/env
metadata are not imported. Sessions have revision/size/count/retention bounds and
are excluded from all model evidence. No desktop session tree is scanned, no shell
command executes, and no agent session path is opened.

Separate provider-bound brief scheduling consent is default off. It sends only
bounded derived cards, at most two periods/pass with three durable attempts each.
IANA timezone/DST determines completed periods. Provider changes stop the old
schedule; journal deletion disables it and invalidates in-flight results. Owner
configuration survives deletion, while all conversations, briefs, focus timers,
agent sessions and replay images are erased. Scheduled outputs are plain text,
never executable tools/HTML.
