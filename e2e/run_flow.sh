#!/usr/bin/env bash
# usage: e2e/run_flow.sh <flowname-without-ext> [device-serial]   (run from repo root)
# Prereqs: ANDROID_HOME set, emulator running, app installed, Maestro on PATH.
# DEVICE defaults to emulator-5554 (the chronicle AVD); pass e.g. emulator-5556
# for chronicle_small, or set E2E_DEVICE.
#
# Every flow starts from a deterministic cold launch on Today: we force-stop
# then monkey-launch here because Maestro's own `launchApp` force-stops too,
# and its stop/start pair races adbd — the app intermittently never comes up
# (or the device goes "offline"). Flows therefore carry no `launchApp` step.
# The launch is verified with pidof and retried; adbd "not connected" and
# "device offline" flakes retry the whole run.
#
# POST_NOTIFICATIONS is granted up front: the app asks the first time a
# reminder is scheduled, and on a fresh install the system dialog would
# otherwise steal focus mid-assertion. RECORD_AUDIO is left alone — e4/e4b
# exercise that dialog via `pm revoke` + `pm clear-permission-flags`.
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
F="$DIR/flows/$1.yaml"
DEVICE="${2:-${E2E_DEVICE:-emulator-5554}}"

adb -s "$DEVICE" shell pm grant com.chronicle.app android.permission.POST_NOTIFICATIONS >/dev/null 2>&1
for i in 1 2 3; do
  adb -s "$DEVICE" shell am force-stop com.chronicle.app
  sleep 1
  adb -s "$DEVICE" shell monkey -p com.chronicle.app -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
  sleep 3
  if adb -s "$DEVICE" shell pidof com.chronicle.app | grep -q '[0-9]'; then
    break
  fi
  echo "[relaunch $i: process not up]"
done
for i in 1 2 3 4 5; do
  if [[ -n "${E2E_REPORT_DIR:-}" ]]; then
    out=$(maestro test --udid "$DEVICE" --test-output-dir "$E2E_REPORT_DIR/attempt-$i" "$F" 2>&1)
  else
    out=$(maestro test --udid "$DEVICE" "$F" 2>&1)
  fi
  rc=$?
  echo "$out" | grep -v "^\s*$" | tail -25
  if echo "$out" | grep -qiE "device offline|not connected"; then
    echo "[retry $i: adb connectivity flake]"; sleep 3; continue
  fi
  exit $rc
done
