package com.music.bitchord.desktop

import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.settings.DialSettings
import com.music.bitchord.data.model.UiState

/** The title Home gives its speed dial shelf, which the shared Home draws as the dial. */
internal const val SPEED_DIAL_TITLE = "Speed dial"
private const val RECENTS_TITLE = "Recents"

/**
 * Home's feed with the speed dial at its head, as the phone builds it: the
 * pins, then what was played lately, YouTube's own speed dial and the rest of
 * the feed, minus what was taken off the dial. YouTube's own speed dial shelf
 * is folded into it rather than shown twice.
 */
internal fun withSpeedDial(
    state: UiState<List<HomeShelf>>,
    speedDialsOn: Boolean,
    pins: List<ShelfItem>,
): UiState<List<HomeShelf>> {
    if (state !is UiState.Success) return state
    val shelves = state.data
    fun isDial(shelf: HomeShelf) = shelf.title.equals(SPEED_DIAL_TITLE, ignoreCase = true)
    if (!speedDialsOn) return UiState.Success(shelves.filterNot(::isDial))
    val official = shelves.firstOrNull(::isDial)?.items.orEmpty()
    val recents = shelves.firstOrNull { it.title.equals(RECENTS_TITLE, ignoreCase = true) }?.items.orEmpty()
    val rest = shelves
        .filterNot { isDial(it) || it.title.equals(RECENTS_TITLE, ignoreCase = true) }
        .flatMap { it.items }
    val dial = DialSettings.assembleDial(pins, recents + official + rest)
    val feed = shelves.filterNot(::isDial)
    return UiState.Success(if (dial.isEmpty()) feed else listOf(HomeShelf(SPEED_DIAL_TITLE, dial)) + feed)
}

/** Whether [item] is one of the mixes YouTube Music builds (a `RDTMAK` / `RDCLAK` playlist, or a radio). */
private fun ShelfItem.isMix(): Boolean {
    val id = browseId?.removePrefix("VL") ?: return false
    return videoId == null && (id.startsWith("RDTMAK") || id.startsWith("RDCLAK") || id.startsWith("RDAMPL") || id.startsWith("RDEM"))
}

/** Whether [item] is an artist's channel. */
private fun ShelfItem.isArtistTile(): Boolean = videoId == null && browseId?.startsWith("UC") == true

/**
 * The three dials for the desktop's dials column, in order: the speed dial,
 * the Mixes Dial and the artists' dial. Until the desktop deals mixes the way
 * the phone does, the Mixes Dial is the mixes in the feed; the artists' dial
 * is its pins, then the artists in the feed.
 */
internal fun desktopDials(
    feed: UiState<List<HomeShelf>>,
    speedDialsOn: Boolean,
    mixesDialOn: Boolean,
    speedPins: List<ShelfItem>,
    artistPins: List<ShelfItem>,
): List<List<ShelfItem>> {
    val shelves = (feed as? UiState.Success)?.data.orEmpty()
    val all = shelves.flatMap { it.items }
    val speed = if (speedDialsOn) {
        (withSpeedDial(feed, true, speedPins) as? UiState.Success)?.data
            ?.firstOrNull { it.title.equals(SPEED_DIAL_TITLE, ignoreCase = true) }?.items.orEmpty()
    } else {
        emptyList()
    }
    val hidden = DialSettings.hiddenFromDial.value
    val mixes = if (mixesDialOn) {
        all.filter { it.isMix() && it.browseId !in hidden }.distinctBy { it.browseId }
    } else {
        emptyList()
    }
    val artists = (artistPins + all.filter { it.isArtistTile() })
        .filter { it.browseId !in hidden }
        .distinctBy { it.browseId }
    return listOf(speed, mixes, artists).map { it.take(DIAL_PAGE_TILES) }
}
