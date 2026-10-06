#!/usr/bin/env bash
# Cut a release: scripts/release.sh <version> [next-snapshot]   (normally via `make release VERSION=0.1.0`)
#
#   1. checks: on main, clean, up to date with origin, CHANGELOG [Unreleased] has entries
#   2. runs the tests
#   3. commit "release: vX"   pom = X, CHANGELOG [Unreleased] -> [X] - <today>
#   4. tag vX on that commit
#   5. commit "chore: start X+1-SNAPSHOT"   pom = next snapshot
#   6. push main and the tag; the tag starts .github/workflows/release.yml (jar, image, GitHub Release)
set -euo pipefail
cd "$(dirname "$0")/.."

VERSION="${1:-}"
[[ "$VERSION" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)(-[0-9A-Za-z.]+)?$ ]] \
	|| { echo "usage: $0 <version> [next-snapshot]   e.g. 0.2.0 or 0.2.0-rc.1" >&2; exit 2; }
MAJOR=${BASH_REMATCH[1]} MINOR=${BASH_REMATCH[2]} PATCH=${BASH_REMATCH[3]} PRE=${BASH_REMATCH[4]}
if [[ -n "${2:-}" ]]; then
	NEXT="${2%-SNAPSHOT}-SNAPSHOT"
elif [[ -n "$PRE" ]]; then
	NEXT="$MAJOR.$MINOR.$PATCH-SNAPSHOT"            # a release candidate precedes its final version
else
	NEXT="$MAJOR.$MINOR.$((PATCH + 1))-SNAPSHOT"
fi
TAG="v$VERSION"
MVN="${MVN:-./mvnw}"
REPO_URL="https://github.com/somprasongd/jasper-report-api"

fail() { echo "release: $*" >&2; exit 1; }

[[ "$(git rev-parse --abbrev-ref HEAD)" == main ]] || fail "run from the main branch"
[[ -z "$(git status --porcelain --untracked-files=no)" ]] || fail "working tree has uncommitted changes"
git fetch --quiet origin main --tags
[[ "$(git rev-parse HEAD)" == "$(git rev-parse origin/main)" ]] || fail "main is not in sync with origin/main"
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && fail "tag $TAG already exists"

CURRENT="$($MVN -B -q help:evaluate -Dexpression=project.version -DforceStdout)"
[[ "$CURRENT" == *-SNAPSHOT ]] || fail "pom.xml version is $CURRENT, expected a -SNAPSHOT"

# [Unreleased] must have at least one entry
UNRELEASED="$(awk '/^## \[Unreleased\]/{f=1; next} /^## \[/{f=0} f && NF' CHANGELOG.md)"
[[ -n "$UNRELEASED" ]] || fail "CHANGELOG.md has no entries under [Unreleased]"

echo "release $CURRENT -> $VERSION (tag $TAG), then $NEXT"
$MVN -B verify

PREV="$(git describe --tags --abbrev=0 --match 'v*' 2>/dev/null || true)"
if [[ -n "$PREV" ]]; then COMPARE="$REPO_URL/compare/$PREV...$TAG"; else COMPARE="$REPO_URL/releases/tag/$TAG"; fi
TMP="$(mktemp)"
awk -v v="$VERSION" -v d="$(date +%F)" -v tag="$TAG" -v url="$REPO_URL" -v cmp="$COMPARE" '
	/^## \[Unreleased\]/ { print; print ""; print "## [" v "] - " d; next }
	/^\[Unreleased\]:/   { print "[Unreleased]: " url "/compare/" tag "...HEAD"; print "[" v "]: " cmp; next }
	{ print }
' CHANGELOG.md > "$TMP" && mv "$TMP" CHANGELOG.md

$MVN -B -q versions:set -DnewVersion="$VERSION" -DgenerateBackupPoms=false
git add pom.xml CHANGELOG.md
git commit -q -m "release: $TAG"
git tag -a "$TAG" -m "$TAG"

$MVN -B -q versions:set -DnewVersion="$NEXT" -DgenerateBackupPoms=false
git add pom.xml
git commit -q -m "chore: start $NEXT"

git push --atomic origin main "$TAG"
echo "pushed $TAG; follow the build with: gh run list --workflow=release.yml (then gh run watch <id>)"
