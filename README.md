# Chronicle

A quiet, local-first Android journal. Speak or write what happened today —
Chronicle keeps it on-device, quietly pulls out the things you said you'd do,
and over time collates your days into a written story.

No accounts. No feeds. No AI gloss. By default, everything lives on the
phone.

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

## The Mind

Settings → The Mind chooses how entries are read:

- **Quiet parser** (default) — deterministic rules; instant, offline,
  nothing leaves the phone.
- **On-device model** — Llama 3.2 3B, downloaded once and run locally.
- **OpenRouter** — free cloud models; pick one from the list or type any
  model id (`vendor/model[:tag]`). Needs an API key.
- **Custom endpoint** — any OpenAI-compatible server. HTTPS only;
  cleartext HTTP is refused app-wide by the network security config.

For the cloud backends, the entry's text — nothing else — is sent to the
configured service when you save. API keys are stored encrypted via
Android Keystore. A cloud call gives up after 20 seconds; on any failure
the Quiet parser answers instead, and the kept card names the engine that
actually responded.

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
