# Chronicle — End-to-End Test Plan

**App:** com.chronicle.app (Kotlin/Compose, Room, SpeechRecognizer, MediaRecorder, AlarmManager)
**Env:** AVD `chronicle` — API 35 google_apis arm64-v8a, TCG software emulation (`-accel off -feature -HVF`; this host is a nested-virt Mac without HVF). Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
**Note:** Emulator speech recognition expected unavailable — accepted per brief; record anyway, verify audio-note path.

## TC1 — Launch → Today screen
- Launch `com.chronicle.app/.MainActivity`.
- **Assert:** greeting line ("Good morning." / "Good afternoon." / "Good evening." per hour) in Fraunces serif; mono meta date "FRI · 25 SEP" top-left; gear icon top-right; italic "What happened today?"; SPEAK / WRITE mode switch (Speak selected, brass underline); bottom bar shows Today · Journal · Story · Intentions, Today in brass.
- **Assert (design):** ivory canvas #FAF9F7, hairline rules, mono uppercase MetaText, single brass accent, no gradients/shadows.

## TC2 — WRITE: keep a plain entry
- Tap "WRITE" → placeholder "Begin anywhere. The day will shape itself." + "KEEP" action (faint while blank).
- Type `walked the coast road, cold wind` → tap KEEP.
- **Assert:** confirmation meta "KEPT." in brass; field cleared; "EARLIER TODAY" section appears with entry (HH:mm + text).

## TC3 — Task extraction from written line
- In WRITE, type `remind me to call the dentist tomorrow at 5` → KEEP.
- **Assert:** confirmation "KEPT — 1 REMINDER SET."
- Intentions tab: **assert** masthead "PULLED FROM YOUR WORDS / Intentions"; task row "Call the dentist" with bell icon + "SAT 26 SEP · 17:00" (tomorrow 17:00 — bare "at 5" maps to evening); empty-state gone.

## TC4 — Manual task + done toggle
- Intentions: type `Water the fern`, tap "TOMORROW" chip, tap SET.
- **Assert:** task "Water the fern" appears, bell + tomorrow date "SAT 26 SEP · 09:00"; draft + chip reset.
- Tap ring on "Call the dentist" → moves under "DONE" heading, strikethrough.
- Shell check (not on camera): `adb shell dumpsys alarm` shows pending com.chronicle alarms.

## TC5 — Journal tab
- **Assert:** masthead "THE RECORD / Journal"; "Today" heading + "Friday"; both saved entries listed newest-first (pen icon, HH:mm, transcript text); hairline separators.

## TC6 — Story tab
- **Assert:** masthead "BEING WRITTEN / The Story"; meta "1 days · 2 entries · N words"; card "CHAPTER I · UNFINISHED", subtitle "The week of the twenty-first of September".
- Tap card → detail: "CHAPTER I" kicker, serif subtitle, "2 entries · N words", paragraph starting "On Friday, the twenty-fifth of September, Walked the coast road, cold wind. Later, Remind me to call the dentist tomorrow at 5."
- Back.

## TC7 — Settings
- Gear → "Details / The quiet particulars".
- **Assert:** Appearance chips INK / PAPER / FOLLOW THE DAY (default = Follow the day); tap INK → entire UI switches to dark canvas #1A1B1F (screenshot); tap PAPER → back to ivory.
- Toggle "Ask me about the day" ON → system notification-permission dialog (API 33+) → Allow → time chips 07:00/12:30/18:00/20:00/21:30 appear, 20:00 selected.
- Toggle OFF → chips disappear.
- Back.

## TC8 — SPEAK mode (emulator caveat)
- Tap SPEAK → big ring record button; tap → RECORD_AUDIO permission dialog → "While using the app".
- **Assert:** recording UI: waveform strip, elapsed timer "0:0X" (brass), stop-square button, "TAP TO KEEP" + "DISCARD".
- Emulator: SpeechRecognizer likely unavailable — live transcript may stay empty (accepted). If MediaRecorder produces a file, tap stop ("Tap to keep") → entry lands in "EARLIER TODAY" as "A voice note" with mic icon; Journal shows play control.
- If app crashes on record start (no audio source) → flag as finding.

## TC9 — Design sweep (throughout)
- Light "paper" canvas; Fraunces serif headings; Inter body; JetBrains Mono metadata; single brass accent; hairline rules; NO gradients/card-shadows. Flag any overlap, truncation, misalignment.

## TC10 — Settings: The ear (STT engines) [new in 209f5a8]
- Settings → "The ear — speech to text" section.
- **Assert:** chips "SYSTEM (ON-DEVICE)" + "PARAKEET TDT 0.6B"; System selected → italic "Android's recognizer, kept on-device. Nothing to download."
- Tap PARAKEET → italic "NVIDIA's Parakeet — sharper ears, transcribes when the take ends."; ModelRow card appears: title "Parakeet TDT 0.6B · speech to text", meta "~250 MB", "GET IT" action, "Model download URL" field.
- Tap GET IT once → state flips to Downloading: "%"/"N MB" in brass + "CANCEL" + hairline progress bar. Tap CANCEL immediately → back to "GET IT" (do NOT wait for 250MB on TCG).

## TC11 — Settings: The mind (backends) [new in 209f5a8]
- Settings → "The mind — words into meaning".
- **Assert:** chips "QUIET PARSER" / "ON-DEVICE MODEL" / "OPENROUTER" / "CUSTOM ENDPOINT"; Quiet parser default → "A deterministic reader — fast, private, no model needed."
- ON-DEVICE → "Llama 3.2 3B runs entirely on this phone. Words never leave." + ModelRow "Llama 3.2 3B · on-device mind" / "~2 GB" + gated note "accept Meta's licence on huggingface.co". Tap GET IT → Downloading UI → CANCEL.
- OPENROUTER → masked "OpenRouter API key (sk-or-…)" field + "FREE MODELS" chips; type a dummy key → masks; pick a model chip.
- CUSTOM → fields "Base URL (https://api.openai.com/v1)", "Model (gpt-4o-mini, …)", "API key (optional for some endpoints)" (last masked).
- Back to QUIET PARSER → all panels collapse, italic baseline text.

## TC12 — Processing hint + byline [new in 209f5a8]
- WRITE → keep an entry → watch for brass "READING YOUR WORDS…" hint before confirmation; confirmation may carry "read by Quiet parser" byline.
- **Assert (edge):** after save, if mind backend is Quiet parser, task extraction still works (hard fallback).

## Edge cases (carry-over)
- KEEP with empty draft → no entry created.
- Double-tap KEEP → exactly one entry (saving guard).
- Force-stop + relaunch → entries/tasks persist; settings persist.
- Deny RECORD_AUDIO → SPEAK tap degrades gracefully (no crash).
- Task done toggle → strikethrough under "DONE"; entry delete → gone from Today+Journal.
- Manual task with "No reminder" chip → saves without bell icon.
