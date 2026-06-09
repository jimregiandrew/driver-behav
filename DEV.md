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

# Wait until fully booted before installing:
adb wait-for-device
until [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do sleep 2; done
echo "boot complete"
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

## Recording file format

`TripService` writes each capture session to a CSV in app-private external
storage: `Android/data/com.driverbehav/files/recordings/trip-YYYYMMDD-HHMMSS.csv`.
The format is defined by `SampleCodec` in `:core` (pure Kotlin) and is the single
source of truth shared by the on-device writer and the JVM replay reader.

Line-oriented, one sample per line, with a leading **type tag** so accel, gyro,
and GPS rows — which have different columns — can share one file:

```
# driver-behav recording v1
A,<tMonoNanos>,<x>,<y>,<z>
G,<tMonoNanos>,<x>,<y>,<z>
L,<tMonoNanos>,<lat>,<lon>,<speedMps>,<accuracyM>,<bearingDeg>
```

| Tag | Sample | Columns after the tag |
|-----|--------|-----------------------|
| `A` | Accelerometer (m/s², device frame) | `tMonoNanos, x, y, z` |
| `G` | Gyroscope (rad/s, device frame) | `tMonoNanos, x, y, z` |
| `L` | GPS fix | `tMonoNanos, lat, lon, speedMps, accuracyM, bearingDeg` |

- **`tMonoNanos`** — monotonic timestamp from one clock across all three sensor
  types (`SystemClock.elapsedRealtimeNanos()` on device). Lets the pipeline merge
  the streams by time. Per-type timestamps are monotonic; the *merged* stream is
  not globally sorted, because accel and gyro arrive interleaved.
- **Accel/gyro are device-frame**, not yet rotated to vehicle frame.
- **Absent GPS fields** (e.g. no speed/bearing from the provider) are written as
  `NaN` and round-trip back to `Float.NaN`.
- The first line is a versioned header comment; any line starting with `#`, and
  blank lines, are ignored on read. Float/double values use the shortest decimal
  that round-trips exactly, so encode→decode is lossless.

CSV is deliberate: the file is `adb pull`-able and drops straight into a notebook
for plotting.

## End-to-end capture test (M2+)

Exercises the whole pipe on the emulator: install → grant permissions → record a
short session → pull the file → check timestamps and sample rates. Proves the
**plumbing**; per the gotchas below, real sample-rate / Doze / battery checks must
happen on a physical phone.

```sh
# 1. Install and pre-grant permissions (skips the runtime dialog)
./gradlew :app:installDebug
adb shell pm grant com.driverbehav android.permission.ACCESS_FINE_LOCATION
adb shell pm grant com.driverbehav android.permission.ACCESS_COARSE_LOCATION
adb shell pm grant com.driverbehav android.permission.POST_NOTIFICATIONS
adb shell am start -n com.driverbehav/.MainActivity

# 2. Seed a GPS fix (without one the GPS stream stays empty)
adb emu geo fix 174.763 -36.848 10          # lon lat altitude

# 3. Start recording (tap "Start trip" — coords are for the 1080x2400 Pixel 6 AVD)
adb shell input tap 540 1306
# Feed 1 Hz GPS for ~12 s while accel/gyro stream at FASTEST:
for i in $(seq 1 12); do adb emu geo fix 174.7630$i -36.8480$i 10 >/dev/null 2>&1; sleep 1; done
# Stop recording
adb shell input tap 540 1400
```

Don't know the button coordinates on a different screen size? Screenshot and read
them off — or just tap the buttons by hand in the emulator window:

```sh
adb exec-out screencap -p > /tmp/app.png
```

Pull and validate the recording:

```sh
adb shell ls -l /storage/emulated/0/Android/data/com.driverbehav/files/recordings/
adb pull /storage/emulated/0/Android/data/com.driverbehav/files/recordings/trip-YYYYMMDD-HHMMSS.csv /tmp/rec.csv

python3 - <<'PY'
counts={'A':0,'G':0,'L':0}; first={}; last={}; mono={'A':True,'G':True,'L':True}
prev=None; overall=True
for line in open('/tmp/rec.csv'):
    line=line.strip()
    if not line or line.startswith('#'): continue
    f=line.split(','); t=f[0]; ts=int(f[1]); counts[t]+=1
    if t in last and ts<last[t]: mono[t]=False
    first.setdefault(t,ts); last[t]=ts
    if prev is not None and ts<prev: overall=False
    prev=ts
def rate(t):
    if counts[t]<2: return 0.0
    d=(last[t]-first[t])/1e9
    return (counts[t]-1)/d if d>0 else 0.0
print("counts:", counts, " overall-monotonic:", overall)
for t in ('A','G','L'):
    print(f"  {t}: n={counts[t]} monotonic={mono[t]} rate={rate(t):.1f} Hz")
PY
```

**Pass looks like:** accel & gyro each per-type `monotonic=True` at ≥100 Hz, GPS
~1 Hz. `overall-monotonic=False` is **expected** (interleaved streams — see the
format notes above). `:core`'s `RecordingStats` runs these same checks in JVM
tests; the Python here is the quick-look / notebook-ready equivalent.

**If GPS rows are missing:** you didn't `geo fix`, or did it before the service
registered for updates — set a fix and keep nudging it during capture.

## Notes / gotchas

- Emulator sensors and Doze behaviour are *not* faithful to real devices.
  Per PLAN.md, anything battery-, foreground-service-, or real-sensor-rate-
  dependent must be tested on a physical phone. Emulator is fine for UI and
  for plumbing-level sensor wiring.
- Run `./gradlew build test` at the end of every milestone — it's the M1
  acceptance gate and stays as a regression gate going forward.
