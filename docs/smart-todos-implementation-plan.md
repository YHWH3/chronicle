# Smart to-dos — implementation plan (handoff)

Owner of decisions: the user. Author of this plan: lead session, 2026-10-04.
Implementer: read this whole file before writing code. Where this file says
"decided", do not re-open the choice; where it says "spike" or "verify", the
result decides the branch you take — report it back.

## 0. Ground rules

- Start from `codex/chronicle-production-hardening` @ `138d4ab` on a new branch
  `feat/smart-todos`. PR #4 (hardening) is OPEN and must NOT be merged by you.
- Repo testing guide: `.agents/skills/testing-chronicle-android/SKILL.md`
  (Maestro quirks, adb PATH, order-dependent suite). Read it first.
- Gates before every hand-back: `./gradlew :app:testDebugUnitTest :app:lintDebug
  :app:assembleDebug`. Full Maestro suite once at the end (36 flows today).
- Do not change: Room schema, reminder scheduling, the recording FGS, Story,
  Parakeet download path, `Routes.*` string values (deep links use them).
- New dependencies: pin versions published ≥ 7 days ago; no `latest.release`.
- Never log tokens, Authorization headers, or signed CDN URLs (query strings).

## 1. Decisions (confirmed by the user)

| Topic | Decision |
|---|---|
| Default smart model | **Qwen2.5-1.5B-Instruct q8 (.task)** — no account, auto-download from the Today screen |
| Other models | Offer a **model list** with what each does: Qwen 1.5B (default), Llama 3.2 1B, Llama 3.2 3B, Basic |
| Llama licence | User accepts Meta's licence on Hugging Face via an in-app guided flow; download **starts by itself** once access is granted |
| Feature name | **Smart to-dos**; tab "Intentions" → **To-dos** |
| Network | **Wi-Fi only by default**, with a visible "Use mobile data" option |
| Wording | Plain everyday words everywhere (table in WP2) |

## 2. Verified facts (checked 2026-10-04 — do not re-derive)

| Model | Repo / file | Bytes | SHA-256 | Gate | Format |
|---|---|---|---|---|---|
| Qwen2.5 1.5B | `litert-community/Qwen2.5-1.5B-Instruct` / `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task` | 1,598,556,720 | `82968d0a6c3872cf016fdbcfc591571605f4c7fd2b0f64d2533df502cc6596b3` | none (Apache-2.0) | `.task` |
| Llama 3.2 1B | `litert-community/Llama-3.2-1B` / `llama3_2_1b_mixed_int4_gpu.litertlm` | ≈964 MB (read exact from HEAD) | `c6f21c5304c40f45b69e5bfb15bb45acbaad7938e941d25a0a391aef0ec6ac27` | `auto` (instant after Agree) | `.litertlm` |
| Llama 3.2 3B | `litert-community/Llama-3.2-3B` / `llama3_2_3b_mixed_int4_gpu.litertlm` | ≈2,208 MB (read exact from HEAD) | `63419d689ef141cc77d6ab5739f27426d779e1165546a4904edefa9fc36fea51` | `auto` | `.litertlm` |

- Download URL form: `https://huggingface.co/<repo>/resolve/main/<file>`.
  Qwen: anonymous → `302` to a signed CDN URL → `200`, `accept-ranges: bytes`.
- Llama anonymous → `401`, header `x-error-code: GatedRepo`.
- Hugging Face OAuth supports **public apps (no secret) with PKCE** and a
  `gated-repos` scope ("read public gated repos the user has been granted").
  Custom-scheme redirect URIs must match exactly. Docs: https://huggingface.co/docs/hub/oauth
- MediaPipe `LlmInference` (current runtime, tasks-genai 0.10.35) is
  maintenance-only; Google recommends LiteRT-LM. `.litertlm` loading through
  MediaPipe is **unverified** for these Llama files → WP3 spike.
- Current gaps in code: `ModelKind.LOCAL_LLM` has no default URL
  (`mind/ModelStore.kt:28-34`); `mindBackend` defaults to `QUIET_PARSER`
  (`data/PrefsRepository.kt:123-124`); `Fetcher.open` sends no auth headers and
  follows redirects implicitly (`mind/ModelInstallManager.kt:57-90`).

## 3. Work packages (do in this order)

### WP1 — Bottom-bar overlap (S23 Ultra, 3-button nav)

Root cause: screens reserve a fixed `96.dp` (`ui/nav/ChronicleRoot.kt:183`)
but `FloatingNavBar` occupies `64 + 12 + navigationBars inset`
(`ui/design/Nav.kt:84-93`). 3-button nav inset ≈ 48dp → ~124dp used,
~28dp of content hidden. Gesture nav (emulator) hid it.

