package com.joey.app.gimbal

import kotlin.math.abs

/**
 * Le câble du téléphone au Roomba s'entortille si la tête fait des tours complets dans le même sens. On compte donc de
 * combien la tête a tourné PAR RAPPORT AU CORPS depuis le démarrage (sans ramener à ±180°), et on interdit de dépasser
 * un demi-tour de chaque côté : pour regarder derrière, la tête repart par l'autre côté ; un tour complet = deux
 * demi-tours, d'un bord à l'autre.
 *
 * Au démarrage, on suppose la tête droite devant, câble au repos.
 */
class CableTwist(val limit: Double = LIMIT) {
    companion object {
        const val LIMIT = 185.0
    }

    /** Tours de la tête par rapport au corps (degrés, + = droite), sans repli. */
    @Volatile var value = 0.0
        private set
    private var lastHead: Double? = null
    private var lastBody: Double? = null

    /**
     * Nouvelle mesure : cap de la tête dans le monde ([head], capteurs du téléphone), cap du corps ([body], roues ;
     * null = pas de roues, corps supposé immobile). À appeler souvent (≥ 5 fois par seconde).
     */
    @Synchronized fun update(head: Double?, body: Double?) {
        if (head == null) return
        val b = body ?: lastBody ?: 0.0
        val lh = lastHead; val lb = lastBody ?: b
        if (lh != null) value += Head.wrap(head - lh) - Head.wrap(b - lb)
        lastHead = head
        lastBody = b
    }

    /** Mesure impossible un moment (tête à la verticale : le cap n'a plus de sens) : on reprendra à la suivante. */
    @Synchronized fun pause() { lastHead = null }

    /**
     * L'angle réel tête-corps vient d'être mesuré ([rel], ±180, par les pastilles du Roomba) : le compteur garde le même
     * nombre de tours, mais prend cet angle (il dérivait : on ne sait jamais où la tête est vraiment sans repère).
     */
    @Synchronized fun correct(rel: Double) { value = rel + 360.0 * Math.round((value - rel) / 360.0) }

    /** La tête est remise droit devant (câble au repos). */
    @Synchronized fun reset() { value = 0.0 }

    /**
     * Le mouvement relatif [delta] (+ = droite, ±180) à faire vraiment : le même, ou par l'autre côté (delta ∓ 360) si
     * le premier entortillerait le câble au-delà de la limite.
     */
    fun safe(delta: Double): Double {
        val t = value + delta
        return when {
            t > limit -> delta - 360
            t < -limit -> delta + 360
            else -> delta
        }
    }

    /** Pousser encore dans ce sens ([dir] > 0 = droite) dépasserait la limite. */
    fun blocks(dir: Double): Boolean = (dir > 0 && value >= limit) || (dir < 0 && value <= -limit)

    /** Où commencer un tour complet dans le sens [dir] : de l'autre bord, pour finir à l'opposé. */
    fun startForFullTurn(dir: Int): Double = -dir * (limit - 10)

    override fun toString() = "%.0f°".format(value)

    /** Proche d'un bord (pour les tests et le journal). */
    fun nearLimit(margin: Double = 20.0) = abs(value) > limit - margin
}
