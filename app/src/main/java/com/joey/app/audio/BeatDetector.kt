package com.joey.app.audio

/**
 * Repère les temps forts de la musique dans le son du micro (PCM 16 bits) : une fenêtre de 20 ms nettement plus
 * forte que la moyenne de la dernière seconde = un temps. Au plus un temps toutes les 280 ms (~210 BPM).
 * Donne aussi le tempo estimé (intervalle moyen entre les temps).
 */
class BeatDetector(private val sampleRate: Int = 16_000, private val now: () -> Long = { System.currentTimeMillis() }) {
    private val win = sampleRate / 50 // 20 ms
    private var acc = 0.0
    private var n = 0
    private var avg = 0.0
    private var lastBeat = 0L
    private val intervals = ArrayDeque<Long>()

    /** Intervalle moyen entre deux temps (ms), ou null. */
    val periodMs: Long? get() = if (intervals.size >= 3) intervals.sorted()[intervals.size / 2] else null

    /** Analyse un morceau de son ; [onBeat] est appelé à chaque temps fort. */
    fun feed(pcm: ShortArray, count: Int, onBeat: () -> Unit) {
        for (i in 0 until count) {
            val s = pcm[i] / 32768.0
            acc += s * s
            n++
            if (n == win) {
                val e = acc / win
                acc = 0.0; n = 0
                // Moyenne glissante sur ~1 s (50 fenêtres)
                avg = if (avg == 0.0) e else avg * 0.98 + e * 0.02
                val t = now()
                if (e > avg * 2.2 && e > 1e-4 && t - lastBeat > 280) {
                    if (lastBeat > 0) {
                        val d = t - lastBeat
                        if (d < 1500) { intervals.addLast(d); if (intervals.size > 12) intervals.removeFirst() }
                    }
                    lastBeat = t
                    onBeat()
                }
            }
        }
    }
}