Do:
1. In `ChronicleRoot`, compute
   `bottomBar = 64.dp + 12.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 8.dp`
   and provide it via `LocalBottomBarPadding`. Remove every other hard-coded
   assumption. Consumers today: `TodayScreen.kt:407,441,469,555`,
   `JournalScreen.kt:78`, `StoryScreen.kt:117`, `TasksScreen.kt:147`,
   `ChronicleRoot.kt:413`.
2. Today idle layout: stop placing greeting/pills/circle/caption with
   `H * fraction` offsets. Use a `Column`: header → greeting → pills →
   `Box(Modifier.weight(1f))` holding the circle + caption centred → bottom
   card. The record/processing/kept morph may keep its own animation, but the
   idle positions must come from measured layout.
3. Times: replace hard-coded `HH:mm` with the phone's 12/24h setting
   (`android.text.format.DateFormat.is24HourFormat`) in Today, To-dos, Journal.

Accept: on an emulator with 3-button nav AND `font_scale 1.3`, no text sits
under the bar on Today (empty + with entries), Kept, Recording, Journal,
Story, To-dos. Commands:
`adb shell cmd overlay enable com.android.internal.systemui.navbar.threebutton`
`adb shell settings put system font_scale 1.3` (reset to 1.0 after).

### WP2 — Plain wording + string resources

Move every user-facing string you touch into `res/values/strings.xml`.
Mapping (decided):

| Where | Now | New |
|---|---|---|
| Tab | Intentions | To-dos |
| To-dos header | What you mean to do. | Things you said you'd do. |
| To-dos field | Add an intention… | Add a to-do… |
| To-dos row meta | From your journal | From your note |
| Kept heading | Kept. | Saved |
| Kept line | Saved to your journal. | Your note is saved. |
| Kept line | 1 reminder set / N reminders set | 1 to-do added / N to-dos added (+ "· reminder 5:00 pm" when exactly one has a time) |
| Kept row | No reminder | (hide) — row tap offers "Add a time" |
| Kept footer | Read by Quiet parser | (remove; show engine only in note details) |
| Kept section | Suggestions | Maybe to-dos? — buttons **Add** / **Skip** |
| Kept buttons | View entry / Add another | Open note / New note |
| Today empty card | Nothing kept yet today. / Speak or write — Chronicle keeps it… | No notes yet today. / Tap the mic and talk. We'll save it and pick out your to-dos. |
| Today caption | Tap to speak your day | Tap to talk |
| Write placeholder | Begin anywhere. The day will shape itself. | Type your note… |
| Settings subtitle | The quiet particulars. | (remove) |
| Settings rows | The Ear / The Mind | Voice to text / Smart to-dos |
| Mind options | Quiet parser / On-device model | Basic (built in) / model list (WP3) |
| Ear options | Android speech recognition / Parakeet | Phone's voice typing / Better voice typing · 487 MB · offline |
| Advanced | OpenRouter, Custom endpoint, model link field | Move under **Settings → Advanced** (unchanged behaviour) |

Keep `Routes.TASKS = "tasks"` and all route strings unchanged.
Update every Maestro flow and the testing skill for new strings in the same
commit as the string change.

### WP3 — Model catalog + runtime spike

**Spike first (blocks WP4/WP5 UI polish, not their plumbing):**
Add a debug-only screen *Settings → Advanced → Test smart mode* that, for
the selected installed model, loads it and runs the 5 spike prompts below,
showing for each: load ms, total ms, raw reply, parsed tasks, JSON valid y/n.
The user runs this on the S23 Ultra and reports. Spike prompts = golden cases
G1, G2, G4, G12, G20 (section 4).

Decision rules from the spike:
- Qwen replies not valid JSON → wrap the prompt in ChatML
  (`<|im_start|>system\n{MIND_PROMPT}<|im_end|>\n<|im_start|>user\n{capture}<|im_end|>\n<|im_start|>assistant\n`)
  inside a per-model `promptFormat`; re-run.
- Llama `.litertlm` fails to load under MediaPipe → add `LiteRtLmMind`
  (`com.google.ai.edge.litertlm:litertlm-android`, pinned) implementing
  `MindEngine` + `ReleasableEngine`; catalog field `runtime` picks the engine.
  Do not remove MediaPipe (Qwen `.task` keeps using it).

**Catalog** (new `mind/SmartModels.kt`):

