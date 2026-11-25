package com.music.bitchord.playback

import com.music.bitchord.data.model.RadioStyle
import com.music.bitchord.data.model.WatchTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which AutoPlay style is kept, loaded and shown, for the styles a song offers. */
class AutoplayStylesTest {

    private fun style(id: String, default: Boolean = false) =
        RadioStyle(id = id, label = id, target = WatchTarget(playlistId = "RDAT$id"), isDefault = default)

    private val offered = listOf(style("All", default = true), style("Popular"), style("Deep cuts"))

    @Test
    fun `the default style is the same as no selection`() {
        assertNull(chosenIdFor("All", offered))
        assertNull(chosenIdFor(null, offered))
        assertEquals("Popular", chosenIdFor("Popular", offered))
    }

    @Test
    fun `a selection is kept when the list of styles is not known`() {
        assertEquals("Popular", chosenIdFor("Popular", emptyList()))
    }

    @Test
    fun `the selected style is loaded when the song offers it`() {
        assertEquals("Deep cuts", styleToLoad("Deep cuts", offered)?.id)
        assertEquals("RDATDeep cuts", styleToLoad("Deep cuts", offered)?.target?.playlistId)
    }

    @Test
    fun `a song that does not offer the selected style plays on with the default radio`() {
        assertNull(styleToLoad("1990s", offered))
        assertNull(styleToLoad(null, offered))
        assertNull(styleToLoad("Popular", emptyList()))
    }

    @Test
    fun `the default style is never loaded as a style`() {
        // It is the radio itself, which is loaded without a style.
        assertNull(styleToLoad("All", offered))
    }

    @Test
    fun `the chip shown as selected is the one in effect`() {
        assertEquals("Popular", shownStyleId("Popular", offered))
        // With no selection, and for a style this song does not offer, the default chip.
        assertEquals("All", shownStyleId(null, offered))
        assertEquals("All", shownStyleId("1990s", offered))
        assertNull(shownStyleId("Popular", emptyList()))
    }
}
