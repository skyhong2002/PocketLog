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
service. It does not add automatic capture, device pairing or AI. See
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

