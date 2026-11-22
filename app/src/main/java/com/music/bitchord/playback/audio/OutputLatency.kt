package com.music.bitchord.playback.audio

import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOutput
import androidx.media3.exoplayer.audio.AudioOutputProvider
import androidx.media3.exoplayer.audio.AudioTrackAudioOutput
import androidx.media3.exoplayer.audio.ForwardingAudioOutputProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.lang.ref.WeakReference
import java.lang.reflect.Method
import kotlin.math.abs

/**
 * How late the sound comes out of the output, read from the AudioTrack the
 * player writes to — the same track whose timestamps are the player's clock.
 *
 * Two numbers come off that one track, and on most phones they agree:
 *
 *  - **Timestamp delay**: frames the mixer has taken from the track but the
 *    output has not presented yet, by `AudioTrack.getTimestamp`. Media3's
 *    position is built on this, so whatever it holds, the position already
 *    allows for.
 *  - **Output delay**: what the audio system says the output path takes
 *    (`AudioTrack.getLatency`, less the track's own buffer). For Bluetooth
 *    this is where the headset's delay shows. Media3 reads it too, but only
 *    when there are no timestamps.
 *
 * Where the timestamps leave the Bluetooth delay out, the player's position
 * runs ahead of the sound by the difference, and so does anything held to it.
 * [extraMs] is that difference: the part of the delay the clock does not know.
 * The music video is held back by it — see
 * [com.music.bitchord.ui.player.MusicVideoEngine].
 */
@OptIn(UnstableApi::class)
object OutputLatency {

    data class Reading(
        /** Delay inside the player's clock (timestamps), or null without timestamps. */
        val timestampMs: Long?,
        /** Delay the output reports, less the track buffer, or null if not readable. */
        val outputMs: Long?,
        /** The part of [outputMs] the clock does not allow for: never below zero. */
        val extraMs: Long,
        val sampleRateHz: Int,
    )

    private val _reading = MutableStateFlow<Reading?>(null)

    /** The latest settled reading, or null before the first one. */
    val reading: StateFlow<Reading?> = _reading.asStateFlow()

    /** Tracks the players opened, newest last; the playing one is measured. */
    private val tracks = ArrayList<WeakReference<AudioTrack>>()

    private val getLatency: Method? by lazy {
        runCatching { AudioTrack::class.java.getMethod("getLatency") }.getOrNull()
    }

    /** Recent samples of the measured track, for a median. */
    private val samples = ArrayDeque<Reading>()
    private var sampledTrack: WeakReference<AudioTrack>? = null
    private var playingSince = 0L

    /** Wraps a sink's output provider so each AudioTrack it opens can be measured. */
    fun wrap(provider: AudioOutputProvider): AudioOutputProvider =
        object : ForwardingAudioOutputProvider(provider) {
            override fun getAudioOutput(config: AudioOutputProvider.OutputConfig): AudioOutput =
                super.getAudioOutput(config).also { output ->
                    (output as? AudioTrackAudioOutput)?.audioTrack?.let(::register)
                }
        }

    private fun register(track: AudioTrack) = synchronized(tracks) {
        tracks.removeAll { it.get() == null || it.get()?.state != AudioTrack.STATE_INITIALIZED }
        tracks += WeakReference(track)
    }

    /** The newest track that is playing now. */
    private fun playingTrack(): AudioTrack? = synchronized(tracks) {
        tracks.asReversed().firstNotNullOfOrNull { ref ->
            ref.get()?.takeIf {
                runCatching {
                    it.state == AudioTrack.STATE_INITIALIZED && it.playState == AudioTrack.PLAYSTATE_PLAYING
                }.getOrDefault(false)
            }
        }
    }

    /**
     * Takes one sample of the playing track and settles [reading] on the
     * median of the last few. Cheap: no binder call. Any thread; call it
     * every second or so while the delay matters.
     */
    @Synchronized
    fun sample() {
        val track = playingTrack()
        val now = SystemClock.elapsedRealtime()
        if (track == null) {
            playingSince = 0L
            return
        }
        if (sampledTrack?.get() !== track) {
            // A new track (seek, new song, new route): its first timestamps
            // are not worth reading. Start over once it has played a while.
            sampledTrack = WeakReference(track)
            samples.clear()
            playingSince = now
        }
        if (playingSince == 0L) playingSince = now
        if (now - playingSince < SETTLE_MS) return
        val one = runCatching { read(track) }.getOrNull() ?: return
        samples.addLast(one)
        while (samples.size > SAMPLES) samples.removeFirst()
        if (samples.size < MIN_SAMPLES) return
        val settled = median(samples.toList())
        val last = _reading.value
        // Hold the value steady against reading noise; a real change (a new
        // output, a new codec) is far larger than this.
        if (last == null || abs(last.extraMs - settled.extraMs) >= CHANGE_MS ||
            last.timestampMs != settled.timestampMs && (last.timestampMs == null || settled.timestampMs == null) ||
            last.sampleRateHz != settled.sampleRateHz
        ) {
            _reading.value = settled
        }
    }

