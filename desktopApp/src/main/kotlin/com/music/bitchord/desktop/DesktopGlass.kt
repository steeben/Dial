package com.music.bitchord.desktop

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import kotlin.math.PI
import kotlin.math.pow

// The phone's liquid glass, on the desktop. Android runs its glass as AGSL runtime shaders; Skia
// runs the same shading language (SkSL), so the phone's shaders are used here as they are: the
// clear glass's lens, fold and rim (ClearLiquidGlass), the dial tiles' glass edge, and the rim
// highlight (from Kyant0/backdrop, Apache License 2.0, vendored in the Android app).
// The backdrop the glass bends is Haze's blur of what is behind it.

private const val ROUNDED_RECT_SDF = """
float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float sdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    float outside = length(max(cornerCoord, 0.0)) - radius;
    float inside = min(max(cornerCoord.x, cornerCoord.y), 0.0);
    return outside + inside;
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}"""

/** The dial tile's glass edge: the art softened near the rim, untouched in the middle. */
private const val DIAL_TILE_GLASS_SKSL = """
uniform shader content;

uniform float2 size;
uniform float radius;
uniform float blurBand;
uniform float blur;

$ROUNDED_RECT_SDF

half4 sampleInside(float2 coord) {
    return content.eval(clamp(coord, float2(0.5), size - float2(0.5)));
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centered = coord - halfSize;
    float inward = -sdRoundedRect(centered, halfSize, radius);
    if (inward >= blurBand) {
        return content.eval(coord);
    }
    float edge = max(inward, 0.0);
    float t = 1.0 - clamp(edge / blurBand, 0.0, 1.0);
    float r = blur * t * t;
    if (r < 0.5) {
        return sampleInside(coord);
    }
    half4 sum = sampleInside(coord) * 0.2;
    for (int i = 0; i < 8; i++) {
        float a = float(i) * 0.785398;
        float2 dir = float2(cos(a), sin(a));
        sum += sampleInside(coord + dir * r * 0.5) * 0.06;
        float2 between = float2(cos(a + 0.392699), sin(a + 0.392699));
        sum += sampleInside(coord + between * r) * 0.04;
    }
    return sum;
}"""

/** The rim's light: brightest where the edge faces the light, at 45 degrees. */
private const val HIGHLIGHT_SKSL = """
uniform float2 size;
uniform float4 cornerRadii;
uniform half4 color;
uniform float angle;
uniform float falloff;

$ROUNDED_RECT_SDF

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(coord, cornerRadii);
    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);
    float2 normal = float2(cos(angle), sin(angle));
    float d = dot(grad, normal);
    float intensity = pow(abs(d), falloff);
    return color * intensity;
}"""

