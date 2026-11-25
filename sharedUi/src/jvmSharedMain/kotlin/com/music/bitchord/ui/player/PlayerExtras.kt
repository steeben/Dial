package com.music.bitchord.ui.player

/**
 * What the playing song's option chips do, in the YT Music layout: the
 * actions the application has that the player does not do itself. A null
 * action leaves its chip out.
 */
class PlayerQuickActions(
    /** Adds the song to a playlist. */
    val onSave: (() -> Unit)? = null,
    val onShare: (() -> Unit)? = null,
    val onDownload: (() -> Unit)? = null,
    /** The song is on the device, so the download chip says so. */
    val downloaded: Boolean = false,
    /** Starts a radio from the song. */
    val onStartRadio: (() -> Unit)? = null,
)

/** One chip of [PlayerAutoplayStyles]. */
class AutoplayStyleChip(val id: String, val label: String)

/**
 * The chips above AutoPlay's songs in the YT Music layout's queue: each
 * selects how AutoPlay finds more songs. Empty where the playing song offers
 * no styles, or where they are not read yet, and the queue then keeps its
 * AutoPlay heading.
 */
class PlayerAutoplayStyles(
    /** The playing song, which the chips are for. */
    val songId: String,
    val chips: List<AutoplayStyleChip>,
    /** The id of the chip that is in effect. */
    val selectedId: String?,
    val onSelect: (String) -> Unit,
    /**
     * The place for the chips is on screen. The application reads the
     * song's styles only then, because each read is a request.
     */
    val onShown: () -> Unit,
)
