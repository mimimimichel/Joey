package com.joey.app.life

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Le « langage du corps » de la tête, ajouté par-dessus le regard (petits écarts en degrés) :
 * - il respire : léger balancement lent, jamais parfaitement immobile ;
 * - il bouge en parlant : petits hochements sur les syllabes fortes, et il change un peu de pose à chaque phrase ;
 * - il écoute : un petit hochement de temps en temps quand on lui parle ;
 * - il réfléchit : il penche la tête (le visage à l'écran s'incline, la nacelle n'a pas de roulis pilotable).
 */
class Expressive(private val now: () -> Long = { System.currentTimeMillis() }, private val random: () -> Double = { Math.random() }) {
    /** Écart de la tête (droite+, haut+) en degrés, et inclinaison du visage à l'écran (degrés). */
    data class Pose(val pan: Double, val tilt: Double, val screenTilt: Float)

    private var fast = 0.0
    private var slow = 0.0
    private var lastAt = 0L
    private var swayFrom = 0.0
    private var swayTo = 0.0
    private var swayAt = 0L
    private var nextSwayAt = 0L
    private var nodAt = -10_000L
    private var nextNodAt = 0L
    private var doubleNod = false

    /**
     * À appeler ~10 à 25 fois par seconde. [voice] : volume de sa voix (0..1) ; [speaking] : il parle ;
     * [listening] : quelqu'un lui parle ; [thinking] : il réfléchit ou cherche ; [energy] : 0 (fatigué) à 1 (en forme).
     */
    fun pose(voice: Float, speaking: Boolean, listening: Boolean, thinking: Boolean, energy: Double): Pose {
        val t = now()
        val dt = if (lastAt == 0L) 0.05 else ((t - lastAt) / 1000.0).coerceIn(0.0, 0.5)
        lastAt = t
        val e = energy.coerceIn(0.0, 1.0)
        // Respiration : lente (4,5 s), un peu plus ample quand il est en forme.
        var pan = 1.5 * sin(2 * PI * t / 7300.0)
        var tilt = (2.0 + 1.0 * e) * sin(2 * PI * t / 4500.0)
        // Il parle : hochements sur l'énergie de la voix (enveloppe rapide - lente) et petite pose différente par phrase.
        val v = if (speaking) voice.toDouble().coerceIn(0.0, 1.0) * 4 else 0.0
        fast += (v - fast) * minOf(1.0, dt / 0.08)
        slow += (v - slow) * minOf(1.0, dt / 0.6)
        if (speaking) {
            tilt += ((fast - slow) * 10).coerceIn(-2.0, 5.0) * (0.6 + 0.4 * e)
            if (t >= nextSwayAt) {
                swayFrom = currentSway(t); swayTo = (random() * 2 - 1) * 5.0; swayAt = t
                nextSwayAt = t + 2200 + (random() * 2000).toLong()
            }
        } else if (swayTo != 0.0) { // il se tait : retour en douceur à la pose de départ
            swayFrom = currentSway(t); swayTo = 0.0; swayAt = t; nextSwayAt = 0L
        }
        pan += currentSway(t)
        // Il écoute : un petit « oui » de la tête toutes les 3,5 à 7 s (parfois deux).
        if (listening && !speaking && t >= nextNodAt) {
            nodAt = t
            doubleNod = random() < 0.3
            nextNodAt = t + 3500 + (random() * 3500).toLong()
        }
        if (!listening) nextNodAt = maxOf(nextNodAt, t + 1500)
        tilt += nod(t - nodAt) + (if (doubleNod) nod(t - nodAt - 650) else 0.0)
        return Pose(pan, tilt, if (thinking) 11f else 0f)
    }

    /** Un hochement : la tête descend de 5° et remonte, en 0,6 s. */
    private fun nod(ms: Long): Double = if (ms in 0..600) -5.0 * sin(PI * ms / 600.0) else 0.0

    private fun currentSway(t: Long): Double {
        val k = ((t - swayAt) / 700.0).coerceIn(0.0, 1.0)
        val s = k * k * (3 - 2 * k) // départ et arrivée en douceur
        return swayFrom + (swayTo - swayFrom) * s
    }
}
