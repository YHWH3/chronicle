---
name: testing-chronicle-android
description: How to drive Chronicle Android E2E on the emulator — Maestro quirks, adb helpers, and app-specific element selectors learned the hard way.
---

# Testing Chronicle (Android/Compose) on the emulator

## Environment
- `ANDROID_HOME=~/Library/Android/sdk` (adb in `platform-tools`); `JAVA_HOME=/opt/homebrew/opt/openjdk@17`. Export both plus SDK platform-tools and `~/.maestro/bin` on PATH before running the runner. AVD `chronicle` (API 35 arm64, TCG software emulation on this host) = emulator-5554; `chronicle_small` = emulator-5556. Wait for `adb shell getprop sys.boot_completed`=1 and for the app to finish its slow first launch.
- Build/install: `./gradlew :app:assembleDebug` → `adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk` (~245 MB). Package `com.chronicle.app`, activity `.MainActivity`.
- Maestro at `~/.maestro/bin/maestro` (install: `curl -fsSL https://get.maestro.mobile.dev | bash`). Flows + runner live in-repo: `e2e/flows/*.yaml` + `e2e/run_flow.sh <name> [device-serial]` (defaults emulator-5554).
- A stale `127.0.0.1:*  offline` entry in `adb devices` makes Maestro report "device was requested, but it is not connected" — `adb disconnect 127.0.0.1:*` fixes it.
- `run_flow.sh` force-stops + monkey-launches per flow (verified with pidof), grants POST_NOTIFICATIONS, and retries the whole run on "device offline"/"not connected" adbd flakes. Flows therefore carry no `launchApp` step — they start with `waitForAnimationToEnd` on the cold-launched Today screen.
- Suites are order-dependent: tc5 asserts entries written by tc2/tc3; e3 expects tc12's "Stretch" + tc4's "Water the fern" + e2's entry; e5 deletes e2's entry. Run tc1→tc12c, then e*, then fix*. `pm clear com.chronicle.app` for a clean baseline (re-grants auto-permissions).

## Maestro-on-Compose pitfalls that cost time
- `launchApp` internally force-stops and flakes adbd with "device offline" (~50% of runs on this host). Omit it from flows; the runner monkey-launches.
- Text selectors are **full-string regex** matches against a node's whole text — use `[\s\S]*` tails for anything dynamic and `^…$` anchors to disambiguate (e.g. `'^Journal$'` vs the Journal page heading).
- Redesign note: each `Text` composable stays a separately-matchable node even inside `selectable`/`clickable`/`toggleable` cards — anchored card titles like `'^Better voice typing · 487 MB · offline$'`/`'^Custom model$'` tap the card fine.
- Sentence case everywhere: `Chron.type.meta` is plain Inter — no more uppercase MetaText anywhere (the legacy `ui/components/` package is deleted). Nav labels are `Today`/`Journal`/`Story`/`To-dos`; mode pills `Speak`/`Write` (Role.Tab).
- `inputText` without a focused field sends keyevents to whatever holds focus. Always `tapOn` the field's placeholder text first (`'Add a to-do…'`, `'Type your note…'`).
- Nav labels collide with same-named headings/rows. Anchored `'^Journal$'` etc. taps work (icon desc + label both match); point taps at y≈93% still work as fallback (TODAY 13%, JOURNAL 35%, STORY 57%, INTENTIONS 82%).
- The FloatingNavBar slides away while the IME is up — `hideKeyboard` before nav taps (the swipe-back IME also makes nav point taps hit keys).
- Long lists: content below the fold isn't in the semantics tree — use `scrollUntilVisible` before asserting/tapping (To-dos open card, Mind page sections).
- `assertVisible` on system permission dialogs races the dialog entrance; use `tapOn` directly or tolerate a retry.
- `doubleTapOn` works (2.10.0) — used for the double-tap-Keep edge case.
- `takeScreenshot:` artifacts land in `~/.maestro/tests/<timestamp>/<flow>/takeScreenshot/`; per-step failure shots in `screenshots/step-NNN-*.png`.
- Maestro 2.10 does NOT accept a top-level `when:` step — use `tapOn: {text: X, optional: true}` for state-tolerant steps (prints WARNED and continues).
- Maestro 2.10 has no `pasteText`. `inputText` can take ~1.5 seconds per character on TCG: split strings into chunks of at most 100 characters. Select all before clearing existing text; `eraseText: 200` only clears before the cursor.
- `connectedDebugAndroidTest` removes the app afterward: reinstall the debug APK before E2E. Headless `-no-audio` emulators cannot certify live mic, SpeechRecognizer, or model transcription.

