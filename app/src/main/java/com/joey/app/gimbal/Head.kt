package com.joey.app.gimbal

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * La tête de Joey : mouvements de haut niveau (regarder à gauche, oui, non, balayage) sur la nacelle.
 * Boucle fermée sur les angles renvoyés par la nacelle ; sans angles, mouvement à l'aveugle (durée estimée).
 * Les fonctions bloquent le temps du mouvement : à appeler hors du fil principal.
 *
 * Repère : pan en degrés (+ = sens où le joystick « + » fait tourner ; [rightSign] dit si c'est la droite),
 * inclinaison en degrés, 0 = droit devant, + = vers le haut.
 */
class Head(
    private val link: GimbalLink,
    /** +1 si joystick + = droite, -1 sinon (réglage « inverser gauche/droite »). */
    private val rightSign: () -> Int = { 1 },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        const val TILT_MIN = -40.0
        const val TILT_MAX = 60.0
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

    private fun fresh(): SpgProtocol.Angles? = link.angles?.takeIf { now() - link.anglesAt < 1500 }
    /** Inclinaison réelle : la nacelle donne ±180 quand la tête est droite. */
    fun tiltOf(a: SpgProtocol.Angles) = wrap(a.tilt - 180.0)

    /** Où regarde la tête, relativement à « devant » : (droite+, haut+), ou null si inconnu. */
    fun pose(): Pair<Double, Double>? {
        val a = fresh() ?: return null
        val home = homePan ?: a.pan
        return Pair(wrap(a.pan - home) * rightSign(), tiltOf(a))
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

    /** Vitesse joystick pour un écart donné : les petites valeurs ne font presque rien sur la SPG. */
    private fun speed(err: Double, max: Int): Int {
        val m = abs(err)
        val s = min(max.toDouble(), 140.0 + 8.0 * m).toInt()
        return (sign(err) * s).toInt()
    }

    /**
     * Tourne de [panRight] degrés vers la droite (négatif = gauche) et/ou incline à [tilt] degrés (absolu, + = haut).
     * null = axe inchangé. Renvoie un résumé.
     */
    fun look(panRight: Double?, tilt: Double?, maxSpeed: Int = 300, timeoutMs: Long = 6000): String {
        cancelled = false
        if (!link.ready) return "nacelle non connectée"
        val start = fresh()
        if (start == null) return blindLook(panRight, tilt, maxSpeed)
        if (homePan == null) homePan = start.pan
        val panTarget = panRight?.let { start.pan + it * rightSign() }
        val tiltTarget = tilt?.coerceIn(TILT_MIN, TILT_MAX)
        moving = true
        gazeX = (panRight?.let { sign(it) } ?: 0.0).toFloat()
        gazeY = (tiltTarget?.let { sign(it - tiltOf(start)) } ?: 0.0).toFloat()
        val t0 = now()
        try {
            while (!cancelled && now() - t0 < timeoutMs) {
                val a = fresh() ?: break
                val ePan = panTarget?.let { wrap(it - a.pan) } ?: 0.0
                val eTilt = tiltTarget?.let { it - tiltOf(a) } ?: 0.0
                val panDone = abs(ePan) < 2.5
                val tiltDone = abs(eTilt) < 2.5
                if (panDone && tiltDone) break
                link.stick(if (panDone) 0 else speed(ePan, maxSpeed), if (tiltDone) 0 else speed(eTilt, maxSpeed))
                sleep(50)
            }
        } finally {
            link.stick(0, 0)
            moving = false
        }
        sleep(250)
        val p = pose() ?: return "fait"
        return "tête à %.0f° %s, inclinée à %.0f°".format(abs(p.first), if (p.first >= 0) "à droite" else "à gauche", p.second)
    }

    /** Sans angles : on pousse le joystick le temps estimé (≈30°/s en pan, ≈18°/s en inclinaison). */
    private fun blindLook(panRight: Double?, tilt: Double?, maxSpeed: Int): String {
        moving = true
        try {
            panRight?.let {
                link.stick((sign(it) * rightSign() * maxSpeed).toInt(), 0)
                sleep((abs(it) / 30.0 * 1000).toLong())
                link.stick(0, 0)
            }
            tilt?.let {
                link.stick(0, (sign(it) * maxSpeed).toInt())
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
            "devant" -> if (homePan == null) center() else look(-curPan, 0.0)
            else -> "direction inconnue : $direction"
        }
    }

    /** Hoche la tête (oui). */
    fun nod(): String {
        val base = pose()?.second ?: 0.0
        repeat(2) {
            look(null, base - 12, maxSpeed = 320, timeoutMs = 1200)
            look(null, base + 6, maxSpeed = 320, timeoutMs = 1200)
        }
        look(null, base, timeoutMs = 1500)
        return "oui de la tête"
    }

    /** Secoue la tête (non). */
    fun shake(): String {
        repeat(2) {
            look(-18.0, null, maxSpeed = 320, timeoutMs = 1200)
            look(36.0, null, maxSpeed = 320, timeoutMs = 1500)
            look(-18.0, null, maxSpeed = 320, timeoutMs = 1200)
        }
        return "non de la tête"
    }

    /** Petite hésitation : penche d'un côté puis revient. */
    fun hesitate(): String {
        look(-15.0, null, maxSpeed = 220, timeoutMs = 1500)
        sleep(400)
        look(15.0, null, maxSpeed = 220, timeoutMs = 1500)
        return "hésitation"
    }

    /** Balaye la pièce : gauche, devant, droite, puis devant. [atStop] est appelé à chaque arrêt (pour envoyer une image). */
    fun sweep(amplitude: Double = 70.0, atStop: (String) -> Unit): String {
        val stops = listOf("à gauche" to -amplitude, "devant" to 0.0, "à droite" to amplitude)
        for ((name, target) in stops) {
            if (cancelled) break
            val cur = pose()?.first ?: 0.0
            look(target - cur, 0.0, maxSpeed = 260)
            sleep(700)
            atStop(name)
            sleep(500)
        }
        val cur = pose()?.first ?: 0.0
        look(-cur, 0.0)
        return "j'ai regardé à gauche, devant et à droite"
    }
}
