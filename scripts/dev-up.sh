#!/usr/bin/env bash
# One-command demo: PostgreSQL with sample data + rustfs + the API, the sample report uploaded to rustfs.
#   scripts/dev-up.sh          (or: make dev-up)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

port="${API_PORT:-8080}"

if [[ ! -f .env ]]; then
  out="$(scripts/api-key.sh demo 0)"
  key="$(sed -n 's/^API key: \(jra_[^ ]*\).*/\1/p' <<<"$out")"
  hash="$(sed -n 's/^REPORT_SECURITY_API_KEYS_0_SHA256=//p' <<<"$out")"
  cat > .env <<ENV
API_KEY_MODE=required
REPORT_SECURITY_API_KEYS_0_CLIENT_ID=demo
REPORT_SECURITY_API_KEYS_0_SHA256=$hash
OPD_DB_URL=jdbc:postgresql://postgres:5432/hosv4
OPD_DB_USER=report_ro
OPD_DB_PASSWORD=report_ro
REPORTS_DIR=./samples/reports
S3_ENDPOINT=http://rustfs:9000
S3_ACCESS_KEY=rustfsadmin
S3_SECRET_KEY=rustfsadmin
S3_ALLOWED_BUCKETS=reports
API_PORT=$port
ENV
  echo "$key" > .dev-api-key
  chmod 600 .env .dev-api-key
  echo "Created .env and .dev-api-key (demo API key; both are git-ignored)."
fi
[[ -f .dev-api-key ]] || { echo "Missing .dev-api-key: delete .env and run again to generate a demo key." >&2; exit 1; }
port="$(sed -n 's/^API_PORT=//p' .env | head -1)"; port="${port:-8080}"

docker compose --profile dev up -d --build
echo -n "Waiting for the API"
for _ in $(seq 1 60); do
  curl -fsS -o /dev/null "http://127.0.0.1:${port}/api/healthz" 2>/dev/null && break
  echo -n "."; sleep 2
done
echo
scripts/seed-rustfs.sh

key="$(cat .dev-api-key)"
cat <<OUT

Ready. Try it:

  curl -X POST http://127.0.0.1:${port}/api/v1/reports/render \\
    -H "X-API-Key: ${key}" -H "Content-Type: application/json" \\
    -d '{"mainReport":{"url":"s3://reports/samples/demo/demo.jrxml"},"parameters":[{"name":"hn","value":"HN001"}]}' \\
    -o demo.pdf && open demo.pdf

(the same report from the mounted folder: "url":"demo/demo.jrxml")
Stop with: make dev-down
OUT
