# Saved-note tools and paragraph extraction

Implemented 2026-10-06 on `feat/smart-todos`, based on
`138d4aba1ee00e02ad20a989a687d98ffc65ef06`. Existing Smart to-dos changes
are preserved. No commit, push, release update, or merge was performed.

## What changed

- Fixed a concrete parser defect: normalization flattened newlines before
  sentence extraction. Line-separated intents now stay separate.
- Longer notes use sentence-aware windows of at most 800 characters. All
  sections are read; no tail is silently truncated. Inputs exceeding 40
  windows are rejected with guidance to split the note.
- Paragraph extraction retains clear rules tasks when a valid but incomplete
  model response omits them. The original single-capture merge contract and
  all 22 R-tier goldens remain unchanged. Whole-note rules recover a time
  that falls beyond a section boundary; duplicate titles prefer a timed result.
- The extraction prompt asks for every future commitment, excludes past,
  negated, hypothetical and other-person actions, and keeps each action's time
  separate. This is an instruction to the model, not a certified quality claim.
- The local runtime token capacity is 2048 instead of 1024. Per-section
  generation timeout stays 10 seconds locally; cloud calls retain their
  existing 20-second limit. Load remains outside the generation budget.
- Expand any saved text note or successfully transcribed voice note in Journal
  to use **Clean up** or **Find tasks** with the selected engine.
- Cleanup uses a separate strict JSON text contract in local and cloud engines.
  It asks for wording/grammar/spelling/paragraph improvements without adding
  facts or summarizing. The result is an editable draft; applying is explicit.
  A failed section falls back to formatting the complete original, not a
  half-rewritten note. Basic only fixes whitespace/punctuation spacing and
  explicitly says so.
- Undo cleanup restores the exact previous text. One undo revision per note
  is stored in the existing app-private DataStore sidecar before the Room
  update, survives process restart, and is only applied if text/status still
  match. Apply/undo use a Room transaction to protect newer transcriptions.
  Room schema/version/identity hash are unchanged.
- Find tasks is preview-only. **Add task** saves no time or alarm, even when a
  time was detected. **Add reminder** requires a future time and explicit
  acceptance. **Edit or set time** opens the existing task editor; **Skip**
  saves nothing. Re-analysis suppresses tasks already saved from the same
  note. Stale note previews cannot apply or add tasks.
- Existing automatic extraction on Save, reminder scheduling implementation,
  recording service, Story, model catalog/URLs and route strings are preserved.
  Cloud privacy copy now includes note tools and per-section time limits.

## Files in this pass

- `domain/TaskExtractor.kt`: preserve newline boundaries.
- `mind/NoteText.kt`, `NoteCleanupParser.kt`, `Mind.kt`, `MindRepository.kt`,
  `LocalLlmMind.kt`, `ChatMind.kt`, `MindResultParser.kt`, `SmartModels.kt`:
  section orchestration, strict cleanup contract, local/cloud support.
- `data/NoteDetailsStore.kt`, `JournalRepository.kt`,
  `data/db/ChronicleDatabase.kt`: undo sidecar and compare/replace transaction;
  no schema migration.
- `ui/vm/JournalViewModel.kt`, `ui/screens/JournalScreen.kt`,
  `ui/screens/NoteReviewDialog.kt`, `ui/nav/ChronicleRoot.kt`,
  `res/values/strings.xml`: saved-note actions and review flow.
- Three JVM test files (`ParagraphNotesTest`, `NoteCleanupCloudTest`,
  `NoteWorkflowTest`) and `NoteCleanupDeviceTest` instrumentation coverage.
- Four Maestro flows (`note-tools`, `note-cleanup-persist`,
  `note-undo-persist`, `note-reminder`) and the Android testing skill selectors.

## Verification

- `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` — PASS.
  **266 unit tests, zero failures**, including the unchanged 22 golden cases.
  **Lint: zero errors, 33 warnings**. Debug APK assembled successfully.
- `ANDROID_SERIAL=emulator-5556 ./gradlew :app:connectedDebugAndroidTest` —
  PASS, **3/3**: existing migration/keystore tests plus the new Room/DataStore
  cleanup restoration and stale-text protection check.
- `note-tools` — PASS on `chronicle_small`, 3-button navigation,
  `font_scale=1.3`: review, add plain task, skip, duplicate suppression,
  Basic cleanup disclosure, original preview, apply, exact undo, accepted task.
- `note-cleanup-persist` then `note-undo-persist` — PASS, separate runner
  cold launches: undo survives full process stop/relaunch.
- `note-reminder` — PASS on the final APK: no reminder action before a time
  is chosen, edit/set Tomorrow, review both plain-task and reminder choices,
  explicitly accept the reminder, then verify it in To-dos. `dumpsys alarm`
  confirms Chronicle's `ReminderReceiver` RTC_WAKEUP for 2026-10-07 09:00.
  All four new flows passed without retries.
- Screenshots inspected. The dense review surface was made opaque so the
  original note does not show through its text; scrolling and pinned actions
  remain bounded at the small-screen/large-text setting.
- `git diff --check` — PASS. No schema artifacts changed.

Artifacts under `app/build/reports/note-tools/`:

- Initial full workflow: `e2e/attempt-1/2026-10-06_123809/note-tools/`.
- Final opaque cleanup preview and persistence setup:
  `persist-setup/attempt-1/2026-10-06_124628/note-cleanup-persist/`.
- Restart/undo: `persist-undo/`.
- Reminder acceptance: `reminder/`.
- Unit XML: `app/build/test-results/testDebugUnitTest/`.
- Instrumentation XML: `app/build/outputs/androidTest-results/connected/debug/`.

APK: `app/build/outputs/apk/debug/app-debug.apk`.
SHA-256: `96a43a6afbf9028bbfd07509e4a36a9e447addcbcdf77e26528e03cee0ab6ebd`.

## Remaining gates and limits

- Actual Qwen/Llama paragraph inference and wording cleanup on the physical
  phone are **not certified**. Emulator E2E uses Basic; JVM model tests use
  fake engines. The new 2048-token runtime configuration needs phone timing,
  memory and quality checks. No model or account/licence was downloaded here.
- Re-test the user's actual failing paragraph with the selected installed
  model. Chunk boundaries can lose cross-sentence references; model rewrites
  can still omit or alter facts. The original/preview/undo safeguards are not
  a guarantee of model accuracy.
- Cleanup keeps one previous revision, not a multi-step undo history. Undo
  does not undo task additions or change existing reminders. Those remain
  editable/removable in To-dos.
- The previous full 36-flow regression and two-AVD layout matrix were not
  re-run for this follow-up; only the targeted new flows and full JVM/build
  gates are current evidence for this pass.
- The downloaded release APK is not changed by local implementation. Install
  the rebuilt debug APK to review these features.
- Emulator font scale/animation scales restored to 1.0/1; the test emulator
  was shut down after verification. No physical phone was connected or changed.