/** The phone's clear glass (ClearLiquidGlass): the lens at the rim, the fold, the rim's glint. */
private const val CLEAR_GLASS_SKSL = """
uniform shader content;

uniform float2 size;
uniform float2 offset;
uniform float4 cornerRadii;
uniform float unit;
uniform float dark;
uniform float caustic;
uniform float tilt;
uniform float2 arcScale;

float radiusAt(float2 p, float4 radii) {
    if (p.x >= 0.0) {
        if (p.y <= 0.0) return radii.y;
        return radii.z;
    }
    if (p.y <= 0.0) return radii.x;
    return radii.w;
}

float sdRoundedRect(float2 p, float2 hs, float r) {
    float2 c = abs(p) - (hs - float2(r));
    return length(max(c, 0.0)) - r + min(max(c.x, c.y), 0.0);
}

float2 normalAt(float2 p, float2 hs, float r) {
    float2 c = abs(p) - (hs - float2(r));
    float2 sg = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
    if (c.x >= 0.0 && c.y >= 0.0) return sg * normalize(c + float2(1e-4));
    if (c.x >= c.y) return float2(sg.x, 0.0);
    return float2(0.0, sg.y);
}

float refrProfile(float q) {
    float u = clamp((q - 0.61) / 0.39, 0.0, 1.0);
    float v = clamp((q - 0.7) / 0.3, 0.0, 1.0);
    float v2 = v * v;
    float d = 0.49 * u * u * (3.0 - 2.0 * u) + 10.0 * v2 * v2 * v;
    if (q <= 1e-6 || d <= 0.0) return 0.0;
    float cap = q * 1.5;
    float x = d / cap;
    float x2 = x * x;
    return cap * x / sqrt(sqrt(1.0 + x2 * x2));
}

float3 saturateRgb(float3 c, float k) {
    float l = dot(c, float3(0.2126, 0.7152, 0.0722));
    return float3(l) + (c - float3(l)) * k;
}

float radialStop(float t) {
    if (t < 0.35) return mix(0.58, 0.42, t / 0.35);
    if (t < 0.62) return mix(0.42, 0.18, (t - 0.35) / 0.27);
    if (t < 0.85) return mix(0.18, 0.0, (t - 0.62) / 0.23);
    return 0.0;
}

half4 main(float2 coord) {
    float2 hs = size * 0.5;
    float2 p = coord + offset - hs;
    float r = min(radiusAt(p, cornerRadii), min(hs.x, hs.y));
    float rs = max(r, 1.0);
    float depth = max(-sdRoundedRect(p, hs, r), 0.0);

    float2 samplePos = coord;
    float q = 1.0 - depth / rs;
    if (q > 0.0) {
        float pr = refrProfile(q) * rs;
        samplePos -= normalAt(p, hs, r) * pr;
        if (size.x >= size.y) {
            float run = max(hs.x - r, 1.0);
            samplePos.x -= tilt * pr * clamp(p.x / run, -1.0, 1.0);
        } else {
            float run = max(hs.y - r, 1.0);
            samplePos.y -= tilt * pr * clamp(p.y / run, -1.0, 1.0);
        }
    }
    half4 c4 = content.eval(samplePos);
    float3 c = float3(c4.rgb);
    float a = float(c4.a);

    float light = (1.0 - dark) * caustic;
    if (light > 0.0) {
        float2 sp = samplePos + offset - hs;
        float2 pc = (sp - float2(0.0, 12.0 * unit)) / 0.86;
        float rc = min(radiusAt(pc, cornerRadii), min(hs.x, hs.y));
        float sdc = sdRoundedRect(pc, hs, rc) * 0.86;
        float x = (sdc + 4.5 * unit) / (11.0 * unit);
        float ring = 0.36 * exp(-0.5 * x * x);
        float2 uv = (sp + hs) / size;
        float t1 = length((uv - float2(0.78, 0.0)) / float2(0.7, 1.2));
        float t2 = length((uv - float2(0.22, 1.0)) / float2(0.7, 1.2));
        float mask = min(1.0, 0.4 + radialStop(t1) + radialStop(t2));
        c = mix(c, float3(a), clamp(0.7 * light * mask * ring, 0.0, 1.0));
    }

    float fold = clamp(1.0 - depth / (0.39 * rs), 0.0, 1.0);
    c = mix(c, saturateRgb(c * 1.08, 1.3), fold);

    float rimW = 0.7 * 1.3 * unit;
    float rimFade = 2.0 * 1.3 * unit;
    float edge = clamp((rimFade - depth) / max(rimFade - rimW, 1e-3), 0.0, 1.0);
    if (edge > 0.0) {
        float n = atan(p.x * arcScale.x, -p.y * arcScale.y);
        float glint = 0.5 + 0.5 * cos(2.0 * (n - 0.7853982));
        float glintExp = 1.35 + 0.1 * dark;
        float glintSat = 3.1 - 1.1 * dark;
        float rimExp = 0.84 + 0.41 * dark;
        float rimSat = 2.2 - 0.9 * dark;
        c = mix(c, saturateRgb(c * glintExp, glintSat), edge * glint);
        c = mix(c, saturateRgb(c * rimExp, rimSat), edge * (1.0 - glint));
        float spec = 0.13 + 0.25 * dark;
        c = mix(c, float3(a), spec * edge * glint);
    }

    c = clamp(c, 0.0, a);
    return half4(half3(c), half(a));
}"""

private val dialTileEffect by lazy { RuntimeEffect.makeForShader(DIAL_TILE_GLASS_SKSL) }
private val highlightEffect by lazy { RuntimeEffect.makeForShader(HIGHLIGHT_SKSL) }
private val clearGlassEffect by lazy { RuntimeEffect.makeForShader(CLEAR_GLASS_SKSL) }

/** The phone's hairline along a glass edge (GLASS_EDGE_WIDTH / GLASS_EDGE_COLOR). */
internal val GlassEdgeWidth = 0.5.dp
internal val GlassEdgeColor = Color.White.copy(alpha = 0.10f)

private val DialTileGlassBlur = 3.dp
private val DialTileBlurBand = 6.dp

/** The phone's clear glass: its own veil in the dark theme, and its blur and saturation defaults. */
private val ClearGlassVeilDark = Color.White.copy(alpha = 0.03f)
private val ClearGlassVeilLight = Color.White.copy(alpha = 0.14f)
private const val CLEAR_GLASS_BLUR_DP = 10f
private const val REFERENCE_HEIGHT_DP = 60f
private const val REFR_TILT = 0.2f
private const val ARC_SHAPE = 0.4f

private fun CornerBasedShape.radiiPx(size: Size, density: androidx.compose.ui.unit.Density): FloatArray {
    val max = size.minDimension / 2f
    return floatArrayOf(
        topStart.toPx(size, density).coerceAtMost(max),
        topEnd.toPx(size, density).coerceAtMost(max),
        bottomEnd.toPx(size, density).coerceAtMost(max),
        bottomStart.toPx(size, density).coerceAtMost(max),
    )
}

