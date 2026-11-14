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
  filesystem and bounded resources. The journal and dedicated AI settings volumes are writable/persistent for the API;
  the worker mounts AI settings read-only.
  The HTTP host port binds to loopback, for a TLS proxy or Tailscale Serve.

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
- Analysis-only raw context is private, max 24 hours, never readable/exportable by API.
  Independent per-image `replay_consent` can additionally create a separate private
  archive (256 images / 24 hours). Only worker-decoded, resized/re-encoded JPEGs
  (480 px, 24 KiB, stripped metadata) become readable. No implicit archive of old
  images. Replay requires owner/journal-device authentication, remains no-store,
  is excluded from ordinary exports and is erased with the journal. This is not OCR
  masking. Default-on local suspected-secret text checks reject the whole window
  before pixels, with false positives and no guarantee for secrets rendered only
  as pixels. Receipts
  retain only payload hashes/IDs for 30-day deduplication. Consumed raw records are
  deleted after derived notes commit. Cleanup occurs per request/worker pass and
  hourly; stopped workers/hosts can delay physical cleanup. The API never decodes
  JPEGs. Worker JPEG decoding bounds dimensions, encoded bytes and allocation.
- Optional model requests require server configuration and per-observation consent.
  Text/images are untrusted evidence; no tools, browsing, shell or API owner token
  are provided to a model. Output schema/length/categories are validated; user
  corrections win. No emotions are inferred. The worker has DB access and any
  explicitly mounted provider key; process separation is not a sandbox against a
  compromised host or decoder. Provider-bound consent prevents old queued captures from being sent to a new
  configuration. Saving any model setting rotates its identity and requires renewed
  capture consent. No provider-side deletion is implemented.
- Pausing/removing allowlists or revoking consent clears pending local context,
  not already transmitted server/provider copies. Owner deletion invalidates jobs
  and in-flight results, but cannot retract requests already sent to a model. The
  Android delete action first disables capture and clears local queue/cache.
  Shared server pause rejects new captures and clears pending server context;
  phones apply it on the existing sync cadence and clear queued capture data.
  Offline phones use their last known state until reconnection, not instant remote
  control. Known invalid/revoked device credentials stop local capture after sync.
  Failed server deletion must be retried; other installations can still upload
  with the shared owner token. Rotate/revoke that token when necessary.
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
