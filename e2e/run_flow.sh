#!/usr/bin/env bash
# usage: e2e/run_flow.sh <flowname-without-ext>   (run from repo root)
# Prereqs: ANDROID_HOME set, emulator running, app installed, Maestro on PATH.
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
F="$DIR/flows/$1.yaml"
adb shell monkey -p com.chronicle.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 2
for i in 1 2 3 4 5; do
  out=$(maestro test "$F" 2>&1)
  rc=$?
  echo "$out" | grep -v "^\s*$" | tail -25
  latest=$(ls -td "$HOME"/.maestro/tests/*/ 2>/dev/null | head -1)
  if [ -n "$latest" ] && grep -rqs "device offline" "$latest"; then
    echo "[retry $i: device offline flake]"; sleep 3; continue
  fi
  exit $rc
done
