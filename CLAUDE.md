# CLAUDE.md

Project conventions and commands for Claude Code sessions. Re-read this at the
start of every session. Source-of-truth plan: [PLAN.md](PLAN.md).

## What this project is

Android-first app that scores driver behaviour from GPS + accelerometer
(+ gyro) data. Native Kotlin + Jetpack Compose. v1.0 is delivered in
milestones M1–M6 (see PLAN.md). One milestone per session.

## Module layout

Three Gradle modules:

- `:core` — **pure Kotlin / JVM**. DSP (Butterworth IIR), event detection,
  trip scoring, `OrientationEstimator` interface. **No Android imports.**
  This rule is non-negotiable: it keeps `:core` testable on the JVM and
  unlocks a future Kotlin Multiplatform migration for iOS.
- `:sensors` — Android library. `TripService` (foreground service),
  `SensorSampler` (accel/gyro at `SENSOR_DELAY_FASTEST` + GPS at 1 Hz).
  Depends on `:core`.
- `:app` — Android application. Compose UI, Room persistence, wiring.
  Depends on `:core` and `:sensors`.

Package root: `com.driverbehav` (sub-packages per module: `com.driverbehav.core.*`,
`com.driverbehav.sensors.*`, `com.driverbehav.*` for app).

## Rules

- **No Android imports in `:core`.** Includes `android.*`, `androidx.*`,
  `kotlinx.coroutines.android`, anything `Context`-bearing. Pure Kotlin
  stdlib + `kotlinx.coroutines.core` only. Violations break the iOS-via-KMP
  path and the JVM replay harness.
- **Tests live next to the code they test**, JVM tests in `:core/src/test`.
  Push verification into `:core` JVM tests wherever possible — they run in
  seconds and don't need a device.
- **Thresholds, weights, and DSP parameters are config**, not magic numbers
  scattered through code. One config object per concern (filter config,
  detector config, scoring config) so tuning against recorded data is a
  one-file edit.
- **Record-and-replay is load-bearing.** From M2 onward, sessions get
  recorded to disk, and `:core` consumes a `Flow<RawSample>` that may come
  from live sensors *or* a recorded file. Iteration on M3/M4 happens on the
  JVM against recordings, not in a car.

## Build / test / lint commands

JDK 17 required. `local.properties` points at the Android SDK; not checked
in. CI/devs without an SDK installed should run `sdkmanager "platforms;android-34"
"build-tools;34.0.0"` first.

| Task | Command |
|------|---------|
| Full build + all checks | `./gradlew build` |
| All unit tests (JVM + Android unit) | `./gradlew test` |
| `:core` tests only (fast) | `./gradlew :core:test` |
| Lint (Android modules) | `./gradlew lint` |
| Assemble debug APK | `./gradlew :app:assembleDebug` |
| Install on connected device | `./gradlew :app:installDebug` |
| Clean | `./gradlew clean` |

`./gradlew build test` is the M1 acceptance gate and must stay green at the
end of every milestone.

## Versions (locked, edit in `gradle/libs.versions.toml`)

- AGP 8.7.2 / Gradle 8.10.2 (wrapper) / Kotlin 2.0.21
- compileSdk 34, minSdk 26, targetSdk 34
- Compose BOM 2024.10.01, Material3
- JVM target 17 everywhere

## v1.0 scope (what's in / out)

In: M1–M6 from PLAN.md — sensor capture, DSP, event detection, scoring,
trip list UI, persistence.

Out (deferred to v2 or later): AI coaching/insights, iOS, cloud sync,
multi-user, real speed-limit lookup. Frame rotation (orientation estimator)
is in-scope as an *interface* in `:core`; the real quaternion/Kalman impl
is sourced from an external repo and excluded from the v1.0 budget.

## Working agreement

- One milestone per session. Re-state the milestone's acceptance test at
  the start; run it at the end.
- Small commits, one PR per milestone.
- Ask before writing source code; docs/plans are fine without asking.
- Verify on a real device for anything touching the foreground service,
  permissions, battery, or background lifecycle. Emulators lie about
  sensors and Doze.
