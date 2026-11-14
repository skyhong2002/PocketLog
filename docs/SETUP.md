# First time here?

PocketLog has two parts: your private website stores and reviews the journal;
the Android app can optionally collect phone activity. Start on the website.
AI and automatic recording have separate consent. Manual mood entry is removed.
After entering a journal device key on Android, press「驗證並連線」. Back on the
website,「重新檢查連線」shows whether an active journal key has ever passed
verification. This is separate from first activity arrival and phone recording
permissions; an offline phone or delayed Android sync may still have a past
verification timestamp.

1. The person who deployed the server holds its **owner key**, which is the
   website password. The initializer creates it in `.secrets/owner_token` for
   development or `.secrets/production_owner_token` for production. Open that
   file privately on the server; never add its contents to a URL or screenshot.
   If someone else deployed it, ask them for private access. Device keys cannot
   sign into the owner website, and this single-owner service has no public signup.
2. Open your server's trusted HTTPS address and sign in. Complete the welcome
   steps, choose your goals, and leave AI off until you want it.
3. In website Settings, issue a **journal device key** for the phone. Install
   the APK and enter the same HTTPS address plus this device key. Keep the owner
   key for recovery instead of sharing it across phones.
4. Enable phone recording only if desired, granting
   its separate Android permissions. Check the first activity after sync; Android
   background scheduling can delay it. The browser does not collect phone activity.

On the deployment machine, `./scripts/setup-status.sh production` checks local
readiness and prints next steps without reading or revealing your key. Use
`development` for the default Compose instance. Details follow below.

# Run the development server

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

## Personal production instance

This runs the current development implementation as a persistent single-owner
service with API and worker. Capture remains off until enabled on Android, and
no model provider is configured by default. Use separately issued device keys for Android; physical-device acceptance remains required. See
[security boundaries](SECURITY.md) for the remaining acceptance work.

```sh
./scripts/init-secret.sh production
docker compose -f compose.yaml -f compose.production.yaml up --build -d
curl --fail http://127.0.0.1:18748/healthz
tailscale serve --bg --https=12446 http://127.0.0.1:18748
```

Choose an unused Tailscale HTTPS port. In the app, use
`https://YOUR-MACHINE.YOUR-TAILNET.ts.net:12446` and the token stored in
`.secrets/production_owner_token`. Keep the token out of URLs, public files and
Git. This is Tailscale Serve (tailnet access only), not Funnel.

The production Compose project is `pocketlog-production`; its journal volume
and token are separate from the default development project. Both configurations
use loopback port 18748, so do not run them simultaneously without changing a
binding. The service restarts automatically after host reboot via Docker's
`unless-stopped` policy; Tailscale's `--bg` configuration persists too.

Use the same two `-f` options for future updates, status, restart and shutdown.
Never add `-v` to a production shutdown. SQLite is not application-encrypted;
host encryption and independently protected, tested backups remain the owner's
deployment responsibilities.


## Enable phone recording

Install the new APK over the existing app to retain its Keystore connection.
Open **紀錄、排除與同步設定**, consent to app usage recording, and grant Android's
Usage Access permission. Only activity after consent is eligible; no infovore
history is imported. The collector runs at most once per 15 minutes when work is
scheduled. Android controls actual timing, especially under Doze.

For context, choose a small app allowlist. In Android Settings →「排除與允許清單」,
「從已安裝 app 選擇」searches launcher app names or full package names without
granting broad package visibility. Selections survive searches; Cancel discards
the temporary edits.「填入清單」only fills the form; press「儲存清單」to apply it.
Manually entered packages outside the launcher list are preserved. Then read
and accept the separate context notice, then enable the PocketLog Accessibility
service in Android settings. Thumbnail capture and model processing each have
additional opt-ins. Exclusions take precedence over allowlists; Settings, Support
and capture diagnostics warn when consent is on but no allowed app is eligible.
The eligible count is a package-rule check, not proof of permissions or successful
capture. Built-in sensitive-app matching is incomplete: explicitly exclude your
sensitive apps even if their package names do not look financial or private.
Image capture is experimental until a full-day device battery run is recorded.
Do not enable banking, authentication or private messaging apps just to increase
coverage. Review [capture limits](INFOVORE_PORT.md) and [analysis setup](ANALYSIS.md).

The default networking policy is unmetered only, not necessarily Wi-Fi. You can
explicitly permit metered mobile data. Pending/rejected counts, expiry drops,
manual retry and deletion are visible in settings. Queues expire after 24 hours,
including rejected records; preserve important manual notes by syncing in time.
Changing servers requires the local-clear action, which stops capture and discards
pending uploads/cache before entering another endpoint/token.

## Update and verify

Back up the SQLite database using SQLite's online backup API into protected,
encrypted storage before an upgrade; do not copy a live database file without its
WAL. Keep backups outside Git/APK shares. Run the Rust and Android checks and
`python3 scripts/smoke-private.py` against a freshly built
`pocketlog:implementation-check` image. The smoke test creates/removes its own
random Docker volume and credentials, never the production volume.

Update production using the existing Compose project and volume. The schema adds
tables/indexes without rewriting existing activities/check-ins. Verify health,
authenticated timeline/status, original record counts and both services afterward.
The worker receives no owner-token secret. Do not enable a provider by merely
adding a key; both the explicit server switch and per-capture client consent are
required. No new production token is needed for this backward-compatible update.

## Private website and bring your own AI

Open the same trusted HTTPS origin used by Android. Sign in with the owner token;
it becomes a Secure/HttpOnly eight-hour session cookie, never a query parameter
or localStorage value. The public shell has no journal. Restart logs browsers out.
Complete onboarding in either client; the other reads its shared completion,
goals and category settings. Each phone still needs its own OS permissions and
separate capture/image/model consent. Browser use does not start screen capture.

The base Compose config mounts `ai_settings:/config` writable for the API and
read-only for the worker, setting `POCKETLOG_AI_CONFIG_DIR=/config` on both.
Configure an OpenAI-compatible, native Anthropic Messages or Gemini generateContent endpoint/model via Settings.
Use the synthetic test, then explicitly approve future phone context for that
provider. Local models are addressed from the server/container network, not the
phone; verified HTTPS or literal loopback HTTP only. No CLI model process executes. See [analysis](ANALYSIS.md).

Back up both private volumes on encrypted storage. The config volume contains the
plaintext provider key (directory0700/file0600); do not publish it or include it in
source archives. Journal deletion preserves workspace/provider setup; disable AI
separately to remove its key. Neither operation can remove copied exports/backups
or requests already delivered to an AI provider.


For a phone, sign into the website with the owner token, open Settings → device
keys, and issue a **journal** credential with a short expiry. Enter that credential
in Android instead of sharing the owner token across installations. Device keys
cannot log into the owner website or manage credentials. A collector credential
is for bounded upload tools and cannot display a journal. Revocation is immediate
for new server requests; Android stops capture when sync discovers invalid auth.
Keep the owner token as recovery; rotating it invalidates all device keys on restart.

Daily/weekly AI briefs and background schedules have separate consent. Configure
IANA timezone and time (04:00–23:59); confirm ongoing derived-card transmission and
possible costs. Changing model configuration requires renewed schedule consent.
No schedule is enabled during upgrade. Deleting the journal disables it.

Short-term screenshot replay requires its own Android opt-in in addition to image
capture. Analysis-only images are never made readable retroactively. At most 256
worker-sanitized thumbnails are kept for 24 hours and can be individually deleted.
Privacy Shield conservatively skips visible text with suspected secrets before
pixels; it is not OCR pixel redaction and does not replace exclusions/allowlists.
