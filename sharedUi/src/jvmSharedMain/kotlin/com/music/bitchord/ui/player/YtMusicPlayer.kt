package com.music.bitchord.ui.player

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speaker
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.PaddingValues
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.data.model.Song
import com.music.bitchord.sharedui.resources.*
import com.music.bitchord.ui.components.ExplicitSongTitle
import com.music.bitchord.ui.components.PlayPauseGlyph
import com.music.bitchord.ui.components.menuAnchor
import com.music.bitchord.ui.haptics.Haptic
import com.music.bitchord.ui.haptics.rememberHaptics
import com.music.bitchord.ui.icons.BitChordIcons
import org.jetbrains.compose.resources.stringResource
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// The player's YT Music layout: the cover, the title, a row of option chips
// that scrolls sideways, the seek bar and the transport, and the queue in a
// sheet at the foot of the screen. The first swipe up puts the queue in the
// lower part of the screen, and the second opens it over the whole screen.
//
// The portrait player only. [NowPlayingScreen] owns the song's state (the
// cover, the Canvas, the lyrics, the drawers) and gives it to this layout,
// as it gives it to the landscape one.

/** The space at each side of the main player's content. */
private val YT_GUTTER = 24.dp

/** The height of the bar at the top: the close button, the caption, the output and the menu. */
private val YT_TOP_BAR = 52.dp

/** The height of the small header that stays above the sheet when the sheet is fully open. */
private val YT_MINI_HEADER = 64.dp

/** How much of the sheet shows at the foot of the screen when it is closed, above the navigation bar. */
private val YT_PEEK_HEIGHT = 60.dp

/** The strip at the top of the sheet that holds the handle. */
private val YT_HANDLE_STRIP = 18.dp

/** The row under the handle that holds the sheet's title. */
private val YT_SHEET_TITLE_ROW = 42.dp

/** Where the sheet's top edge is at the half rest, as a share of the player's height. */
private const val YT_HALF_TOP = 0.54f

/** The least room the cover and the controls keep above a half-open sheet. */
private val YT_HALF_MIN_ABOVE = 372.dp

/** The widest the content gets. A tablet held upright centres it. */
private val YT_MAX_WIDTH = 620.dp

private val YtSheetShape = RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)
private val YtArtShape = RoundedCornerShape(14.dp)
private val YtChipShape = RoundedCornerShape(50)
private val YtStyleChipShape = RoundedCornerShape(9.dp)

/** A flick of the sheet, in dp a second. */
private val YT_SHEET_FLICK = 420.dp

/**
 * The portrait player in its YT Music layout — see the note at the top of
 * this file.
 */
