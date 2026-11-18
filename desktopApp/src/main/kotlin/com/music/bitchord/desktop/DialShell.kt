package com.music.bitchord.desktop

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.CompositionLocalProvider
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.ui.components.DialTileStyle
import com.music.bitchord.ui.components.LocalDialTileGlassEdge
import com.music.bitchord.ui.components.LocalDialTileOutset
import com.music.bitchord.ui.components.LocalDialTileStyle
import com.music.bitchord.ui.screens.DialColumnGrid
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBackIos
import androidx.compose.material.icons.automirrored.rounded.ArrowForwardIos
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Podcasts
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import bitchord.desktopapp.generated.resources.Res
import bitchord.desktopapp.generated.resources.metropolis_extrabold
import bitchord.desktopapp.generated.resources.tiny5_regular
import com.music.bitchord.data.model.ShelfItem
import org.jetbrains.compose.resources.Font as composeFont

// Dial's desktop shell: the title bar, and the dials' column on the left of the
// three-column window (dials, the open page, the player).

internal val DialTitleBarHeight = 44.dp
internal val DialLeftColumnWidth = 410.dp
internal val DialPlayerColumnWidth = 470.dp
internal val DialPanelShape = RoundedCornerShape(14.dp)
internal val DialBodyPadding = 10.dp

private val TextColor = Color.White
private val DimText = Color.White.copy(alpha = 0.62f)
private val GlassFill = Color.White.copy(alpha = 0.07f)
private val GlassRim = Color.White.copy(alpha = 0.16f)

@Composable
internal fun tiny5Family() = FontFamily(composeFont(Res.font.tiny5_regular, FontWeight.W400))

@Composable
internal fun metropolisFamily() = FontFamily(composeFont(Res.font.metropolis_extrabold, FontWeight.W800))

/** Dial's mark: the bare 3x4 grid, lit squares in [color] and the rest at a quarter of it. */
@Composable
internal fun DialLogo(color: Color = TextColor, modifier: Modifier = Modifier.size(16.dp, 22.dp)) {
    Canvas(modifier) {
        // The phone's mark, on its 160x220 grid: 40 squares, 20 gaps.
        val unit = size.width / 160f
        val cell = 40f * unit
        val radius = CornerRadius(8f * unit)
        val lit = setOf(0 to 1, 0 to 2, 1 to 1, 2 to 0, 2 to 1, 3 to 0, 3 to 1)
        for (row in 0 until 4) for (col in 0 until 3) {
            drawRoundRect(
                color = if ((row to col) in lit) color else color.copy(alpha = color.alpha * 0.25f),
                topLeft = Offset(col * 60f * unit, row * 60f * unit),
                size = Size(cell, cell),
                cornerRadius = radius,
            )
        }
    }
}

// ── Title bar ──────────────────────────────────────────────────────────────

@Composable
internal fun DialTitleBar(
    canGoBack: Boolean,
    onBack: () -> Unit,
    canGoForward: Boolean,
    onForward: () -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    focusSearch: Boolean,
    onSearchFocused: () -> Unit,
    onOpenSettings: () -> Unit,
    accountAvatar: String?,
    onOpenAccounts: () -> Unit,
) {
    DesktopTitleBarDragArea(Modifier.fillMaxWidth().height(DialTitleBarHeight)) {
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier.align(Alignment.CenterStart).padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DialLogo()
                Spacer(Modifier.width(10.dp))
                Text(
                    "Dial",
                    color = TextColor,
                    fontFamily = tiny5Family(),
                    fontSize = 24.sp,
                    modifier = Modifier.offset(y = (-1).dp),
                )
                Spacer(Modifier.width(14.dp))
                RoundIconButton(Icons.AutoMirrored.Rounded.ArrowBackIos, "Back", enabled = canGoBack, size = 30, iconSize = 14, onClick = onBack)
                RoundIconButton(Icons.AutoMirrored.Rounded.ArrowForwardIos, "Forward", enabled = canGoForward, size = 30, iconSize = 14, onClick = onForward)
            }
            DialSearchField(
                query = query,
                onQueryChange = onQueryChange,
                onSearch = onSearch,
                focusSearch = focusSearch,
                onSearchFocused = onSearchFocused,
                modifier = Modifier.align(Alignment.Center).width(520.dp).height(32.dp),
            )
            Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.Rounded.Settings, "Settings", size = 34, iconSize = 18, onClick = onOpenSettings)
                Spacer(Modifier.width(6.dp))
                Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                    DesktopAccountButton(avatar = accountAvatar, onClick = onOpenAccounts)
                }
                Spacer(Modifier.width(10.dp))
                Box(Modifier.width(1.dp).height(18.dp).background(Color.White.copy(alpha = 0.14f)))
                WindowsCaptionButtons()
            }
        }
    }
}

