---
name: testing-chronicle-android
description: How to drive Chronicle Android E2E on the emulator — Maestro quirks, adb helpers, and app-specific element selectors learned the hard way.
---

# Testing Chronicle (Android/Compose) on the emulator

## Environment
- `ANDROID_HOME=~/Library/Android/sdk` (adb in `platform-tools`); `JAVA_HOME=/opt/homebrew/opt/openjdk@17`. AVD `chronicle` (API 35 google_apis x86_64, pixel_6) = emulator-5554; `chronicle_small` = emulator-5556. Boot: `emulator -avd chronicle`; wait for `adb shell getprop sys.boot_completed`=1.
- Build/install: `./gradlew :app:assembleDebug` → `adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk` (~245 MB). Package `com.chronicle.app`, activity `.MainActivity`.
- Maestro at `~/.maestro/bin/maestro` (install: `curl -fsSL https://get.maestro.mobile.dev | bash`). Flows + runner live in-repo: `e2e/flows/*.yaml` + `e2e/run_flow.sh <name> [device-serial]` (defaults emulator-5554).
- A stale `127.0.0.1:*  offline` entry in `adb devices` makes Maestro report "device was requested, but it is not connected" — `adb disconnect 127.0.0.1:*` fixes it.
- `run_flow.sh` force-stops + monkey-launches per flow (verified with pidof), grants POST_NOTIFICATIONS, and retries the whole run on "device offline"/"not connected" adbd flakes. Flows therefore carry no `launchApp` step — they start with `waitForAnimationToEnd` on the cold-launched Today screen.
- Suites are order-dependent: tc5 asserts entries written by tc2/tc3; e3 expects tc12's "Stretch" + tc4's "Water the fern" + e2's entry; e5 deletes e2's entry. Run tc1→tc12c, then e*, then fix*. `pm clear com.chronicle.app` for a clean baseline (re-grants auto-permissions).

## Maestro-on-Compose pitfalls that cost time
- `launchApp` internally force-stops and flakes adbd with "device offline" (~50% of runs on this host). Omit it from flows; the runner monkey-launches.
- Text selectors are **full-string regex** matches against a node's whole text — use `[\s\S]*` tails for anything dynamic and `^…$` anchors to disambiguate (e.g. `'^Journal$'` vs the Journal page heading).
- Redesign note: each `Text` composable stays a separately-matchable node even inside `selectable`/`clickable`/`toggleable` cards — anchored card titles like `'^Parakeet$'`/`'^On-device model$'` tap the card fine.
- Sentence case everywhere: `Chron.type.meta` is plain Inter — no more uppercase MetaText anywhere (the legacy `ui/components/` package is deleted). Nav labels are `Today`/`Journal`/`Story`/`Intentions`; mode pills `Speak`/`Write` (Role.Tab).
- `inputText` without a focused field sends keyevents to whatever holds focus. Always `tapOn` the field's placeholder text first (`'Add an intention…'`, `'Begin anywhere. The day will shape itself.'`).
- Nav labels collide with same-named headings/rows. Anchored `'^Journal$'` etc. taps work (icon desc + label both match); point taps at y≈93% still work as fallback (TODAY 13%, JOURNAL 35%, STORY 57%, INTENTIONS 82%).
- The FloatingNavBar slides away while the IME is up — `hideKeyboard` before nav taps (the swipe-back IME also makes nav point taps hit keys).
- Long lists: content below the fold isn't in the semantics tree — use `scrollUntilVisible` before asserting/tapping (Intentions open card, Mind page sections).
- `assertVisible` on system permission dialogs races the dialog entrance; use `tapOn` directly or tolerate a retry.
- `doubleTapOn` works (2.10.0) — used for the double-tap-Keep edge case.
- `takeScreenshot:` artifacts land in `~/.maestro/tests/<timestamp>/<flow>/takeScreenshot/`; per-step failure shots in `screenshots/step-NNN-*.png`.
- Maestro 2.10 does NOT accept a top-level `when:` step — use `tapOn: {text: X, optional: true}` for state-tolerant steps (prints WARNED and continues).

