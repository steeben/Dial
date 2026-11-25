package com.music.bitchord.data.model

/**
 * One of the chips YouTube Music shows above a radio queue ("Popular",
 * "Deep cuts", "1990s" ...): a different selection of songs for the same seed.
 *
 * YouTube Music makes the list for each seed song, in the listener's
 * language. Thus the labels and the list are different from song to song, and
 * [id] is the only stable name of a style.
 */
data class RadioStyle(
    /** YouTube Music's own name for the chip. The same for each seed song. */
    val id: String,
    /** The text on the chip, in the language of the request. */
    val label: String,
    /** The watch queue that gives the songs of this style for the seed. */
    val target: WatchTarget,
    /** The selection a radio plays when the listener selects no chip ("All"). */
    val isDefault: Boolean = false,
)

/** A radio's songs, and the styles YouTube Music offers for the same seed. */
data class RadioStation(
    val songs: List<Song>,
    val styles: List<RadioStyle> = emptyList(),
)