/** The rim light the phone's glass draws over its edge ([Highlight] at its default). */
internal fun Modifier.glassHighlight(shape: Shape, width: androidx.compose.ui.unit.Dp = 0.5.dp): Modifier =
    drawWithCache {
        val corner = shape as? CornerBasedShape
        val radii = corner?.radiiPx(size, this) ?: floatArrayOf(0f, 0f, 0f, 0f)
        val builder = RuntimeShaderBuilder(highlightEffect).apply {
            uniform("size", size.width, size.height)
            uniform("cornerRadii", radii[0], radii[1], radii[2], radii[3])
            uniform("color", 1f, 1f, 1f, 1f)
            uniform("angle", (45f * PI / 180f).toFloat())
            uniform("falloff", 1f)
        }
        val brush = ShaderBrush(builder.makeShader())
        val stroke = width.toPx()
        val outline = shape.createOutline(
            Size(size.width - stroke, size.height - stroke),
            LayoutDirection.Ltr,
            this,
        )
        onDrawWithContent {
            drawContent()
            drawContext.canvas.save()
            drawContext.transform.translate(stroke / 2f, stroke / 2f)
            drawOutline(outline, brush, alpha = 0.5f, style = Stroke(stroke))
            drawContext.canvas.restore()
        }
    }

/**
 * A dial tile's glass edge, as the phone's [dialTileGlassEdge]: the art frosted along the rim
 * without bending, the rim's light, and the hairline, for [LocalDialTileGlassEdge].
 */
internal fun desktopDialTileGlassEdge(shape: Shape): Modifier = Modifier.composed {
    val corner = shape as? CornerBasedShape
    val glass = if (corner != null) {
        Modifier.graphicsLayer {
            val radius = corner.topStart.toPx(size, this).coerceAtMost(size.minDimension / 2f)
            val builder = RuntimeShaderBuilder(dialTileEffect).apply {
                uniform("size", size.width, size.height)
                uniform("radius", radius)
                uniform("blurBand", DialTileBlurBand.toPx())
                uniform("blur", DialTileGlassBlur.toPx())
            }
            renderEffect = ImageFilter.makeRuntimeShader(builder, "content", null).asComposeRenderEffect()
        }
    } else {
        Modifier
    }
    Modifier
        .glassHighlight(shape, 1.dp)
        .border(GlassEdgeWidth, GlassEdgeColor, shape)
        .then(glass)
}

/**
 * The phone's clear liquid glass over whatever [haze] is the source of: that backdrop blurred
 * and veiled, then bent at the rim, folded and glinting as the phone's glass is. For a surface
 * that is only glass; lay its content over it in a separate layer, so the lens bends only the
 * backdrop.
 */
@Composable
internal fun Modifier.clearLiquidGlass(
    shape: CornerBasedShape,
    haze: HazeState,
    dark: Boolean = true,
    veil: Color? = null,
    blurDp: Float = CLEAR_GLASS_BLUR_DP,
): Modifier {
    val fill = veil ?: if (dark) ClearGlassVeilDark else ClearGlassVeilLight
    val darkF = if (dark) 1f else 0f
    val lens = remember(shape, dark) {
        Modifier.graphicsLayer {
            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@graphicsLayer
            val radii = shape.radiiPx(size, this)
            val short = minOf(w, h)
            val s = (short / REFERENCE_HEIGHT_DP.dp.toPx()).coerceIn(0.6f, 1.4f)
            val unit = REFERENCE_HEIGHT_DP.dp.toPx() / 60f * s
            val r = radii.max().coerceAtLeast(0.01f)
            val straight = (((maxOf(w, h) / 2f) - r) / r).coerceIn(0f, 1f)
            val builder = RuntimeShaderBuilder(clearGlassEffect).apply {
                uniform("size", w, h)
                uniform("offset", 0f, 0f)
                uniform("cornerRadii", radii[0], radii[1], radii[2], radii[3])
                uniform("unit", unit)
                uniform("dark", darkF)
                uniform("caustic", 1f)
                uniform("tilt", REFR_TILT * straight)
                uniform("arcScale", 1f / (maxOf(1f, w) / 2f).pow(ARC_SHAPE), 1f / (maxOf(1f, h) / 2f).pow(ARC_SHAPE))
            }
            renderEffect = ImageFilter.makeRuntimeShader(builder, "content", null).asComposeRenderEffect()
            this.shape = shape
            clip = true
        }
    }
    return this
        .then(lens)
        .hazeEffect(state = haze) {
            blurEnabled = true
            blurRadius = blurDp.dp
            backgroundColor = Color(0xFF0B0E0F)
            tints = listOf(HazeTint(fill))
            noiseFactor = 0f
        }
}
