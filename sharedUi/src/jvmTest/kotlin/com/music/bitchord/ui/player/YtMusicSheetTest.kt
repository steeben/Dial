package com.music.bitchord.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The queue sheet of the YT Music layout: where it is, and where it comes to rest. */
class YtMusicSheetTest {

    // A 2000 px tall player: the sheet's top is at 1800 closed, 1100 half open
    // and 300 fully open. The two parts of its travel are 700 and 800 px.
    private val peek = 1800f
    private val half = 1100f
    private val full = 300f

    private fun top(value: Float) = ytSheetTop(value, peek, half, full)
    private fun drag(value: Float, px: Float) = ytSheetValueAfterDrag(value, px, peek, half, full)

    @Test
    fun `the top edge is at each rest and goes straight between them`() {
        assertEquals(peek, top(YT_SHEET_PEEK), 1e-4f)
        assertEquals(half, top(YT_SHEET_HALF), 1e-4f)
        assertEquals(full, top(YT_SHEET_FULL), 1e-4f)
        assertEquals(1450f, top(0.5f), 1e-4f)
        assertEquals(700f, top(1.5f), 1e-4f)
        // Out of range values stay at the ends.
        assertEquals(peek, top(-1f), 1e-4f)
        assertEquals(full, top(3f), 1e-4f)
    }

    @Test
    fun `the sheet follows the finger exactly in each part of its travel`() {
        // 350 px up from closed is half of the lower part.
        assertEquals(0.5f, drag(YT_SHEET_PEEK, -350f), 1e-4f)
        // 400 px up from half open is half of the upper part.
        assertEquals(1.5f, drag(YT_SHEET_HALF, -400f), 1e-4f)
        // And the same down again.
        assertEquals(YT_SHEET_HALF, drag(1.5f, 400f), 1e-4f)
        assertEquals(YT_SHEET_PEEK, drag(0.5f, 350f), 1e-4f)
    }

    @Test
    fun `one move can go through the half rest`() {
        // 350 px to the half rest, then 400 px of the upper part.
        val value = drag(0.5f, -750f)
        assertEquals(1.5f, value, 1e-4f)
        assertEquals(top(0.5f) - 750f, top(value), 0.5f)
        // Down through it: 400 px to the half rest, then 350 px of the lower part.
        assertEquals(0.5f, drag(1.5f, 750f), 1e-4f)
    }

    @Test
    fun `the sheet stops at its two ends`() {
        assertEquals(YT_SHEET_FULL, drag(1.9f, -5000f), 1e-4f)
        assertEquals(YT_SHEET_PEEK, drag(0.1f, 5000f), 1e-4f)
        assertEquals(YT_SHEET_PEEK, drag(YT_SHEET_PEEK, 40f), 1e-4f)
        assertEquals(YT_SHEET_FULL, drag(YT_SHEET_FULL, -40f), 1e-4f)
    }

    @Test
    fun `a sheet that has nowhere to go does not move`() {
        // Before the first layout the three rests are the same place.
        assertEquals(0.4f, ytSheetValueAfterDrag(0.4f, -100f, 0f, 0f, 0f), 1e-4f)
    }

    @Test
    fun `a slow release goes to the nearest rest`() {
        assertEquals(YT_SHEET_PEEK, ytSheetRestAfterRelease(0.4f, velocity = 0f, flick = 1000f), 1e-4f)
        assertEquals(YT_SHEET_HALF, ytSheetRestAfterRelease(0.6f, velocity = 0f, flick = 1000f), 1e-4f)
        assertEquals(YT_SHEET_HALF, ytSheetRestAfterRelease(1.4f, velocity = 300f, flick = 1000f), 1e-4f)
        assertEquals(YT_SHEET_FULL, ytSheetRestAfterRelease(1.6f, velocity = -300f, flick = 1000f), 1e-4f)
    }

    @Test
    fun `a flick goes one rest in its direction and no more`() {
        // The first swipe up shows the queue in the lower part of the screen.
        assertEquals(YT_SHEET_HALF, ytSheetRestAfterRelease(0.1f, velocity = -2000f, flick = 1000f), 1e-4f)
        // The second swipe opens it fully.
        assertEquals(YT_SHEET_FULL, ytSheetRestAfterRelease(1.1f, velocity = -2000f, flick = 1000f), 1e-4f)
        // A flick down from fully open stops at the half rest.
        assertEquals(YT_SHEET_HALF, ytSheetRestAfterRelease(1.9f, velocity = 2000f, flick = 1000f), 1e-4f)
        assertEquals(YT_SHEET_PEEK, ytSheetRestAfterRelease(0.9f, velocity = 2000f, flick = 1000f), 1e-4f)
    }

    @Test
    fun `a queue row is removed only when it is swiped far enough`() {
        assertFalse(rowSwipeRemoves(offsetPx = 100f, widthPx = 1000f))
        assertFalse(rowSwipeRemoves(offsetPx = -340f, widthPx = 1000f))
        assertTrue(rowSwipeRemoves(offsetPx = 350f, widthPx = 1000f))
        assertTrue(rowSwipeRemoves(offsetPx = -900f, widthPx = 1000f))
        // A row with no width yet is not removed by a stray release.
        assertFalse(rowSwipeRemoves(offsetPx = 0f, widthPx = 0f))
    }
}
