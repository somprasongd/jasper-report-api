#!/usr/bin/env bash
# Migration check for a folder of JasperReports 6.x reports, run against a running API:
#   1. converts every .jrxml with POST /v1/reports/convert and writes the result to <reports-dir>/<subdir>/
#   2. POST /v1/reports/validate on each converted main report (compiles it, lists parameters and warnings)
#   3. renders it when <name>.request.json exists next to the source, and when <reference-dir>/<name>.pdf exists
#      (made once by the old system) compares page count, text and page images with the new PDF.
#
#   API_KEY=jra_... scripts/jrxml-migrate-check.sh <source-dir> <reports-dir> [reference-dir]
#
# <reports-dir> must be the folder the API reads (report.sources.local.root); results go to <reports-dir>/migrated/
# (SUBDIR to change). <name>.request.json holds the rest of the render request, e.g.
#   {"datasource":"opd","parameters":[{"name":"hn","value":"HN001"}]}
# Needs curl, jq, pdftotext and pdfinfo (poppler); pdftoppm and ImageMagick's `compare` add the page-image check.
# Environment: API_URL (default http://127.0.0.1:8080/api), API_KEY, SUBDIR (migrated), PIXEL_TOLERANCE (percent of
# pixels allowed to differ per page, default 1), OUT (default ./migrate-check-out: new PDFs and image diffs).
set -o pipefail

