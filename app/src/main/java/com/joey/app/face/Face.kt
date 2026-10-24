package com.joey.app.face

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.min
import kotlin.random.Random

enum class Mood { NEUTRE, CONTENT, TRISTE, ETONNE, FACHE, FATIGUE, AMUSE, MEFIANT, DORT, ETOURDI }

/** Ce que fait Joey en ce moment (change l'animation des yeux). */
enum class Activity { REPOS, ECOUTE, PARLE, REFLECHIT }

private val EYE = Color(0xFF4FC3F7)
private val EYE_ANGRY = Color(0xFFFF8A65)
private val EYE_SAD = Color(0xFF81A4D4)

/**
 * Le visage de Joey : deux yeux plein écran sur fond noir.
 * [level] : volume de la voix (0..1) pendant qu'il parle. [gazeX]/[gazeY] : où regardent les yeux (-1..1).
 */
@Composable
fun Face(
    mood: Mood,
    activity: Activity,
    level: () -> Float,
    gazeX: Float,
    gazeY: Float,
    modifier: Modifier = Modifier,
    /** Tête penchée (degrés) : il réfléchit, il est curieux. */
    tilt: Float = 0f,
    /**
     * Yeux pixelisés (style vieil écran à LED) : nombre de pixels en largeur, 0 = yeux lisses. Le visage est dessiné dans
     * une toute petite image, agrandie sans lissage, avec un fin quadrillage entre les pixels.
     */
    pixels: Int = 0,
) {
    // Clignement : toutes les 2 à 6 s.
    val blink = remember { Animatable(1f) }
    LaunchedEffect(mood) {
        if (mood == Mood.DORT) return@LaunchedEffect
        while (isActive) {
            delay(Random.nextLong(2000, 6000))
            blink.animateTo(0.05f, tween(70))
            blink.animateTo(1f, tween(110))
            if (Random.nextInt(5) == 0) { // double clignement de temps en temps
                delay(120)
                blink.animateTo(0.05f, tween(70))
                blink.animateTo(1f, tween(110))
            }
        }
    }
    // Petits mouvements d'yeux quand il ne fait rien, yeux en l'air quand il réfléchit.
    var idleX by remember { mutableFloatStateOf(0f) }
    var idleY by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(activity) {
        while (isActive) {
            when (activity) {
                Activity.REFLECHIT -> { idleX = if (idleX > 0) -0.7f else 0.7f; idleY = -0.85f; delay(700) }
                Activity.REPOS -> {
                    delay(Random.nextLong(1500, 4000))
                    idleX = Random.nextFloat() * 0.6f - 0.3f
                    idleY = Random.nextFloat() * 0.4f - 0.2f
                }
                else -> { idleX = 0f; idleY = 0f; delay(500) }
            }
        }
    }
    val gx by animateFloatAsState((gazeX + idleX).coerceIn(-1f, 1f), tween(250), label = "gx")
    val gy by animateFloatAsState((gazeY + idleY).coerceIn(-1f, 1f), tween(250), label = "gy")
    // Voix : on lit le volume à chaque image.
    var voice by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(activity) {
        while (isActive) {
            val target = if (activity == Activity.PARLE) (level() * 4f).coerceIn(0f, 1f) else 0f
            voice += (target - voice) * 0.35f
            delay(33)
        }
    }
    val listen by animateFloatAsState(if (activity == Activity.ECOUTE) 1f else 0f, tween(300), label = "listen")
    val lean by animateFloatAsState(tilt, tween(450), label = "lean")
    // Réflexion : trois points qui s'allument l'un après l'autre (bien visible).
    val think by animateFloatAsState(if (activity == Activity.REFLECHIT) 1f else 0f, tween(250), label = "think")
    val dots = rememberInfiniteTransition(label = "dots")
    val phase by dots.animateFloat(0f, 3f, infiniteRepeatable(tween(1100, easing = LinearEasing)), label = "phase")

    Canvas(modifier) {
        val content: DrawScope.() -> Unit = {
            drawRect(Color.Black)
            rotate(lean) {
                drawFace(this, mood, activity, voice, listen * (1f - think), gx, gy, blink.value * (1f - 0.35f * think))
                if (think > 0.01f) {
                    val unit = min(size.width / 2.3f, size.height / 1.45f)
                    val r = unit * 0.075f
                    val y = size.height / 2f + unit * 0.78f
                    for (i in 0..2) {
                        val on = (1f - kotlin.math.abs(((phase - i + 3f) % 3f) - 0.5f) * 1.3f).coerceIn(0.25f, 1f)
                        drawCircle(EYE.copy(alpha = think * on), r * (0.8f + 0.35f * on), Offset(size.width / 2f + (i - 1) * r * 3.4f, y))
                    }
                }
            }
        }
        if (pixels <= 0 || size.width < 2f || size.height < 2f) { content(); return@Canvas }
        // Pixelisé : le même visage dessiné en petit, agrandi sans lissage.
        val cols = pixels
        val rows = kotlin.math.max(1, kotlin.math.round(cols * size.height / size.width).toInt())
        val small = androidx.compose.ui.graphics.ImageBitmap(cols, rows)
        androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(this, layoutDirection,
            androidx.compose.ui.graphics.Canvas(small), Size(cols.toFloat(), rows.toFloat()), content)
        drawRect(Color.Black)
        drawImage(small, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
        // Fin quadrillage noir entre les pixels (l'écran à LED).
        val cw = size.width / cols; val ch = size.height / rows
        for (i in 1 until cols) drawLine(Color.Black, Offset(i * cw, 0f), Offset(i * cw, size.height), strokeWidth = cw * 0.14f)
        for (j in 1 until rows) drawLine(Color.Black, Offset(0f, j * ch), Offset(size.width, j * ch), strokeWidth = ch * 0.14f)
    }
}

private fun drawFace(scope: DrawScope, mood: Mood, activity: Activity, voice: Float, listen: Float, gx: Float, gy: Float, blinkValue: Float) = with(scope) {
    run {
        val w = size.width
        val h = size.height
        val unit = min(w / 2.3f, h / 1.45f)
        val eyeW = unit * 0.62f
        val eyeH = unit * (if (mood == Mood.ETONNE) 1.05f else 0.9f) * (1f + 0.12f * voice)
        val gap = unit * 0.5f
        val cy = h / 2f + gy * unit * 0.22f
        val cx = w / 2f + gx * unit * 0.35f
        val color = when (mood) {
            Mood.FACHE -> EYE_ANGRY
            Mood.TRISTE, Mood.FATIGUE -> EYE_SAD
            else -> EYE
        }
        for (side in listOf(-1, 1)) {
            val ex = cx + side * (gap / 2f + eyeW / 2f)
            drawEye(ex, cy, eyeW, eyeH * blinkValue, side, mood, color)
        }
        // Écoute : un petit halo sous les yeux.
        if (listen > 0.01f) {
            val r = unit * 0.06f
            for (i in -1..1) {
                drawCircle(color.copy(alpha = 0.5f * listen), r, Offset(w / 2f + i * r * 3f, cy + eyeH / 2f + unit * 0.25f))
            }
        }
        // Parle : une bouche fine qui bouge avec la voix.
        if (activity == Activity.PARLE) {
            val mw = unit * 0.5f
            val mh = unit * 0.04f + unit * 0.18f * voice
            drawRoundRect(color.copy(alpha = 0.85f), Offset(w / 2f - mw / 2f, cy + eyeH / 2f + unit * 0.18f),
                Size(mw, mh), CornerRadius(mh / 2f))
        }
    }
}

private fun DrawScope.drawEye(cx: Float, cy: Float, w: Float, h: Float, side: Int, mood: Mood, color: Color) {
    val hh = h.coerceAtLeast(w * 0.06f)
    val left = cx - w / 2f
    val top = cy - hh / 2f
    if (mood == Mood.DORT) {
        drawLine(color, Offset(left, cy), Offset(left + w, cy), strokeWidth = w * 0.08f)
        return
    }
    if (mood == Mood.ETOURDI) {
        // Yeux en spirale (on l'a secoué)
        val p = Path()
        val turns = 3
        val steps = 90
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val a = t * turns * 2 * Math.PI * side
            val r = t * w * 0.45f
            val x = cx + (r * kotlin.math.cos(a)).toFloat()
            val y = cy + (r * kotlin.math.sin(a)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, color, style = Stroke(width = w * 0.07f))
        return
    }
    if (mood == Mood.CONTENT || mood == Mood.AMUSE) {
        // Yeux en arc « ^ ^ »
        val p = Path().apply {
            moveTo(left, cy + hh * 0.15f)
            quadraticTo(cx, cy - hh * 0.55f, left + w, cy + hh * 0.15f)
        }
        drawPath(p, color, style = Stroke(width = w * 0.16f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        return
    }
    val eye = Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(left, top, left + w, top + hh, CornerRadius(w * 0.42f)))
    }
    // Paupières : une forme noire qui coupe le haut (fâché, triste, méfiant, fatigué).
    val lid = Path()
    when (mood) {
        Mood.FACHE -> { // paupière qui descend vers le centre du visage
            val inner = if (side < 0) left + w else left
            val outer = if (side < 0) left else left + w
            lid.moveTo(outer, top - 2); lid.lineTo(inner, top - 2); lid.lineTo(inner, top + hh * 0.45f); lid.lineTo(outer, top + hh * 0.05f); lid.close()
        }
        Mood.TRISTE -> { // paupière qui descend vers l'extérieur
            val inner = if (side < 0) left + w else left
            val outer = if (side < 0) left else left + w
            lid.moveTo(outer, top - 2); lid.lineTo(inner, top - 2); lid.lineTo(inner, top + hh * 0.05f); lid.lineTo(outer, top + hh * 0.45f); lid.close()
        }
        Mood.FATIGUE -> lid.addRect(androidx.compose.ui.geometry.Rect(left - 2, top - 2, left + w + 2, top + hh * 0.5f))
        Mood.MEFIANT -> lid.addRect(androidx.compose.ui.geometry.Rect(left - 2, top - 2, left + w + 2, top + hh * 0.35f))
        else -> {}
    }
    clipPath(eye) {
        drawRect(color, Offset(left, top), Size(w, hh))
        if (!lid.isEmpty) drawPath(lid, Color.Black)
        // Reflet
        drawCircle(Color.White.copy(alpha = 0.35f), w * 0.09f, Offset(left + w * 0.3f, top + hh * 0.25f))
    }
}
