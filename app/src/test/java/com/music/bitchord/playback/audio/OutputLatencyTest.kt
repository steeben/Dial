package com.music.bitchord.playback.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutputLatencyTest {

    @Test
    fun timestampDelayIsHeadLessPresentedNow() {
        // Stamp says frame 48_000 was presented 100 ms ago, so 52_800 is
        // presented now; the head is at 62_400: 9_600 frames = 200 ms behind.
        val delay = OutputLatency.timestampDelayMs(
            head = 62_400,
            framePosition = 48_000,
            stampNs = 1_000_000_000L,
            nowNs = 1_100_000_000L,
            rate = 48_000,
        )
        assertEquals(200L, delay)
    }

    @Test
    fun timestampFromBeforeAFlushIsDropped() {
        assertNull(OutputLatency.timestampDelayMs(1_000, 480_000, 1L, 2L, 48_000))
        assertNull(OutputLatency.timestampDelayMs(1_000, 0, 0L, 2L, 48_000))
    }

    @Test
    fun outputDelayLeavesOutTheTrackBuffer() {
        // getLatency 400 ms with a 9_600-frame (200 ms) buffer: 200 ms of output path.
        assertEquals(200L, OutputLatency.outputDelayMs(400, 9_600, 48_000))
        assertNull(OutputLatency.outputDelayMs(0, 9_600, 48_000))
    }

    @Test
    fun extraIsWhatTheClockLeavesOut() {
        // Timestamps already hold the headset delay: nothing to add.
        assertEquals(0L, OutputLatency.extraMs(timestampMs = 210, outputMs = 220))
        // Timestamps stop at the phone: the headset delay is extra.
        assertEquals(190L, OutputLatency.extraMs(timestampMs = 30, outputMs = 220))
        // No timestamps: Media3 takes the output delay off itself.
        assertEquals(0L, OutputLatency.extraMs(timestampMs = null, outputMs = 220))
        assertEquals(0L, OutputLatency.extraMs(timestampMs = 40, outputMs = null))
    }

    @Test
    fun medianIgnoresOneBadSample() {
        fun r(stamp: Long?, out: Long?) = OutputLatency.Reading(stamp, out, OutputLatency.extraMs(stamp, out), 48_000)
        val settled = OutputLatency.median(listOf(r(30, 220), r(32, 221), r(900, 222), r(31, 219), r(29, 220)))
        assertEquals(31L, settled.timestampMs)
        assertEquals(220L, settled.outputMs)
        assertEquals(189L, settled.extraMs)
    }
}