## New-UI selectors (post-redesign)
- Today idle: date meta `EEE · d MMM`; serif greeting renders as TWO nodes ("Good" / "evening."); "What happened today?"; gear `content-desc="Settings"`; capture circle `content-desc="Record"`; caption "Tap to talk" (hidden when the measured circle slot is too short, e.g. a tall note card on the small AVD at 1.3x text). All idle positions are measured. Speak-only bottom card is "No notes yet today.", "Earlier today", or the Smart setup card. After the first note, "Get Smart to-dos" can replace Earlier today; tap "Not now" to restore the baseline for old flows. RAM gating can hide the offer (Qwen needs 6 GB).
- Write mode: placeholder "Type your note…"; "Keep" pill is disabled until the draft is non-blank.
- Recording: HUD "Recording…" + `M:SS` timer + waveform; bottom row has Discard icon+text left, centred stop circle `content-desc="Stop"`, and a non-clickable "Tap to keep" caption.
- Processing: headline "Transcribing…"/"Looking for to-dos…"/"Keeping your words…" + StepList rows.
- Saved state (multi-node, not merged): heading `Saved` + `Your note is saved\.`/`1 to-do added[\s\S]*`/`N to-dos added`; no engine footer. The engine is shown in expanded note details. Untimed row taps open "Add a time". "Maybe to-dos?" rows have Add/Skip; only Add persists a suggested task. Buttons: Open note / New note.
- Journal: heading "Journal"; day labels `Today`/`Yesterday`/`EEEE · d MMM`; times follow the phone's 12/24-hour preference; tapping a row expands a `^Delete$` ghost button (or customAction "Delete entry"); voice rows have `Play`/`Pause` descs + `M:SS`; empty state "Your journal is waiting." + "Start on Today".
- Saved-note tools: expand a Journal row → `^Clean up$` / `^Find tasks$`; each opens a bounded scrollable review dialog. Basic cleanup explicitly says it only fixes spacing; model cleanup is a draft, never auto-applied. `Apply cleanup` persists the edit; `Undo cleanup` restores the exact previous text across relaunches. `Review tasks` offers `Add task` (never an alarm), `Add reminder` only with a future time, `Edit or set time`, and `Skip`. Already accepted tasks from the same source note are omitted on re-analysis. New flows run in order: `note-tools` → `note-cleanup-persist` → `note-undo-persist` → `note-reminder`. They use the Zora/theatre-fees fixture and hedged sentences so Save itself creates no tasks. The runners cold-launch separately to verify persistent Undo. Use a clean emulator fixture, never clear a physical phone.
- Story: "Story" + italic "Your weeks, written back to you."; share `content-desc="Share story"`; hero card = current chapter (`Chapter [IVXLC]+` meta + "The week of the …" headline + "N entries · M words" + first paragraph); previous chapters are merged index rows (`content-desc="Chapter N, The week of…"`); chapter page `Back` + `Share story`; empty "Your first chapter is still being lived."
- To-dos: "To-dos" + "Things you said you'd do."; placeholder "Add a to-do…"; submit circle `content-desc="Add to-do"` (or IME Done); reminder pills (`No reminder`/`In an hour`/`Tonight`/`Tomorrow`, Role.RadioButton) appear after field focus; rows have `Mark done`/`Mark not done`, phone-format times (+ "· From your note" for extracted ones); completed rows move under `Done` after ~350 ms; delete via swipe or customAction "Delete to-do"; empty "No to-dos yet."
- Settings overview: title "Settings" (no subtitle); rows Appearance / Daily nudge / Voice to text / Smart to-dos / Advanced / Privacy / About. Sub-pages have a back button `content-desc="Back"`; Settings has no bottom bar — `pressKey: back` (×2 from a sub-page) to return to Today.
- Appearance page: "Ink is for the evening; Paper for morning light."; theme tiles are big selectable ScenePreviews (Role.RadioButton) — the `Ink`/`Paper`/`Follow the day` labels sit BELOW the selectable box, so tap the tile's embedded preview text: `Good evening\.` = Ink, `Good morning\.` = Paper (also inside the split Follow-the-day tile — first match hits Paper's). Theme switch stays on the page (no eject).
- Daily nudge: "A gentle reminder to write, once a day." + SoftSwitch (Role.Switch but NO text/desc → point-tap ~`84%,26%`); chips `07:00`/`12:30`/`18:00`/`20:00`/`21:30` appear when on.
- Voice to text: "How your voice becomes words."; cards `Phone.s voice typing` / `Better voice typing · 487 MB · offline`; selecting the latter reveals `Parakeet TDT 0.6B` status and download controls.
- Smart to-dos: Qwen 1.5B / Llama 3.2 1B / Llama 3.2 3B / `^Basic \(built in\)$`, descriptions, memory gating, Wi-Fi only switch. Llama opens an explanation sheet, then OAuth when configured or access-token fallback, then a user-operated licence page. The access watcher starts the download after approval. Smart setup state is shared with Today.
- Advanced: Basic / Custom model (paste-a-link pack) / OpenRouter / Custom endpoint. Existing keys/URL/model fields and behavior remain here. Debug builds show Test smart mode; no model installed → Install the selected model first. OAuth client ID is optional `hf.oauthClientId` in local.properties; never automate account/licence submissions.
- Model page: "On-device speech transcription." (Parakeet) / "An on-device mind that reads your entries." (Llama); `≈ 487 MB` / `varies with the model you pick` + "Runs entirely on this phone"; actions `Download · ≈ 487 MB`/`Download` → `Cancel` → `Try again` on failure → `Delete` + confirm dialog when Ready; "Download source" section is collapsed behind an `Edit`/`Hide` GhostButton — the URL EditText is prefilled for Parakeet (`https://github.com/k2-fsa.*`) and EMPTY for the LLM (licence-gated — the user pastes their own link); assert the field's value, there is no visible label.
- Emulator model-download reality: the custom model has no default URL; Qwen has a catalog URL. Never download its 1.6 GB for UI checks. smart1 runs with emulator Wi-Fi disabled, tests waiting/mobile constraint and cancels. Restore emulator Wi-Fi afterward. smart2 tests catalog/licence/token/spike navigation without signing in.

