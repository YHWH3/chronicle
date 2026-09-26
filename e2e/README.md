# Chronicle E2E (Maestro)

Black-box UI suite implementing `TEST_PLAN.md` (TC1–TC12 + edge cases).

## Prereqs
- Android SDK + emulator: AVD `chronicle` (API 35, google_apis, x86_64 on KVM hosts).
  Boot `emulator -avd chronicle`, wait for `adb shell getprop sys.boot_completed` = 1.
- `adb install app/build/outputs/apk/debug/app-debug.apk`
- Maestro CLI: `curl -fsSL https://get.maestro.mobile.dev | bash` → `~/.maestro/bin/maestro`

## Run
`e2e/run_flow.sh tc3` — monkey-launches the app first (Maestro's `launchApp`
force-stops and flakes adbd), retries on "device offline".

See `.agents/skills/testing-chronicle-android/SKILL.md` for Compose-selector
quirks (full-string regex asserts, keyboard/nav collisions, permission revoke
dance, dynamic date strings).