@Composable
private fun DialSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    focusSearch: Boolean,
    onSearchFocused: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(focusSearch) {
        if (focusSearch) {
            focus.requestFocus()
            onSearchFocused()
        }
    }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .clip(shape)
            .background(GlassFill)
            .border(1.dp, GlassRim, shape)
            .clickable { focus.requestFocus() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = DimText, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    "Search songs, albums, artists, podcasts",
                    color = DimText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = TextColor, fontSize = 13.sp),
                cursorBrush = SolidColor(TextColor),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focus)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyUp && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                            onSearch()
                            true
                        } else {
                            false
                        }
                    },
            )
        }
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .clip(RoundedCornerShape(5.dp))
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(5.dp))
                .padding(horizontal = 6.dp, vertical = 1.dp),
        ) {
            Text("Ctrl K", color = DimText, fontSize = 11.sp)
        }
    }
}

@Composable
private fun RoundIconButton(
    icon: ImageVector,
    description: String,
    enabled: Boolean = true,
    size: Int,
    iconSize: Int,
    onClick: () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(if (hovered && enabled) Color.White.copy(alpha = 0.10f) else Color.Transparent)
            .hoverable(hover)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            description,
            tint = if (enabled) TextColor else TextColor.copy(alpha = 0.3f),
            modifier = Modifier.size(iconSize.dp),
        )
    }
}

/** Windows' minimize, maximize and close, 48 wide each, close turning red under the pointer. */
@Composable
private fun WindowsCaptionButtons() {
    if (!DesktopPlatform.drawsOwnWindowFrame) return
    val actions = LocalDesktopWindowActions.current ?: return
    val maximized by DesktopWindowMode.maximized.collectAsState()
    Row(Modifier.fillMaxHeight()) {
        CaptionButton("Minimize", onClick = actions.minimize) { color ->
            val y = size.height / 2
            drawLine(color, Offset(size.width / 2 - 5.dp.toPx(), y), Offset(size.width / 2 + 5.dp.toPx(), y), 1.dp.toPx())
        }
        CaptionButton(if (maximized) "Restore" else "Maximize", onClick = actions.toggleMaximize) { color ->
            val s = 9.dp.toPx()
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx())
            val left = size.width / 2 - s / 2
            val top = size.height / 2 - s / 2
            if (maximized) {
                drawRect(color, Offset(left + 2.dp.toPx(), top - 2.dp.toPx()), Size(s, s), style = stroke)
                drawRect(Color.Black.copy(alpha = 0f), Offset(left, top), Size(s, s))
                drawRect(color, Offset(left, top), Size(s, s), style = stroke)
            } else {
                drawRect(color, Offset(left, top), Size(s, s), style = stroke)
            }
        }
        CaptionButton("Close", danger = true, onClick = actions.close) { color ->
            val r = 5.dp.toPx()
            val c = Offset(size.width / 2, size.height / 2)
            drawLine(color, c + Offset(-r, -r), c + Offset(r, r), 1.dp.toPx())
            drawLine(color, c + Offset(-r, r), c + Offset(r, -r), 1.dp.toPx())
        }
    }
}

@Composable
private fun CaptionButton(
    description: String,
    danger: Boolean = false,
    onClick: () -> Unit,
    glyph: androidx.compose.ui.graphics.drawscope.DrawScope.(Color) -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val background = when {
        hovered && danger -> Color(0xFFC42B1C)
        hovered -> Color.White.copy(alpha = 0.09f)
        else -> Color.Transparent
    }
    Canvas(
        Modifier
            .width(48.dp)
            .fillMaxHeight()
            .background(background)
            .hoverable(hover)
            .clickable(onClick = onClick),
    ) {
        glyph(if (hovered && danger) Color.White else TextColor.copy(alpha = 0.9f))
    }
}

// ── The dials' column ──────────────────────────────────────────────────────

/** One page of a dial: the 3x4 tiles it shows. */
internal const val DIAL_PAGE_TILES = 12

internal enum class DialNavItem(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    EXPLORE("Explore", Icons.Rounded.Explore),
    LIBRARY("Library", Icons.Rounded.LibraryMusic),
    PODCASTS("Podcasts", Icons.Rounded.Podcasts),
}

/**
 * The left column: the speed dial, the Mixes Dial and the artists' dial in one
 * scrolling list, each drawn by the phone's own dial (its tiles, glass rim,
 * labels, entrance and drag to reorder), a short hairline between each, and
 * the glass nav bar floating over the bottom.
 */
