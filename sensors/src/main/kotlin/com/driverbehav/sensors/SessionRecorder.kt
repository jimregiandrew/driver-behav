package com.driverbehav.sensors

import com.driverbehav.core.model.RawSample
import com.driverbehav.core.record.SampleCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.io.File

/**
 * Drains [RawSample]s off the sampling thread and appends them to [file] as
 * [SampleCodec] lines.
 *
 * The high-rate sensor callbacks must never touch the disk, so [offer] only
 * enqueues onto an unbounded [Channel]; a single writer coroutine on
 * [Dispatchers.IO] does the buffered I/O. One consumer means the file stays in
 * channel (i.e. arrival) order without locking.
 */
class SessionRecorder(val outputFile: File) {

    private val channel = Channel<RawSample>(Channel.UNLIMITED)
    private var writerJob: Job? = null

    /** Samples written to disk so far. Updated by the writer coroutine only. */
    @Volatile
    var written: Int = 0
        private set

    fun start(scope: CoroutineScope) {
        if (writerJob != null) return
        writerJob = scope.launch(Dispatchers.IO) {
            outputFile.parentFile?.mkdirs()
            outputFile.bufferedWriter().use { w ->
                w.appendLine(SampleCodec.HEADER)
                for (sample in channel) {
                    w.appendLine(SampleCodec.encode(sample))
                    written++
                }
                w.flush()
            }
        }
    }

    /** Non-blocking enqueue; safe to call from the sensor/location callback thread. */
    fun offer(sample: RawSample) {
        channel.trySend(sample)
    }

    /** Stop accepting samples and wait for the buffer to flush to disk. */
    suspend fun stop() {
        channel.close()
        writerJob?.join()
        writerJob = null
    }
}
