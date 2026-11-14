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
- Automatic collection defaults off. App usage consent, context consent, image
  consent and model consent are separate. Context requires an explicit app
  allowlist; exclusions override it. Structural password/editable-field checks run
  before any screenshot/text capture; incomplete traversal fails closed. Locked
  screens and secure-window screenshot failures reject capture. OS/AppOps behavior
  and permission revocation still require physical-device validation.
- Phone queues/checkpoints/cache use AndroidKeyStore AES-GCM and AtomicFile, with
  filename AAD. Queue max 24 h / 32 MiB / 4,096 entries, including quarantine;
  timeline cache max 30 entries, roughly 2.5 MiB and 30 days. Expiry runs while the
  app/workers execute, not while powered off. Key loss can make local records
  unrecoverable; corrupt files are discarded with a visible drop count.
- Raw context is private, max 24 hours, never readable/exportable by API. Receipts
  retain only payload hashes/IDs for 30-day deduplication. Consumed raw records are
  deleted after derived notes commit. Cleanup occurs per request/worker pass and
  hourly; stopped workers/hosts can delay physical cleanup. The API never decodes
  JPEGs. Worker JPEG decoding bounds dimensions, encoded bytes and allocation.
- Optional model requests require server configuration and per-observation consent.
  Text/images are untrusted evidence; no tools, browsing, shell or API owner token
  are provided to a model. Output schema/length/categories are validated; user
  corrections win. No emotions are inferred. The worker has DB access and any
  explicitly mounted provider key; process separation is not a sandbox against a
  compromised host or decoder. No provider identity binding or provider-side
  deletion is implemented; review/renew consent before changing providers.
- Pausing/removing allowlists or revoking consent clears pending local context,
  not already transmitted server/provider copies. Owner deletion invalidates jobs
  and in-flight results, but cannot retract requests already sent to a model. The
  Android delete action first disables capture and clears local queue/cache.
  Failed server deletion must be retried; other installations can still upload
  with the shared owner token. Rotate/revoke that token when necessary.
- Raw screen content never belongs in logs or infovore. Daily exports contain
  private derived content and are outside app encryption/retention after export.
- Dependency auditing is a development check, not proof of safety. SQLite/native
  libraries and transitive dependencies remain within the supply-chain boundary.

## Required acceptance checks before routine personal use

Device pairing and revocation; physical-device verification of consent, exclusions,
AndroidKeyStore and Doze; full-day battery benchmarks; backup+restore and import;
independent capture/worker threat review; and private integration scopes. Automated
queue, retention, deletion-race and model/decoder tests do not replace these checks.
See [port coverage](INFOVORE_PORT.md) and the roadmap.
