#!/bin/sh
# Read-only first-run guidance. Never reads or prints the owner's credential.
set -eu
case "${1:-development}" in
  development) token_file=.secrets/owner_token; compose='docker compose'; mode=development ;;
  production) token_file=.secrets/production_owner_token; compose='docker compose -f compose.yaml -f compose.production.yaml'; mode=production ;;
  *) echo 'Usage: ./scripts/setup-status.sh [development|production]' >&2; exit 2 ;;
esac
printf '\nPocketLog · first steps (%s)\n\n' "$mode"
if [ -s "$token_file" ]; then
  printf '1. Your private owner key is ready: %s\n' "$token_file"
  printf '   Open this file privately on the server when the website asks for your owner key.\n'
  printf '   The key is a password. Do not put it in a URL, screenshot, chat, or Git.\n'
else
  printf '1. Create your private owner key: ./scripts/init-secret.sh %s\n' "$mode"
fi
printf '\n2. Start or update your private service:\n   %s up --build -d\n' "$compose"
if command -v curl >/dev/null 2>&1 && curl --fail --silent --max-time 3 http://127.0.0.1:18748/healthz >/dev/null 2>&1; then
  printf '   A service is responding on this machine at loopback port 18748.\n'
  printf '   This check does not establish which Compose project is running.\n'
else
  printf '   No service responded at loopback port 18748. Check: %s ps\n' "$compose"
fi
printf '\n3. Open the trusted HTTPS website from your private proxy or Tailscale Serve.\n'
printf '   Sign in with the owner key above, then follow the welcome checklist.\n'
printf '\n4. In website Settings, create a journal device key for your phone.\n'
printf '   Install the APK; enter the same HTTPS address and that device key.\n'
printf '   Phone access permissions and recording consent are separate and optional.\n'
printf '\n5. Start with a manual check-in. AI is optional; configure your own provider later.\n'
printf '\nHelp: docs/SETUP.md · Keep your owner key for recovery.\n\n'
