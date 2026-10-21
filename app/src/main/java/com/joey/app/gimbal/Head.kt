package com.joey.app.gimbal

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * La tête de Joey : mouvements de haut niveau (regarder à gauche, oui, non, balayage) sur la nacelle.
 * Boucle fermée sur les angles renvoyés par la nacelle ; sans angles, mouvement à l'aveugle (durée estimée).
 * Les fonctions bloquent le temps du mouvement : à appeler hors du fil principal.
 *
 * Repère de Joey : pan en degrés (+ = sa droite ; [rightSign] dit si c'est le joystick +),
 * inclinaison en degrés, 0 = droit devant, + = vers le haut POUR LA CAMÉRA QUI REGARDE ([tiltSign] = -1 avec la caméra
 * avant : la nacelle raisonne pour la caméra arrière, et quand celle-ci monte, la caméra avant descend).
 */
class Head(
    private val link: GimbalLink,
    /** +1 si joystick + = droite, -1 sinon (réglage « inverser gauche/droite »). */
    private val rightSign: () -> Int = { 1 },
    /** -1 quand Joey regarde avec la caméra avant (côté écran) : haut et bas sont inversés pour la nacelle. */
    private val tiltSign: () -> Int = { 1 },
    /** Valeur maximale du joystick envoyée (vitesse max de la tête). La capture montre ±314 au manche ; on va au-delà. */
    private val maxStick: () -> Int = { 600 },
    private val onLog: (String) -> Unit = {},
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        // La SPG incline sur 320° : on va de tout en bas à tout en haut.
        const val TILT_MIN = -90.0
        const val TILT_MAX = 90.0
        /** Ramène un angle entre -180 et +180. */
        fun wrap(a: Double): Double {
            var x = a % 360.0
            if (x > 180) x -= 360
            if (x < -180) x += 360
            return x
        }
    }

    /** Pan de référence « devant » (pris au recentrage, ou au premier mouvement). */
    @Volatile private var homePan: Double? = null
    @Volatile var moving = false
        private set
    /** Direction du dernier mouvement (-1..1), pour que les yeux suivent. */
    @Volatile var gazeX = 0f
        private set
    @Volatile var gazeY = 0f
        private set
    @Volatile private var cancelled = false
    /** Plafond découvert : si la nacelle ignore les valeurs au-delà de ce qu'on a vu (±314), on redescend. */
    @Volatile var stickCap = Int.MAX_VALUE
        private set

    private fun fresh(): SpgProtocol.Angles? = link.angles?.takeIf { now() - link.anglesAt < 1500 }
    /** Inclinaison pour la nacelle (caméra arrière) : elle donne ±180 quand la tête est droite. */
    fun tiltOf(a: SpgProtocol.Angles) = wrap(a.tilt - 180.0)
    /** Inclinaison pour la caméra qui regarde (+ = Joey regarde vers le haut). */
    fun viewTilt(a: SpgProtocol.Angles) = tiltOf(a) * tiltSign()

    /** Où regarde la tête, relativement à « devant » : (droite+, haut+), ou null si inconnu. */
    fun pose(): Pair<Double, Double>? {
        val a = fresh() ?: return null
        val home = homePan ?: a.pan
        return Pair(wrap(a.pan - home) * rightSign(), viewTilt(a))
    }

    fun stop() {
        cancelled = true
        link.stick(0, 0)
    }

    /** Recentre la tête (commande de la nacelle) et note la nouvelle direction « devant ». */
    fun center(): String {
        cancelled = false
        link.stick(0, 0)
        link.recenter()
        gazeX = 0f; gazeY = 0f
        sleep(1200)
        fresh()?.let { homePan = it.pan }
        return "tête droite devant"
    }

    /**
     * Vitesse joystick pour un écart donné : à fond tant qu'on est loin, on freine dans les ~25 derniers degrés.
     * Sous ~110 la SPG ne bouge presque pas.
     */
    private fun speed(err: Double, max: Int): Int {
        val m = abs(err)
        val gain = (max - 120.0) / 25.0
        val s = min(max.toDouble(), 120.0 + gain * m).toInt()
        return (sign(err) * s).toInt()
    }

    /**
     * Tourne de [panRight] degrés vers la droite (négatif = gauche) et/ou incline à [tilt] degrés (absolu, + = haut).
     * null = axe inchangé. Renvoie un résumé.
     */
    fun look(panRight: Double?, tilt: Double?, maxSpeed: Int = maxStick(), timeoutMs: Long = 6000): String {
        cancelled = false
        if (!link.ready) return "nacelle non connectée"
        val start = fresh()
        if (start == null) return blindLook(panRight, tilt, maxSpeed)
        if (homePan == null) homePan = start.pan
        val speedMax = min(maxSpeed, stickCap)
        val panTarget = panRight?.let { start.pan + it * rightSign() }
        val tiltTarget = tilt?.coerceIn(TILT_MIN, TILT_MAX)
        moving = true
        gazeX = (panRight?.let { sign(it) } ?: 0.0).toFloat()
        gazeY = (tiltTarget?.let { sign(it - viewTilt(start)) } ?: 0.0).toFloat()
        val ts = tiltSign()
        val t0 = now()
        var fastest = 0.0
        var prev: SpgProtocol.Angles? = null
        var prevAt = 0L
        try {
            while (!cancelled && now() - t0 < timeoutMs) {
                val a = fresh() ?: break
                // Vitesse réelle mesurée (pour régler la vitesse max) :
                if (prev != null && link.anglesAt > prevAt) {
                    val dt = (link.anglesAt - prevAt) / 1000.0
                    if (dt > 0.05) fastest = maxOf(fastest, abs(wrap(a.pan - prev.pan)) / dt)
                }
                if (link.anglesAt != prevAt) { prev = a; prevAt = link.anglesAt }
                val ePan = panTarget?.let { wrap(it - a.pan) } ?: 0.0
                val eTilt = tiltTarget?.let { it - viewTilt(a) } ?: 0.0
                val panDone = abs(ePan) < 2.5
                val tiltDone = abs(eTilt) < 2.5
                if (panDone && tiltDone) break
                link.stick(if (panDone) 0 else speed(ePan, speedMax), if (tiltDone) 0 else speed(eTilt, speedMax) * ts)
                // Sécurité : poussé fort 0,6 s sans que rien ne bouge -> la nacelle refuse ces valeurs, on redescend à 314.
                if (speedMax > 314 && now() - t0 > 600 && now() - t0 < 700 &&
                    abs(wrap(a.pan - start.pan)) < 1.0 && abs(a.tilt - start.tilt) < 1.0) {
                    stickCap = 314
                    onLog("La nacelle ne réagit pas au-delà de 314 : vitesse max ramenée à 314.")
                    return look(panRight, tilt, 314, timeoutMs)
                }
                sleep(50)
            }
        } finally {
            link.stick(0, 0)
            moving = false
        }
        if (fastest > 5 && panRight != null && abs(panRight) >= 60) onLog("Vitesse mesurée : %.0f°/s (joystick %d)".format(fastest, speedMax))
        sleep(150)
        val p = pose() ?: return "fait"
        return "tête à %.0f° %s, inclinée à %.0f°".format(abs(p.first), if (p.first >= 0) "à droite" else "à gauche", p.second)
    }

    /** Sans angles : on pousse le joystick le temps estimé (≈30°/s en pan, ≈18°/s en inclinaison ; prudent). */
    private fun blindLook(panRight: Double?, tilt: Double?, maxSpeedAsked: Int): String {
        val maxSpeed = min(maxSpeedAsked, 314)
        moving = true
        try {
            panRight?.let {
                link.stick((sign(it) * rightSign() * maxSpeed).toInt(), 0)
                sleep((abs(it) / 30.0 * 1000).toLong())
                link.stick(0, 0)
            }
            tilt?.let {
                link.stick(0, (sign(it) * tiltSign() * maxSpeed).toInt())
                sleep((abs(it) / 18.0 * 1000).toLong())
                link.stick(0, 0)
            }
        } finally {
            link.stick(0, 0)
            moving = false
        }
        return "fait (sans retour d'angle)"
    }

    /** Regarde dans une direction, par rapport à « devant ». */
    fun lookDirection(direction: String, degrees: Double?): String {
        val p = pose()
        val curPan = p?.first ?: 0.0
        return when (direction.lowercase()) {
            "gauche" -> look(-(degrees ?: 45.0), null)
            "droite" -> look(degrees ?: 45.0, null)
            "haut" -> look(null, (p?.second ?: 0.0) + (degrees ?: 25.0))
            "bas" -> look(null, (p?.second ?: 0.0) - (degrees ?: 25.0))
            "derriere" -> look(Head.wrap(180.0 - curPan).let { if (abs(it) > 179) 179.0 else it }, null, timeoutMs = 8000)
            "devant" -> if (homePan == null) center() else look(-curPan, 0.0)
            else -> "direction inconnue : $direction"
        }
    }

    /**
     * Cherche quelqu'un du regard : d'abord là où il regarde, puis tout autour (360°), un peu en haut puis à hauteur.
     * [seenSince] : un visage a-t-il été vu depuis cette heure ? Renvoie true dès qu'un visage est trouvé.
     */
    fun searchTarget(seenSince: (Long) -> Boolean): Boolean =
        scan(listOf(0.0, -45.0, 45.0, -90.0, 90.0, -135.0, 135.0, 179.0), listOf(25.0, 0.0)) { t ->
            sleep(350) // le temps que la caméra voie
            seenSince(t)
        }

    /**
     * Fait le tour du regard : d'abord sans bouger, puis chaque direction de [pans] (par rapport à « devant ») pour
     * chaque inclinaison de [tilts]. À chaque arrêt, [probe] (reçoit l'heure de l'arrêt) dit si c'est trouvé.
     */
    fun scan(pans: List<Double>, tilts: List<Double>, probe: (Long) -> Boolean): Boolean {
        cancelled = false
        if (probe(now())) return true
        for (tilt in tilts) {
            for (target in pans) {
                if (cancelled) return false
                val cur = pose()?.first ?: 0.0
                look(wrap(target - cur), tilt, timeoutMs = 3000)
                if (probe(now())) {
                    onLog("Trouvé en regardant à %.0f°, incliné à %.0f°.".format(target, tilt))
                    return true
                }
            }
        }
        return false
    }

    /**
     * Tourne d'un angle donné dans le sens du joystick (pas forcément « droite ») : sert à centrer quelque chose vu dans
     * l'image, avec les sens appris par le suivi (image -> joystick).
     */
    fun turnStick(panStickDeg: Double, tiltStickDeg: Double): String =
        look(panStickDeg * rightSign(), (pose()?.second ?: 0.0) + tiltStickDeg * tiltSign(), timeoutMs = 3000)

    /** Hoche la tête (oui). */
    fun nod(): String {
        val base = pose()?.second ?: 0.0
        repeat(2) {
            look(null, base - 15, timeoutMs = 900)
            look(null, base + 8, timeoutMs = 900)
        }
        look(null, base, timeoutMs = 1500)
        return "oui de la tête"
    }

    /** Secoue la tête (non). */
    fun shake(): String {
        repeat(2) {
            look(-20.0, null, timeoutMs = 900)
            look(40.0, null, timeoutMs = 1000)
            look(-20.0, null, timeoutMs = 900)
        }
        return "non de la tête"
    }

    /** Petite hésitation : penche d'un côté puis revient. */
    fun hesitate(): String {
        look(-15.0, null, maxSpeed = minOf(maxStick(), 300), timeoutMs = 1200)
        sleep(300)
        look(15.0, null, maxSpeed = minOf(maxStick(), 300), timeoutMs = 1200)
        return "hésitation"
    }

    /** Balaye la pièce : gauche, devant, droite, puis devant. [atStop] est appelé à chaque arrêt (pour envoyer une image). */
    fun sweep(amplitude: Double = 70.0, atStop: (String) -> Unit): String {
        val stops = listOf("à gauche" to -amplitude, "devant" to 0.0, "à droite" to amplitude)
        for ((name, target) in stops) {
            if (cancelled) break
            val cur = pose()?.first ?: 0.0
            look(target - cur, 0.0)
            sleep(400)
            atStop(name)
            sleep(300)
        }
        val cur = pose()?.first ?: 0.0
        look(-cur, 0.0)
        return "j'ai regardé à gauche, devant et à droite"
    }
}