@Composable
internal fun YtMusicPlayer(
    song: Song,
    isPlaying: Boolean,
    /** While the stream resolves and buffers, a play glyph would be wrong. */
    isLoading: Boolean,
    queue: List<Song>,
    queueIndex: Int,
    hasNext: Boolean,
    repeatMode: Int,
    shuffleEnabled: Boolean,
    autoplayEnabled: Boolean,
    /** Whether the song can be liked: an account is signed in, or the song is on the device. */
    canLike: Boolean,
    liked: Boolean,
    /** A party's host has the controls: only play and pause stay. */
    controlsLocked: Boolean,
    /** "Playing from …"; null where Settings hides it. */
    caption: String?,
    art: PlayerArtwork,
    /** The looping clip that plays where the cover is, if the song has one. */
    canvas: CanvasArtwork?,
    canvasRendered: Boolean,
    onCanvasRenderedChange: (Boolean) -> Unit,
    /** The song is a music video that plays as a video, in the cover's place. */
    videoMode: Boolean,
    videoRendered: Boolean,
    onVideoRenderedChange: (Boolean) -> Unit,
    onVideoFullscreen: () -> Unit,
    /** See [OutgoingArtwork]. */
    lastCoverHidden: () -> Boolean,
    statusBarTop: Dp,
    /** The menu's glyph is the undo arrow for a moment after a quality upgrade. */
    showRevertCue: Boolean,
    /** The playing song is a podcast episode: it has comments and no lyrics. */
    comments: Boolean,
    lyricsOpen: Boolean,
    queueOpen: Boolean,
    onOpenLyrics: () -> Unit,
    onCloseLyrics: () -> Unit,
    onQueueOpenChange: (Boolean) -> Unit,
    /** Skips the song with a sideways swipe on the cover. */
    artGesture: Modifier,
    /** How far that swipe has moved the cover, in pixels. */
    artOffset: () -> Float,
    quickActions: PlayerQuickActions?,
    autoplayStyles: PlayerAutoplayStyles?,
    onCollapse: (() -> Unit)?,
    onToggleLike: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onJumpTo: (Int) -> Unit,
    onRemoveFromQueue: (Int) -> Unit,
    onMoveInQueue: (Int, Int) -> Unit,
    onQueueDragActiveChange: (Boolean) -> Unit,
    onClearQueue: () -> Unit,
    onOpenMenu: () -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (browseId: String?, name: String) -> Unit,
    onOpenCaption: () -> Unit,
    onOpenOutput: () -> Unit,
    onListenTogether: () -> Unit,
    onOpenListenTogetherMembers: () -> Unit,
    /** The blurred cover the whole layout stands on. */
    backdrop: @Composable (Modifier) -> Unit,
    scrubber: @Composable () -> Unit,
    /** The one line of lyrics above the seek bar; null where Settings hides it. */
    lyricStrip: (@Composable () -> Unit)?,
    /** The lyrics, for the sheet. */
    lyricsPane: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        val density = LocalDensity.current
        val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val heightPx = constraints.maxHeight.toFloat()
        val scope = rememberCoroutineScope()
        val sheet = remember {
            YtQueueSheet(
                initial = when {
                    lyricsOpen -> YT_SHEET_FULL
                    queueOpen -> YT_SHEET_HALF
                    else -> YT_SHEET_PEEK
                },
                scope = scope,
            )
        }
        // Written before anything below reads them. They change only with the window.
        with(density) {
            val fullTop = (statusBarTop + YT_MINI_HEADER).toPx()
            val peekTop = max(fullTop, heightPx - (YT_PEEK_HEIGHT + navBottom).toPx())
            sheet.fullTop = fullTop
            sheet.peekTop = peekTop
            sheet.halfTop = max(heightPx * YT_HALF_TOP, (statusBarTop + YT_HALF_MIN_ABOVE).toPx())
                .coerceIn(fullTop, peekTop)
            sheet.flick = YT_SHEET_FLICK.toPx()
        }

        // The sheet and the player's own two flags say the same thing two ways:
        // the lyrics and the queue are "open" for the back gesture and for the
        // screen the player comes back to. Each follows the other.
        LaunchedEffect(sheet.target) {
            if (sheet.target == YT_SHEET_PEEK) {
                if (lyricsOpen) onCloseLyrics()
                onQueueOpenChange(false)
            } else if (!lyricsOpen) {
                onQueueOpenChange(true)
            }
        }
        LaunchedEffect(lyricsOpen, queueOpen) {
            when {
                lyricsOpen -> if (sheet.target == YT_SHEET_PEEK) sheet.animateTo(YT_SHEET_FULL)
                !queueOpen -> if (sheet.target != YT_SHEET_PEEK) sheet.animateTo(YT_SHEET_PEEK)
                sheet.target == YT_SHEET_PEEK -> sheet.animateTo(YT_SHEET_HALF)
            }
        }

        // Composed once for each side of a threshold, not for each frame of the sheet.
        val mainShown by remember { derivedStateOf { sheet.value < 1.7f } }
        val miniShown by remember { derivedStateOf { sheet.value > 1.3f } }

        val mainScroll = remember(sheet) { ytSheetScroll(sheet, keepLeftover = false) }
        val listScroll = remember(sheet) { ytSheetScroll(sheet, keepLeftover = true) }

        backdrop(Modifier.fillMaxSize())

        // ---- The main player: everything above the sheet ----
        Box(
            modifier = Modifier
                .fillMaxSize()
                // The foot of this column is the top of the sheet, down to the
                // half rest. Above that the sheet goes over it.
                .layout { measurable, constraints ->
                    val foot = max(sheet.top(), sheet.halfTop).roundToInt()
                        .coerceIn(0, constraints.maxHeight)
                    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = foot))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
                }
                .drawWithContent {
                    // Not drawn under the sheet, which is not opaque.
                    clipRect(bottom = sheet.top()) { this@drawWithContent.drawContent() }
                }
                .graphicsLayer {
                    alpha = (1f - (sheet.value - YT_SHEET_HALF) / 0.6f).coerceIn(0f, 1f)
                }
                // A move up on the player opens the queue. A move down that the
                // sheet does not use goes on to the panel around the player,
                // which puts the player away.
                .nestedScroll(mainScroll)
                .scrollable(
                    state = rememberScrollableState { 0f },
                    orientation = Orientation.Vertical,
                    enabled = mainShown,
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = YT_MAX_WIDTH)
                    .fillMaxSize()
                    .padding(top = statusBarTop),
            ) {
                YtTopBar(
                    caption = caption,
                    showRevertCue = showRevertCue,
                    onCollapse = onCollapse,
                    onOpenCaption = onOpenCaption,
                    onOpenOutput = onOpenOutput,
                    onOpenMenu = onOpenMenu,
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = YT_GUTTER, vertical = 10.dp)
                        .then(artGesture),
                ) {
                    YtArtwork(
                        song = song,
                        isPlaying = isPlaying,
                        art = art,
                        // A clip under a sheet that covers it is work nobody sees.
                        canvas = canvas.takeIf { mainShown },
                        canvasRendered = canvasRendered,
                        onCanvasRenderedChange = onCanvasRenderedChange,
                        videoMode = videoMode && mainShown,
                        videoRendered = videoRendered,
                        onVideoRenderedChange = onVideoRenderedChange,
                        onVideoFullscreen = onVideoFullscreen,
                        lastCoverHidden = lastCoverHidden,
                        offset = artOffset,
                    )
                }
                YtCredits(
                    song = song,
                    onOpenAlbum = onOpenAlbum,
                    onOpenArtist = onOpenArtist,
                    modifier = Modifier.padding(horizontal = YT_GUTTER),
                )
                // The chips and the lyric line give their room to the queue as
                // the sheet comes up to the half rest.
                Column(Modifier.foldAway { sheet.value.coerceIn(YT_SHEET_PEEK, YT_SHEET_HALF) }) {
                    Spacer(Modifier.height(14.dp))
                    YtOptionChips(
                        canLike = canLike,
                        liked = liked,
                        comments = comments,
                        autoplayEnabled = autoplayEnabled,
                        controlsLocked = controlsLocked,
                        quickActions = quickActions,
                        onToggleLike = onToggleLike,
                        onOpenLyrics = onOpenLyrics,
                        onToggleAutoplay = onToggleAutoplay,
                        onOpenOutput = onOpenOutput,
                        onListenTogether = onListenTogether,
                        onOpenListenTogetherMembers = onOpenListenTogetherMembers,
                    )
                    if (lyricStrip != null) {
                        Box(Modifier.padding(horizontal = YT_GUTTER).padding(top = 6.dp)) { lyricStrip() }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Box(Modifier.padding(horizontal = YT_GUTTER)) { scrubber() }
                YtTransport(
                    isPlaying = isPlaying,
                    isLoading = isLoading,
                    hasNext = hasNext,
                    repeatMode = repeatMode,
                    shuffleEnabled = shuffleEnabled,
                    controlsLocked = controlsLocked,
                    onPlayPause = onPlayPause,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onToggleShuffle = onToggleShuffle,
                    onCycleRepeat = onCycleRepeat,
                    modifier = Modifier.padding(horizontal = YT_GUTTER - 12.dp),
                )
                Spacer(Modifier.height(10.dp))
            }
        }

        // ---- The small header, above a sheet that is fully open ----
        if (miniShown) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = statusBarTop)
                    .height(YT_MINI_HEADER)
                    .graphicsLayer {
                        alpha = ((sheet.value - 1.4f) / 0.6f).coerceIn(0f, 1f)
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                YtMiniHeader(
                    song = song,
                    art = art,
                    isPlaying = isPlaying,
                    isLoading = isLoading,
                    onPlayPause = onPlayPause,
                    onOpenPlayer = { sheet.animateTo(YT_SHEET_PEEK) },
                    modifier = Modifier.widthIn(max = YT_MAX_WIDTH),
                )
            }
        }

        // ---- The sheet: the queue, or the lyrics ----
        Box(
            modifier = Modifier
                .fillMaxSize()
                // Always its full height, and moved: the list in it is laid out
                // once, not on each frame of the sheet's travel.
                .layout { measurable, constraints ->
                    val height = (constraints.maxHeight - sheet.fullTop).roundToInt().coerceAtLeast(0)
                    val placeable = measurable.measure(Constraints.fixed(constraints.maxWidth, height))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(0, sheet.top().roundToInt())
                    }
                },
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = YT_MAX_WIDTH)
                    .fillMaxSize()
                    .clip(YtSheetShape)
                    .background(Color.White.copy(alpha = 0.08f)),
            ) {
                val sheetDrag = rememberDraggableState { delta -> sheet.dragBy(delta) }
                val haptics = rememberHaptics()
                YtSheetHeader(
                    title = queueTitle(song),
                    lyrics = lyricsOpen,
                    autoplayEnabled = autoplayEnabled,
                    open = { (sheet.value / YT_SHEET_HALF).coerceIn(0f, 1f) },
                    onToggleAutoplay = onToggleAutoplay,
                    onCloseLyrics = onCloseLyrics,
                    modifier = Modifier
                        .draggable(
                            state = sheetDrag,
                            orientation = Orientation.Vertical,
                            onDragStopped = { velocity -> sheet.settle(velocity) },
                        )
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            // A tap opens a closed sheet, as the first swipe does.
                            if (sheet.target == YT_SHEET_PEEK) {
                                haptics.play(Haptic.Expand)
                                sheet.animateTo(YT_SHEET_HALF)
                            }
                        },
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = PLAYER_GUTTER),
                ) {
                    if (lyricsOpen) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .nestedScroll(listScroll)
                                .padding(bottom = navBottom + 8.dp),
                        ) { lyricsPane() }
                    } else {
                        // No chips while a party's host has the queue.
                        val styles = autoplayStyles?.takeUnless { controlsLocked }
                        InlineQueue(
                            queue = queue,
                            currentIndex = queueIndex,
                            autoplayEnabled = autoplayEnabled,
                            controlsLocked = controlsLocked,
                            onJumpTo = onJumpTo,
                            onRemove = onRemoveFromQueue,
                            onMove = onMoveInQueue,
                            onClear = onClearQueue,
                            onDragActiveChange = onQueueDragActiveChange,
                            modifier = Modifier.fillMaxSize(),
                            showTitle = false,
                            listScroll = listScroll,
                            autoplayStyles = if (styles != null) {
                                { heading ->
                                    // Asked for only when the queue is open
                                    // and this place in it is near the screen.
                                    val open by remember { derivedStateOf { sheet.value > 0.05f } }
                                    LaunchedEffect(styles.songId, open) { if (open) styles.onShown() }
                                    // A song that offers no styles keeps the heading.
                                    if (styles.chips.isNotEmpty()) YtAutoplayStyleChips(styles) else heading()
                                }
                            } else {
                                null
                            },
                            bottomInset = navBottom + 16.dp,
                            swipeRows = true,
                        )
                    }
                }
            }
        }
    }
}

