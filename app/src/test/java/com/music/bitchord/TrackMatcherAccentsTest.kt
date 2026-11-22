package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Song matching without accents (from upstream BitChord 1.10). Titles and
 * artists are compared with their Latin accents removed, and the romanized
 * aside of a CJK title is compared the same way.
 */
class TrackMatcherAccentsTest {

    private fun song(title: String, artist: String) =
        Song(videoId = "x", title = title, artist = artist, thumbnailUrl = null)

    @Test
    fun `a Turkish title matches its unaccented upload`() {
        assertTrue(TrackMatcher.matches(song("Simarik", "Tarkan"), "Şımarık", "Tarkan"))
        assertTrue(TrackMatcher.matches(song("Şımarık", "Tarkan"), "Simarik", "Tarkan"))
    }

    @Test
    fun `an accented artist matches its unaccented spelling`() {
        assertTrue(TrackMatcher.matches(song("Nilüfer", "Muslum Gurses"), "Nilüfer", "Müslüm Gürses"))
        assertTrue(TrackMatcher.matches(song("Nilufer", "Müslüm Gürses"), "Nilüfer", "Muslum Gurses"))
    }

    @Test
    fun `a different title still does not match`() {
        assertFalse(TrackMatcher.matches(song("Kuzu Kuzu", "Tarkan"), "Şımarık", "Tarkan"))
    }

    @Test
    fun `a Spanish title matches its unaccented spelling`() {
        assertTrue(TrackMatcher.matches(song("Senorita", "Shawn Mendes"), "Señorita", "Shawn Mendes"))
    }

    // This case matched before the change too. The aside is folded, so it must not break.
    @Test
    fun `a CJK title with an accented romanization matches the romanized upload`() {
        assertTrue(TrackMatcher.matches(song("Tōkyō", "Yui"), "東京 (Tōkyō)", "Yui"))
        assertTrue(TrackMatcher.matches(song("東京 (Tōkyō)", "Yui"), "Tōkyō", "Yui"))
    }

    @Test
    fun `a CJK title with an accented romanization matches the unaccented upload`() {
        assertTrue(TrackMatcher.matches(song("Tokyo", "Yui"), "東京 (Tōkyō)", "Yui"))
        assertTrue(TrackMatcher.matches(song("東京 (Tōkyō)", "Yui"), "Tokyo", "Yui"))
    }
}
