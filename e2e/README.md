# Chronicle E2E (Maestro)

Black-box UI suite implementing `TEST_PLAN.md` (TC1–TC12 + edge cases), updated
for the redesigned UI (sentence-case labels, "Settings" gear, option cards,
model pages).

## Prereqs
- Android SDK + emulator: AVD `chronicle` (API 35, google_apis, x86_64 on KVM hosts).
  Boot `emulator -avd chronicle`, wait for `adb shell getprop sys.boot_completed` = 1.
- `adb install app/build/outputs/apk/debug/app-debug.apk`
- Maestro CLI: `curl -fsSL https://get.maestro.mobile.dev | bash` → `~/.maestro/bin/maestro`

## Run
`e2e/run_flow.sh tc3 [emulator-5554]` — force-stops then monkey-launches the
app first (Maestro's `launchApp` force-stops and flakes adbd), grants
POST_NOTIFICATIONS, retries on "device offline"/"not connected".

Flows are order-dependent (they share app state):
- tc5 expects tc2/tc3's entries; tc6's chapter is composed from them.
- e3 expects e2's entry + tc12's "Stretch" + tc4's "Water the fern";
  e5 deletes e2's entry.
- e4/e4b need the mic permission revoked first:
  `adb shell pm revoke com.chronicle.app android.permission.RECORD_AUDIO`
- A clean baseline: `adb shell pm clear com.chronicle.app` (re-grants the
  auto-granted permissions on this image).

See `.agents/skills/testing-chronicle-android/SKILL.md` for Compose-selector
quirks (full-string regex asserts, keyboard/nav collisions, permission revoke
dance, dynamic date strings) and the post-redesign selector map.