/**
 * What reads on a surface filled with [ink]: black on the light ink the
 * player has over most covers, white on the dark ink it has over a bright
 * one. Asked of the ink as it is drawn, because the ink changes over most of
 * a second: asked of the verdict, the play glyph was white on an ink that was
 * still nearly white.
 */
private fun inkContrast(ink: Color): Color = if (ink.luminance() > 0.5f) Color.Black else Color.White

/** What the queue is called at the top of its sheet: where the music is playing from. */
@Composable
private fun queueTitle(song: Song): String =
    song.radioName?.takeIf { it.isNotBlank() }?.let { stringResource(Res.string.radio_named, it) }
        ?: song.playbackSource?.takeIf { it.isNotBlank() }
        ?: song.albumName?.takeIf { it.isNotBlank() }
        ?: stringResource(Res.string.up_next)

/**
 * Gives a block's height away as [fraction] goes from 0 to 1, and fades it
 * out ahead of that. Read in layout and draw, so the fold does not recompose.
 */
private fun Modifier.foldAway(fraction: () -> Float): Modifier = this
    // To the height that is left, not to the block's own.
    .clipToBounds()
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val height = (placeable.height * (1f - fraction().coerceIn(0f, 1f))).roundToInt()
        layout(placeable.width, height) { placeable.place(0, 0) }
    }
    .graphicsLayer { alpha = (1f - fraction() * 2f).coerceIn(0f, 1f) }

