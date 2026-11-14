#!/bin/sh
set -eu
case "${1:-development}" in
  development) token_file=.secrets/owner_token ;;
  production) token_file=.secrets/production_owner_token ;;
  *) echo 'Usage: ./scripts/init-secret.sh [development|production]' >&2; exit 2 ;;
esac
# The directory is private; the bind-mounted file must be readable by container UID 10001.
umask 077
mkdir -p .secrets
chmod 0700 .secrets
if [ -e "$token_file" ]; then
  echo 'Owner token already exists; left unchanged.'
  exit 0
fi
openssl rand -hex 32 > "$token_file"
chmod 0444 "$token_file"
echo "Created $token_file inside private .secrets directory. Token not printed."
