# Security boundaries

This development repository has not had an independent security audit. Please
report suspected vulnerabilities privately through the repository owner.

## Implemented

- No analytics, crash uploads, public journal endpoint, external inference, shell
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
- Android requires HTTPS and system certificate verification, refuses redirects,
  stores endpoint+token together using Android Keystore AES-GCM, excludes app
  data from backups/device transfer, and blocks normal app screenshots. Screen
  security flags do not protect against a rooted device or a camera.
- Container runs as UID/GID 10001, drops all capabilities, has a read-only root
  filesystem and bounded resources. Only the journal volume is writable/persistent.
  The HTTP host port binds to loopback, for a TLS proxy or Tailscale Serve.

## Trust and known limitations

- Single owner only. Owner token grants read, write and delete. Independent
  upload/read/device credentials, pairing expiration, device revocation and a
  recovery flow are required before broader distribution. Rotate today's token
  by replacing the secret file with a fresh random value and recreating the
  container; update each client. Keep the generated token private.
- No built-in TLS termination or public-internet abuse protection. Keep behind
  Tailscale ACLs or an authenticated/private HTTPS ingress. Do not expose raw
  container networking as a public service. Host firewall and proxy request/header,
  connection and rate limits remain necessary. Docker is not a VM security boundary.
- SQLite is plaintext inside the host volume. Use full-disk/volume encryption,
  restricted host accounts, encrypted backups and a tested restore process. The
  server administrator, a compromised host, or an opted-in AI provider can access
  whatever data is sent there. No claim of E2EE against server-side analysis.
- Deletion uses SQL secure_delete and a WAL checkpoint; backups, snapshots,
  flash wear leveling and copied exports may still retain data. Retention is
  enforced while the service runs, not while a host is powered off.
- No automatic collection, raw image/text ingestion or AI is implemented here.
  Before those features ship, enforce exclusion before capture, encrypt bounded
  queues, isolate image decoding/inference, reject oversized/decompression-bomb
  payloads and enforce raw-data TTLs even on failed jobs.
- AI input is untrusted evidence, never instructions. Planned workers get no
  tools, outbound browsing, shell, token access or authority to act. Validate
  structured output, label uncertainty and allow user correction. Raw screen
  content must never enter logs, exception reports, public exports or infovore.
- Dependency auditing is a development check, not proof of safety. SQLite/native
  libraries and transitive dependencies remain within the supply-chain boundary.

## Required acceptance checks before routine personal use

Device pairing and revocation; encrypted/bounded offline queues; consent/pause and
sensitive-app exclusions; battery benchmarks on an actual phone; backup+restore
and deletion tests; capture/worker threat review; export/import; private integration
scopes; and dependency vulnerability review. These are tracked in the roadmap.
