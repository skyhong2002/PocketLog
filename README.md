# PocketLog

**See your day. Understand your rhythm.**

PocketLog is an Android activity journal for reviewing what you did, how you felt,
and which patterns support your wellbeing. It is an independent product; infovore
will be an optional, explicitly authorized integration.

**Development foundation, not a complete Dayflow replacement or a security-audited release.**
The existing infovore installation and APK have not been migrated or replaced.

## What runs today

- Kotlin Android app (`tw.skyhong.pocketlog`): HTTPS server setup, encrypted
  destination-bound credentials using Android Keystore, private daily timeline
  reader, and explicit mood/energy check-ins. No capture permissions are requested.
- Rust API: authenticated batch activity ingest, idempotent retries, clipped daily
  timeline queries, check-ins, full journal deletion, 30-day retention, and quotas.
- SQLite on a persistent Docker volume; no Redis, external database, analytics,
  cloud AI, public feed, or infovore account required.
- Non-root Docker image and Compose with loopback-only port publishing, read-only
  root filesystem, dropped capabilities, memory/CPU limits, and a file-based secret.

Not implemented yet: automatic activity/screenshot collection, offline sync queue,
AI summaries, end-user pairing/per-device revocation, category editing, exports,
backup/restore UI, and infovore integration. The new app does not read the old
infovore phone API. Do not point it at an infovore endpoint.

## Run the development server

Prerequisites: Docker Compose, OpenSSL, and a trusted HTTPS reverse proxy or
Tailscale Serve. One instance currently belongs to **one person**. This is not a
multi-user hosted service and should stay behind your private network/ACLs.

```sh
./scripts/init-secret.sh
docker compose up --build -d
curl --fail http://127.0.0.1:18748/healthz
```

Expose loopback port 18748 through your existing HTTPS proxy or Tailscale Serve.
The Android client requires valid HTTPS, including on Tailscale. Do not publish the
HTTP port to the internet. The token is in `.secrets/owner_token` (not printed by
initialization). Transfer it to the app through a private channel; never a URL,
public QR code, screenshot, log, or Git commit. QR/device pairing is future work.
The secret's parent directory is mode 0700; the bind-mounted file is readable by
the container's non-root UID. Protect copies of this directory just like the DB.

The default server does not call any AI provider. No captures or existing journal
records are copied during setup. Stop with `docker compose down`; this preserves
the journal volume. `docker compose down -v` permanently removes that volume.

## API v1

All `/v1/*` routes require `Authorization: Bearer <64-hex-owner-token>`. `/healthz`
returns only process health. Responses use `Cache-Control: no-store`; there is no
CORS allowance or public data projection. Bodies are capped at 64 KiB.

| Method | Path | Behavior |
|---|---|---|
| POST | `/v1/activities` | `{activities:[{id,start_ms,end_ms,app,category}]}`, max 100 |
| POST | `/v1/checkins` | `{id,at_ms,mood,energy}`, mood/energy 1–5 |
| GET | `/v1/timeline?from_ms=…&to_ms=…` | Up to 26 hours, clipped intervals; bounded results and `truncated` |
| DELETE | `/v1/journal` | Deletes this single owner's activities and check-ins |

Timestamps are Unix milliseconds. Clients choose their local day boundary (the
Android UI uses 04:00 and the phone's current time zone, including DST). Allowed
categories: `unsorted`, `work`, `learning`, `communication`, `entertainment`,
`life_admin`, `rest`. `app` is an application package identifier, not screen text.
Unknown fields, including screenshots, are rejected. IDs are client-generated,
stable across retries. Reusing an activity ID for different content returns 409
and rolls back the whole batch. Check-in IDs support explicit replacement.

Current policy: last 30 days only; expired data is pruned at startup, hourly and
on use. Up to 100,000 activities and 10,000 check-ins; capacity exhaustion refuses
new data without silently evicting retained records. Queries return at most 1,000
of each record type. Clients must display `truncated`, rather than interpret this
response as complete. Retention settings/pagination are future work.

SQLite is **not encrypted by this implementation**. Use an encrypted host disk,
restricted volume access and encrypted backups. Secure deletion on SSDs, backups,
and filesystem snapshots cannot be guaranteed by SQL deletion. The server owner
can read the journal. See [security boundaries](docs/SECURITY.md).

## Development

Android 14+, JDK 17+, Android SDK 36:

```sh
cd android
./gradlew testDebugUnitTest assembleDebug lintDebug
```

Rust stable (minimum 1.90):

```sh
cd server
cargo fmt --check
cargo clippy --locked --all-targets -- -D warnings
cargo test --locked
```

[Architecture](docs/ARCHITECTURE.md) · [Security](docs/SECURITY.md) ·
[Development plan](docs/ROADMAP.md) · [Origin](docs/ORIGIN.md)

Repository is private. No open-source license has been selected yet.
