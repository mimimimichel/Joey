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
    /** Vitesse max du joystick (réglage). */
    private val maxStick: () -> Int = { 600 },
    /** Valeur du joystick pour une vitesse donnée (°/s), d'après la mesure de la tête. null : approximation. */
    private val stickFor: ((Double) -> Int)? = null,
) {
    @Volatile var enabled = true
    /**
     * Ne fait que noter où est le visage, sans toucher au joystick : la tête vise alors en degrés grâce à ses capteurs
     * (plus sûr que de deviner le sens en regardant l'image bouger).
     */
    @Volatile var observeOnly = false
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
    private var chasing = false
    private var chasePan = 0
    private var chaseTilt = 0
    private val pan = Axis("gauche/droite", initialConfirmed)
    private val tilt = Axis("haut/bas", initialConfirmed)

    val sees: Boolean get() = now() - seenAt < 800
    fun seenSince(t: Long) = seenAt > t
    /** Oublie la cible vue (on change de cible : visage -> objet). */
    fun forget() { seenAt = 0L }

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
        if (m < 0.06f) return 0
        val sf = stickFor
        if (sf != null) {
            // Écart en degrés (~34° du centre au bord de l'image), rattrapé en ~0,35 s ; plus doux tant que le sens est incertain.
            val deg = m * 34.0
            val want = if (sure) deg / 0.35 else minOf(deg / 0.6, 25.0)
            return (sign(e) * minOf(maxStick(), sf(maxOf(want, 4.0)))).toInt()
        }
        val max = if (sure) maxStick().toFloat() else minOf(maxStick(), 260).toFloat()
        // Visage au bord de l'image (|e| ≈ 0,6 et plus) : à fond.
        return (sign(e) * min(max, 130f + (max - 130f) * m / 0.6f)).toInt()
    }

    fun onFace(x: Float?, y: Float?) {
        val t = now()
        if (x != null && y != null) {
            faceX = x; faceY = y; seenAt = t
        }
        if (observeOnly) {
            if (driving) { link.stick(0, 0); driving = false }
            chasing = false
            return
        }
        if (!enabled || !link.ready || t < pausedUntil) return
        if (x == null || y == null) {
            if (driving && !chasing && t - seenAt < 400) {
                // Le visage sort de l'image pendant qu'on tourne et que l'écart grandissait : on tournait à l'envers.
                if (pan.lostWhileGrowing()) { panSign = -panSign; onSignsChanged(panSign, tiltSign) }
                if (tilt.lostWhileGrowing()) { tiltSign = -tiltSign; onSignsChanged(panSign, tiltSign) }
                // On continue vers là où il a disparu (dans le bon sens), le temps de le retrouver.
                chasePan = if (abs(faceX) > 0.3f) speed(faceX, true) * panSign else 0
                chaseTilt = if (abs(faceY) > 0.3f) speed(-faceY, true) * tiltSign else 0
                chasing = true
            }
            if (chasing && t - seenAt < 1300 && (chasePan != 0 || chaseTilt != 0)) {
                link.stick(chasePan, chaseTilt)
                return
            }
            if (driving) { link.stick(0, 0); driving = false; pan.reset(); tilt.reset() }
            chasing = false
            return
        }
        chasing = false
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
        private var started = false
        private var startAt = 0L
        private var startErr = 0f
        private var lastErr = 0f

        fun reset() { started = false }

        /** Visage perdu : il partait vers le bord (écart nettement plus grand qu'au début de la poussée, près du bord). */
        fun lostWhileGrowing(): Boolean {
            if (!started) return false
            val wrong = abs(lastErr) > 0.7f && abs(lastErr) > abs(startErr) + 0.15f
            started = false
            if (wrong) {
                confirmed = false
                onLog("Suivi du visage : sens $name inversé (visage sorti de l'image).")
            }
            return wrong
        }

        /** true s'il faut inverser le sens. */
        fun check(err: Float, pushing: Boolean, t: Long): Boolean {
            lastErr = err
            if (!pushing) { started = false; return false }
            if (!started || sign(err) != sign(startErr)) { started = true; startAt = t; startErr = err; return false }
            if (t - startAt < (if (confirmed) 800 else 450)) return false
            val grew = abs(err) - abs(startErr)
            // Il faut une preuve nette : la détection du visage tremble un peu, et la personne bouge aussi.
            val wrong = grew > (if (confirmed) 0.3f else 0.15f)
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