/** As wide as the room allows at [aspect] (width over height), and in the middle of it. */
private fun Modifier.fitAspect(aspect: Float): Modifier = layout { measurable, constraints ->
    val width = min(constraints.maxWidth.toFloat(), constraints.maxHeight * aspect).roundToInt().coerceAtLeast(0)
    val height = (width / aspect).roundToInt().coerceAtLeast(0)
    val placeable = measurable.measure(Constraints.fixed(width, height))
    layout(constraints.maxWidth, constraints.maxHeight) {
        placeable.place((constraints.maxWidth - width) / 2, (constraints.maxHeight - height) / 2)
    }
}

@Composable
private fun YtIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = PlayerInk,
    haptic: Haptic = Haptic.Tap,
    enabled: Boolean = true,
    iconSize: Dp = 24.dp,
) {
    val haptics = rememberHaptics()
    Box(
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled) {
                haptics.play(haptic)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** The bar at the top: close, what the music is playing from, the output and the menu. */
@Composable
private fun YtTopBar(
    caption: String?,
    showRevertCue: Boolean,
    onCollapse: (() -> Unit)?,
    onOpenCaption: () -> Unit,
    onOpenOutput: () -> Unit,
    onOpenMenu: () -> Unit,
) {
    val cast by PlayerPlatform.host.castState.collectAsStateWithLifecycle()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(YT_TOP_BAR)
            .padding(horizontal = YT_GUTTER - 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onCollapse != null) {
            YtIconButton(
                icon = Icons.Rounded.KeyboardArrowDown,
                contentDescription = stringResource(Res.string.close_player),
                onClick = onCollapse,
                iconSize = 30.dp,
            )
        } else {
            Spacer(Modifier.size(44.dp))
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            if (caption != null) {
                PlaybackOriginCaption(
                    text = caption,
                    onClick = onOpenCaption,
                    textAlign = TextAlign.Center,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                )
            }
        }
        YtIconButton(
            icon = if (cast.supported) Icons.Rounded.Cast else Icons.Rounded.Speaker,
            contentDescription = stringResource(Res.string.audio_output),
            onClick = onOpenOutput,
        )
        // The menu hangs off this button — see [menuAnchor].
        Box(Modifier.menuAnchor()) {
            YtIconButton(
                icon = if (showRevertCue) Icons.AutoMirrored.Rounded.Undo else Icons.Rounded.MoreVert,
                contentDescription = stringResource(Res.string.more),
                onClick = onOpenMenu,
            )
        }
    }
}

/** The cover, the clip that plays over it, or the music video in its place. */
@Composable
private fun YtArtwork(
    song: Song,
    isPlaying: Boolean,
    art: PlayerArtwork,
    canvas: CanvasArtwork?,
    canvasRendered: Boolean,
    onCanvasRenderedChange: (Boolean) -> Unit,
    videoMode: Boolean,
    videoRendered: Boolean,
    onVideoRenderedChange: (Boolean) -> Unit,
    onVideoFullscreen: () -> Unit,
    lastCoverHidden: () -> Boolean,
    offset: () -> Float,
) {
    if (videoMode) {
        val videoAspect by PlayerPlatform.host.musicVideoAspect.collectAsStateWithLifecycle()
        Box(
            modifier = Modifier
                .fitAspect(videoAspect.coerceIn(1f, 2.4f))
                .graphicsLayer { translationX = offset() }
                .clip(YtArtShape),
        ) {
            // Black with a spinner under the picture until its first frame.
            if (!videoRendered) VideoLoadingBackdrop(Modifier.fillMaxSize())
            MusicVideoSurface(
                videoId = song.videoId,
                onRenderedChanged = onVideoRenderedChange,
                modifier = Modifier.fillMaxSize(),
            )
            VideoCornerControls(
                fullscreen = false,
                onToggleFullscreen = onVideoFullscreen,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            )
        }
    } else {
        val shape: () -> Shape = remember { { YtArtShape } }
        LandscapeArtwork(
            artRequest = art.request,
            artLoaded = art.loaded,
            onArtState = art::onState,
            canvas = canvas,
            canvasRendered = canvasRendered && canvas != null,
            isPlaying = isPlaying,
            onCanvasRenderedChange = onCanvasRenderedChange,
            sleeveShape = shape,
            outgoingArt = { OutgoingArtwork(art, Modifier.fillMaxSize(), lastCoverHidden = lastCoverHidden) },
            modifier = Modifier
                .fitAspect(1f)
                .graphicsLayer { translationX = offset() },
        )
    }
}

/** The title and the artists, at the start of the line as YouTube Music has them. */
@Composable
private fun YtCredits(
    song: Song,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (browseId: String?, name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Crossfade(
        targetState = song.title to song.artist,
        animationSpec = tween(durationMillis = 300),
        modifier = modifier.fillMaxWidth(),
        label = "ytCredits",
    ) {
        Column {
            var titleOverflowing by remember { mutableStateOf(false) }
            MarqueeText(
                text = song.title,
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                color = PlayerInk,
                onOverflowChange = { titleOverflowing = it },
                leading = if (song.isExplicit == true) {
                    { ExplicitBadge(PlayerInk) }
                } else {
                    null
                },
                modifier = Modifier.opensPage(song.albumId, onOpenAlbum),
            )
            Spacer(Modifier.height(2.dp))
            ArtistCreditsMarquee(
                text = song.artist,
                credits = remember(song.artist, song.artists, song.artistId) {
                    artistCredits(song.artist, song.artists, song.artistId)
                },
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.W500),
                color = playerInk(alpha = 0.6f),
                startDelayMillis = if (titleOverflowing) MARQUEE_ARTIST_STAGGER_MS else 0L,
                onOpenArtist = onOpenArtist,
            )
        }
    }
}

/** One chip: a glyph and a word. A selected chip is filled with the ink. */
@Composable
private fun YtChip(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    shape: Shape = YtChipShape,
    haptic: Haptic = Haptic.Tap,
) {
    val haptics = rememberHaptics()
    val ink = PlayerInk
    val onInk = inkContrast(ink)
    val fill by animateFloatAsState(if (selected) 1f else 0f, tween(180), label = "ytChipFill")
    val content = if (selected) onInk else ink
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(shape)
            .background(ink.copy(alpha = 0.14f + (0.92f - 0.14f) * fill))
            .clickable(enabled = enabled) {
                haptics.play(haptic)
                onClick()
            }
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f }
            .padding(horizontal = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.W600),
            color = content,
            maxLines = 1,
        )
    }
}

