---
name: testing-android-emulator
description: How to build and run Android apps on a Devin macOS machine — emulator provisioning, the nested-VM/no-hypervisor workaround (TCG), image selection, and expected boot times.
---

# Testing Android apps on the Devin macOS box

## Environment facts (verify first)

- Check virtualization: `sysctl kern.hv_support`. `1` = hardware accel available; `0` = nested-virt Mac (e.g. VirtualMac2,1 "Apple Virtual Machine") where HVF CANNOT be used — the stock emulator will die with `HVF error: HV_UNSUPPORTED` / `failed to initialize HVF: Invalid argument`.
- Android SDK lives at `~/Library/Android/sdk`; `ANDROID_HOME` is usually NOT persisted — pass it explicitly: `ANDROID_HOME=$HOME/Library/Android/sdk`.
- sdkmanager accepts licenses with `yes | sdkmanager --licenses`.

## Provisioning an emulator from scratch

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
yes | $ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager --licenses
$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager "emulator" "platform-tools" \
  "system-images;android-35;default;arm64-v8a"   # see image guidance below
echo "no" | $ANDROID_HOME/cmdline-tools/latest/bin/avdmanager create avd \
  -n <name> -k "system-images;android-35;default;arm64-v8a" -d "pixel_3"
```

## The no-hypervisor workaround (IMPORTANT)

On a nested-VM Mac (`kern.hv_support=0`), plain `-accel off` does NOT work — the emulator frontend still appends `-enable-hvf` to the QEMU command line and it crashes. You must additionally disable the HVF feature flag:

```bash
$ANDROID_HOME/emulator/emulator -avd <name> -accel off -feature -HVF \
  -no-snapshot-save -gpu swiftshader_indirect &
```

This forces pure TCG software emulation. QEMU runs (~250% CPU) and the guest DOES boot, but very slowly: budget 1.5–2+ hours for a first boot of an API-35 image on this VM. Subsequent interactions are also slow.

## Image selection for TCG speed

- Prefer `system-images;android-XX;default;arm64-v8a` or `aosp_atd` over `google_apis`/`google_apis_playstore` — far fewer packages means far less first-boot dexopt. Only use google_apis when the app genuinely needs Play services.
- `armeabi-v7a` (qemu-system-armel) images top out at API 25 — unusable for apps with minSdk ≥ 26.
- No `qemu-system-x86_64` ships in the darwin-aarch64 emulator bundle — x86 images cannot run.
- Check app `minSdk` in app/build.gradle.kts before picking an image API level.

## Monitoring boot under TCG

```bash
adb -s emulator-5554 shell getprop sys.boot_completed   # "1" = done
adb -s emulator-5554 shell getprop init.svc.bootanim    # "running" = still booting
adb -s emulator-5554 shell "service check package"      # package service up = late boot
adb -s emulator-5554 logcat -d | grep -iE "SystemUI|ActivityManager|PackageManager"
```

Progress markers: adbd connects (`adb devices` shows `device` before `offline`), Google/android logo → boot animation (SurfaceFlinger up) → PackageManager/dexopt scan → SystemServer + SystemUI → launcher → `sys.boot_completed=1`. ANR/watchdog dumps during boot are normal under TCG slowness.

## Practical tips

- Install the APK as soon as `service check package` returns `Service package: found` — no need to wait for full boot.
- Don't pass `-wipe-data` after the first successful boot — the dexopt cost is paid once and cached in userdata; reboots are faster.
- If boot keeps failing entirely: check `ps aux | grep qemu` alive, `tail /tmp/emulator.log` for `enable-hvf` still reaching QEMU (re-add `-feature -HVF`), and crash dumps in `~/emu-crash-*.db`.
- If `sysctl kern.hv_support` returns 1, just use `emulator -avd <name>` normally (HVF path) — none of the above is needed.
