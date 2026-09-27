---
name: testing-chronicle-android
description: How to drive Chronicle Android E2E on the emulator — Maestro quirks, adb helpers, and app-specific element selectors learned the hard way.
---

# Testing Chronicle (Android/Compose) on the emulator

## Environment
- `ANDROID_HOME=~/Android/sdk`; AVD `chronicle` (API 35 google_apis x86_64, pixel_6, KVM). Boot: `emulator -avd chronicle`; wait for `adb shell getprop sys.boot_completed`=1.
- Build/install: `./gradlew :app:assembleDebug` → `adb install app/build/outputs/apk/debug/app-debug.apk` (~245 MB). Package `com.chronicle.app`, activity `.MainActivity`.
- Maestro at `~/.maestro/bin/maestro` (install: `curl -fsSL https://get.maestro.mobile.dev | bash`). Flows + runner live in `~/chronicle-e2e/` (`run_flow.sh <name>` runs `flows/<name>.yaml`).

## Maestro-on-Compose pitfalls that cost time
- `launchApp` internally force-stops and flakes adbd with "device offline" (~50% of runs on this host). Omit it from flows; launch via `adb shell monkey -p com.chronicle.app -c android.intent.category.LAUNCHER 1` before `maestro test`.
- Text selectors are **full-string regex** matches against the node's whole text. Multiline nodes (e.g. confirmations like "KEPT.\nREAD BY QUIET PARSER") need `[\s\S]*` patterns, e.g. `assertVisible: 'KEPT — 1 REMINDER SET\.[\s\S]*'`.
- `MetaText` renders `text.uppercase()` — chip/label text appears in semantics already uppercased.
- `inputText` without a focused field sends keyevents to whatever holds focus (can activate buttons — a stray space once opened Settings). Always `tapOn` the field's placeholder text first.
- Bottom-nav labels collide with in-content text (e.g. `tapOn "TODAY"` matches Journal's "Today" heading first). Navigate with point taps: TODAY 13%, JOURNAL 35%, STORY 57%, INTENTIONS 82% at y≈93%.
- If the IME is open, bottom-nav point taps hit keyboard keys (a nav tap once typed "." into the editor). `hideKeyboard` before nav taps.
- Long lists: content below the fold isn't in the semantics tree — use `scrollUntilVisible` before asserting/tapping (gated-note, FREE MODELS chips).
- Gear icon has `contentDescription="Open details"` (post-F4); assert `'The quiet particulars'` to confirm Settings opened.
- `assertVisible` on system permission dialogs races the dialog entrance; screenshot may show it while the assert fails. Use `tapOn` directly or tolerate a retry.
- The SPEAK record ring is left-aligned, not centered: clickable bounds ≈[200,743][452,995] on 1080x2400 → point `"30%,36%"`.
- `doubleTapOn` works (2.10.0) — used for the double-tap-KEEP edge case.
- `takeScreenshot:` artifacts land in `~/.maestro/tests/<timestamp>/<flow>/takeScreenshot/`; per-step failure shots in `screenshots/step-NNN-*.png`.

## Post-motion-pass additions (bf8cce2)
- During recording, "TAP TO KEEP" is only a caption — the clickable target is the big stop ring above it, `content-desc="Stop"`, bounds ≈[409,1010][671,1272] → tap (540,1141). DISCARD is the text button at [450,1356][630,1482]. Tapping the caption's text bounds does nothing.
- Theme chips (INK/PAPER): on bf8cce2 a real theme change ejected the app to Today mid-crossfade (F5 — fixed in the token-animation build; theme tokens now tween in place and NavHost isn't recomposed). If a similar stray-nav ever resurfaces, recover with an `optional: true` `tapOn 'Open details'` to re-enter Settings.
- Settings has NO bottom nav bar — flows that begin with nav-point taps must start with `launchApp` (lands on Today) or `pressKey: back`.
- The KEPT confirmation is a single merged node `KEPT.\nREAD BY <ENGINE>` (post-motion) — assert with `'KEPT\.[\s\S]*'`.
- Maestro 2.10 does NOT accept a top-level `when:` step — use `tapOn: {text: X, optional: true}` for state-tolerant steps.
- The Parakeet model-row "Model download URL" label text isn't rendered anymore; assert the EditText's prefilled value (`https://github.com/k2-fsa.*`) instead.
- `pm revoke` on an auto-granted permission does work (granted=false + USER_SET) — the dialog then appears on next use; but `pm clear` re-grants.

## Permission/perm-state quirks on the API-35 image
- RECORD_AUDIO and POST_NOTIFICATIONS arrive auto-granted (no USER_SET flag) — permission dialogs never appear unless you `adb shell pm revoke com.chronicle.app android.permission.<PERM>` first. `pm revoke`/`pm clear` force-stop the app → relaunch with monkey.
- Dangerous-permission dialog buttons: "While using the app" / "Only this time" / "Don't allow" (Maestro-visible; `'Don.t allow'` regex sidesteps the apostrophe).

## adb checks that worked well
- Entry counting without flaky scroll: `uiautomator dump` + count `text="[0-9]{2}:[0-9]{2}"` nodes (each entry row starts with HH:mm).
- Reminder verification: `dumpsys alarm | grep chronicle` — pending `RTC_WAKEUP ... ReminderReceiver` entries; done-toggle shows up as `pi_cancelled` in the log section.
- Persistence: `am force-stop` + monkey relaunch, then assert data + settings via Maestro.
- Chips now use `Modifier.selectable` (post-F3) → semantics expose `checkable=true`/`checked=true` on the active chip.

## Expected dynamic strings (compute from device date)
- Header meta: `EEE · d MMM` ("SAT · 26 SEP"). Reminder rows: `EEE d MMM · HH:mm`. Confirmation: `KEPT.` or `KEPT — N REMINDER SET.` + optional `\nREAD BY <ENGINE>` byline. Task extraction: "remind me to X tomorrow at 5" → title "X" capitalized, time heuristic `at <1..7>` ⇒ PM, `8..12` ⇒ as-is; same-day past times roll to tomorrow.
