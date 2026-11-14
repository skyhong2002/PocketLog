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

