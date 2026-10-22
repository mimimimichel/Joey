package com.joey.app.life

/**
 * Un bruit fort et soudain (porte qui claque, verre qui tombe, clap) : montée brutale bien au-dessus du bruit de fond,
 * puis retombée rapide (moins de 0,35 s). La parole monte aussi, mais elle dure : elle ne compte pas.
 * [jump] : écart au-dessus du fond (dB) ; [floor] : niveau minimum du pic.
 */
class Startle(private val jump: Double, private val floor: Double, private val now: () -> Long = { System.currentTimeMillis() }) {
    private var background = Double.NaN
    private var onsetAt = 0L
    private var peak = Double.NEGATIVE_INFINITY
    private var lastFired = 0L

    /** Un niveau (dB). Renvoie true quand un bruit soudain vient de se terminer. */
    fun feed(db: Double): Boolean {
        val t = now()
        if (background.isNaN()) { background = db; return false }
        if (onsetAt == 0L) {
            if (db > background + jump && db > floor) { onsetAt = t; peak = db; return false }
            background += (db - background) * 0.05 // le fond suit doucement
            return false
        }
        peak = maxOf(peak, db)
        val dur = t - onsetAt
        if (db < peak - jump * 0.6 && db < background + jump * 0.5) {
            onsetAt = 0L
            if (dur <= 350 && t - lastFired > 3000) { lastFired = t; return true }
            return false
        }
        if (dur > 350) { // ça dure : c'est de la parole ou de la musique
            if (dur > 1500) { onsetAt = 0L; background += (db - background) * 0.2 }
        }
        return false
    }
}
