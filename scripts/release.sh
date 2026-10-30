#!/usr/bin/env bash
# Cuts a release locally so the tagged commit is exactly what CI builds.
#
#   scripts/release.sh 1.8.2
#
# Bumps `versionName` and `versionCode` in gradle.properties, archives `## [Unreleased]` in
# CHANGELOG.md as `## [1.8.2] - <UTC date>`, commits both as "Release v1.8.2",
# tags that commit v1.8.2, and moves `main` to it. Nothing is pushed: the
# script prints the push commands at the end.
#
# Why this is local and not in CI: CHANGELOG.md is bundled into the APK (What's
# New), so any edit CI made on the runner before building would be missing from
# the tagged source. F-Droid rebuilds from the tag and compares against the
# published APK, and that mismatch fails its reproducible-build check.
set -euo pipefail

die() { echo "error: $*" >&2; exit 1; }

[ $# -eq 1 ] || die "usage: scripts/release.sh <MAJOR.MINOR.PATCH>"
VERSION="$1"
TAG="v$VERSION"

cd "$(git rev-parse --show-toplevel)"

# Same rules app/build.gradle.kts enforces when deriving versionCode.
[[ "$VERSION" =~ ^([0-9]+)\.([0-9]+)\.([0-9]+)$ ]] || die "'$VERSION' is not MAJOR.MINOR.PATCH"
MAJOR=$((10#${BASH_REMATCH[1]})); MINOR=$((10#${BASH_REMATCH[2]})); PATCH=$((10#${BASH_REMATCH[3]}))
[ "$MINOR" -le 99 ] && [ "$PATCH" -le 99 ] || die "minor and patch must each be 0-99"
NEW_CODE=$((MAJOR * 10000 + MINOR * 100 + PATCH))

CURRENT="$(sed -n 's/^versionName=//p' gradle.properties)"
CURRENT_CODE="$(sed -n 's/^versionCode=//p' gradle.properties)"
[[ "$CURRENT_CODE" =~ ^[0-9]+$ ]] || die "can't parse current versionCode '$CURRENT_CODE' in gradle.properties"
[ "$NEW_CODE" -gt "$CURRENT_CODE" ] || die "$VERSION ($NEW_CODE) is not newer than current $CURRENT ($CURRENT_CODE)"

git rev-parse -q --verify "refs/tags/$TAG" >/dev/null && die "tag $TAG already exists"
grep -qxF "## [Unreleased]" CHANGELOG.md || die "CHANGELOG.md has no '## [Unreleased]' heading"

NOTES="$(awk '
  /^## \[Unreleased\]$/ { found=1; next }
  found && /^## \[/ { exit }
  found { print }
' CHANGELOG.md | awk 'NF{p=1} p')"
[ -n "$NOTES" ] || die "nothing under '## [Unreleased]' in CHANGELOG.md"

USE_JJ=false
if [ -d .jj ] && command -v jj >/dev/null; then USE_JJ=true; fi

if $USE_JJ; then
  [ -z "$(jj diff --summary)" ] || die "working copy has changes; commit them or start a fresh change (jj new) first"
else
  [ -z "$(git status --porcelain --untracked-files=no)" ] || die "working tree has uncommitted changes"
fi

# The baseline profile is a checked-in source file (so F-Droid's rebuild matches);
# it only stays useful if it's regenerated as the code changes.
PROFILE_DIR=app/src/release/generated/baselineProfiles
[ -n "$(ls "$PROFILE_DIR"/*.txt 2>/dev/null)" ] || die "no baseline profile in $PROFILE_DIR; run ./gradlew :app:generateBaselineProfile and commit it"
PREV_TAG="$(git describe --tags --abbrev=0 2>/dev/null || true)"
if [ -n "$PREV_TAG" ] && [ -z "$(git log --oneline "$PREV_TAG"..HEAD -- "$PROFILE_DIR")" ]; then
  echo "warning: baseline profile unchanged since $PREV_TAG; consider ./gradlew :app:generateBaselineProfile first" >&2
fi

sed -i.bak -e "s/^versionName=.*/versionName=$VERSION/" -e "s/^versionCode=.*/versionCode=$NEW_CODE/" gradle.properties
rm gradle.properties.bak

DATE="$(date -u +%Y-%m-%d)"
awk -v ver="$VERSION" -v date="$DATE" '
  /^## \[Unreleased\]$/ && !done {
    print
    print ""
    print "## [" ver "] - " date
    done=1
    next
  }
  { print }
' CHANGELOG.md > CHANGELOG.md.tmp
mv CHANGELOG.md.tmp CHANGELOG.md

if $USE_JJ; then
  jj commit -m "Release $TAG" gradle.properties CHANGELOG.md
  jj tag set "$TAG" -r @-
  jj bookmark set main -r @-
  PUSH_MAIN="jj git push -b main"
else
  git add gradle.properties CHANGELOG.md
  git commit -m "Release $TAG"
  git tag "$TAG"
  PUSH_MAIN="git push origin HEAD:main"
fi

cat <<EOF

Release $TAG committed and tagged locally (versionCode $NEW_CODE).

Release notes:
$NOTES

Review with 'git show $TAG', then push the branch and the tag:
  $PUSH_MAIN
  git push origin $TAG

Pushing the tag triggers the GitHub Release build.
EOF
