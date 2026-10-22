package com.joey.app.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sign

/**
 * D'où vient la voix, pendant la conversation, avec les deux micros du téléphone (gauche / droite seulement).
 * Il se règle tout seul en t'écoutant :
 * - quand tu parles bien en face (visage au centre de l'image), les deux micros devraient entendre pareil : il apprend
 *   l'écart de sensibilité entre eux (et le petit décalage) ;
 * - quand il te voit sur un côté, il apprend lequel des deux micros est à gauche.
 * Réglages gardés d'une fois sur l'autre ([save]).
 */
class StereoDirection(
    private var balanceDb: Double = 0.0,
    private var lagBias: Double = 0.0,
    private var sideSign: Int = 1,
    private val save: (balanceDb: Double, lagBias: Double, sideSign: Int, samples: Int) -> Unit = { _, _, _, _ -> },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val maxLag: Int = 8,
) {
    private class Est(val at: Long, val db: Double, val lag: Int, val energy: Double)
    private val recent = ArrayDeque<Est>()
    private var floor = Double.NaN
    @Volatile var calibrated = 0
        private set
    private var agree = 0
    private var disagree = 0
    /** Les deux canaux sont identiques (le téléphone ne donne pas vraiment deux micros) : inutilisable. */
    @Volatile var mono = false
        private set
    private var sameCount = 0
    private var diffCount = 0

    /** Un morceau stéréo (100 ms). */
    fun feed(left: ShortArray, right: ShortArray, n: Int) {
        if (n < 400) return
        var el = 0.0; var er = 0.0; var same = 0
        for (i in 0 until n) { el += left[i].toDouble() * left[i]; er += right[i].toDouble() * right[i]; if (left[i] == right[i]) same++ }
        val e = (el + er) / (2 * n)
        if (floor.isNaN()) floor = e
        // Le bruit de fond n'apprend que des moments calmes (sinon, pendant une longue phrase, la voix devenait « du bruit »).
        floor = if (e < floor) e else if (e < floor * 4) floor + (e - floor) * 0.05 else floor
        // Canaux recopiés l'un sur l'autre ?
        if (e > floor * 4) { if (same > n * 0.95) sameCount++ else diffCount++ }
        if (sameCount > 20 && diffCount < 3) mono = true
        if (e < floor * 6 || e < 2e4) return // pas de parole
        var bestLag = 0; var best = Double.NEGATIVE_INFINITY
        for (lag in -maxLag..maxLag) {
            var c = 0.0
            for (i in maxLag until n - maxLag) c += left[i].toDouble() * right[i + lag]
            if (c > best) { best = c; bestLag = lag }
        }
        val db = 10 * log10((er + 1) / (el + 1))
        val t = now()
        synchronized(recent) {
            recent.addLast(Est(t, db, bestLag, e))
            while (recent.isNotEmpty() && t - recent.first().at > 1500) recent.removeFirst()
        }
    }

    /** Score brut récent (positif = plutôt « micro 2 »), sans le signe appris ; null si pas assez de parole. */
    private fun rawScore(): Pair<Double, Int>? = synchronized(recent) {
        val t = now()
        val r = recent.filter { t - it.at < 1500 }
        if (r.size < 3) return null
        val db = r.map { it.db }.average() - balanceDb
        val lag = r.map { it.lag.toDouble() }.average() - lagBias
        // lag > 0 : le micro 2 est en retard -> le son vient du côté du micro 1.
        (db / 3.0 - lag / 2.0) to r.size
    }

    /** Côté de la voix (-1 gauche, +1 droite, 0 inconnu) et confiance 0..1. */
    fun side(): Pair<Int, Double> {
        if (mono) return 0 to 0.0
        val (s, n) = rawScore() ?: return 0 to 0.0
        val conf = (abs(s) / 1.5).coerceIn(0.0, 1.0) * (n / 8.0).coerceAtMost(1.0) * (if (calibrated >= 10) 1.0 else 0.5)
        return (if (abs(s) < 0.25) 0 else (sign(s) * sideSign).toInt()) to conf
    }

    /**
     * Pendant que la personne parle et qu'on voit son visage en [faceX] (-1 gauche .. 1 droite de l'image) : réglage.
     * Au centre -> équilibre des micros ; sur un côté -> lequel est à gauche.
     */
    fun learn(faceX: Float) {
        if (mono) return
        val r = synchronized(recent) { recent.filter { now() - it.at < 600 } }
        if (r.size < 3) return
        if (abs(faceX) < 0.15f) {
            val db = r.map { it.db }.average()
            val lag = r.map { it.lag.toDouble() }.average()
            val k = if (calibrated < 10) 0.3 else 0.05
            balanceDb += (db - balanceDb) * k
            lagBias += (lag - lagBias) * k
            calibrated++
            if (calibrated == 10 || calibrated % 50 == 0) save(balanceDb, lagBias, sideSign, calibrated)
        } else if (abs(faceX) > 0.35f && calibrated >= 5) {
            val (s, _) = rawScore() ?: return
            if (abs(s) < 0.3) return
            if (sign(s).toInt() * sideSign == sign(faceX).toInt()) agree++ else disagree++
            if (disagree >= 6 && disagree > agree * 2) { sideSign = -sideSign; agree = 0; disagree = 0; save(balanceDb, lagBias, sideSign, calibrated) }
            if (agree > 30) { agree /= 2; disagree /= 2 }
        }
    }

    fun describe() = if (mono) "micros : un seul canal (pas de direction)" else
        "micros : écart %.1f dB, décalage %.1f, sens %d, %d mesures de réglage".format(balanceDb, lagBias, sideSign, calibrated)
}
