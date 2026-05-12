# driver-behav

An Android app that scores driver behaviour from on-board phone sensors
(GPS + accelerometer, optionally gyroscope). Trips are recorded as a
foreground service, accelerometer data is anti-alias filtered and decimated
on-device, harsh-event detectors run on the filtered signal, and each trip
gets a **golf-style score** — 0 is ideal, harsh events add penalty.

Status: pre-v1.0 (planning complete, implementation not yet started).

## v1.0 scope

- Android only (iOS deferred to v2 via a Kotlin Multiplatform migration).
- Sensor capture: GPS @ 1 Hz + accel (+ gyro) at `SENSOR_DELAY_FASTEST`.
- DSP in pure-Kotlin `:core` module: 4th-order Butterworth low-pass
  (cascaded biquads, DF II Transposed), decimation to a parameterised
  output rate (default ~2 Hz).
- Event detection: harsh brake, harsh accel, harsh cornering, speeding
  (vs. a fixed limit).
- Trip storage in Room, trip list + trip detail UI in Jetpack Compose.
- Record-and-replay infrastructure: raw sensor sessions can be dumped to
  file and replayed deterministically through the JVM test harness.

No AI coaching/insights in v1.0.

## Stack

- Kotlin + Jetpack Compose
- Coroutines / Flow
- Room (SQLite)
- Gradle, three modules:
  - `:app` — Android UI + foreground service + persistence
  - `:sensors` — Android sensor + GPS collection
  - `:core` — **pure Kotlin** DSP, event detection, scoring (no Android
    imports — kept portable for a future KMP iOS port)

## External dependency: orientation estimator

The `:core` pipeline consumes accelerometer samples already rotated into
the vehicle frame. Frame estimation (quaternion / Kalman-filter based) is
provided by a separate module from another repo, wired in via the
`OrientationEstimator` interface in `:core`. v1.0 ships with an identity
pass-through impl; the real impl is plugged in later.

## Build & test

```sh
./gradlew build       # compile everything
./gradlew test        # JVM unit tests (DSP, scoring, replay harness)
./gradlew :app:installDebug   # install on a connected device
```

JVM tests are the primary feedback loop — most iteration on filters,
detectors, and scoring happens against recorded sessions on the laptop,
not in a car.

## Project documents

- [`PLAN.md`](PLAN.md) — full architecture, milestones (M1–M6) with
  acceptance tests, decisions log, and working agreements.