    /** Forgets the reading: the output changed and the old one says nothing about the new. */
    @Synchronized
    fun reset() {
        samples.clear()
        sampledTrack = null
        playingSince = 0L
        _reading.value = null
    }

    private fun read(track: AudioTrack): Reading? {
        val rate = track.sampleRate.takeIf { it > 0 } ?: return null
        val stamp = AudioTimestamp()
        val hasStamp = track.getTimestamp(stamp)
        val nowNs = System.nanoTime()
        val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
        val timestampMs = if (hasStamp) {
            timestampDelayMs(head, stamp.framePosition, stamp.nanoTime, nowNs, rate)
        } else {
            null
        }
        val outputMs = getLatency?.let { method ->
            val totalMs = (method.invoke(track) as? Int)?.toLong() ?: return@let null
            outputDelayMs(totalMs, track.bufferCapacityInFrames.toLong(), rate)
        }
        return Reading(
            timestampMs = timestampMs,
            outputMs = outputMs,
            extraMs = extraMs(timestampMs, outputMs),
            sampleRateHz = rate,
        )
    }

    /**
     * Frames taken from the track but not yet presented, in ms: the playback
     * head less where the timestamp says the output is now.
     */
    internal fun timestampDelayMs(head: Long, framePosition: Long, stampNs: Long, nowNs: Long, rate: Int): Long? {
        if (stampNs <= 0L || framePosition < 0L) return null
        val presentedNow = framePosition + (nowNs - stampNs) * rate / 1_000_000_000L
        val delayMs = (head - presentedNow) * 1000L / rate
        // A timestamp from before a flush or a head that wrapped reads nonsense.
        return delayMs.takeIf { it in 0L..MAX_DELAY_MS }
    }

    /** `getLatency` counts the track's whole buffer too; what is left is the output path. */
    internal fun outputDelayMs(totalMs: Long, bufferFrames: Long, rate: Int): Long? {
        if (totalMs <= 0L || rate <= 0) return null
        val bufferMs = bufferFrames * 1000L / rate
        return (totalMs - bufferMs).takeIf { it in 0L..MAX_DELAY_MS }
    }

    /**
     * The delay the clock leaves out. Without timestamps Media3 already takes
     * off the output delay itself, so there is nothing extra; under
     * [MIN_EXTRA_MS] is reading noise, not a delay anyone hears.
     */
    internal fun extraMs(timestampMs: Long?, outputMs: Long?): Long {
        if (timestampMs == null || outputMs == null) return 0L
        val extra = outputMs - timestampMs
        return if (extra >= MIN_EXTRA_MS) extra.coerceAtMost(MAX_DELAY_MS) else 0L
    }

    internal fun median(readings: List<Reading>): Reading {
        fun mid(values: List<Long>): Long? = values.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
        val stamps = readings.mapNotNull { it.timestampMs }
        val outputs = readings.mapNotNull { it.outputMs }
        // Half or more without a timestamp: the clock is running without them.
        val timestampMs = if (stamps.size * 2 > readings.size) mid(stamps) else null
        val outputMs = mid(outputs)
        return Reading(
            timestampMs = timestampMs,
            outputMs = outputMs,
            extraMs = mid(readings.map { it.extraMs })?.let { if (timestampMs == null) 0L else it } ?: 0L,
            sampleRateHz = readings.last().sampleRateHz,
        )
    }

    /** A track's first second of timestamps is not reliable (see PlaybackService.lockStandby). */
    private const val SETTLE_MS = 1_500L
    private const val SAMPLES = 7
    private const val MIN_SAMPLES = 3
    private const val CHANGE_MS = 20L
    internal const val MIN_EXTRA_MS = 30L
    internal const val MAX_DELAY_MS = 1_000L
}
