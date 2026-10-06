#!/usr/bin/env bash
# Generates an API key for one client and prints the hash to put into the configuration.
# The plain key is shown ONCE: give it to the client; the API only ever stores the SHA-256.
#
#   scripts/api-key.sh <client-id> [index]
set -euo pipefail

client="${1:-}"
index="${2:-0}"
[[ -n "$client" ]] || { echo "usage: $0 <client-id> [index]" >&2; exit 1; }
[[ "$client" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "client-id may contain only letters, digits, . _ -" >&2; exit 1; }

key="jra_$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=\n')"
if command -v sha256sum >/dev/null; then
  hash="$(printf '%s' "$key" | sha256sum | cut -d' ' -f1)"
else
  hash="$(printf '%s' "$key" | shasum -a 256 | cut -d' ' -f1)"
fi

cat <<OUT
Client : $client
API key: $key        <- give this to the client (header "X-API-Key"); it cannot be recovered later

Put the hash on the server, either as environment variables (.env):

REPORT_SECURITY_API_KEYS_${index}_CLIENT_ID=$client
REPORT_SECURITY_API_KEYS_${index}_SHA256=$hash

or in config/application.yml:

report:
  security:
    api-keys:
      - client-id: $client
        sha256: $hash
OUT
