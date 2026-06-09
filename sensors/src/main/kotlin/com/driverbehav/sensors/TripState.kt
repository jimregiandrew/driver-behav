package com.driverbehav.sensors

/** Observable state of [TripService], surfaced to the UI. */
sealed interface TripState {
    data object Idle : TripState

    /** A capture is in progress, writing to [filePath]; [samples] flushed so far. */
    data class Recording(val filePath: String, val samples: Int) : TripState
}
