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

/**
 * The three blobs as the phone draws them (radial gradients: full strength, 45% at half the
 * radius, gone at the edge), over the panel's black, in one shader that also dithers: a fixed
 * per-pixel noise of about 1/255 that breaks up the 8-bit steps a soft dark gradient otherwise
 * shows as bands. The noise is tied to the pixel, so it never flickers.
 */
private const val AMBIENT_SKSL = """
uniform float2 size;
uniform float radius;
uniform float2 p0; uniform float3 c0; uniform float s0;
uniform float2 p1; uniform float3 c1; uniform float s1;
uniform float2 p2; uniform float3 c2; uniform float s2;

float3 blob(float3 under, float2 xy, float2 p, float3 c, float s) {
    float t = clamp(length(xy - p) / radius, 0.0, 1.0);
    float a;
    float3 col;
    if (t < 0.5) {
        a = mix(s, s * 0.45, t / 0.5);
        col = c;
    } else {
        float u = (t - 0.5) / 0.5;
        a = mix(s * 0.45, 0.0, u);
        col = mix(c, float3(0.0), u);
    }
    return mix(under, col, a);
}

float hash(float2 xy) {
    return fract(sin(dot(xy, float2(12.9898, 78.233))) * 43758.5453);
}

half4 main(float2 xy) {
    float3 col = float3(0.0);
    col = blob(col, xy, p0, c0, s0);
    col = blob(col, xy, p1, c1, s1);
    col = blob(col, xy, p2, c2, s2);
    // Triangular noise of about one step either way: invisible, but no bands.
    float n = hash(xy) + hash(xy + float2(17.13, 3.71)) - 1.0;
    col += n / 255.0;
    return half4(half3(clamp(col, 0.0, 1.0)), 1.0);
}"""

private val ambientEffect by lazy { org.jetbrains.skia.RuntimeEffect.makeForShader(AMBIENT_SKSL) }

internal fun Modifier.ambientBlobs(blobs: DesktopAmbientBlobs?): Modifier =
    if (blobs == null) {
        this
    } else {
        drawBehind {
            val t = blobs.phase.value * 2f * PI.toFloat()
            val w = size.width
            val h = size.height
            val main = blobs.primary()
            val dim = main.copy(red = main.red * 0.7f, green = main.green * 0.7f, blue = main.blue * 0.7f)
            val second = blobs.secondary()
            val builder = org.jetbrains.skia.RuntimeShaderBuilder(ambientEffect).apply {
                uniform("size", w, h)
                uniform("radius", max(w, h) * AMBIENT_RADIUS_FRACTION)
                uniform("p0", w * (0.5f + 0.38f * sin(t)), h * (0.35f + 0.28f * sin(2f * t + 0.6f)))
                uniform("c0", main.red, main.green, main.blue)
                uniform("s0", AMBIENT_ALPHA)
                uniform("p1", w * (0.5f + 0.38f * cos(2f * t + 1.3f)), h * (0.6f + 0.3f * sin(t + 2.2f)))
                uniform("c1", dim.red, dim.green, dim.blue)
                uniform("s1", AMBIENT_ALPHA * 0.9f)
                uniform("p2", w * (0.5f + 0.4f * sin(3f * t + 2.4f)), h * (0.5f + 0.34f * cos(t + 0.9f)))
                uniform("c2", second.red, second.green, second.blue)
                uniform("s2", AMBIENT_ALPHA * 0.95f)
            }
            drawRect(androidx.compose.ui.graphics.ShaderBrush(builder.makeShader()))
        }
    }
