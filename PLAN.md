# Driver Behavior App — v1.0 Plan & Process Guide

## Context

You want to build an Android-first mobile app that scores driver behavior from
GPS + accelerometer (and possibly gyroscope) data, with a ~8h coding/AI-direction
budget for v1.0 (range 4–16h). No AI coaching/insights in v1.0. iOS is a
possible v2 target. You want this developed *with* AI (Claude Code) following
good practice: architecture-first, milestones with tests, iterative loops, and
no premature coding.

A core technical constraint shapes the stack: accelerometer data needs
high-rate sampling with software anti-aliasing filtering before downsampling
to the scoring layer, because phone hardware filtering is inconsistent.

## Stack (confirmed)

**Native Android — Kotlin + Jetpack Compose + Coroutines/Flow + Room (SQLite).**

Why not React Native / Expo for v1.0:
- RN sensor bridges typically cap around ~60 Hz with jitter; doing IIR/FIR
  filtering in JS at 100–200 Hz is power-hungry and unreliable.
- You already know Kotlin — no language tax.
- Direct access to `SensorManager` (`SENSOR_DELAY_FASTEST`), foreground
  services, and battery optimisation APIs.
- 8h budget is tight; adding a JS↔native module learning curve would burn it.

Path to iOS later (v2): extract the **scoring + DSP** into a **Kotlin
Multiplatform (KMP)** module. The Android UI/sensor-collection layer stays
Android-only; iOS gets its own thin sensor-collection layer that feeds the
shared KMP scoring engine. Decision can be deferred to v2 — just keep DSP and
scoring code free of Android-specific imports from day one (pure Kotlin in a
`core/` module).

## Architecture (v1.0)

Three layers, separated so Claude can reason about and unit-test the core
without a device:

1. **Sensor collection layer** (`app/sensors/`) — Android-only.
   - `TripService`: a foreground service holding wake lock + location updates.
   - `SensorSampler`: registers accel (+ gyro) at `SENSOR_DELAY_FASTEST`,
     stamps each sample with `SystemClock.elapsedRealtimeNanos()`.
   - Battery gating: only sample sensors when GPS speed > ~3 m/s (walking
     threshold) sustained for N seconds → enter "driving" state. Exit after
     speed < threshold for M seconds. GPS itself runs at 1 Hz throughout.
   - Emits a `Flow<RawSample>` into the core layer.

2. **Core / DSP / scoring layer** (`core/`) — pure Kotlin, no Android imports.
   - **Filtering**: 4th-order Butterworth low-pass IIR per accel axis,
     implemented as cascaded biquads (Direct Form II Transposed for numerical
     stability). Cutoff and output rate are both parameterized. Defaults:
     ~1 Hz cutoff, decimate to **2 Hz** output for the scoring layer. Real
     vehicle motion (harsh brake/accel events) is sub-2 Hz content, so 2 Hz
     output is sufficient; parameterization lets you bump it up cheaply if
     event-edge timing turns out to need finer resolution.
   - **Frame rotation / orientation**: handled by an external module
     (quaternion / Kalman-filter based, sourced from your other project).
     The `:core` pipeline consumes an already-rotated, vehicle-frame accel
     signal. Define an `OrientationEstimator` interface in `:core` that, per
     sample, returns `Rotated(accel_vehicle_frame, status)` where `status`
     carries at minimum a `known: Boolean` and ideally a `confidence: Float
     [0,1]`. The scoring layer treats samples with `known=false` (or
     confidence below a threshold) as "orientation unknown" and either
     suppresses event detection or marks events as low-confidence. For v1.0
     a binary known/unknown is fine; the interface leaves room for confidence
     without churn. **Orientation impl itself is excluded from the 8h budget.**
   - **Event detection**: threshold-based detectors for harsh brake, harsh
     accel, harsh cornering, and speeding (vs. a fixed limit for v1.0).
     Each event has start/end time, peak magnitude, severity.
   - **Trip scoring (golf-style / loss):** trip score starts at **0** (ideal)
     and each detected event adds a penalty. Penalty per event is a function
     of type and severity, e.g. `penalty = w_type * max(0, peak − threshold)^p`
     with `p` likely in `[1, 2]`. Final trip score is the sum (optionally
     normalised by trip duration or distance — parameterize, decide from
     data). Lower is better. All weights/thresholds live in one config
     object so tuning against recordings is a one-file edit.