/** The row of options under the title. It scrolls sideways, edge to edge. */
@Composable
private fun YtOptionChips(
    canLike: Boolean,
    liked: Boolean,
    comments: Boolean,
    autoplayEnabled: Boolean,
    controlsLocked: Boolean,
    quickActions: PlayerQuickActions?,
    onToggleLike: () -> Unit,
    onOpenLyrics: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onOpenOutput: () -> Unit,
    onListenTogether: () -> Unit,
    onOpenListenTogetherMembers: () -> Unit,
) {
    val showParty by PlayerSettings.showListenTogetherButton.collectAsStateWithLifecycle()
    val party by PlayerPlatform.host.party.collectAsStateWithLifecycle()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = YT_GUTTER),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (canLike) {
            YtChip(
                label = stringResource(Res.string.like),
                icon = if (liked) BitChordIcons.HeartFilled else BitChordIcons.Heart,
                selected = liked,
                onClick = onToggleLike,
                haptic = if (liked) Haptic.ToggleOff else Haptic.ToggleOn,
            )
        }
        YtChip(
            label = stringResource(if (comments) Res.string.comments else Res.string.open_lyrics),
            icon = if (comments) BitChordIcons.Comments else BitChordIcons.LyricsQuote,
            onClick = onOpenLyrics,
            haptic = Haptic.Expand,
        )
        quickActions?.onSave?.let {
            YtChip(
                label = stringResource(Res.string.save_to_playlist),
                icon = Icons.AutoMirrored.Rounded.PlaylistAdd,
                onClick = it,
            )
        }
        quickActions?.onShare?.let {
            YtChip(label = stringResource(Res.string.share), icon = Icons.Rounded.Share, onClick = it)
        }
        quickActions?.onDownload?.let {
            YtChip(
                label = stringResource(
                    if (quickActions.downloaded) Res.string.downloaded else Res.string.download,
                ),
                icon = if (quickActions.downloaded) Icons.Rounded.DownloadDone else BitChordIcons.Download,
                onClick = it,
            )
        }
        quickActions?.onStartRadio?.let {
            YtChip(
                label = stringResource(Res.string.start_radio),
                icon = Icons.Rounded.Radio,
                onClick = it,
                enabled = !controlsLocked,
            )
        }
        YtChip(
            label = stringResource(Res.string.autoplay),
            icon = BitChordIcons.Infinity,
            selected = autoplayEnabled,
            onClick = onToggleAutoplay,
            haptic = if (autoplayEnabled) Haptic.ToggleOff else Haptic.ToggleOn,
        )
        YtChip(
            label = stringResource(Res.string.audio_output),
            icon = Icons.Rounded.Speaker,
            onClick = onOpenOutput,
        )
        if (showParty) {
            YtChip(
                label = stringResource(Res.string.listen_together),
                icon = Icons.Rounded.Group,
                selected = party.inParty,
                // In a party, who is in it is worth a look before its settings.
                onClick = if (party.inParty) onOpenListenTogetherMembers else onListenTogether,
            )
        }
    }
}