```kotlin
enum class Runtime { MEDIAPIPE, LITERT_LM }
data class SmartModel(
    val id: String,              // "qwen2_5_1_5b", "llama3_2_1b", "llama3_2_3b"
    val displayName: String,     // "Qwen 1.5B"
    val whatItDoes: String,      // user copy below
    val repo: String, val file: String,
    val bytes: Long, val sha256: String,
    val gated: Boolean,
    val minRamGb: Int,           // 6, 4, 8
    val runtime: Runtime,
    val promptFormat: PromptFormat,
)
```

User copy (`whatItDoes`) — final wording must not contradict the WP6 eval:
- Qwen 1.5B — "Recommended. Good at spotting to-dos and times. 1.6 GB. No account needed."
- Llama 3.2 1B — "Smaller and quicker, a little less accurate. About 1 GB. Needs a free Hugging Face account."
- Llama 3.2 3B — "Most accurate, but slower. About 2.2 GB. Needs a newer phone and a free Hugging Face account."
- Basic — "Built in. Catches clear phrases like 'remind me to…'. Nothing to download."

Models whose `minRamGb` exceeds `ActivityManager.MemoryInfo.totalMem` are
shown disabled with "Needs a phone with more memory".

**Install plumbing:** key `ModelInstallManager` state/jobs by a `packId:
String` instead of `ModelKind` (`"parakeet"`, `"llm/<id>"`), files under
`models/llm/<id>/`. Keep Parakeet behaviour identical. Keep the existing
paste-a-link path as a `"llm/custom"` pack under Advanced. Prefs:
`selectedSmartModelId` (default `qwen2_5_1_5b`). Only the selected model is
loaded (`LlmEngineHolder` already reuses/releases). Installing a second model
keeps the first on disk; the list shows **Delete** per installed model.
Pin each model's `sha256` (verification already exists — `sha256For`).

### WP4 — One-tap setup on Today + background download

**Auto mode.** Add `MindBackend.AUTO` and make it the default:
resolve → selected smart model if installed, else `DeterministicMind`.
Add pref `mind_user_chosen: Boolean`. Migration on first read: if
`mind_user_chosen` is absent and `mind_backend` is absent or `QUIET_PARSER`,
treat as `AUTO`. Any explicit choice in Settings sets `mind_user_chosen=true`.
Unit-test the migration both ways.

**Smart setup card** (`ui/screens/SmartSetupCard.kt`) occupies Today's bottom
card slot. When it is visible and today has entries, a one-line
"N notes today ›" link sits above it (opens Journal).

| State | Shown | Content |
|---|---|---|
| Hidden | smart model installed & ready; dismissed < 7 days ago; RAM too low; zero notes ever | — |
| Offer | after the first saved note | "Get Smart to-dos" / "Understands your notes better. 1.6 GB, works offline." [Download] [Not now] · small link "Other models" (→ Settings → Smart to-dos) |
| Waiting | on mobile data, not allowed | "Waiting for Wi-Fi" [Use mobile data] |
| Downloading | progress | "Downloading… 42%" + bar [Pause] |
| Setting up | extracting / first load | "Almost ready…" |
| Ready | once, then Hidden | "Smart to-dos is on." |
| Failed | | "Couldn't finish. [Try again]" (reason in Settings only) |

"Not now" stores `smartOfferDismissedAt`. Settings → Smart to-dos always
shows the same states for the selected model.

**Background download (decided: WorkManager).**
- Add `androidx.work:work-runtime-ktx` (pinned).
- `ModelDownloadWorker` (CoroutineWorker) with unique work name
  `model-<packId>`, `ExistingWorkPolicy.KEEP`, calls a new suspend
  `ModelInstallManager.installNow(packId, url, headers)` (extract the body of
  `runInstall`; `install()` keeps working for Parakeet).
- Foreground: `setForeground(ForegroundInfo(id, notification,
  FOREGROUND_SERVICE_TYPE_DATA_SYNC))`. Manifest: `FOREGROUND_SERVICE_DATA_SYNC`
  permission + override WorkManager's `SystemForegroundService` with
  `android:foregroundServiceType="dataSync"` (`tools:node="merge"`).
  Channel `chronicle_downloads`, low importance, progress, actions Pause/Cancel.
- Constraints: `NetworkType.UNMETERED` unless pref `allowMobileData=true`
  (then `CONNECTED`); `setRequiresBatteryNotLow(true)`; exponential backoff.
- Pause = cancel work (the `.part` stays; resume already works).
  Cancel = cancel work + delete `.part`.