3. **UI + storage layer** (`app/ui/`, `app/data/`) — Android-only.
   - Room DB: `trips`, `events`, optional `raw_samples` (for dev builds only).
   - Compose screens: Start/Stop, Trip list, Trip detail (map + events + score).
   - One-tap export of a trip's raw samples to JSON/CSV for offline analysis.

### Critical: record-and-replay infrastructure (do this early)

Single most important productivity investment for an AI-assisted project on a
tight budget. Without it, every iteration of the scoring algorithm requires
you to go drive a car.

- `core/` consumes a `Flow<RawSample>` — the source can be live sensors **or**
  a recorded file.
- Add a debug-build "record raw session" toggle that dumps samples to a file.
- Add a JVM-side replay harness (`./gradlew :core:test`) that feeds recorded
  sessions through the pipeline and asserts on detected events.
- Once you have 2–3 recorded real drives (one calm, one with deliberate harsh
  events, one mixed), 90% of iteration happens on the laptop, not in a car.

## Milestones (each with an acceptance test)

Each milestone is sized to roughly 1–2 hours of AI-directed work. Stop and
verify after each one before moving on.

### M1 — Project skeleton (≈1h)
- Empty Android Studio project, Kotlin + Compose, min SDK 26.
- Three Gradle modules: `:app`, `:core` (pure Kotlin), `:sensors` (Android).
- `CLAUDE.md` committed with: module layout, build commands, lint, test cmd,
  the "no Android imports in `core/`" rule.
- **Test:** `./gradlew build test` passes. Empty app launches on device/emulator.

### M2 — Foreground service + raw sensor capture (≈2h)
- `TripService` runs as foreground service with notification.
- Captures GPS (1 Hz) + accel + gyro at FASTEST, writes raw samples to a file
  in app-private storage.
- Start/Stop button in UI.
- **Test:** Manual — start trip, walk/drive 2 min, stop. Verify file contains
  monotonically-increasing timestamps and sample rate ≥ 100 Hz on your device.
  Eyeball-plot in a Python notebook or similar.

### M3 — DSP pipeline in `:core` (≈2h)
- Implement 4th-order Butterworth low-pass as cascaded biquads (Direct Form
  II Transposed), decimation to a parameterised output rate (default 2 Hz).
  All in `:core`, unit-tested on JVM: step response, sinusoid attenuation at
  several frequencies (verify ~-3 dB at cutoff, ~-24 dB/oct rolloff per
  cascade), impulse response sanity.
- Define `OrientationEstimator` interface in `:core` with `known: Boolean`
  (and `confidence: Float` placeholder). Provide an identity pass-through
  impl that returns `known=true`. The real quaternion/Kalman impl is
  out-of-budget and wired in later.
- Replay harness: feed a recorded file from M2 through the pipeline and dump
  filtered output.
- **Test:** Unit tests pass. Visual sanity check: feed in a recorded session,
  plot raw vs filtered — high-frequency noise gone, vehicle-frame longitudinal
  axis shows the expected brake/accel signatures.

### M4 — Event detection + scoring (≈2h)
- Threshold detectors for harsh brake / accel / corner / speeding.
- Trip score aggregator.
- **Test:** Unit tests on recorded sessions: a calm drive scores **≤ ~5**
  (near zero, golf-style); a drive with three deliberate hard brakes detects
  ≥ 3 brake events and produces a clearly higher penalty than the calm drive.
  Tune thresholds and per-event weights against your own recordings, not
  made-up numbers.

### M5 — Persistence + trip list UI (≈1.5h)
- Room schema: trips, events. Save on trip stop.
- Compose: trip list screen + trip detail screen (score, event list, map is
  optional — see "cut list" below).
- **Test:** Manual — record 2 trips, kill app, relaunch, both trips appear
  with correct scores.

### M6 — Polish, battery sanity check, install on your phone (≈0.5h)
- Verify Doze/background restrictions don't kill the service mid-trip.
- Verify a 20-min trip uses < ~5% battery on your device.
- **Test:** Real drive end-to-end; resulting trip looks right.

**Budget note:** M1–M6 sums to ~9h. You've said the 8h figure is a ballpark
rather than a hard ceiling — so the cut list below is a fallback, not a
prescription. Where I think cutting items would significantly hurt the
end result, I've flagged it inline.