## Permission/perm-state quirks on the API-35 image
- RECORD_AUDIO and POST_NOTIFICATIONS arrive auto-granted — permission dialogs need `adb shell pm revoke com.chronicle.app android.permission.<PERM>` first (+ `pm clear-permission-flags ... user-set user-fixed` to re-prompt on this API-35 image; uppercase names are rejected). `pm revoke`/`pm clear` force-stop the app → the runner relaunches.
- Dangerous-permission dialog buttons: "While using the app" / "Only this time" / "Don't allow" (Maestro-visible; `'Don.t allow'` regex sidesteps the U+2019 apostrophe). e4/e4b expect RECORD_AUDIO revoked before the run; a zombie dialog can survive a relaunch — dismiss with an optional `Don.t allow` tap first.

## adb checks that worked well
- Entry counting without flaky scroll: `uiautomator dump` + count `text="[0-9]{2}:[0-9]{2}"` nodes (each entry row starts with HH:mm).
- Reminder verification: `dumpsys alarm | grep chronicle` — pending `RTC_WAKEUP ... ReminderReceiver` entries; done-toggle shows up as `pi_cancelled` in the log section.
- Persistence: `am force-stop` + monkey relaunch, then assert data + settings via Maestro.
- Chips/pills (`selectable`, `toggleable`) expose `checkable=true`/`checked=true` on the active one.

## Expected dynamic strings (compute from device date)
- Dates derive from the device date; displayed times follow 12/24-hour settings. Saved count: `N to-dos added`, optionally `· reminder <time>` for exactly one timed task. Same-day-strict phrases (e.g. tonight) are ambiguous once past: use rollover-proof `tomorrow at 9pm` in E2E. Rules goldens use fixed 2026-10-04T16:43 and must not override existing time tests.

## Layout matrix
- Use both AVDs, 3-button navigation (enable-exclusive --category overlay), font_scale 1.0 and 1.3. Run layout on a clean emulator baseline; capture Today empty/with notes, Saved, Recording, Journal, Story and To-dos. Restore font_scale 1.0 afterward. Do not clear the attached physical phone.
- Run the TCG AVDs serially. A paused emulator can still appear online and stall Maestro device discovery: shut down the unused AVD cleanly instead. Two active software-emulated AVDs produced startup ANRs/very slow swipes here. For test-only runs, system animation scales may be set to 0 (restore 1 afterward), and `adb shell cmd package compile -m speed -f com.chronicle.app` can precompile the installed debug app. This does not change app data or source. tc11c/tc11d use 120-second scroll deadlines without weakening their assertions.
- `run_smart_verification.sh smart` requires a >=6 GB AVD and disables both emulator Wi-Fi and mobile data while exercising constraints/cancel; neither path can transfer the 1.6 GB model. Both networks are restored afterward. Optional `E2E_REPORT_DIR` on run_flow saves per-attempt artifacts under a chosen report directory.
