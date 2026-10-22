package com.joey.app.life

import kotlin.math.exp
import kotlin.math.hypot

/**
 * Mouvements qu'on lui fait subir (le téléphone sur la nacelle sur le Roomba) :
 * - soulevé / reposé : vitesse verticale estimée (accélération intégrée, avec fuite pour ne pas dériver) ;
 * - poussé, déplacé : vitesse horizontale alors que ses roues ne bougent pas ;
 * - chute : quasi apesanteur (le téléphone tombe).
 * Accélérations dans le repère du monde (z vers le haut), sans la pesanteur, en m/s².
 */
class BodyMotion(private val now: () -> Long = { System.currentTimeMillis() }) {
    enum class Event { LIFTED, PUT_DOWN, PUSHED, FALLING }

    private var vz = 0.0
    private var vx = 0.0
    private var vy = 0.0
    private var lastAt = 0L
    private var upSince = 0L
    private var downSince = 0L
    private var pushSince = 0L
    private var lifted = false
    private var liftedAt = 0L
    private var lastEvent = HashMap<Event, Long>()
    private var lowGSince = 0L
    /** Pas de détection jusqu'à cette heure (ses roues bougent, on le secoue…). */
    @Volatile var quietUntil = 0L

    private fun fire(e: Event, t: Long, gap: Long): Event? {
        if (lastEvent[e]?.let { t - it < gap } == true) return null
        lastEvent[e] = t
        return e
    }

    /** Accélération linéaire (monde, sans pesanteur). */
    fun feed(ax: Double, ay: Double, az: Double): Event? {
        val t = now()
        val dt = if (lastAt == 0L) 0.0 else ((t - lastAt) / 1000.0).coerceIn(0.0, 0.1)
        lastAt = t
        val leak = exp(-dt / 0.5)
        // Le bruit des capteurs sous 0,25 m/s² est ignoré (sinon la vitesse dérive).
        fun dead(a: Double) = if (kotlin.math.abs(a) < 0.25) 0.0 else a
        vz = (vz + dead(az) * dt) * leak
        vx = (vx + dead(ax) * dt) * leak
        vy = (vy + dead(ay) * dt) * leak
        if (t < quietUntil) { vz = 0.0; vx = 0.0; vy = 0.0; upSince = 0; downSince = 0; pushSince = 0; return null }
        // Soulevé : il monte franchement pendant 0,15 s.
        if (vz > 0.3) { if (upSince == 0L) upSince = t } else upSince = 0L
        if (vz < -0.3) { if (downSince == 0L) downSince = t } else downSince = 0L
        if (!lifted && upSince != 0L && t - upSince >= 150) {
            lifted = true; liftedAt = t
            return fire(Event.LIFTED, t, 8000)
        }
        if (lifted && downSince != 0L && t - downSince >= 150 && t - liftedAt > 600) {
            lifted = false
            return fire(Event.PUT_DOWN, t, 4000)
        }
        if (lifted && t - liftedAt > 20_000) lifted = false
        // Poussé / déplacé : glisse à plat sans qu'il roule.
        if (!lifted && hypot(vx, vy) > 0.45 && kotlin.math.abs(vz) < 0.2) { if (pushSince == 0L) pushSince = t } else pushSince = 0L
        if (pushSince != 0L && t - pushSince >= 400) { pushSince = 0L; return fire(Event.PUSHED, t, 8000) }
        return null
    }

    /** Accélération brute (avec la pesanteur) : près de 0 = il tombe. */
    fun feedRaw(magnitude: Double): Event? {
        val t = now()
        if (magnitude < 3.0) {
            if (lowGSince == 0L) lowGSince = t
            if (t - lowGSince >= 90) { lowGSince = Long.MAX_VALUE; return fire(Event.FALLING, t, 5000) }
        } else lowGSince = 0L
        return null
    }
}
