package com.joey.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * D'où vient la voix ? Le téléphone a deux micros (haut et bas) : posé en paysage sur la nacelle, ils sont à gauche et
 * à droite. Le son arrive un peu plus tôt (et un peu plus fort) du côté de la personne. On ne sait pas distinguer
 * devant de derrière : c'est juste un indice « plutôt à gauche / plutôt à droite » pour commencer à chercher du bon côté.
 */
object SoundDirection {
    /**
     * Côté d'où vient le son dans un enregistrement stéréo : -1 (canal 1, « gauche »), +1 (canal 2), 0 si on ne sait pas.
     * Renvoie aussi une confiance 0..1. Retard estimé par corrélation croisée (±[maxLag] échantillons), niveau en appoint.
     */
    fun estimate(left: ShortArray, right: ShortArray, maxLag: Int = 24): Pair<Int, Double> {
        val n = minOf(left.size, right.size)
        if (n < 2000) return 0 to 0.0
        // On ne garde que les morceaux de parole (fenêtres de 20 ms nettement au-dessus du bruit de fond).
        val win = 960
        val energies = (0 until n / win).map { w ->
            var e = 0.0
            for (i in w * win until (w + 1) * win) e += left[i].toDouble() * left[i] + right[i].toDouble() * right[i]
            e / win
        }
        if (energies.isEmpty()) return 0 to 0.0
        val floor = energies.sorted()[energies.size / 4].coerceAtLeast(1.0)
        val loud = energies.indices.filter { energies[it] > floor * 6 }
        if (loud.size < 3) return 0 to 0.0
        // Corrélation croisée sur les fenêtres de parole : le décalage qui aligne le mieux les deux micros.
        var bestLag = 0
        var best = Double.NEGATIVE_INFINITY
        var zero = 0.0
        for (lag in -maxLag..maxLag) {
            var c = 0.0
            for (w in loud) {
                val a = w * win + maxLag
                val b = (w + 1) * win - maxLag
                for (i in a until b) c += left[i].toDouble() * right[i + lag]
            }
            if (lag == 0) zero = c
            if (c > best) { best = c; bestLag = lag }
        }
        // Niveaux : le micro le plus proche entend un peu plus fort.
        var el = 0.0; var er = 0.0
        for (w in loud) for (i in w * win until (w + 1) * win) { el += left[i].toDouble() * left[i]; er += right[i].toDouble() * right[i] }
        val db = 10 * log10((er + 1) / (el + 1))
        // lag > 0 : la droite est en retard -> le son arrive d'abord à gauche.
        val byLag = if (abs(bestLag) >= 2 && best > zero * 1.02) (if (bestLag > 0) -1 else 1) else 0
        val byLevel = if (abs(db) >= 1.5) (if (db > 0) 1 else -1) else 0
        return when {
            byLag != 0 && (byLevel == 0 || byLevel == byLag) -> byLag to (0.6 + 0.4 * minOf(1.0, abs(bestLag) / maxLag.toDouble() * 2))
            byLag == 0 && byLevel != 0 -> byLevel to 0.4
            byLag != 0 -> byLag to 0.3 // désaccord : on suit le retard, peu sûr
            else -> 0 to 0.0
        }
    }

    /**
     * Écoute ~[ms] millisecondes en stéréo (micros séparés, sans traitement) et dit d'où vient la voix.
     * Le micro doit être libre (pas de conversation en cours). Bloquant.
     */
    @SuppressLint("MissingPermission")
    fun listen(ms: Int = 1000): Pair<Int, Double> {
        val rate = 48_000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(MediaRecorder.AudioSource.CAMCORDER, rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, rate * 4))
        }.getOrNull() ?: return 0 to 0.0
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED || rec.channelCount != 2) return 0 to 0.0
            val total = rate * ms / 1000 * 2
            val buf = ShortArray(total)
            rec.startRecording()
            var off = 0
            while (off < total) {
                val r = rec.read(buf, off, total - off)
                if (r <= 0) break
                off += r
            }
            val n = off / 2
            val l = ShortArray(n) { buf[2 * it] }
            val r = ShortArray(n) { buf[2 * it + 1] }
            return estimate(l, r)
        } catch (e: Exception) {
            return 0 to 0.0
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    /** Puissance moyenne (pour les tests). */
    fun rms(x: ShortArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size.coerceAtLeast(1))
}
