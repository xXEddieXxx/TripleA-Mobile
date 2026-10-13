#!/bin/sh
# Compares engine/src/main/java with the desktop engine at a tag.
# usage: engine/upstream/diff.sh <desktop checkout> [tag]
set -eu
DESKTOP=$(cd "${1:?path to the desktop triplea checkout}" && pwd)
TAG=${2:-2026-10.16.15815}
git -C "$DESKTOP" rev-parse -q --verify "$TAG^{commit}" >/dev/null || { echo "unknown tag $TAG in $DESKTOP" >&2; exit 2; }
SRC=$(cd "$(dirname "$0")/../src/main/java" && pwd)
LIST=$(mktemp)
trap 'rm -f "$LIST"' EXIT
git -C "$DESKTOP" ls-tree -r --full-name --name-only "$TAG" | grep '/src/main/java/.*\.java$' > "$LIST"
same=0
cd "$SRC"
for f in $(find . -name '*.java' | sed 's|^\./||' | sort); do
  m=$(grep -F "/src/main/java/$f" "$LIST" | head -1 || true)
  if [ -z "$m" ]; then
    echo "mobile-only $f"
  elif git -C "$DESKTOP" show "$TAG:$m" | diff -q --strip-trailing-cr - "$f" >/dev/null; then
    same=$((same + 1))
  else
    echo "differs     $f"
  fi
done
echo "identical: $same"
