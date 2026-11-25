package com.music.bitchord.ui.player

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

// The queue sheet of the YT Music layout has three places to rest. The first
// swipe up shows the queue in the lower part of the screen, with the cover
// and the controls above it. The second swipe opens it over the whole screen.

/** The sheet shows only its handle and title, at the foot of the screen. */
internal const val YT_SHEET_PEEK = 0f

/** The sheet is in the lower part of the screen, under the controls. */
internal const val YT_SHEET_HALF = 1f

/** The sheet is over the whole screen, under a small header. */
internal const val YT_SHEET_FULL = 2f

/**
 * Where the top edge of the sheet is, in pixels from the top of the player,
 * for a sheet [value] between [YT_SHEET_PEEK] and [YT_SHEET_FULL]. The three
 * tops are the top edge at each rest, and [peekTop] is the lowest.
 */
internal fun ytSheetTop(value: Float, peekTop: Float, halfTop: Float, fullTop: Float): Float {
    val v = value.coerceIn(YT_SHEET_PEEK, YT_SHEET_FULL)
    return if (v <= YT_SHEET_HALF) {
        peekTop + (halfTop - peekTop) * v
    } else {
        halfTop + (fullTop - halfTop) * (v - YT_SHEET_HALF)
    }
}

/**
 * The sheet value after a finger moves [dragPx] pixels (down is positive)
 * from [value]. The sheet follows the finger exactly, so the two parts of
 * its travel, which are different lengths, each use their own length.
 */
internal fun ytSheetValueAfterDrag(
    value: Float,
    dragPx: Float,
    peekTop: Float,
    halfTop: Float,
    fullTop: Float,
): Float {
    var v = value.coerceIn(YT_SHEET_PEEK, YT_SHEET_FULL)
    var left = dragPx
    // At most the two parts of the travel.
    repeat(2) {
        if (left == 0f) return v
        val up = left < 0f
        // At the half rest, a move up is in the upper part and a move down in the lower.
        val lowerPart = if (up) v < YT_SHEET_HALF else v <= YT_SHEET_HALF
        val span = if (lowerPart) peekTop - halfTop else halfTop - fullTop
        if (span <= 0f) return v
        val start = if (lowerPart) YT_SHEET_PEEK else YT_SHEET_HALF
        val next = (v - left / span).coerceIn(start, start + 1f)
        left -= (v - next) * span
        v = next
    }
    return v
}

/**
 * The rest the sheet goes to when the finger lets go at [value]: the next
 * rest in the direction of a flick (a [velocity] of [flick] pixels a second
 * or more; down is positive), else the nearest one.
 */
internal fun ytSheetRestAfterRelease(value: Float, velocity: Float, flick: Float): Float {
    val v = value.coerceIn(YT_SHEET_PEEK, YT_SHEET_FULL)
    return when {
        velocity <= -flick -> ceil(v)
        velocity >= flick -> floor(v)
        else -> v.roundToInt().toFloat()
    }
}

/** The queue sheet's position, and the drags and animations that move it. */
@Stable
internal class YtQueueSheet(initial: Float, private val scope: CoroutineScope) {
    /** Between [YT_SHEET_PEEK] and [YT_SHEET_FULL]. Read in layout and draw. */
    var value by mutableFloatStateOf(initial)
        private set

    /** The rest the sheet is at, or is on its way to. */
    var target by mutableFloatStateOf(initial)
        private set

    // The top edge at each rest, set by the layout that knows the screen.
    var peekTop by mutableFloatStateOf(0f)
    var halfTop by mutableFloatStateOf(0f)
    var fullTop by mutableFloatStateOf(0f)

    /** A flick is a release at this many pixels a second or more. */
    var flick = 0f

    private var motion: Job? = null

    /** The top edge now, in pixels from the top of the player. */
    fun top(): Float = ytSheetTop(value, peekTop, halfTop, fullTop)

    /** Moves the sheet with the finger. Gives back how many of [dragPx] it used. */
    fun dragBy(dragPx: Float): Float {
        if (dragPx == 0f) return 0f
        motion?.cancel()
        val before = top()
        value = ytSheetValueAfterDrag(value, dragPx, peekTop, halfTop, fullTop)
        return top() - before
    }

    /** The finger let go, at [velocity] pixels a second (down is positive). */
    fun settle(velocity: Float) = animateTo(ytSheetRestAfterRelease(value, velocity, flick))

    fun animateTo(rest: Float) {
        target = rest
        motion?.cancel()
        if (value == rest) return
        motion = scope.launch {
            animate(
                initialValue = value,
                targetValue = rest,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ) { now, _ -> value = now }
        }
    }
}

/**
 * Lets a scroll move the sheet: a move up opens the sheet before the list
 * under the finger scrolls, and a move down that the list does not use
 * closes it.
 *
 * With [keepLeftover], scroll that neither the list nor the sheet used stops
 * here. Without that, it would go on to the player around the sheet, and a
 * scroll of the queue would put the player away. The main player's own
 * drag does not keep it: there, a move down that the sheet does not use is
 * the move that puts the player away.
 */
internal fun ytSheetScroll(sheet: YtQueueSheet, keepLeftover: Boolean) = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
        if (source != NestedScrollSource.UserInput) return Offset.Zero
        if (available.y < 0f && sheet.value < YT_SHEET_FULL) return Offset(0f, sheet.dragBy(available.y))
        return Offset.Zero
    }

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
        val used = if (source == NestedScrollSource.UserInput && available.y > 0f && sheet.value > YT_SHEET_PEEK) {
            sheet.dragBy(available.y)
        } else {
            0f
        }
        return if (keepLeftover) available else Offset(0f, used)
    }

    override suspend fun onPreFling(available: Velocity): Velocity {
        // Between two rests: the release is the sheet's, and the list stays where it is.
        if (sheet.value != sheet.target || sheet.value % 1f != 0f) {
            sheet.settle(available.y)
            return available
        }
        return Velocity.Zero
    }

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (keepLeftover) available else Velocity.Zero
}
