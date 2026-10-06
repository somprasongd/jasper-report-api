#!/usr/bin/env bash
# Creates the "reports" bucket on the demo rustfs (compose profile "dev") and uploads samples/reports/ to it.
#   scripts/seed-rustfs.sh
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
project="${COMPOSE_PROJECT_NAME:-$(basename "$root" | tr '[:upper:]' '[:lower:]' | tr -c 'a-z0-9\n_-' '_')}"
network="${COMPOSE_NETWORK:-${project}_default}"
access="${S3_ACCESS_KEY:-rustfsadmin}"
secret="${S3_SECRET_KEY:-rustfsadmin}"

aws_cli() {
  docker run --rm --network "$network" \
    -e AWS_ACCESS_KEY_ID="$access" -e AWS_SECRET_ACCESS_KEY="$secret" -e AWS_DEFAULT_REGION=us-east-1 \
    -e AWS_REQUEST_CHECKSUM_CALCULATION=when_required -e AWS_RESPONSE_CHECKSUM_VALIDATION=when_required \
    -v "$root/samples/reports:/reports:ro" \
    amazon/aws-cli --endpoint-url http://rustfs:9000 "$@"
}

aws_cli s3 mb s3://reports 2>/dev/null || true
aws_cli s3 sync /reports s3://reports/samples --delete
echo "Uploaded. Use mainReport.url = s3://reports/samples/demo/demo.jrxml"