## Cut list (if running over budget)

In rough order of what to drop first (and the cost of dropping it):
1. **Map view on trip detail** — cheap to drop. Event list with timestamps
   is enough for a tuning-focused v1.0.
2. **Gyroscope** — moderate cost. Cornering can be estimated from GPS heading
   rate + lateral accel, but heading rate is noisy at low speed and GPS is
   1 Hz, so the corner-event signal degrades. **Worth keeping if at all
   possible** — gyro is the cleanest cornering signal.
3. **Speeding detection** — cheap to drop for v1.0 (needs a speed-limit data
   source; defer to v1.1). Not central to the harsh-event story.
4. (Frame rotation is *not* on the cut list — handled by the external
   orientation module and excluded from the 8h budget.)
5. (Record-and-replay infrastructure is *not* on the cut list. Dropping it
   to save time will cost far more time downstream — see Process step 5.)

## Process / how to work with Claude Code on this

These are the steps you asked for input on, beyond just "write code":

1. **Architecture doc first** (this file). Refine it with me before any code.
2. **`CLAUDE.md` second.** Once the skeleton exists, run `/init`, then hand-edit
   to lock in the module rules ("no Android imports in `:core`"), build/test
   commands, and the v1.0 scope. Claude reads this every session.
3. **One milestone per session.** Start each session by re-stating the
   milestone's acceptance test. End by running it.
4. **Commit per milestone, PR per milestone.** Small diffs are dramatically
   easier for both you and the AI to review.
5. **Record sessions early** (M2) so M3/M4 iteration is JVM-only.
6. **Unit tests are your feedback loop.** Claude can run `./gradlew test`
   itself; it cannot see your screen or feel a brake event. Push as much
   verification as possible into JVM tests against recorded data.
7. **You verify on device.** Anything touching the foreground service,
   permissions, battery, or background lifecycle gets tested on your real
   phone, not the emulator. Emulators lie about sensors and Doze.
8. **Iterate on thresholds with data, not vibes.** When tuning harsh-brake
   thresholds, edit numbers, re-run the replay harness on your recordings,
   look at the resulting event list. Don't guess.
9. **Defer iOS until v1.0 ships.** The KMP migration is straightforward *if*
   you keep `:core` pure-Kotlin from day one. Don't pay the multiplatform
   tax now.
10. **Keep a `DECISIONS.md`** (optional, lightweight). One line per non-obvious
    choice + why. Useful both for future-you and for giving Claude context in
    later sessions.

## Critical files (will be created during execution)

- `CLAUDE.md` — project conventions, build/test commands, module rules.
- `core/src/main/kotlin/.../dsp/Butterworth.kt` — IIR filter.
- `core/src/main/kotlin/.../pipeline/Pipeline.kt` — sample → filter →
  decimate → rotate → detect.
- `core/src/main/kotlin/.../score/EventDetectors.kt`
- `core/src/test/kotlin/.../ReplayTest.kt` — replays recorded sessions,
  asserts on detected events.
- `app/src/main/kotlin/.../service/TripService.kt` — foreground service.
- `app/src/main/kotlin/.../sensors/SensorSampler.kt`
- `app/src/main/kotlin/.../data/` — Room entities & DAOs.
- `app/src/main/kotlin/.../ui/` — Compose screens.

## Verification (end-to-end)

After M6 you should be able to:
1. `./gradlew build test` — all green.
2. Install on your phone, grant permissions, start a trip, drive ~15 min
   including 2 deliberate hard brakes, stop.
3. See a trip in the list with a non-zero (golf-style) score, the two brake
   events listed with approximately-correct timestamps.
4. Replay the recorded file from that drive on the JVM and get the same
   score + same event list — deterministic.
5. Battery delta over the trip is < ~5% on a reasonable device.

If all five hold, v1.0 is done.

## Decisions locked in

- **Stack:** Native Android, Kotlin + Compose. iOS deferred; `:core` stays
  pure Kotlin to allow a later KMP migration.
- **Scope:** Full M1–M6 (~9h). No cut list applied up front.
- **Mounting:** Fixed mount, unknown orientation. Orientation handled by an
  external module (quaternion / Kalman-filter based, from your other repo).
  Excluded from the 8h budget. `:core` exposes an `OrientationEstimator`
  interface; the real impl is wired in later.
