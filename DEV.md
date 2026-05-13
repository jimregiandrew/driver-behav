# Development environment notes

Setup, commands, and emulator usage for working on driver-behav from this Mac.

## Toolchain (installed via Homebrew)

- `openjdk@17` — JDK 17 at `/opt/homebrew/opt/openjdk@17`.
- `android-commandlinetools` — Android SDK root at
  `/opt/homebrew/share/android-commandlinetools`. Provides `sdkmanager`,
  `avdmanager`, `adb`, `emulator`, `lint`, `apkanalyzer`.
- `gradle` — only used once to bootstrap `./gradlew`. Optional; the wrapper
  is self-contained going forward.

SDK packages installed: `platform-tools`, `platforms;android-34`,
`build-tools;34.0.0`, `emulator`, `system-images;android-34;google_apis;arm64-v8a`.

## Environment (already in `~/.zshrc`)

```sh
export JAVA_HOME="/opt/homebrew/opt/openjdk@17"
export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"
```

`local.properties` (gitignored) points the Gradle Android plugin at the SDK:
```
sdk.dir=/opt/homebrew/share/android-commandlinetools
```

## Gradle commands

| Task | Command |
|------|---------|
| Full build + all checks | `./gradlew build` |
| All unit tests | `./gradlew test` |
| `:core` tests only (fast, JVM) | `./gradlew :core:test` |
| Lint (Android modules) | `./gradlew lint` |
| Assemble debug APK | `./gradlew :app:assembleDebug` |
| Install on running device/emulator | `./gradlew :app:installDebug` |
| Clean | `./gradlew clean` |

## SDK management

```sh
sdkmanager --list                 # show installed + available packages
sdkmanager --list_installed       # just what's installed
sdkmanager "platforms;android-35" # install a new API level
sdkmanager --licenses             # accept licences (run after adding packages)
sdkmanager --update               # update everything to latest
```

## Emulator

### AVD (already created)

`pixel6_api34` — Pixel 6 hardware profile, Android 14 / API 34, Google APIs,
arm64-v8a (native on Apple Silicon). Stored at
`~/.android/avd/pixel6_api34.avd`.

```sh
avdmanager list avd                                       # list AVDs
emulator -list-avds                                       # same, terser
avdmanager delete avd -n pixel6_api34                     # remove
echo "no" | avdmanager create avd -n <name> \
  -k "system-images;android-34;google_apis;arm64-v8a" \
  -d "pixel_6" --force                                    # create new
```

### Boot / shutdown

```sh
emulator -avd pixel6_api34 &              # normal boot, restores last snapshot
emulator -avd pixel6_api34 -no-snapshot-save &  # don't save state on exit
emulator -avd pixel6_api34 -wipe-data &   # factory-reset userdata
adb emu kill                              # clean shutdown
```

**Persistence:** apps installed via `installDebug` survive normal restarts
(restored from the quick-boot snapshot). They are wiped by `-wipe-data`
or by "Cold Boot" from Android Studio's AVD Manager.

### Install / launch the app

```sh
./gradlew :app:installDebug                          # build + install
adb shell am start -n com.driverbehav/.MainActivity  # launch
adb uninstall com.driverbehav                        # remove
```

## Emulator interaction cheat-sheet

### Mouse → touch
- **Tap** — click
- **Long-press** — click and hold
- **Swipe** — click-drag
- **Pinch / rotate** — hold ⌃ Ctrl + click-drag (second touch point mirrored
  about the centre)
- **Scroll** — trackpad two-finger scroll or mouse wheel

### Keyboard shortcuts (with emulator window focused)
- ⌘H — Home
- ⌘← — Back
- ⌘O — Overview / recents
- ⌘P — Power (lock/unlock)
- ⌘= / ⌘- — Volume up / down
- ⌃⌘← / ⌃⌘→ — Rotate

### Home screen / app drawer
- Open drawer: swipe up from the bottom of the screen.
- Pin app to home: open drawer → long-press the app icon → drag to home.

### Side-panel extended controls (`…` button)
- **Location** — set fake GPS or replay GPX/KML route. Use this for M2+.
- **Virtual sensors** — fake accel/gyro/magnetometer, tilt a 3D phone widget.
- Battery, Cellular, Phone (SMS/call), Fingerprint, Camera, Snapshots,
  Record screen, Settings.

### Scriptable input via adb

```sh
adb devices                                  # list connected devices
adb shell input tap <x> <y>
adb shell input swipe <x1> <y1> <x2> <y2> <ms>
adb shell input keyevent KEYCODE_HOME        # 3
adb shell input keyevent KEYCODE_BACK        # 4
adb shell input keyevent KEYCODE_APP_SWITCH
adb shell input text "hello"
adb emu geo fix <lon> <lat>                  # fake GPS coordinate
adb logcat                                   # live logs
adb logcat -d -t 200                         # last 200 lines, exit
adb logcat *:E                               # only error-level
adb shell dumpsys activity activities | grep ResumedActivity
adb shell pm list packages | grep driverbehav
```

## Notes / gotchas

- Emulator sensors and Doze behaviour are *not* faithful to real devices.
  Per PLAN.md, anything battery-, foreground-service-, or real-sensor-rate-
  dependent must be tested on a physical phone. Emulator is fine for UI and
  for plumbing-level sensor wiring.
- Run `./gradlew build test` at the end of every milestone — it's the M1
  acceptance gate and stays as a regression gate going forward.
