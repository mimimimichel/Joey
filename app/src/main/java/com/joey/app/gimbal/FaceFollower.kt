package com.joey.app.gimbal

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * La tête suit le visage vu par la caméra : on pousse le joystick pour ramener le visage au centre de l'image.
 * Le sens (gauche/droite, haut/bas) se règle tout seul : si le visage s'éloigne du centre pendant qu'on tourne,
 * c'est qu'on tourne à l'envers, on inverse (et on le retient).
 *
 * [onFace] est appelé à chaque image (~10 par seconde) avec la position du visage dans l'image, de -1 à 1
 * (x : vers la droite de l'image, y : vers le bas), ou null si personne.
 */
class FaceFollower(
    private val link: GimbalLink,
    private val now: () -> Long = { System.currentTimeMillis() },
    initialPanSign: Int = 1,
    initialTiltSign: Int = 1,
    /** Sens déjà confirmés lors d'une utilisation précédente. */
    initialConfirmed: Boolean = false,
    private val onSignsChanged: (pan: Int, tilt: Int) -> Unit = { _, _ -> },
    private val onConfirmed: () -> Unit = {},
    private val onLog: (String) -> Unit = {},
) {
    @Volatile var enabled = true
    @Volatile var panSign = initialPanSign
        private set
    @Volatile var tiltSign = initialTiltSign
        private set
    /** Pas de suivi avant cette heure (pendant un mouvement demandé par Joey ou à la main). */
    @Volatile var pausedUntil = 0L
    /** Dernier visage vu (pour les yeux), et quand. */
    @Volatile var faceX = 0f
        private set
    @Volatile var faceY = 0f
        private set
    @Volatile var seenAt = 0L
        private set

    private var driving = false
    private val pan = Axis("gauche/droite", initialConfirmed)
    private val tilt = Axis("haut/bas", initialConfirmed)

    val sees: Boolean get() = now() - seenAt < 800

    fun pause(ms: Long) {
        pausedUntil = maxOf(pausedUntil, now() + ms)
        if (driving) { link.stick(0, 0); driving = false }
        pan.reset(); tilt.reset()
    }

    /**
     * Vitesse joystick pour un écart dans l'image : zone morte au centre, et la SPG ne bouge pas sous ~110.
     * Tant que le sens n'est pas confirmé, on tourne doucement (pour ne pas perdre le visage si c'est à l'envers).
     */
    private fun speed(e: Float, sure: Boolean): Int {
        val m = abs(e)
        if (m < 0.08f) return 0
        return (sign(e) * min(if (sure) 300f else 210f, 135f + 260f * m)).toInt()
    }

    fun onFace(x: Float?, y: Float?) {
        val t = now()
        if (x != null && y != null) {
            faceX = x; faceY = y; seenAt = t
        }
        if (!enabled || !link.ready || t < pausedUntil) return
        if (x == null || y == null) {
            if (driving && t - seenAt > 600) { link.stick(0, 0); driving = false; pan.reset(); tilt.reset() }
            return
        }
        // Visage plus haut que le centre (y < 0) : lever la tête (+).
        val ex = x
        val ey = -y
        val p = speed(ex, pan.confirmed)
        val q = speed(ey, tilt.confirmed)
        if (pan.check(ex, p != 0, t)) { panSign = -panSign; onSignsChanged(panSign, tiltSign) }
        if (tilt.check(ey, q != 0, t)) { tiltSign = -tiltSign; onSignsChanged(panSign, tiltSign) }
        val cmdPan = p * panSign
        val cmdTilt = q * tiltSign
        if (cmdPan != 0 || cmdTilt != 0) {
            link.stick(cmdPan, cmdTilt)
            driving = true
        } else if (driving) {
            link.stick(0, 0)
            driving = false
        }
    }

    /**
     * Surveille un axe : si l'écart grandit pendant qu'on pousse, le sens est faux. Vérifié vite (0,3 s) tant que le
     * sens n'est pas confirmé ; ensuite il faut une preuve plus nette (la personne peut bouger elle aussi).
     */
    private inner class Axis(val name: String, @Volatile var confirmed: Boolean) {
        private var startAt = 0L
        private var startErr = 0f

        fun reset() { startAt = 0L }

        /** true s'il faut inverser le sens. */
        fun check(err: Float, pushing: Boolean, t: Long): Boolean {
            if (!pushing) { startAt = 0L; return false }
            if (startAt == 0L || sign(err) != sign(startErr)) { startAt = t; startErr = err; return false }
            if (t - startAt < (if (confirmed) 700 else 300)) return false
            val grew = abs(err) - abs(startErr)
            val wrong = grew > (if (confirmed) 0.2f else 0.07f)
            if (!confirmed && grew < -0.1f) {
                confirmed = true
                onLog("Suivi du visage : sens $name confirmé.")
                if (pan.confirmed && tilt.confirmed) onConfirmed()
            }
            startAt = t; startErr = err
            if (wrong) {
                confirmed = false
                onLog("Suivi du visage : sens $name inversé (corrigé tout seul).")
            }
            return wrong
        }
    }
}
