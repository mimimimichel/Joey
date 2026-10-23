package com.joey.app.audio

import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * « Babil » du Roomba : pendant que Joey parle (par le téléphone), le haut-parleur du Roomba bipe en même temps, au
 * rythme et à la hauteur de sa voix, comme un petit R2-D2. Le Roomba ne sait jouer que des notes : on découpe la voix
 * en tranches de ~150 ms, et pour chaque début de syllabe (le volume remonte) on donne une note qui suit la hauteur
 * de la voix (deux octaves plus haut, pour sonner « robot »).
 *
 * Reçoit le son de la voix (PCM 16 bits, petit-boutiste) ; renvoie la note MIDI à jouer, ou null.
 */
class Babble(private val sampleRate: Int, private val windowMs: Int = 150) {
    private val window = FloatArray(sampleRate * windowMs / 1000)
    private var filled = 0
    private var prevRms = 0.0
    private var lastNote = 0

    /** Ajoute un morceau de voix ; renvoie les notes (une par tranche qui commence une syllabe). */
    fun feed(pcm: ByteArray, length: Int = pcm.size): List<Int> {
        val notes = ArrayList<Int>()
        var i = 0
        while (i + 1 < length) {
            window[filled++] = ((pcm[i].toInt() and 0xFF) or (pcm[i + 1].toInt() shl 8)).toShort() / 32768f
            i += 2
            if (filled == window.size) {
                analyse()?.let { notes.add(it) }
                filled = 0
            }
        }
        return notes
    }

    private fun analyse(): Int? {
        var sum = 0.0
        for (v in window) sum += v * v
        val rms = sqrt(sum / window.size)
        val onset = rms > 0.03 && rms > prevRms * 1.15
        prevRms = rms
        if (!onset) return null
        val f0 = pitch() ?: return null
        // Hauteur de la voix -> note MIDI, deux octaves plus haut (le haut-parleur du Roomba est meilleur dans l'aigu).
        val note = (69 + 12 * ln(f0 / 440.0) / ln(2.0) + 24).roundToInt().coerceIn(60, 100)
        // Jamais deux fois exactement la même note de suite : ça sonne plus « parlé ».
        val n = if (note == lastNote) note + 2 else note
        lastNote = n
        return n
    }

    /** Hauteur de la voix (Hz) par autocorrélation, entre 70 et 400 Hz ; null si pas de voix nette. */
    private fun pitch(): Double? {
        val lo = sampleRate / 400
        val hi = sampleRate / 70
        val n = window.size
        var e0 = 0.0
        for (v in window) e0 += v * v
        if (e0 <= 0) return null
        var best = 0.0; var lag = 0
        for (k in lo..minOf(hi, n - 1)) {
            var s = 0.0
            for (j in 0 until n - k) s += window[j] * window[j + k]
            if (s > best) { best = s; lag = k }
        }
        return if (lag > 0 && best > 0.3 * e0) sampleRate.toDouble() / lag else null
    }
}
