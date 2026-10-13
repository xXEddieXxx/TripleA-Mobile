#!/bin/sh
# Regenerates the desktop-written fixture save: minimap, all AI, 3 steps into round 2.
# usage: sh engine/upstream/generate-desktop-save.sh <desktop checkout> [tag]
# Needs git, internet (Gradle dependencies and the desktop smoke-test downloads), a JDK 17+ on
# JAVA_HOME to start Gradle and a locally installed JDK 25 that Gradle auto-detects.
set -eu
DESKTOP=$(cd "${1:?path to the desktop triplea checkout}" && pwd)
TAG=${2:-2026-10.16.15815}
git -C "$DESKTOP" rev-parse -q --verify "$TAG^{commit}" >/dev/null || { echo "unknown tag $TAG in $DESKTOP" >&2; exit 2; }
HERE=$(cd "$(dirname "$0")" && pwd)
OUT="$HERE/../src/test/resources/saves/desktop-$TAG.tsvg"
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT
git -C "$DESKTOP" archive "$TAG" | tar -x -C "$WORK"
# the desktop build runs `git rev-list --count HEAD`, which the archive lacks
git -C "$WORK" init -q
git -C "$WORK" -c user.name=x -c user.email=x@x -c commit.gpgsign=false \
  commit -q --no-verify --allow-empty -m export
cp "$HERE/WriteDesktopSaveTest.java" \
  "$WORK/game-app/smoke-testing/src/test/java/games/strategy/engine/data/"
mkdir -p "$(dirname "$OUT")"
OUT=$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")
# write into $WORK and replace the fixture only on success (cp + same-folder mv keeps it whole)
(cd "$WORK" && DESKTOP_SAVE_OUT="$WORK/save.tsvg" ./gradlew --no-daemon \
  :smoke-testing:test --rerun --tests games.strategy.engine.data.WriteDesktopSaveTest)
[ -s "$WORK/save.tsvg" ] || { echo "WriteDesktopSaveTest wrote no save (skipped?)" >&2; exit 1; }
cp "$WORK/save.tsvg" "$OUT.tmp"
mv "$OUT.tmp" "$OUT"
ls -l "$OUT"
