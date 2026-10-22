package com.joey.app.life

import java.util.Calendar

/**
 * Une humeur qui dure (pas seulement l'expression du moment) :
 * - énergie : selon l'heure (en forme en journée, fatigué tard le soir et au réveil) ;
 * - joie : monte quand on joue avec lui, qu'on lui sourit, qu'on le félicite ; redescend doucement ;
 * - solitude : monte quand il ne voit personne pendant longtemps (il est content de te revoir… ou boude un peu).
 */
class Temperament(private val now: () -> Long = { System.currentTimeMillis() }) {
    @Volatile var joy = 0.5
        private set
    @Volatile private var lastPersonAt = now()
    @Volatile private var lastUpdate = now()

    /** Énergie 0..1 selon l'heure. */
    fun energy(at: Long = now()): Double {
        val c = Calendar.getInstance().apply { timeInMillis = at }
        val h = c.get(Calendar.HOUR_OF_DAY) + c.get(Calendar.MINUTE) / 60.0
        return when {
            h < 7 -> 0.15
            h < 9 -> 0.15 + (h - 7) / 2 * 0.65     // il se réveille
            h < 21 -> 0.8 + if (h in 13.5..15.0) -0.15 else 0.0 // petit coup de mou après manger
            h < 23.5 -> 0.8 - (h - 21) / 2.5 * 0.6 // il fatigue
            else -> 0.15
        }
    }

    /** Solitude 0..1 : 0 s'il a vu quelqu'un il y a peu, 1 après ~3 h seul. */
    fun loneliness(at: Long = now()): Double = ((at - lastPersonAt) / (3 * 3600_000.0)).coerceIn(0.0, 1.0)

    /** Il voit quelqu'un. */
    fun sawPerson() { lastPersonAt = now() }

    /** Un moment agréable (jeu, sourire, compliment) [+] ou désagréable (secoué, grondé) [-]. */
    fun feel(delta: Double) { decay(); joy = (joy + delta).coerceIn(0.0, 1.0) }

    private fun decay() {
        val t = now()
        val k = ((t - lastUpdate) / (30 * 60_000.0)).coerceIn(0.0, 1.0) // ~30 min pour revenir à la normale
        joy += (0.5 - joy) * k
        lastUpdate = t
    }

    /** Pour la conversation : comment il se sent, en une phrase. */
    fun describe(): String {
        decay()
        val e = energy()
        val parts = ArrayList<String>()
        parts.add(when {
            e < 0.3 -> "tu es fatigué (il est tard), plus calme et un peu endormi"
            e < 0.6 -> "tu es un peu mou"
            else -> "tu es en forme"
        })
        parts.add(when {
            joy > 0.75 -> "de très bonne humeur"
            joy < 0.3 -> "un peu grognon"
            else -> "d'humeur égale"
        })
        val l = loneliness()
        if (l > 0.6) parts.add("tu es resté seul longtemps : tu es très content d'avoir de la compagnie (tu peux faire semblant de bouder deux secondes)")
        return parts.joinToString(", ")
    }
}
