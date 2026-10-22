package com.joey.app.senses

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Comme quelqu'un d'attentif : Joey regarde la personne, mais quand elle bouge nettement les mains (elle montre
 * quelque chose, fait un geste, tient un objet), il jette un coup d'œil à ses mains, puis revient à son visage.
 *
 * On lui donne les repères du corps (MediaPipe, x/y de 0 à 1) à chaque analyse ; [aimPoint] dit où viser
 * (x/y de -1 à 1) pendant le coup d'œil : entre le visage et la main si les deux tiennent dans l'image, sinon la main.
 */
class HandAttention(private val now: () -> Long = { System.currentTimeMillis() }) {
    companion object {
        const val NOSE = 0
        const val L_WRIST = 15; const val R_WRIST = 16
        const val L_INDEX = 19; const val R_INDEX = 20
        const val L_SHOULDER = 11; const val R_SHOULDER = 12; const val L_ELBOW = 13; const val R_ELBOW = 14
        /** Même chose pour une main déduite du bras (hors de l'image). */
        const val MOVE_GUESSED = 0.10f
        /** Déplacement de la main (part de l'image) en 0,7 s pour dire qu'elle bouge vraiment. */
        const val MOVE = 0.07f
        const val HOLD_MS = 1200L   // il reste sur les mains tant qu'elles bougent, plus ce temps
        const val MAX_MS = 5000L    // jamais plus longtemps d'affilée
        const val REST_MS = 2500L   // puis au moins ce temps sur le visage
    }

    private val history = listOf(ArrayDeque<Triple<Long, Float, Float>>(), ArrayDeque())
    private var streak = 0
    private var startAt = 0L
    private var until = 0L
    private var endedAt = -REST_MS
    private var handX = 0f
    private var handY = 0f

    /** Coup d'œil en cours aux mains. */
    @Volatile var active = false
        private set

    /** Appelé quand un coup d'œil commence (pour montrer l'image à Gemini, par exemple). */
    var onStart: (() -> Unit)? = null

    private fun visible(p: List<FloatArray>, i: Int) = p.size > i && (p[i].size < 3 || p[i][2] > 0.5f)

    private fun dist(p: List<FloatArray>, a: Int, b: Int) = hypot(p[a][0] - p[b][0], p[a][1] - p[b][1])

    /**
     * Position d'une main (et si elle est vue ou déduite) : entre le poignet et le bout de l'index si on les voit.
     * Hors de l'image (souvent : la caméra est centrée sur le visage, les mains sont plus bas), on la déduit du bras :
     * le poignet estimé par MediaPipe s'il est cohérent avec la longueur du bras, sinon le bras prolongé
     * (épaule -> coude, l'avant-bras est à peu près aussi long).
     */
    private fun hand(p: List<FloatArray>, shoulder: Int, elbow: Int, wrist: Int, index: Int): Pair<Pair<Float, Float>, Boolean>? {
        if (visible(p, wrist)) {
            val pos = if (visible(p, index)) ((p[wrist][0] + p[index][0]) / 2) to ((p[wrist][1] + p[index][1]) / 2)
            else p[wrist][0] to p[wrist][1]
            return pos to true
        }
        if (!visible(p, elbow) || !visible(p, shoulder)) return null
        val arm = dist(p, shoulder, elbow).coerceAtLeast(0.02f)
        val guessed = p.size > wrist && p[wrist].size >= 2 && dist(p, elbow, wrist) in (arm * 0.5f)..(arm * 1.5f)
        val pos = if (guessed) p[wrist][0] to p[wrist][1]
        else (p[elbow][0] + (p[elbow][0] - p[shoulder][0])) to (p[elbow][1] + (p[elbow][1] - p[shoulder][1]))
        return pos to false
    }

    /**
     * Une analyse du corps. [faceX]/[faceY] : le visage regardé (-1..1), pour ne suivre que les mains de cette
     * personne-là (si plusieurs). Renvoie true quand un coup d'œil commence.
     */
    fun update(pose: List<FloatArray>?, faceX: Float?, faceY: Float?): Boolean {
        val t = now()
        if (active && (t > until || t - startAt > MAX_MS)) { active = false; endedAt = t }
        if (pose == null || pose.size < 21) { streak = 0; history.forEach { it.clear() }; return false }
        // Le corps vu est-il celui de la personne regardée ? (le nez près de son visage)
        if (faceX != null && visible(pose, NOSE)) {
            val nx = pose[NOSE][0] * 2 - 1
            val ny = pose[NOSE][1] * 2 - 1
            if (abs(nx - faceX) > 0.35f || abs(ny - (faceY ?: ny)) > 0.45f) { streak = 0; return false }
        }
        val nose = if (visible(pose, NOSE)) pose[NOSE][0] to pose[NOSE][1] else 0f to 0f
        var bestMove = 0f
        var best: Pair<Float, Float>? = null
        for ((k, ids) in listOf(intArrayOf(L_SHOULDER, L_ELBOW, L_WRIST, L_INDEX), intArrayOf(R_SHOULDER, R_ELBOW, R_WRIST, R_INDEX)).withIndex()) {
            val h = history[k]
            val found = hand(pose, ids[0], ids[1], ids[2], ids[3])
            if (found == null) { h.clear(); continue }
            val (pos, seen) = found
            // Position par rapport au nez : quand c'est la tête de Joey qui tourne, tout bouge ensemble et ça ne compte pas.
            val rx = pos.first - nose.first
            val ry = pos.second - nose.second
            h.addLast(Triple(t, rx, ry))
            while (h.isNotEmpty() && t - h.first().first > 700) h.removeFirst()
            // Déduite du bras : plus imprécise, il faut qu'elle bouge un peu plus pour y croire.
            val moved = h.maxOf { hypot(it.second - rx, it.third - ry) } * (if (seen) 1f else MOVE / MOVE_GUESSED)
            if (moved > bestMove) { bestMove = moved; best = pos }
        }
        val moving = bestMove > MOVE && best != null
        streak = if (moving) streak + 1 else 0
        if (moving) { handX = best!!.first * 2 - 1; handY = best.second * 2 - 1 }
        if (active) {
            if (moving) until = t + HOLD_MS
            return false
        }
        // Deux analyses de suite où la main bouge, et pas juste après un autre coup d'œil.
        if (streak >= 2 && t - endedAt >= REST_MS) {
            active = true
            startAt = t
            until = t + HOLD_MS
            onStart?.invoke()
            return true
        }
        return false
    }

    /** Où viser pendant le coup d'œil (-1..1), ou null : regarder le visage. */
    fun aimPoint(faceX: Float?, faceY: Float?): Pair<Float, Float>? {
        val t = now()
        if (active && (t > until || t - startAt > MAX_MS)) { active = false; endedAt = t }
        if (!active) return null
        if (faceX == null || faceY == null) return handX to handY
        // Les deux tiennent dans l'image : on vise entre les deux (plus près des mains) ; sinon les mains (même hors de
        // l'image : la tête descend vers elles).
        return if (abs(handX - faceX) < 1.3f && abs(handY - faceY) < 1.3f)
            (faceX * 0.4f + handX * 0.6f) to (faceY * 0.4f + handY * 0.6f)
        else handX to handY
    }

    /** Fin du coup d'œil (on lui parle d'autre chose, un mouvement demandé…). */
    fun stop() { if (active) { active = false; endedAt = now() } }
}
