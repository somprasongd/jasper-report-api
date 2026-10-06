#!/usr/bin/env bash
# Prints a temporary (presigned GET) URL for one object on the demo rustfs. Only the URL goes to stdout.
#   scripts/presign-rustfs.sh [s3://bucket/key] [expires-in-seconds]
#   scripts/presign-rustfs.sh s3://reports/samples/single/http_demo.jrxml 300
#
# The signature covers the host name, so sign with the host the API will use to reach the store: from the
# compose network that is rustfs:9000 (PRESIGN_ENDPOINT overrides it). Signing is offline: nothing is sent.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
project="${COMPOSE_PROJECT_NAME:-$(basename "$root" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9\n_-' '_')}"
network="${COMPOSE_NETWORK:-${project}_default}"
access="${S3_ACCESS_KEY:-rustfsadmin}"
secret="${S3_SECRET_KEY:-rustfsadmin}"
endpoint="${PRESIGN_ENDPOINT:-http://rustfs:9000}"
target="${1:-s3://reports/samples/single/http_demo.jrxml}"
expires="${2:-300}"

[[ "$target" == s3://*/* ]] || { echo "usage: $0 [s3://bucket/key] [expires-in-seconds]" >&2; exit 1; }
[[ "$expires" =~ ^[0-9]+$ ]] || { echo "expires-in-seconds must be a number" >&2; exit 1; }

docker run --rm --network "$network" \
  -e AWS_ACCESS_KEY_ID="$access" -e AWS_SECRET_ACCESS_KEY="$secret" -e AWS_DEFAULT_REGION=us-east-1 \
  amazon/aws-cli --endpoint-url "$endpoint" s3 presign "$target" --expires-in "$expires" | tr -d '\r\n'
echo
