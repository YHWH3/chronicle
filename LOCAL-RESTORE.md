# Local restoration checkpoint — Chronicle

Owner-authorized parking backup, 24 October 2026. This is a recovery snapshot, not a tested release or approval of unfinished features.

## Exact source checkpoint

- Repository: https://github.com/YHWH3/chronicle
- Recovery branch: `codex/park-20261024/chronicle`
- Original checkout: `/Users/trinity/Desktop/Chronicle`
- Original branch: `feat/smart-todos`
- Original HEAD: `138d4aba1ee00e02ad20a989a687d98ffc65ef06`
- The recovery commit is the tip of the recovery branch; its parent is the original HEAD above.
- The source snapshot preserves the working files, including unfinished tracked edits and nonignored source additions. OS metadata and `.freebuff` runtime databases are excluded from source publication; private runtime files are in the encrypted backup.

```sh
git clone --branch codex/park-20261024/chronicle https://github.com/YHWH3/chronicle.git "/Users/trinity/Desktop/Chronicle"
```

Do not reset an existing checkout. Clone into an empty location or choose a new path. Restore sibling worktrees from their respective recovery branches; the private recovery manifest lists every one.

## Private local state and history

Encrypted recovery assets are at https://github.com/YHWH3/local-project-recovery-2026-10-24/releases/tag/recovery-2026-10-24 . The private repository README and restore script describe checksum verification, decryption and restoration into an empty staging directory. The runtime manifest records original absolute locations under `restored/Users/trinity/...`.

The age key is retained separately in `~/Documents/Storage-Recovery-2026-10-24/private/recovery.agekey`. Copy it to a secure independent backup before losing or replacing this Mac. It is not uploaded to GitHub. No archive can be restored without it. Environment files, local databases, credentials, signed keys and emulator app data must never be committed as ordinary source.

Local Git bundles preserve all local refs, stashes and recovery snapshot branches. After decrypting the history archive, `git clone <project>.bundle <destination>` reconstructs the repository history; then check out the selected recovery branch. Exact checkpoint hashes and upload verification are in the private recovery repository's checkpoint manifest. Existing branches/default branches were not overwritten.

## Reinstall and restore

# Resume notes: Chronicle

Audit date: 2026-10-24T10:55:59.580831+05:30

Canonical local repository: `/Users/trinity/Desktop/Chronicle`
Origin: `https://github.com/YHWH3/chronicle.git`

This guide is stored in a recovery checkpoint created with owner authorization. Project status is inferred from observed activity, not a confirmed business priority.

## Source and worktrees

| Checkout | Branch | HEAD | Uncommitted status entries | Remote evidence |
|---|---|---|---:|---|
| `/Users/trinity/Desktop/Chronicle` | `feat/smart-todos` | `138d4aba1ee00e02ad20a989a687d98ffc65ef06` | 106 | Committed HEAD preserved on GitHub |

One representative committed source snapshot: 2.31 MB in 184 tracked files. This is uncompressed working-tree content, not incremental Git transfer size or complete branch history. Uncommitted additions need separate review.

Before any checkpoint push: review the exact source diff and untracked files; exclude credentials, local environment files, personal data, model weights, databases and bulky generated output. Preserve each unique branch tip and detached commit, then verify remote SHAs. Existing dirty work is not covered merely because HEAD is on GitHub.

## Reinstallable / rebuildable local folders

- `/Users/trinity/Desktop/Chronicle/build`: 0.01 GiB; rebuildable output/cache; Git ignored and no tracked files found at audit time.
- `/Users/trinity/Desktop/Chronicle/.gradle`: 0.02 GiB; rebuildable output/cache; Git ignored and no tracked files found at audit time.
- `/Users/trinity/Desktop/Chronicle/.kotlin`: 0.00 GiB; rebuildable output/cache; Git ignored and no tracked files found at audit time.
- `/Users/trinity/Desktop/Chronicle/app/build`: 0.61 GiB; rebuildable output/cache; Git ignored and no tracked files found at audit time.

Exact per-checkout dirty paths, remote refs and sizes are in `../repositories.json`; generated candidates are in `../generated-folders.json`.

## Resume locally

Recheck the current remote branch before resuming; several remote branches are newer than these local checkouts. Restore the selected checkpoint, use the package manager and lockfile in that checkout, restore private runtime configuration from secure backup, reinstall dependencies and rebuild. Do not reset or overwrite a dirty checkout.

The two emulator profiles belong by name/configuration to Chronicle: `~/.android/avd/chronicle.avd` (13.48 GiB) and `chronicle_small.avd` (7.97 GiB). Both use `system-images;android-35;google_apis;arm64-v8a`. Existing snapshot subfolders total 7.81 GiB. No open files were observed in these profiles during the audit. Their installed app data was not inspected or exported. Export journals/recordings and any desired app state before full profile deletion; a GitHub clone does not restore that state.

The current source requires Android platform 36 for builds. Run `./gradlew :app:assembleDebug` after restoring the SDK/JDK configuration. The current temporary TCG test emulators also use the shared Android 35 image, so do not remove the shared SDK image during those tests.

The uncommitted `SmartModels.kt` declares these optional packs (presence inside emulator disks has not been inspected):

- Qwen 1.5B: `litert-community/Qwen2.5-1.5B-Instruct`, file `Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task`, 1,598,556,720 bytes; SHA256 `82968d0a6c3872cf016fdbcfc591571605f4c7fd2b0f64d2533df502cc6596b3`.
- Llama 3.2 1B: `litert-community/Llama-3.2-1B`, file `llama3_2_1b_mixed_int4_gpu.litertlm`, 963,903,488 bytes; SHA256 `c6f21c5304c40f45b69e5bfb15bb45acbaad7938e941d25a0a391aef0ec6ac27`.
- Llama 3.2 3B: `litert-community/Llama-3.2-3B`, file `llama3_2_3b_mixed_int4_gpu.litertlm`, 2,207,940,608 bytes; SHA256 `63419d689ef141cc77d6ab5739f27426d779e1165546a4904edefa9fc36fea51`.
- Speech: `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2`, nominal 487,170,055 bytes.

Restore through the app download flow where possible. The source marks the Llama models as licence gated; user access/consent must be restored normally. Never put account tokens in GitHub notes. Model URLs/access were recorded from local code and were not network-tested.


## All local refs present before this snapshot

These are preservation checkpoints, not claims of validation. Their histories are retained in the encrypted Git bundle.

| Local ref | Commit |
|---|---|
| `refs/heads/codex/chronicle-production-hardening` | `138d4aba1ee00e02ad20a989a687d98ffc65ef06` |
| `refs/heads/feat/smart-todos` | `138d4aba1ee00e02ad20a989a687d98ffc65ef06` |
| `refs/heads/main` | `086a16ee9858deb3f2c5e6c4b7dc13ef27cbff12` |

## Validation limits

The backup process checks source scanning, Git tree construction, remote SHA agreement, archive decryption and file checksums. Application tests/builds are not rerun; unfinished source is kept as-is. Recreate dependencies and validate the app before continuing development. Shared SDKs, system runtimes, Keychain and unrelated Docker projects are not part of the deletion scope.
