#!/usr/bin/env bash
# Reproducible, emulator-only smart-todos acceptance checks. Run from repo root.
# Usage: bash e2e/run_smart_verification.sh regression|smart|layout emulator-5554
set -euo pipefail
mode=${1:?Choose regression, smart, or layout}
device=${2:?Pass an emulator serial explicitly}
case "$device" in emulator-*) ;; *) echo "Refusing to clear a physical device" >&2; exit 2;; esac
export ANDROID_HOME=${ANDROID_HOME:-/Users/trinity/Library/Android/sdk}
export PATH="$ANDROID_HOME/platform-tools:/Users/trinity/.maestro/bin:$PATH"
reports="app/build/reports/smart-todos/$mode/$device"
mkdir -p "$reports"
adb -s "$device" install -r app/build/outputs/apk/debug/app-debug.apk > "$reports/install.log"
baseline() {
  adb -s "$device" shell pm clear com.chronicle.app > /dev/null
  adb -s "$device" shell pm grant com.chronicle.app android.permission.RECORD_AUDIO
  adb -s "$device" shell settings put system time_12_24 24
}
flow() {
  echo "START $1"
  if E2E_REPORT_DIR="$reports/$1" e2e/run_flow.sh "$1" "$device" > "$reports/$1.log" 2>&1; then
    echo "PASS $1"
  else
    tail -35 "$reports/$1.log"
    echo "FAIL $1" >&2
    return 1
  fi
}
case "$mode" in
 regression)
  baseline
  adb -s "$device" shell settings put system font_scale 1.0
  for name in tc1 tc2 tc3 tc3b tc4 tc5 tc6 tc6b tc7 tc7a tc10 tc10b tc11 tc11b tc11c tc11d tc12 tc12b tc12c e1 e1b e2 e3 e3b e4 e4b e5 e6 fix1 fix1b fix1c fix2 fix3 fix4 fix5 fix5b; do
    case "$name" in
      e4|e4b)
        adb -s "$device" shell pm revoke com.chronicle.app android.permission.RECORD_AUDIO
        adb -s "$device" shell pm clear-permission-flags com.chronicle.app android.permission.RECORD_AUDIO user-set user-fixed
        ;;
    esac
    flow "$name"
    case "$name" in e4|e4b) adb -s "$device" shell pm grant com.chronicle.app android.permission.RECORD_AUDIO;; esac
  done
  ;;
 smart)
  ram_kb=$(adb -s "$device" shell 'awk "/MemTotal/ {print \$2}" /proc/meminfo' | tr -d '\r')
  if [[ "$ram_kb" -lt 5859375 ]]; then
    echo "Smart offer checks need a >=6 GB AVD; use chronicle with -memory 8192" >&2
    exit 2
  fi
  baseline
  # No 1.6 GB model transfer: exercise constraints without an active network.
  trap 'adb -s "$device" shell svc wifi enable; adb -s "$device" shell svc data enable' EXIT
  adb -s "$device" shell svc wifi disable
  adb -s "$device" shell svc data disable
  flow smart1
  flow smart2
  ;;
 layout)
  trap 'adb -s "$device" shell settings put system font_scale 1.0' EXIT
  adb -s "$device" shell cmd overlay enable-exclusive --category com.android.internal.systemui.navbar.threebutton
  for scale in 1.0 1.3; do
    baseline
    adb -s "$device" shell settings put system font_scale "$scale"
    reports="app/build/reports/smart-todos/layout/$device/font-$scale"
    mkdir -p "$reports"
    flow layout
  done
  ;;
 *) echo "Unknown mode: $mode" >&2; exit 2;;
esac
