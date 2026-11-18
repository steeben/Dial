package com.music.bitchord.desktop

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
 * scrolling list, a short hairline between each, and the glass nav bar
 * floating over the bottom.
 */
@Composable
internal fun DialColumn(
    dials: List<List<ShelfItem>>,
    labelShown: (ShelfItem) -> Boolean,
    onTileClick: (ShelfItem) -> Unit,
    selectedNav: DialNavItem?,
    onNav: (DialNavItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    Box(
        modifier
            .clip(DialPanelShape)
            .background(
                Brush.linearGradient(
                    listOf(Color(0xFF2A2C1C), Color(0xFF15211F), Color(0xFF0F1A1C)),
                ),
            ),
    ) {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 18.dp + 62.dp + 18.dp),
            modifier = Modifier
                .fillMaxSize()
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
            dials.filter { it.isNotEmpty() }.forEachIndexed { index, tiles ->
                if (index > 0) {
                    item(key = "rule$index") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 13.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.fillMaxWidth(0.4f).height(1.dp).background(Color.White.copy(alpha = 0.16f)))
                        }
                    }
                }
                item(key = "dial$index") {
                    DialGrid(tiles.take(DIAL_PAGE_TILES), labelShown, onTileClick)
                }
            }
        }
        DialNavBar(
            selected = selectedNav,
            onSelect = onNav,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 20.dp, end = 20.dp, bottom = 18.dp)
                .fillMaxWidth()
                .height(62.dp),
        )
    }
}

@Composable
private fun DialGrid(
    tiles: List<ShelfItem>,
    labelShown: (ShelfItem) -> Boolean,
    onTileClick: (ShelfItem) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        tiles.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                row.forEach { item ->
                    DialTile(item, labelShown(item), onClick = { onTileClick(item) }, modifier = Modifier.weight(1f))
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DialTile(item: ShelfItem, showLabel: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(14.dp)
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Box(
        modifier
            .aspectRatio(1f)
            .shadow(6.dp, shape, clip = false, ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.4f))
            .clip(shape)
            .background(Color(0xFF1E2224))
            .hoverable(hover)
            .clickable(onClick = onClick),
    ) {
        DesktopArtwork(item.thumbnailUrl, Modifier.fillMaxSize(), contentScale = ContentScale.Crop, px = 256)
        if (showLabel) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))),
            )
            Text(
                item.title,
                color = TextColor,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
        if (hovered) Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.06f)))
        Box(Modifier.fillMaxSize().border(1.dp, Color.White.copy(alpha = 0.14f), shape))
    }
}

@Composable
private fun DialNavBar(
    selected: DialNavItem?,
    onSelect: (DialNavItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(31.dp)
    Row(
        modifier
            .shadow(16.dp, shape, clip = false)
            .clip(shape)
            .background(Color(0xFF1B2422).copy(alpha = 0.82f))
            .border(1.dp, GlassRim, shape)
            .padding(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DialNavItem.entries.forEach { item ->
            val isSelected = item == selected
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(26.dp))
                    .background(if (isSelected) Color.White.copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { onSelect(item) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(item.icon, null, tint = if (isSelected) TextColor else DimText, modifier = Modifier.size(20.dp))
                Spacer(Modifier.height(2.dp))
                Text(item.label, color = if (isSelected) TextColor else DimText, fontSize = 11.5.sp, maxLines = 1, fontWeight = FontWeight.Medium)
            }
        }
    }
}
