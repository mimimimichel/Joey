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
    fun pose(voice: Float, speaking: Boolean, listening: Boolean, thinking: Boolean, energy: Double,
             mood: com.joey.app.face.Mood = com.joey.app.face.Mood.NEUTRE, moodSince: Long = 0L): Pose {
        val t = now()
        val dt = if (lastAt == 0L) 0.05 else ((t - lastAt) / 1000.0).coerceIn(0.0, 0.5)
        lastAt = t
        val e = energy.coerceIn(0.0, 1.0)
        // Respiration : lente (4,5 s), un peu plus ample quand il est en forme.
        var pan = 2.0 * sin(2 * PI * t / 7300.0)
        var tilt = (2.5 + 1.5 * e) * sin(2 * PI * t / 4500.0)
        // Il parle : hochements sur l'énergie de la voix (enveloppe rapide - lente) et petite pose différente par phrase.
        val v = if (speaking) voice.toDouble().coerceIn(0.0, 1.0) * 4 else 0.0
        fast += (v - fast) * minOf(1.0, dt / 0.08)
        slow += (v - slow) * minOf(1.0, dt / 0.6)
        if (speaking) {
            tilt += ((fast - slow) * 16).coerceIn(-3.0, 8.0) * (0.7 + 0.3 * e)
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
        tilt += nod(t - nodAt) + (if (doubleNod) nod(t - nodAt - 550) else 0.0)
        var screen = 0f
        // Il réfléchit : ça doit se voir. Il lève les yeux et la tête sur le côté, et son regard va et vient.
        if (thinking) {
            thinkW = minOf(1.0, thinkW + dt / 0.35)
        } else thinkW = maxOf(0.0, thinkW - dt / 0.35)
        if (thinkW > 0) {
            pan += thinkW * (5.0 + 2.0 * sin(2 * PI * t / 2400.0))
            tilt += thinkW * 5.0
            screen += (thinkW * 15).toFloat()
        }
        // L'attitude du moment (quelques secondes après un changement d'humeur, ou tant qu'elle dure).
        val since = (t - moodSince).coerceAtLeast(0)
        val ease = (since / 500.0).coerceIn(0.0, 1.0)
        when (mood) {
            com.joey.app.face.Mood.CONTENT, com.joey.app.face.Mood.AMUSE -> {
                if (since < 900) tilt += 7.0 * abs(sin(2 * PI * since / 450.0)) // petits bonds de joie
                if (mood == com.joey.app.face.Mood.AMUSE) screen += 8f * ease.toFloat()
            }
            com.joey.app.face.Mood.TRISTE -> tilt -= 15.0 * ease
            com.joey.app.face.Mood.FATIGUE -> { tilt -= 9.0 * ease; pan += 3.0 * sin(2 * PI * t / 9000.0) }
            com.joey.app.face.Mood.ETONNE -> tilt += if (since < 450) 12.0 * sin(PI * since / 450.0) + 4.0 * ease else 4.0
            com.joey.app.face.Mood.FACHE -> {
                if (since < 800) pan += 7.0 * sin(2 * PI * since / 260.0) // « non non non »
                tilt -= 5.0 * ease
            }
            com.joey.app.face.Mood.MEFIANT -> { pan -= 8.0 * ease; screen -= 8f * ease.toFloat() }
            com.joey.app.face.Mood.ETOURDI -> { pan += 7.0 * sin(2 * PI * t / 900.0); tilt += 5.0 * kotlin.math.cos(2 * PI * t / 900.0) }
            com.joey.app.face.Mood.DORT -> tilt -= 20.0 * ease
            else -> {}
        }
        val (cp, ct) = cueOffset(t)
        pan += cp; tilt += ct
        if (cue == Cue.HMM && t - cueAt in 0..1200) screen += 9f
        return Pose(pan, tilt, screen)
    }

    private var thinkW = 0.0

    /** Petits gestes accordés à ce qu'il dit. */
    enum class Cue { YES, NO, HMM }
    @Volatile private var cue: Cue? = null
    @Volatile private var cueAt = -10_000L

    fun cue(c: Cue) { cue = c; cueAt = now() }

    private fun cueOffset(t: Long): Pair<Double, Double> {
        val ms = t - cueAt
        return when (cue) {
            Cue.YES -> 0.0 to (nod(ms) + nod(ms - 450)) // oui oui
            Cue.NO -> (if (ms in 0..900) 8.0 * sin(2 * PI * ms / 300.0) * (1 - ms / 900.0) else 0.0) to 0.0
            Cue.HMM -> (if (ms in 0..1200) 6.0 * sin(PI * ms / 1200.0) else 0.0) to (if (ms in 0..1200) 3.0 * sin(PI * ms / 1200.0) else 0.0)
            null -> 0.0 to 0.0
        }
    }

    /** Un hochement : la tête descend de 7° et remonte, en 0,5 s. */
    private fun nod(ms: Long): Double = if (ms in 0..500) -7.0 * sin(PI * ms / 500.0) else 0.0

    private fun currentSway(t: Long): Double {
        val k = ((t - swayAt) / 700.0).coerceIn(0.0, 1.0)
        val s = k * k * (3 - 2 * k) // départ et arrivée en douceur
        return swayFrom + (swayTo - swayFrom) * s
    }
}
