package com.music.bitchord.playback

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.RadioStyle
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.isEpisode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The AutoPlay style the listener selected, and the styles YouTube Music
 * offers for the songs AutoPlay plays on from.
 *
 * A style is one of the chips above YouTube Music's own radio queue
 * ("Popular", "Deep cuts", "1990s" ...). YouTube Music makes the list for
 * each seed song, so the list changes from song to song. The selection is
 * kept by the style's id: a song that offers the selected style plays on in
 * that style, and a song that does not plays on in the default one.
 *
 * The playback service reads [chosen] when it loads AutoPlay's songs. The
 * player's queue shows [offer] and sends the selection to the service.
 */
object AutoplayStyles {
    /** The styles on offer for one song, by the id of that song. */
    data class Offer(val songId: String, val styles: List<RadioStyle>)

    private val _chosen = MutableStateFlow<String?>(null)

    /** The id of the selected style. Null is the default selection. */
    val chosen: StateFlow<String?> = _chosen.asStateFlow()

    private val _offer = MutableStateFlow<Offer?>(null)

    /** The styles to show in the queue: those of the song last asked for with [show]. */
    val offer: StateFlow<Offer?> = _offer.asStateFlow()

    /** The lists already read, so the queue does not ask again for a song AutoPlay loaded. */
    private val known = object : LinkedHashMap<String, List<RadioStyle>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<RadioStyle>>?): Boolean =
            size > MAX_KNOWN
    }

    /** Selects [id]. The default style, and a null id, both clear the selection. */
    fun choose(id: String?, styles: List<RadioStyle> = _offer.value?.styles.orEmpty()) {
        _chosen.value = chosenIdFor(id, styles)
    }

    /** A new queue starts with the default selection. */
    fun reset() {
        _chosen.value = null
    }

    /** Keeps the [styles] that came with the radio of [songId]. */
    fun remember(songId: String, styles: List<RadioStyle>) {
        synchronized(known) { known[songId] = styles }
        // The queue can be open on this song with an empty list from before.
        if (_offer.value?.songId == songId) _offer.value = Offer(songId, styles)
    }

    private fun knownFor(songId: String): List<RadioStyle>? = synchronized(known) { known[songId] }

    /**
     * Makes [song]'s styles the ones on [offer], and reads them if they are
     * not known. A song that has no radio of songs (a podcast episode, a
     * song with no match on YouTube) offers an empty list.
     */
    suspend fun show(song: Song) {
        val songId = song.videoId
        knownFor(songId)?.let {
            _offer.value = Offer(songId, it)
            return
        }
        // The last song's chips do not stay under a different song.
        if (_offer.value?.songId != songId) _offer.value = Offer(songId, emptyList())
        if (song.isEpisode) return
        val seed = youtubeSeedFor(song) ?: return
        val styles = YtMusicRepository.radioStation(seed).getOrNull()?.styles ?: return
        remember(songId, styles)
        _offer.value = Offer(songId, styles)
    }

    private const val MAX_KNOWN = 32
}

/**
 * The id to keep when the listener selects the style [id] from [styles]:
 * null for the default style, because that is the same as no selection.
 */
fun chosenIdFor(id: String?, styles: List<RadioStyle>): String? =
    id?.takeUnless { chosen -> styles.any { it.id == chosen && it.isDefault } }

/**
 * The style to load AutoPlay's songs in, from the [styles] a seed offers:
 * the selected one if the seed offers it. Null is the default radio.
 */
fun styleToLoad(chosenId: String?, styles: List<RadioStyle>): RadioStyle? =
    chosenId?.let { id -> styles.firstOrNull { it.id == id && !it.isDefault } }

/**
 * The id of the chip to show as selected among [styles]: the selected
 * style if this song offers it, else the default one.
 */
fun shownStyleId(chosenId: String?, styles: List<RadioStyle>): String? =
    styleToLoad(chosenId, styles)?.id ?: styles.firstOrNull { it.isDefault }?.id
