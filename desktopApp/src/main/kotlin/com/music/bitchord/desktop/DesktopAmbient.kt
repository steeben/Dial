package com.music.bitchord.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// The phone's ambient background (AmbientBlobs): three soft colour blobs drifting behind the
// pages, in the playing song's colour, held between a floor and a ceiling of brightness so text on
// top stays legible and a dark cover still shows some colour.

/** The app icon's red: the ambient colour while nothing plays. */
internal val AmbientAppColor = Color(0xFFF9263E)

private const val AMBIENT_MAX_VALUE = 0.85f
private const val AMBIENT_MIN_VALUE = 0.6f
private const val AMBIENT_ALPHA = 0.30f
private const val AMBIENT_RADIUS_FRACTION = 0.48f
private const val AMBIENT_CYCLE_MS = 36_000
private const val AMBIENT_COLOR_FADE_MS = 900
private const val AMBIENT_STEP_MS = 80L

private fun hsv(color: Color): FloatArray = java.awt.Color.RGBtoHSB(
    (color.red * 255).toInt(),
    (color.green * 255).toInt(),
    (color.blue * 255).toInt(),
    null,
)

private fun fromHsv(h: Float, s: Float, v: Float): Color = Color(java.awt.Color.HSBtoRGB(h, s, v))

private fun ambientTone(target: Color): Color {
    val (h, s, v) = hsv(target).let { Triple(it[0], it[1], it[2]) }
    return fromHsv(h, s, v.coerceIn(AMBIENT_MIN_VALUE, AMBIENT_MAX_VALUE))
}

/** A second colour for a single one: the same hue turned a little and darkened, as the phone's. */
internal fun ambientCompanion(color: Color): Color {
    val c = hsv(color)
    return fromHsv((c[0] + 28f / 360f) % 1f, c[1], c[2] * 0.8f)
}

internal class DesktopAmbientBlobs(
    val primary: () -> Color,
    val secondary: () -> Color,
    val phase: State<Float>,
)

@Composable
internal fun rememberDesktopAmbientBlobs(primary: Color, secondary: Color, animate: Boolean): DesktopAmbientBlobs {
    val first by animateColorAsState(ambientTone(primary), tween(AMBIENT_COLOR_FADE_MS), label = "ambientFirst")
    val second by animateColorAsState(ambientTone(secondary), tween(AMBIENT_COLOR_FADE_MS), label = "ambientSecond")
    val phase = remember { mutableFloatStateOf(0.15f) }
    if (animate) {
        LaunchedEffect(Unit) {
            val start = withFrameNanos { it } - (phase.floatValue * AMBIENT_CYCLE_MS * 1_000_000L).toLong()
            while (true) {
                delay(AMBIENT_STEP_MS)
                val now = withFrameNanos { it }
                phase.floatValue = ((now - start) / 1_000_000L % AMBIENT_CYCLE_MS) / AMBIENT_CYCLE_MS.toFloat()
            }
        }
    }
    return DesktopAmbientBlobs({ first }, { second }, phase)
}

/** The three blobs, as the phone draws them: plain radial gradients, no blur pass. */
internal fun Modifier.ambientBlobs(blobs: DesktopAmbientBlobs?): Modifier =
    if (blobs == null) {
        this
    } else {
        drawBehind {
            val t = blobs.phase.value * 2f * PI.toFloat()
            val w = size.width
            val h = size.height
            val radius = max(w, h) * AMBIENT_RADIUS_FRACTION
            val main = blobs.primary()
            listOf(
                Triple(Offset(w * (0.5f + 0.38f * sin(t)), h * (0.35f + 0.28f * sin(2f * t + 0.6f))), main, AMBIENT_ALPHA),
                Triple(
                    Offset(w * (0.5f + 0.38f * cos(2f * t + 1.3f)), h * (0.6f + 0.3f * sin(t + 2.2f))),
                    main.copy(red = main.red * 0.7f, green = main.green * 0.7f, blue = main.blue * 0.7f),
                    AMBIENT_ALPHA * 0.9f,
                ),
                Triple(
                    Offset(w * (0.5f + 0.4f * sin(3f * t + 2.4f)), h * (0.5f + 0.34f * cos(t + 0.9f))),
                    blobs.secondary(),
                    AMBIENT_ALPHA * 0.95f,
                ),
            ).forEach { (centre, colour, strength) ->
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(colour.copy(alpha = strength), colour.copy(alpha = strength * 0.45f), Color.Transparent),
                        center = centre,
                        radius = radius,
                    ),
                    radius = radius,
                    center = centre,
                )
            }
        }
    }