src="${1:-}"; reports="${2:-}"; reference="${3:-}"
[[ -d "$src" && -d "$reports" ]] || { sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//' >&2; exit 2; }
for tool in curl jq pdftotext pdfinfo; do command -v "$tool" >/dev/null || { echo "$tool is required" >&2; exit 2; }; done

api="${API_URL:-http://127.0.0.1:8080/api}"
subdir="${SUBDIR:-migrated}"
tolerance="${PIXEL_TOLERANCE:-1}"
out="${OUT:-./migrate-check-out}"
target="$reports/$subdir"
mkdir -p "$target" "$out"
auth=(); [[ -z "${API_KEY:-}" ]] || auth=(-H "X-API-Key: $API_KEY")

post() { # post <path> <json-body-file> <output-file>  -> prints the HTTP status
  curl -sS -o "$3" -w '%{http_code}' "${auth[@]}" -H 'Content-Type: application/json' --data-binary @"$2" "$api$1"
}

# everything that is not a report (images, message bundles, ...) goes along unchanged
(cd "$src" && find . -type f ! -name '*.jrxml' ! -name '*.request.json' ! -name '.*' -print0 | while IFS= read -r -d '' f; do
  mkdir -p "$target/$(dirname "$f")"; cp "$f" "$target/$f"; done)

tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
declare -a rows; failed=0

for file in "$src"/*.jrxml; do
  [[ -e "$file" ]] || { echo "no .jrxml files in $src" >&2; exit 2; }
  name="$(basename "$file" .jrxml)"; notes=(); verdict="ok"

  jq -Rs '{jrxml: .}' "$file" > "$tmp/convert.json"
  code="$(post /v1/reports/convert "$tmp/convert.json" "$tmp/convert.out")"
  if [[ "$code" != 200 ]]; then
    rows+=("FAIL  $name  convert: $(jq -r '.detail // .' "$tmp/convert.out" | head -c 200)"); failed=$((failed+1)); continue
  fi
  jq -r .jrxml "$tmp/convert.out" > "$target/$name.jrxml"
  warnings="$(jq '.warnings | length' "$tmp/convert.out")"
  [[ "$(jq -r .alreadyCurrent "$tmp/convert.out")" == true ]] && notes+=("already JR 7")
  [[ "$warnings" == 0 ]] || { notes+=("$warnings convert warning(s)"); verdict="check"; jq -r '.warnings[] | "      ! " + .' "$tmp/convert.out" >&2; }

  request="$src/$name.request.json"
  base='{"mainReport":{"url":"'"$subdir/$name.jrxml"'"}}'
  jq -c --argjson base "$base" '$base + (. // {})' "${request}" 2>/dev/null > "$tmp/request.json" || echo "$base" > "$tmp/request.json"

  code="$(post /v1/reports/validate "$tmp/request.json" "$tmp/validate.out")"
  if [[ "$code" != 200 ]]; then
    rows+=("FAIL  $name  validate: $(jq -r '.detail // .' "$tmp/validate.out" | head -c 200)"); failed=$((failed+1)); continue
  fi
  vw="$(jq '.warnings | length' "$tmp/validate.out")"
  [[ "$vw" == 0 ]] || { notes+=("$vw validate warning(s)"); verdict="check"; jq -r '.warnings[] | "      ? " + .' "$tmp/validate.out" >&2; }

  if [[ ! -f "$request" ]]; then
    notes+=("not rendered: no $name.request.json")
  else
    code="$(post /v1/reports/render "$tmp/request.json" "$out/$name.pdf")"
    if [[ "$code" != 200 ]]; then
      rows+=("FAIL  $name  render: $(jq -r '.detail // .' "$out/$name.pdf" 2>/dev/null | head -c 200)"); rm -f "$out/$name.pdf"; failed=$((failed+1)); continue
    fi
    if [[ -n "$reference" && -f "$reference/$name.pdf" ]]; then
      old="$reference/$name.pdf"; new="$out/$name.pdf"
      pages_old="$(pdfinfo "$old" | awk '/^Pages:/{print $2}')"; pages_new="$(pdfinfo "$new" | awk '/^Pages:/{print $2}')"
      if [[ "$pages_old" != "$pages_new" ]]; then
        verdict="FAIL"; notes+=("pages: reference $pages_old, new $pages_new")
      else
        notes+=("$pages_new page(s)")
        if ! diff -q <(pdftotext -layout "$old" - | tr -s '[:space:]' ' ') <(pdftotext -layout "$new" - | tr -s '[:space:]' ' ') >/dev/null; then
          verdict="FAIL"; notes+=("text differs")
          diff <(pdftotext -layout "$old" - | sed 's/[[:space:]]\+/ /g') <(pdftotext -layout "$new" - | sed 's/[[:space:]]\+/ /g') | head -6 | sed 's/^/      | /' >&2
        fi
        if command -v pdftoppm >/dev/null && command -v compare >/dev/null; then
          pdftoppm -r 60 -png "$old" "$tmp/old"; pdftoppm -r 60 -png "$new" "$tmp/new"
          worst=0
          for o in "$tmp"/old-*.png; do
            n="${o/old-/new-}"; page="${o##*-}"; page="${page%.png}"
            diffpx="$(compare -metric AE -fuzz 8% "$o" "$n" "$out/$name-p$page-diff.png" 2>&1 || true)"
            total="$(magick identify -format '%[fx:w*h]' "$o")"
            pct="$(awk -v d="${diffpx%% *}" -v t="$total" 'BEGIN{printf "%.2f", 100*d/t}')"
            worst="$(awk -v a="$worst" -v b="$pct" 'BEGIN{print (b>a)?b:a}')"
            awk -v p="$pct" -v t="$tolerance" 'BEGIN{exit !(p<=t)}' && rm -f "$out/$name-p$page-diff.png"
          done
          rm -f "$tmp"/old-*.png "$tmp"/new-*.png
          notes+=("worst page image difference ${worst}%")
          awk -v p="$worst" -v t="$tolerance" 'BEGIN{exit !(p>t)}' && { verdict="FAIL"; notes+=("see $out/$name-p*-diff.png"); }
        fi
      fi
    else
      notes+=("rendered to $out/$name.pdf; no reference PDF to compare")
    fi
  fi
  [[ "$verdict" == FAIL ]] && failed=$((failed+1))
  rows+=("$(printf '%-5s' "$(echo "$verdict" | tr a-z A-Z)")  $name  $(IFS=';'; echo "${notes[*]}")")
done

echo
printf '%s\n' "${rows[@]}"
echo
echo "converted reports are in $target ($failed failure(s))"
echo "Open the converted files in Jaspersoft Studio 7 as well before using them for real."
exit $((failed > 0))
