#!/usr/bin/env bash
# End-to-end check of presigned URLs against the running demo (make dev-up): sign a temporary URL for a report
# in the private rustfs bucket, hand it to the API as mainReport.url and look at what comes back.
#   scripts/test-presigned.sh            (or: make test-presigned)
set -uo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."

[[ -f .dev-api-key ]] || { echo "No .dev-api-key: run scripts/dev-up.sh first." >&2; exit 1; }
key="$(cat .dev-api-key)"
port="$(sed -n 's/^API_PORT=//p' .env 2>/dev/null | head -1)"; port="${port:-8080}"
api="http://127.0.0.1:${port}/api/v1/reports/render"
object="${PRESIGN_OBJECT:-s3://reports/samples/single/http_demo.jrxml}"
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT

failed=0
pass() { echo "PASS  $1"; }
fail() { echo "FAIL  $1"; failed=$((failed + 1)); }

# render <url> -> HTTP status on stdout; body in $tmp/body, headers in $tmp/headers
render() {
  curl -sS -o "$tmp/body" -D "$tmp/headers" -w '%{http_code}' -X POST "$api" \
    -H "X-API-Key: $key" -H "Content-Type: application/json" \
    -d "{\"mainReport\":{\"url\":\"$1\"},\"parameters\":[{\"name\":\"hn\",\"value\":\"HN001\"}]}"
}
version() { tr -d '\r' < "$tmp/headers" | sed -n 's/^[Xx]-[Rr]eport-[Vv]ersion: //p'; }
expect_status() { # <name> <expected> <actual>
  if [[ "$3" == "$2" ]]; then pass "$1 (HTTP $3)"; else fail "$1: expected HTTP $2, got $3 -> $(head -c 300 "$tmp/body")"; fi
}

url1="$(scripts/presign-rustfs.sh "$object" 300)" || { echo "Could not presign: is the demo up (make dev-up)?" >&2; exit 1; }
echo "object: $object"
echo "url   : ${url1%%\?*}?<signature hidden>"
echo

# 1. a valid presigned URL renders a PDF
code="$(render "$url1")"
if [[ "$code" == 400 ]] && grep -q SOURCE_NOT_ALLOWED "$tmp/body"; then
  echo "The API refuses the storage host. Add HTTP_ALLOWED_HOSTS=rustfs to .env, then: docker compose up -d api" >&2
  exit 1
fi
expect_status "presigned URL renders" 200 "$code"
if [[ "$code" == 200 ]]; then
  [[ "$(head -c 4 "$tmp/body")" == "%PDF" ]] && pass "response is a PDF" || fail "response is not a PDF"
  v1="$(version)"
fi

# 2. a different signature for the same object is the same report version
url2="$(scripts/presign-rustfs.sh "$object" 600)"
[[ "$url2" != "$url1" ]] || fail "second presign returned the same URL"
code="$(render "$url2")"; expect_status "second signature renders" 200 "$code"
[[ -n "${v1:-}" && "$(version)" == "$v1" ]] && pass "same object, same X-Report-Version ($v1)" || fail "X-Report-Version differs between signatures"

# 3. a tampered signature is refused by the storage
last="${url1: -1}"; [[ "$last" == 0 ]] && swap=1 || swap=0
code="$(render "${url1%?}$swap")"; expect_status "tampered signature refused" 502 "$code"
grep -q 'X-Amz' "$tmp/body" && fail "error body echoes the signed URL" || pass "error body does not echo the signature"

# 4. an expired URL is refused
expired="$(scripts/presign-rustfs.sh "$object" 1)"; sleep 3
code="$(render "$expired")"; expect_status "expired URL refused" 502 "$code"

# 5. without a signature the bucket is private
code="$(render "${url1%%\?*}")"; expect_status "unsigned URL refused (bucket is private)" 502 "$code"

echo
if [[ $failed -eq 0 ]]; then echo "All presigned URL checks passed."; else echo "$failed check(s) failed."; exit 1; fi
