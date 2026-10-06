#!/usr/bin/env bash
# Converts a JasperReports 6.x JRXML file to the JR 7 syntax through the running API (POST /v1/reports/convert).
# Warnings (what could not be converted faithfully) go to stderr; the result is written next to the input.
#
#   API_KEY=jra_... scripts/jrxml-upgrade.sh report.jrxml [report.v7.jrxml]
#   API_URL (default http://127.0.0.1:8080/api) points at another server.
set -euo pipefail

in="${1:-}"
[[ -f "$in" ]] || { echo "usage: $0 <report.jrxml> [output.jrxml]" >&2; exit 1; }
out="${2:-${in%.jrxml}.v7.jrxml}"
command -v jq >/dev/null || { echo "jq is required" >&2; exit 1; }

url="${API_URL:-http://127.0.0.1:8080/api}/v1/reports/convert"
auth=()
[[ -z "${API_KEY:-}" ]] || auth=(-H "X-API-Key: $API_KEY")

response="$(jq -Rs '{jrxml: .}' "$in" | curl -sS --fail-with-body "${auth[@]}" -H 'Content-Type: application/json' --data-binary @- "$url")" \
  || { echo "conversion failed: $response" >&2; exit 1; }

if [[ "$(jq -r .alreadyCurrent <<<"$response")" == "true" ]]; then
  echo "$in is already in the JR 7 syntax; nothing written" >&2
  exit 0
fi
jq -r .jrxml <<<"$response" > "$out"
count="$(jq '.warnings | length' <<<"$response")"
jq -r '.warnings[] | "warning: " + .' <<<"$response" >&2
echo "wrote $out ($count warning(s))" >&2