## New-UI selectors (post-redesign)
- Today idle: date meta `EEE · d MMM` sentence case ("Tue · 30 Sep"); serif greeting renders as TWO nodes ("Good" / "evening." — split on last word); "What happened today?"; gear icon `content-desc="Settings"` (was "Open details"); capture circle `content-desc="Record"` (centred now); caption "Tap to speak your day" (hidden on ≤700dp-high screens once today's entries exist — the Earlier-today card takes its slot); Speak-only "Earlier today" card lists today's entries (Write mode shows the editor instead).
- Write mode: placeholder "Begin anywhere. The day will shape itself."; "Keep" pill is disabled until the draft is non-blank.
- Recording: HUD "Recording…" + `M:SS` timer + waveform; bottom row has Discard icon+text left, centred stop circle `content-desc="Stop"`, and a non-clickable "Tap to keep" caption.
- Processing: headline "Transcribing…"/"Looking for intentions…"/"Keeping your words…" + StepList rows.
- Kept state (multi-node, not merged): heading `Kept.` + `Saved to your journal\.`/`1 reminder set`/`N reminders set` + `Read by Quiet parser` + intention rows + `View entry`/`Add another` pills.
- Journal: heading "Journal"; day labels `Today`/`Yesterday`/`EEEE · d MMM`; rows lead with `HH:mm`; tapping a row expands a `^Delete$` ghost button (or customAction "Delete entry"); voice rows have `Play`/`Pause` descs + `M:SS`; empty state "Your journal is waiting." + "Start on Today".
- Story: "Story" + italic "Your weeks, written back to you."; share `content-desc="Share story"`; hero card = current chapter (`Chapter [IVXLC]+` meta + "The week of the …" headline + "N entries · M words" + first paragraph); previous chapters are merged index rows (`content-desc="Chapter N, The week of…"`); chapter page `Back` + `Share story`; empty "Your first chapter is still being lived."
- Intentions: "Intentions" + "What you mean to do."; placeholder "Add an intention…"; submit is the round + circle `content-desc="Add intention"` (or IME Done); reminder pills (`No reminder`/`In an hour`/`Tonight`/`Tomorrow`, Role.RadioButton) only render once the field is focused or has text — tap the placeholder first; rows have `Mark done`/`Mark not done` CircleCheck descs, meta `Today · HH:mm`/`Tomorrow · HH:mm`/`EEE d MMM · HH:mm` (+ "· From your journal" for extracted ones); completed rows move under a `Done` label after a ~350 ms beat; delete via swipe or customAction "Delete intention"; empty "Nothing you mean to do, yet."
- Settings overview: title "Settings" + "The quiet particulars."; rows Appearance / Daily nudge / The Ear / The Mind / Privacy / About — plain `tapOn 'The Ear'` matches the row title. Sub-pages have a frosted back button `content-desc="Back"`; the whole Settings section is a nested nav graph with no bottom bar — `pressKey: back` (×2 from a sub-page) or tap `Back` to return to Today.
- Appearance page: "Ink is for the evening; Paper for morning light."; theme tiles are big selectable ScenePreviews (Role.RadioButton) — the `Ink`/`Paper`/`Follow the day` labels sit BELOW the selectable box, so tap the tile's embedded preview text: `Good evening\.` = Ink, `Good morning\.` = Paper (also inside the split Follow-the-day tile — first match hits Paper's). Theme switch stays on the page (no eject).
- Daily nudge: "A gentle reminder to write, once a day." + SoftSwitch (Role.Switch but NO text/desc → point-tap ~`84%,26%`); chips `07:00`/`12:30`/`18:00`/`20:00`/`21:30` appear when on.
- Ear page: "How your voice becomes words."; option cards `Android speech recognition` / `Parakeet`; selecting Parakeet reveals the `Parakeet TDT 0.6B` status row (`Not installed`/`Downloading N%`/`Unpacking`/`Ready`/`Failed`) + "Until it's installed, Chronicle uses Android's recogniser."
- Mind page: "How your words become intentions."; cards `Quiet parser` / `On-device model` (reveals `Llama 3.2 3B` status row) / `OpenRouter` (reveals `OpenRouter API key \(sk-or-…\)` SecretField + "Free models" ChoicePills like `google/gemini-2.0-flash-exp`, `meta-llama/llama-3.2-3b-instruct`) / `Custom endpoint` (reveals Base URL/Model/API key fields). Selecting a card collapses the others — scroll + `^Quiet parser$` to restore.
- Model page: "On-device speech transcription." (Parakeet) / "An on-device mind that reads your entries." (Llama); `≈ 487 MB` / `varies with the model you pick` + "Runs entirely on this phone"; actions `Download · ≈ 487 MB`/`Download` → `Cancel` → `Try again` on failure → `Delete` + confirm dialog when Ready; "Download source" section is collapsed behind an `Edit`/`Hide` GhostButton — the URL EditText is prefilled for Parakeet (`https://github.com/k2-fsa.*`) and EMPTY for the LLM (licence-gated — the user pastes their own link); assert the field's value, there is no visible label.
- Emulator model-download reality: the GitHub-hosted Parakeet download runs long enough to exercise `Cancel`; the LLM ships no default URL — "Download" stays disabled until the user pastes one — make download flows state-tolerant: `(^Download$|Try again)`, `(Cancel|Try again)`, optional Cancel tap.

## Permission/perm-state quirks on the API-35 image
- RECORD_AUDIO and POST_NOTIFICATIONS arrive auto-granted (no USER_SET flag) — permission dialogs never appear unless you `adb shell pm revoke com.chronicle.app android.permission.<PERM>` first (+ `pm clear-permission-flags ... USER_SET USER_FIXED` to re-prompt). `pm revoke`/`pm clear` force-stop the app → the runner relaunches.
- Dangerous-permission dialog buttons: "While using the app" / "Only this time" / "Don't allow" (Maestro-visible; `'Don.t allow'` regex sidesteps the U+2019 apostrophe). e4/e4b expect RECORD_AUDIO revoked before the run; a zombie dialog can survive a relaunch — dismiss with an optional `Don.t allow` tap first.

## adb checks that worked well
- Entry counting without flaky scroll: `uiautomator dump` + count `text="[0-9]{2}:[0-9]{2}"` nodes (each entry row starts with HH:mm).
- Reminder verification: `dumpsys alarm | grep chronicle` — pending `RTC_WAKEUP ... ReminderReceiver` entries; done-toggle shows up as `pi_cancelled` in the log section.
- Persistence: `am force-stop` + monkey relaunch, then assert data + settings via Maestro.
- Chips/pills (`selectable`, `toggleable`) expose `checkable=true`/`checked=true` on the active one.

## Expected dynamic strings (compute from device date)
- Header meta: `EEE · d MMM` ("Tue · 30 Sep"). Journal day labels: `Today`/`Yesterday`/`EEEE · d MMM`. Reminder meta: `Today · HH:mm`/`Tomorrow · HH:mm`/`EEE d MMM · HH:mm`. Kept state: `Kept.` + `Saved to your journal\.`/`N reminder(s)? set` + `Read by <ENGINE>`. Task extraction: "remind me to X tomorrow at 5" → title "X" capitalized, time heuristic `at <1..7>` ⇒ PM, `8..12` ⇒ as-is; same-day past times roll to tomorrow ("tonight at 9" → 09:00 tomorrow after 9 am).