/** Shuffle, previous, play or pause, next and repeat, as YouTube Music has them. */
@Composable
private fun YtTransport(
    isPlaying: Boolean,
    isLoading: Boolean,
    hasNext: Boolean,
    repeatMode: Int,
    shuffleEnabled: Boolean,
    controlsLocked: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    val ink = PlayerInk
    val onInk = inkContrast(ink)
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        YtIconButton(
            icon = BitChordIcons.Shuffle,
            contentDescription = stringResource(if (shuffleEnabled) Res.string.shuffle_on else Res.string.shuffle_off),
            onClick = onToggleShuffle,
            tint = playerInk(alpha = if (shuffleEnabled) 1f else 0.5f),
            haptic = if (shuffleEnabled) Haptic.ToggleOff else Haptic.ToggleOn,
            enabled = !controlsLocked,
        )
        YtIconButton(
            icon = Icons.Rounded.SkipPrevious,
            contentDescription = stringResource(Res.string.widget_previous),
            onClick = onPrevious,
            tint = playerInk(alpha = if (controlsLocked) 0.3f else 1f),
            haptic = Haptic.SkipPrevious,
            enabled = !controlsLocked,
            iconSize = 40.dp,
            modifier = Modifier.size(56.dp),
        )
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(ink)
                .clickable(enabled = !isLoading) {
                    haptics.play(if (isPlaying) Haptic.Pause else Haptic.Resume)
                    onPlayPause()
                },
            contentAlignment = Alignment.Center,
        ) {
            if (isLoading) {
                CircularProgressIndicator(color = onInk, strokeWidth = 3.dp, modifier = Modifier.size(30.dp))
            } else {
                PlayPauseGlyph(
                    isPlaying = isPlaying,
                    tint = onInk,
                    contentDescription = stringResource(if (isPlaying) Res.string.pause else Res.string.play),
                    modifier = Modifier.size(38.dp),
                )
            }
        }
        YtIconButton(
            icon = Icons.Rounded.SkipNext,
            contentDescription = stringResource(Res.string.widget_next),
            onClick = onNext,
            tint = playerInk(alpha = if (controlsLocked || !hasNext) 0.3f else 1f),
            haptic = Haptic.SkipNext,
            enabled = !controlsLocked && hasNext,
            iconSize = 40.dp,
            modifier = Modifier.size(56.dp),
        )
        Box(contentAlignment = Alignment.Center) {
            YtIconButton(
                icon = BitChordIcons.Repeat,
                contentDescription = stringResource(
                    when (repeatMode) {
                        RepeatModes.ONE -> Res.string.repeat_one
                        RepeatModes.ALL -> Res.string.repeat_all
                        else -> Res.string.repeat_off
                    },
                ),
                onClick = onCycleRepeat,
                tint = playerInk(alpha = if (repeatMode != RepeatModes.OFF) 1f else 0.5f),
                haptic = when (repeatMode) {
                    RepeatModes.OFF -> Haptic.ToggleOn
                    RepeatModes.ONE -> Haptic.ToggleOff
                    else -> Haptic.Select
                },
                enabled = !controlsLocked,
            )
            if (repeatMode == RepeatModes.ONE) {
                // The "1" of repeat one, on the glyph's corner.
                Text(
                    text = "1",
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    color = onInk,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 6.dp, end = 4.dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(ink),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** The song and play or pause, above a sheet that is fully open. A tap goes back to the player. */
@Composable
private fun YtMiniHeader(
    song: Song,
    art: PlayerArtwork,
    isPlaying: Boolean,
    isLoading: Boolean,
    onPlayPause: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = rememberHaptics()
    Row(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {
                haptics.play(Haptic.Tap)
                onOpenPlayer()
            }
            .padding(start = PLAYER_GUTTER, end = PLAYER_GUTTER - 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = art.previewRequest,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.White.copy(alpha = 0.08f)),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            ExplicitSongTitle(song = song, style = MaterialTheme.typography.titleMedium, color = PlayerInk)
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                color = playerInk(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            if (isLoading) {
                CircularProgressIndicator(color = PlayerInk, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
            } else {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .clickable {
                            haptics.play(if (isPlaying) Haptic.Pause else Haptic.Resume)
                            onPlayPause()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    PlayPauseGlyph(
                        isPlaying = isPlaying,
                        tint = PlayerInk,
                        contentDescription = stringResource(if (isPlaying) Res.string.pause else Res.string.play),
                        modifier = Modifier.size(30.dp),
                    )
                }
            }
        }
    }
}

/**
 * The top of the sheet: the handle, and under it the sheet's title. Closed,
 * the title is small and in the middle, as a label on the handle. Open, it is
 * the heading of the queue or of the lyrics, with that page's one button.
 */
@Composable
private fun YtSheetHeader(
    title: String,
    lyrics: Boolean,
    autoplayEnabled: Boolean,
    /** 0 with the sheet closed, 1 from the half rest up. */
    open: () -> Float,
    onToggleAutoplay: () -> Unit,
    onCloseLyrics: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val heading = if (lyrics) stringResource(Res.string.open_lyrics) else title
    Column(modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(YT_HANDLE_STRIP), contentAlignment = Alignment.Center) {
            Box(
                Modifier
                    .size(width = 36.dp, height = 4.dp)
                    .clip(CircleShape)
                    .background(playerInk(alpha = 0.45f)),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(YT_SHEET_TITLE_ROW)
                .padding(start = PLAYER_GUTTER, end = PLAYER_GUTTER - 10.dp),
        ) {
            // The label of a closed sheet.
            Text(
                text = heading,
                style = MaterialTheme.typography.labelLarge,
                color = playerInk(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(end = 10.dp, top = 4.dp)
                    .fillMaxWidth()
                    .graphicsLayer { alpha = (1f - open() * 2.5f).coerceIn(0f, 1f) },
            )
            // The heading of an open one.
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = ((open() - 0.4f) / 0.6f).coerceIn(0f, 1f) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = heading,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                    color = PlayerInk,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                val shown by remember { derivedStateOf { open() > 0.4f } }
                if (shown) {
                    if (lyrics) {
                        YtIconButton(
                            icon = Icons.Rounded.Close,
                            contentDescription = stringResource(Res.string.close_lyrics),
                            onClick = onCloseLyrics,
                        )
                    } else {
                        YtIconButton(
                            icon = BitChordIcons.Infinity,
                            contentDescription = stringResource(
                                if (autoplayEnabled) Res.string.autoplay_on else Res.string.autoplay_off,
                            ),
                            onClick = onToggleAutoplay,
                            tint = playerInk(alpha = if (autoplayEnabled) 1f else 0.45f),
                            haptic = if (autoplayEnabled) Haptic.ToggleOff else Haptic.ToggleOn,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The chips where AutoPlay's songs begin: each selects how AutoPlay finds
 * more songs. The row scrolls sideways, edge to edge of the sheet.
 */
@Composable
private fun YtAutoplayStyleChips(styles: PlayerAutoplayStyles) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .bleedHorizontally(PLAYER_GUTTER)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = PLAYER_GUTTER, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (chip in styles.chips) {
            YtChip(
                label = chip.label,
                selected = chip.id == styles.selectedId,
                onClick = { styles.onSelect(chip.id) },
                shape = YtStyleChipShape,
                haptic = Haptic.Select,
            )
        }
    }
}
