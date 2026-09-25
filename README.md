# Chronicle

A quiet, local-first Android journal. Speak or write what happened today —
Chronicle keeps it on-device, quietly pulls out the things you said you'd do,
and over time collates your days into a written story.

No accounts. No feeds. No AI gloss. Everything lives on the phone.

## What it does

- **Today** — capture the day by voice (on-device live transcription via
  `SpeechRecognizer`) or by text.
- **Journal** — every entry in order, grouped by day, with audio playback.
- **The Story** — deterministic collation of entries into weekly chapters
  ("On Friday, the twenty-fifth of September, …"), shareable as Markdown.
- **Intentions** — rule-based extraction turns "remind me to call Sam tomorrow
  at 5" into tasks with real reminders (`AlarmManager` + notifications),
  rescheduled across reboots.
- **Details** — Ink (dark) / Paper (light) / system theme; an optional daily
  writing nudge.

## Stack

Kotlin · Jetpack Compose (Material 3) · Room (KSP) · DataStore ·
Navigation Compose · MediaRecorder/MediaPlayer · SpeechRecognizer ·
AlarmManager. JUnit JVM tests for the task extractor and story composer.

## Design language

Editorial "quiet luxury": warm ivory / deep ink surfaces, a single brass
accent, hairline rules instead of cards, Fraunces serif headlines with Inter
body and JetBrains Mono metadata, tracked-uppercase labels, generous negative
space, spring motion and haptics. Deliberately free of gradients,
glassmorphism, streak counters, and other AI-fluff patterns.

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Requires Android SDK (platform 36) and `local.properties` or `ANDROID_HOME`.
Min SDK 26, target SDK 36.