- When install completes and backend is `AUTO`, smart mode is live on the
  next capture — no extra step. Warm the engine off-main when Today opens.

### WP5 — Llama: guided licence + automatic download

Flow (user taps a Llama model in Settings → Smart to-dos):

1. **Explain sheet:** "Meta asks you to accept their licence first.
   1) Sign in to Hugging Face (free). 2) Tap **Agree** on Meta's page.
   3) Come back — the download starts by itself." [Continue] [Cancel]
2. **Sign in** (choose by config):
   - **OAuth (preferred)** when `BuildConfig.HF_OAUTH_CLIENT_ID` is non-blank
     (read from `local.properties` key `hf.oauthClientId`; never commit it as
     a secret — it is public, but keep it out of git anyway). Authorization
     code + PKCE (S256), scopes `openid profile gated-repos`, redirect
     `chronicle://oauth/huggingface`, opened in a Custom Tab
     (`androidx.browser:browser`, pinned). New exported `HfAuthRedirectActivity`
     with a BROWSABLE intent filter for that URI; verify `state`. Exchange at
     `https://huggingface.co/oauth/token`. Store access/refresh tokens through
     the existing `SecretStore` sealing in `PrefsRepository`.
   - **Token paste (fallback, always available under "Use an access token
     instead")**: instructions + button opening
     `https://huggingface.co/settings/tokens`; user creates a *Read* token;
     validate with `GET https://huggingface.co/api/whoami-v2` (Bearer).
3. **Licence:** open `https://huggingface.co/<repo>` in a Custom Tab. The app
   never submits the licence form itself.
4. **Access watcher:** on return (`ON_RESUME`) and every 5 s for up to 3 min
   while the sheet is open: `HEAD <resolve url>` with Bearer and redirects
   disabled. `200`/`302` → granted → enqueue `ModelDownloadWorker`
   automatically and show "Downloading…". `401/403` + `x-error-code:
   GatedRepo` → keep "Waiting for you to tap Agree…" with [Open page again].
   Token rejected (`401` without GatedRepo) → back to step 2.
5. **Downloader auth:** extend `Fetcher.open(url, rangeStart, headers)`; set
   `instanceFollowRedirects = false`; follow ≤ 5 redirects manually; send
   `Authorization` **only** to host `huggingface.co`, never to the CDN host
   (signed URLs reject a second auth mechanism). Each resume re-starts from
   the `huggingface.co` URL (signed URLs expire). Unit-test with the fake
   fetcher: header present on hop 1, absent on hop 2; Range preserved.
6. Sign out (Settings → Advanced) clears tokens; installed models stay.

About page: credit "Qwen2.5 — Alibaba Cloud, Apache-2.0" and "Llama 3.2 —
Meta, Llama 3.2 Community License" when installed.

### WP6 — Parser quality (most important)

Pipeline: `clean text → rules (always) ∥ smart model (if ready, 10 s
generation budget, load time excluded) → merge`.

**Rules (`domain/TaskExtractor.kt`) — required changes:**
1. Normalise: remove spaces before `,.;:!?`; collapse whitespace.
2. Strip fillers anywhere in a title: actually, basically, just, really,
   probably, um, uh, like (as filler, i.e. `, like,`), you know, kind of, sort of, I mean.
3. Intent phrases: also accept `i also need to`, `i still need to`,
   `need to` at sentence start (no "I").
4. Vague placeholder: `do (some|a few|my|the) (tasks|things|stuff|errands|chores)`
   followed by `, mainly|, like|, such as|, especially|:` → drop the
   placeholder, keep what follows.
5. Split coordinated actions: inside one capture, split on `and`, `and then`,
   `then`, `and to` when the right side starts with an action verb from
   `ACTION_VERBS` (call, go, pick, buy, send, email, text, book, pay, finish,
   clean, fix, take, get, check, write, submit, renew, cancel, order, collect,
   return, drop, meet, visit, schedule, sign, print, water, feed, pack, back,
   start, sort). Each part resolves time on its own text; a part with no time
   inherits none.
6. Title cut: end at the first clause break (`,` + filler/“so that”/“because”),
   then cap at 10 words, never ending on a function word.
7. Do not change existing time resolution behaviour (all current tests stay green).

**Smart model:** keep `MIND_PROMPT` JSON contract; add 6 few-shot examples
(taken from G-cases below, not the spike ones); temperature/top-k lowest the
runtime allows. Validate with existing `MindResultParser`.

**Merge (`mind/MindRepository`):** model titles win; every model time is
accepted only if in the future and < 365 days ahead, else re-resolved by
rules on that task's text; any rules task from an explicit `remind me` /
`don't let me forget` phrase that the model dropped is added back.
Fallback on timeout/error = rules result (existing behaviour).

**Golden tests:** add `TaskExtractorGoldenTest` covering every `R` case in
section 4 (now = 2026-10-04T16:43, Sunday). Compare titles case-insensitively
with whitespace normalised; times to the minute. `M` cases are for the
on-device check screen (WP3), not JVM tests.

### WP7 — Verification

- Unit: all existing (194) + new: prefs migration, catalog RAM gating,
  fetcher redirect/auth, access-watcher state machine (fake HTTP), golden set.
- E2E (update + add): new strings everywhere; smart card appears after first
  note; Download → Cancel on emulator (never pull 1.6 GB in CI); "Use mobile
  data" toggles constraint; Settings → Smart to-dos lists 4 options with
  descriptions; Llama shows the account explanation sheet.
- Layout matrix: 3-button nav × font 1.0/1.3 × `chronicle` and
  `chronicle_small` AVDs — screenshots of Today/Kept/To-dos.
- Hand back: branch, SHAs, gate output, screenshots paths, spike screen ready
  for the user's S23 Ultra.

## 4. Golden cases (now = Sun 2026-10-04 16:43)

| ID | Tier | Input | Expected to-dos (title → reminder) |
|---|---|---|---|
| G1 | R | remind me to call mum tomorrow at 5 | Call mum → 2026-10-05 17:00 |
| G2 | R | I need to actually do some tasks , mainly to go to the bank and pick up the parcel | Go to the bank → none; Pick up the parcel → none |
| G3 | R | I need to buy milk , eggs and bread | Buy milk, eggs and bread → none |
| G4 | R | um so basically I gotta email Sarah about the invoice by Friday | Email Sarah about the invoice → 2026-10-09 09:00 |
| G5 | R | I should have called her yesterday | (none) |
| G6 | R | I should probably call the dentist | Call the dentist → none, suggested |
| G7 | R | walked the coast road, cold wind | (none) |
| G8 | R | remind me to stretch tonight at 9 | Stretch → 2026-10-04 21:00 |
| G9 | R | remind me in half an hour to take the pizza out | Take the pizza out → 2026-10-04 17:13 |
| G10 | R | don't let me forget to renew my passport next week | Renew my passport → 2026-10-11 09:00 |
| G11 | R | I'm supposed to meet Raj for lunch on Tuesday at 1 | Meet Raj for lunch → 2026-10-06 13:00 |
| G12 | R | I need to pay rent and then call the landlord about the leak | Pay rent → none; Call the landlord about the leak → none |
| G13 | R | the meeting went well, I need to send the notes to the team tomorrow morning | Send the notes to the team → 2026-10-05 09:00 |
| G14 | R | can you remind me at 7am to go for a run | Go for a run → 2026-10-05 07:00 |
| G15 | R | note to self: back up the laptop | Back up the laptop → none |
| G16 | R | I need to call the bank at 10:30 tomorrow | Call the bank → 2026-10-05 10:30 |
| G17 | R | remind me to text Ana in 2 hours and to water the plants tonight | Text Ana → 2026-10-04 18:43; Water the plants → 2026-10-04 20:00 |
| G18 | R | remind me to call john tomorrow at 3 I also need to book the car service | Call john → 2026-10-05 15:00; Book the car service → none |
| G19 | R | I really need to sort out my taxes this week | Sort out my taxes → none |
| G20 | R | uh um | (none) |
| G21 | R | I need to go to the gym, like, three times this week | Go to the gym → none |
| G22 | R | remind me to call mum tomorrow at 5 a.m. | Call mum → 2026-10-05 05:00 |
| G23 | M | had a long day. need to sleep early | Sleep early → none |
| G24 | M | I need to I mean I want to start running | Start running → none |
| G25 | M | remind me to call john tomorrow at 3 | Call John (capitalised name) → 2026-10-05 15:00 |

If a rules case is impossible without breaking an existing test, stop and
report the conflict with both inputs — do not edit the existing test.

## 5. Needs from the user

1. (Optional, enables one-tap sign-in) Create a **public** OAuth app at
   https://huggingface.co/settings/applications/new — no secret, scopes
   `openid profile gated-repos`, redirect URI `chronicle://oauth/huggingface`
   — and put the client ID in `local.properties` as `hf.oauthClientId=…`.
   Without it the token-paste path is used.
2. Run *Test smart mode* (WP3) on the S23 Ultra and share the results.
