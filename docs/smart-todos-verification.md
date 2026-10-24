# Smart to-dos implementation and verification

Implementation branch: `feat/smart-todos`, based on
`138d4aba1ee00e02ad20a989a687d98ffc65ef06`.
Changes are uncommitted. No push, PR change, or merge was performed.
The user-owned implementation plan is preserved.

## Scope delivered

- WP1: navigation-inset-aware bottom clearance; measured Today idle layout;
  a keyboard-visible Keep button; phone-format times in Today, Journal and To-dos.
- WP2: plain wording/resources, To-dos navigation, Saved summary, pending
  suggestions with Add/Skip, untimed-row Add a time, note-detail attribution,
  and cloud/custom model configuration under Advanced. Existing route values
  remain unchanged. Maestro selectors and the Chronicle testing skill are updated.
- WP3: pinned three-model catalog plus Basic, RAM gating, string pack IDs,
  separate model directories, custom-pack migration, checksum verification,
  per-model deletion, and a debug Test smart mode screen. The screen shows
  load/total time, raw reply, JSON validity and parsed tasks for G1/G2/G4/G12/G20;
  additional G23/G24/G25 checks and a ChatML switch are available.
- WP4: Auto default/migration respecting explicit choices, first-note offer,
  shared setup state, dismissal, Wi-Fi/mobile constraints, unique WorkManager
  foreground transfers with pause/cancel, and off-main warm-up. Native release
  is deferred while an inference lease is active.
- WP5: optional public-client OAuth/PKCE with state checking, sealed tokens,
  token-paste validation, a user-operated licence page, bounded access polling,
  automatic enqueue on access, redirect-safe authorization and resumable Range
  requests. Sign-out keeps installed packs. Installed model credits appear in About.
- WP6: all 22 rules goldens, filler/clause cleanup, coordinated actions,
  ten-word title cap, few-shot prompt, ten-second local generation budget with
  load excluded, date validation, explicit-reminder merge and rules fallback.

Room schema, reminder scheduling, recording FGS, Story implementation and the
Parakeet download URL were not changed. Existing JVM tests were not rewritten
to accommodate goldens; no golden/existing-test conflict was found.

## Verification

Verified locally on 2026-10-05/06:

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` | 243 tests, 0 failures/errors, including all 22 rules goldens |
| `:app:lintDebug` | Passed; 0 errors, 33 warnings (not warning-free) |
| `:app:assembleDebug` | Passed |
| Existing Maestro suite | All 36 flows passed in the original stateful order, with successful retries noted below |
| `smart1` / `smart2` | Passed on the final APK: first-note offer, Wi-Fi wait/mobile opt-in/cancel, four-model catalog, licence explanation, token fallback and debug-spike navigation |
| `smart3` / `time-format` | Passed again on the final APK: Skip does not persist, Add persists, Add a time works, and 12-hour Journal/To-dos formatting is correct |
| Large AVD, three-button nav, font 1.0 / 1.3 | Both final layout flows passed |
| Small AVD, three-button nav, font 1.0 / 1.3 | Both final layout flows passed; no ANR since the serial boot |
| Small AVD crowded-card check, font 1.3 | Passed: mic visible and tappable above the scroll-bounded note card |

The reproducible emulator-only harness is
`bash e2e/run_smart_verification.sh <regression|smart|layout> <emulator-serial>`.
It refuses physical-device data clearing. The two AVDs are headless/no-audio;
the large AVD uses 8 GB RAM to exercise Qwen/Llama catalog gating.

Artifacts (all relative to the repository):

- `app/build/reports/smart-todos/gradle-gates.log`
- `app/build/reports/smart-todos/regression-summary.log` and `regression/emulator-5554/`
- `app/build/reports/smart-todos/smart/emulator-5554/`
- `app/build/reports/smart-todos/smart3-final/` and `time-format-final/`
- `app/build/reports/smart-todos/layout/emulator-5554/font-1.0/` and `font-1.3/`
- `app/build/reports/smart-todos/layout/emulator-5556/font-1.0/layout/attempt-1/2026-10-06_080352/layout/takeScreenshot/`
- `app/build/reports/smart-todos/layout/emulator-5556/font-1.3/layout/attempt-1/2026-10-06_080440/layout/takeScreenshot/`
- `app/build/reports/smart-todos/layout-dense-final/attempt-1/2026-10-06_080611/layout-dense/takeScreenshot/`
- `app/build/reports/smart-todos/small-anr-trace.txt` and `small-last-anr.txt`

The legacy sequence was continued at failed-flow boundaries with its test data
preserved; builds were updated in place between flows. It was not a single
uninterrupted first-attempt run. The download/cancel failure exposed missing
connection-phase UI and was fixed with a cancellable Connecting state, a real
partial file and early connection abort. The slow tc11c scroll passed after
serializing the AVDs and increasing its deadline (assertions unchanged). fix2's
mistaken string-edit assertion was restored to its original expectation:
the To-dos **choice** still says No reminder; only the Saved-row label disappears.

TCG startup ANR popups occurred, including with a single AVD. The captured main
stack was in the existing recording-service stop path, not smart-model loading;
this is diagnostic evidence, not a proven root cause or a production ANR fix.
The layout fixture can tap Android's Wait button before its first assertion.
Recording FGS code was left untouched. Physical cold-start performance remains
an explicit gate. Test-only system animations were disabled and the installed
debug app precompiled for the serial run; restore animation scales to 1 and
font scale to 1.0 when finished.

Final cleanup: both AVDs were shut down cleanly after serial testing. Font scale
was restored to 1.0, animation scales to 1, and the larger AVD's Wi-Fi/mobile
data were enabled again. The original physical phone was not modified.

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
SHA-256: `f3ec49cda84eaafff275ff5c5c0196411b29040a0b9ab4deece30cbf01106287`.

## Remaining hardware/configuration gates

The WP3 model runtime spike is **not certified**. Neither the 1.6 GB Qwen pack
nor gated Llama packs were downloaded for emulator UI tests. Catalog Llama
runtime remains provisionally MediaPipe. No LiteRT-LM adapter or permanent
ChatML default was guessed without the required S23 results. Functional setup
surfaces are implemented; hardware-dependent runtime selection/polish remains
gated by those results.

On the S23 Ultra:

1. Install the debug APK (preserve app data; do not uninstall/clear it). If an
   update fails because signing keys differ, stop and arrange a data-preserving
   handoff instead of uninstalling the existing app.
2. In Settings → Smart to-dos, choose Qwen and Download on Wi-Fi.
3. Open Settings → Advanced → Test smart mode and Run five checks.
4. Share raw replies, JSON validity, load/total times and parsed tasks. If JSON
   is invalid, use Try ChatML and rerun. Run the additional model checks too.
5. For Llama, use the guided sign-in/licence flow and run the same checks after
   installation. A demonstrated MediaPipe load failure triggers the planned
   pinned LiteRT-LM adapter; Qwen keeps MediaPipe.

Optional OAuth configuration: create a public Hugging Face app with scopes
`openid profile gated-repos`, exact redirect `chronicle://oauth/huggingface`,
then add `hf.oauthClientId=<public id>` to ignored `local.properties` and rebuild.
No client secret is needed. Token paste remains available without this config.
Real account approval, OAuth redirect/refresh, large-download interruption,
native model loading/generation, real microphone/transcription and reboot
reminders still require hardware/account verification. The attached phone was
not modified by emulator automation.
