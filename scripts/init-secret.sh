#!/bin/sh
set -eu
# The directory is private; the bind-mounted file must be readable by container UID 10001.
umask 077
mkdir -p .secrets
chmod 0700 .secrets
if [ -e .secrets/owner_token ]; then
  echo 'Owner token already exists; left unchanged.'
  exit 0
fi
openssl rand -hex 32 > .secrets/owner_token
chmod 0444 .secrets/owner_token
echo 'Created .secrets/owner_token inside private .secrets directory. Token not printed.'
