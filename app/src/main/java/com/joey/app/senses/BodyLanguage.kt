package com.joey.app.senses

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot

/** Ce que Joey comprend d'un geste. Pour [POINT] : direction montrée dans l'image (dx vers la droite, dy vers le bas). */
sealed class Body {
    object ThumbUp : Body()
    object ThumbDown : Body()
    object Victory : Body()
    object Wave : Body()
    data class Point(val dx: Float, val dy: Float) : Body()
}

/**
 * Lit les gestes image après image (main : MediaPipe Gesture Recognizer ; corps : 33 repères MediaPipe Pose,
 * x/y de 0 à 1, y vers le bas). Un geste doit être tenu (pas de déclenchement au hasard), puis 3 s de pause.
 * Garde aussi la dernière main vue (pierre / feuille / ciseaux).
 */
class BodyLanguage {
    companion object {
        const val L_SHOULDER = 11; const val R_SHOULDER = 12; const val L_ELBOW = 13; const val R_ELBOW = 14
        const val L_WRIST = 15; const val R_WRIST = 16; const val NOSE = 0
        const val COOLDOWN = 3000L

        /** Main MediaPipe -> pierre / feuille / ciseaux. */
        fun rps(hand: String?): String? = when (hand) {
            "Closed_Fist" -> "pierre"
            "Open_Palm" -> "feuille"
            "Victory" -> "ciseaux"
            else -> null
        }
    }

    private var candidate: String? = null
    private var since = 0L
    private var lastFired = Long.MIN_VALUE / 2
    private val wristX = ArrayDeque<Pair<Long, Float>>()

    /** Dernière main reconnue et quand (pour pierre-feuille-ciseaux). */
    @Volatile var lastHand: String? = null
        private set
    @Volatile var lastHandAt = 0L
        private set

    private fun visible(p: List<FloatArray>, i: Int) = p.size > i && (p[i].size < 3 || p[i][2] > 0.5f)

    /** Bras tendu vers quelque chose (coude presque droit, main loin de l'épaule, pas levée au-dessus de la tête). */
    fun pointing(p: List<FloatArray>?): Body.Point? {
        if (p == null || p.size < 17) return null
        val shW = abs(p[L_SHOULDER][0] - p[R_SHOULDER][0]).coerceAtLeast(0.03f)
        for ((s, e, w) in listOf(Triple(L_SHOULDER, L_ELBOW, L_WRIST), Triple(R_SHOULDER, R_ELBOW, R_WRIST))) {
            if (!visible(p, s) || !visible(p, e) || !visible(p, w)) continue
            val ax = p[s][0] - p[e][0]; val ay = p[s][1] - p[e][1]
            val bx = p[w][0] - p[e][0]; val by = p[w][1] - p[e][1]
            val la = hypot(ax, ay); val lb = hypot(bx, by)
            if (la < 1e-3 || lb < 1e-3) continue
            val angle = Math.toDegrees(acos(((ax * bx + ay * by) / (la * lb)).coerceIn(-1f, 1f).toDouble()))
            val dx = p[w][0] - p[s][0]; val dy = p[w][1] - p[s][1]
            val reach = hypot(dx, dy)
            val aboveHead = visible(p, NOSE) && p[w][1] < p[NOSE][1] - shW * 0.3f
            if (angle > 150 && reach > shW * 1.1f && !aboveHead && abs(dx) > shW * 0.6f) {
                return Body.Point(dx / reach, dy / reach)
            }
        }
        return null
    }

    /** Coucou : une main levée (au-dessus de l'épaule) qui va et vient (3 changements de sens en 2 s). */
    private fun waving(now: Long, p: List<FloatArray>?): Boolean {
        if (p == null || p.size < 17) { wristX.clear(); return false }
        val w = listOf(L_WRIST to L_SHOULDER, R_WRIST to R_SHOULDER).firstOrNull { (wi, si) ->
            visible(p, wi) && visible(p, si) && p[wi][1] < p[si][1]
        } ?: run { wristX.clear(); return false }
        wristX.addLast(now to p[w.first][0])
        while (wristX.isNotEmpty() && now - wristX.first().first > 2000) wristX.removeFirst()
        if (wristX.size < 5) return false
        var turns = 0
        var lastDir = 0
        for (i in 1 until wristX.size) {
            val d = wristX[i].second - wristX[i - 1].second
            if (abs(d) < 0.01f) continue
            val dir = if (d > 0) 1 else -1
            if (lastDir != 0 && dir != lastDir) turns++
            lastDir = dir
        }
        return turns >= 3
    }

    /** Une image analysée : renvoie un geste quand il a été tenu assez longtemps (ou un coucou complet). */
    fun update(now: Long, hand: String?, pose: List<FloatArray>?): Body? {
        if (hand != null) { lastHand = hand; lastHandAt = now }
        if (now - lastFired < COOLDOWN) { candidate = null; return null }
        if (waving(now, pose)) { lastFired = now; wristX.clear(); return Body.Wave }
        val pt = pointing(pose)
        val key = when {
            hand == "Thumb_Up" -> "up"
            hand == "Thumb_Down" -> "down"
            hand == "Victory" -> "v"
            pt != null -> "point"
            else -> null
        }
        if (key == null) { candidate = null; return null }
        if (key != candidate) { candidate = key; since = now; return null }
        val hold = if (key == "point") 700L else 600L
        if (now - since < hold) return null
        lastFired = now
        candidate = null
        return when (key) {
            "up" -> Body.ThumbUp
            "down" -> Body.ThumbDown
            "v" -> Body.Victory
            else -> pt
        }
    }
}