@Composable
internal fun DialColumn(
    dials: List<List<ShelfItem>>,
    pinnedIds: Set<String>,
    rows: Int,
    showLabels: Boolean,
    labelOverrides: Set<String>,
    showPinIcon: Boolean,
    currentSong: com.music.bitchord.data.model.Song?,
    isPlaying: Boolean,
    onTileClick: (ShelfItem) -> Unit,
    onReorderSpeedDial: (List<ShelfItem>) -> Unit,
    onReorderArtistDial: (List<ShelfItem>) -> Unit,
    selectedNav: DialNavItem?,
    onNav: (DialNavItem) -> Unit,
    modifier: Modifier = Modifier,
    ambient: DesktopAmbientBlobs? = null,
) {
    val listState = rememberLazyListState()
    val haze = remember { HazeState() }
    val titles = listOf("Speed dial", "Mixes Dial", "Artist dial")
    Box(
        modifier
            .clip(DialPanelShape)
            .background(Color.Black)
            .ambientBlobs(ambient),
    ) {
        CompositionLocalProvider(
            LocalDialTileStyle provides DialTileStyle.GLASS,
            LocalDialTileGlassEdge provides ::desktopDialTileGlassEdge,
            LocalDialTileOutset provides 2.dp,
        ) {
            LazyColumn(
                state = listState,
                // The phone's dial keeps a 16 dp gutter of its own.
                contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 18.dp, bottom = 18.dp + 62.dp + 18.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .hazeSource(haze)
                    // The top edge fades out over 28 px as the list scrolls under it.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            Brush.verticalGradient(
                                0f to Color.Transparent,
                                28.dp.toPx() / size.height to Color.Black,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                dials.forEachIndexed { index, tiles ->
                    if (tiles.isEmpty()) return@forEachIndexed
                    if (dials.take(index).any { it.isNotEmpty() }) {
                        item(key = "rule$index") {
                            Box(Modifier.fillMaxWidth().padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                                Box(Modifier.fillMaxWidth(0.4f).height(1.dp).background(Color.White.copy(alpha = 0.16f)))
                            }
                        }
                    }
                    item(key = "dial$index") {
                        DialColumnGrid(
                            shelf = HomeShelf(titles[index], tiles.take(DIAL_PAGE_TILES)),
                            pinnedIds = pinnedIds,
                            rows = rows,
                            onItemClick = onTileClick,
                            onItemLongPress = null,
                            onReorder = when (index) {
                                0 -> onReorderSpeedDial
                                2 -> onReorderArtistDial
                                else -> null
                            },
                            currentSong = currentSong,
                            isPlaying = isPlaying,
                            showLabels = showLabels,
                            labelOverrides = labelOverrides,
                            showPinIcon = showPinIcon,
                        )
                    }
                }
            }
        }
        DialNavBar(
            selected = selectedNav,
            onSelect = onNav,
            haze = haze,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 20.dp, end = 20.dp, bottom = 18.dp)
                .fillMaxWidth()
                .height(62.dp),
        )
    }
}

@Composable
private fun DialNavBar(
    selected: DialNavItem?,
    onSelect: (DialNavItem) -> Unit,
    haze: HazeState,
    modifier: Modifier = Modifier,
) {
    // The phone's glass bar: a pill of clear glass, the tabs dividing it by weight, a 6 dp inset,
    // and the selected tab raised on a faint white pill that slides between tabs.
    val shape = RoundedCornerShape(percent = 50)
    val selectedIndex = DialNavItem.entries.indexOf(selected)
    BoxWithConstraints(
        modifier.shadow(18.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f)),
    ) {
        Box(Modifier.matchParentSize().clearLiquidGlass(shape, haze))
        Box(Modifier.matchParentSize().glassHighlight(shape))
        val inset = 6.dp
        val tabWidth = (maxWidth - inset * 2) / DialNavItem.entries.size
        val pillX by animateDpAsState(
            targetValue = inset + tabWidth * selectedIndex.coerceAtLeast(0),
            animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow),
            label = "navPill",
        )
        if (selectedIndex >= 0) {
            Box(
                Modifier
                    .offset(x = pillX, y = inset)
                    .width(tabWidth)
                    .height(maxHeight - inset * 2)
                    .clip(shape)
                    .background(Color.White.copy(alpha = 0.14f)),
            )
        }
        Row(Modifier.fillMaxSize().padding(inset), verticalAlignment = Alignment.CenterVertically) {
            DialNavItem.entries.forEach { item ->
                val isSelected = item == selected
                val tint = if (isSelected) TextColor else TextColor.copy(alpha = 0.65f)
                val press = remember { MutableInteractionSource() }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(shape)
                        .clickable(interactionSource = press, indication = null) { onSelect(item) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(item.icon, item.label, tint = tint, modifier = Modifier.size(25.dp).pressBounce(press))
                    Text(
                        item.label,
                        color = tint,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.W600,
                        maxLines = 1,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
