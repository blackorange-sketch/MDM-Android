#!/bin/sh
# Fetches Mister Download Manager at the pinned revision into third_party/mdm and applies the
# patches in patches/ (the few changes Android needs). Run it before any cargo or gradle command.
set -eu
REV=3bb924491549a2085d8d6795fd630cf6fa2a14c5
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$ROOT/third_party/mdm"

if [ ! -d "$DIR/.git" ]; then
  mkdir -p "$DIR"
  git init -q "$DIR"
  git -C "$DIR" remote add origin https://github.com/anefzaoui/misterdownloadmanager
fi
git -C "$DIR" fetch -q --depth 1 origin "$REV"
git -C "$DIR" checkout -q -f "$REV"
for p in "$ROOT"/patches/*.patch; do
  git -C "$DIR" apply "$p"
done
echo "upstream $REV ready in third_party/mdm"
