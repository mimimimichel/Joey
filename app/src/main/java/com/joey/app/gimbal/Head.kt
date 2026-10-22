package com.joey.app.gimbal

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign

/**
 * Où regarde la caméra de Joey, mesuré par les capteurs du téléphone (gyroscope + accéléromètre), qui est posé sur
 * la nacelle : cap (degrés, + = vers la droite) et élévation (degrés, + = vers le haut, 0 = horizon).
 */
interface Orientation {
    /** (cap, élévation), ou null si pas de mesure récente. */
    fun read(): Pair<Double, Double>?
}

/**
 * La tête de Joey : mouvements de haut niveau (regarder à gauche, oui, non, chercher, suivre un casque) sur la nacelle.
 * Boucle fermée sur l'orientation : celle du téléphone si possible (rapide, vraie verticale), sinon les angles de la
 * nacelle ; sans rien, mouvement à l'aveugle (durée estimée). Les fonctions bloquent le temps du mouvement : à appeler
 * hors du fil principal.
 *
 * Repère de Joey : pan en degrés (+ = sa droite), inclinaison en degrés (0 = horizon, + = vers le haut).
 * Avec les angles de la nacelle seuls, [rightSign] et [tiltSign] disent dans quel sens va le joystick (la nacelle
 * raisonne pour la caméra arrière ; avec la caméra avant, haut et bas sont inversés). Avec le téléphone, les sens
 * sont mesurés par [calibrate].
 */
class Head(
    private val link: GimbalLink,
    /** Angles de la nacelle seuls : +1 si joystick + = droite, -1 sinon (réglage « inverser gauche/droite »). */
    private val rightSign: () -> Int = { 1 },
    /** Angles de la nacelle seuls : -1 quand Joey regarde avec la caméra avant. */
    private val tiltSign: () -> Int = { 1 },
    /** Part de la vitesse max mesurée à utiliser (réglage « vitesse de la tête », 0..1). */
    private val speedFraction: () -> Double = { 1.0 },
    private val onLog: (String) -> Unit = {},
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val now: () -> Long = { System.currentTimeMillis() },
    /** Capteurs du téléphone (null : pas de capteur). */
    private val phone: Orientation? = null,
    /** Sens du joystick mesurés avec le téléphone, à retenir (pan, tilt). */
    private val onPhoneSigns: (Int, Int) -> Unit = { _, _ -> },
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

    /** Sens du joystick pour faire tourner à droite / lever, mesurés avec le téléphone (0 = pas encore mesuré). */
    @Volatile var phonePanSign = 0
        private set
    @Volatile var phoneTiltSign = 0
        private set

    fun setPhoneSigns(pan: Int, tilt: Int) {
        phonePanSign = pan
        phoneTiltSign = tilt
    }

    /** Cap de référence « devant » (pris au recentrage, ou au premier mouvement), dans la source utilisée. */
    @Volatile private var homePan: Double? = null
    @Volatile private var homeFromPhone = false
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

    // ---- orientation ----------------------------------------------------------------------------
    private fun freshGimbal(): SpgProtocol.Angles? = link.angles?.takeIf { now() - link.anglesAt < 1500 }

    /** Inclinaison pour la nacelle (caméra arrière) : elle donne ±180 quand la tête est droite. */
    fun tiltOf(a: SpgProtocol.Angles) = wrap(a.tilt - 180.0)
    /** Inclinaison pour la caméra qui regarde, d'après la nacelle (+ = Joey regarde vers le haut). */
    fun viewTilt(a: SpgProtocol.Angles) = tiltOf(a) * tiltSign()

    /** Le téléphone est utilisé dès qu'il mesure (et que ses sens ont été appris). */
    val usesPhone: Boolean get() = phonePanSign != 0 && phoneTiltSign != 0 && phone?.read() != null

    /** Où regarde la tête (pan absolu dans la source, + = droite ; inclinaison, + = haut), ou null. */
    private fun cur(): Pair<Double, Double>? {
        if (usesPhone) {
            val p = phone!!.read() ?: return null
            if (!homeFromPhone) { homeFromPhone = true; homePan = p.first }
            return p
        }
        val a = freshGimbal() ?: return null
        if (homeFromPhone) { homeFromPhone = false; homePan = null }
        return Pair(a.pan * rightSign(), viewTilt(a))
    }

    /** Sens du joystick qui fait tourner à droite (+1/-1) et lever (+1/-1). */
    private fun panStick() = if (usesPhone) phonePanSign else rightSign()
    private fun tiltStick() = if (usesPhone) phoneTiltSign else tiltSign()

    /** Où regarde la tête, relativement à « devant » : (droite+, haut+), ou null si inconnu. */
    fun pose(): Pair<Double, Double>? {
        val c = cur() ?: return null
        val home = homePan ?: c.first.also { homePan = it }
        return Pair(wrap(c.first - home), c.second)
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
        cur()?.let { homePan = it.first }
        return "tête droite devant"
    }

    /** Cap absolu de la tête dans la source actuelle (+ = droite), pour retenir où on a vu quelqu'un. */
    fun absPan(): Double? = cur()?.first

    /** « Devant » = là où la tête regarde maintenant. */
    fun setHomeHere() { cur()?.let { homePan = it.first } }

    // ---- vitesses ---------------------------------------------------------------------------------
    /**
     * Vitesse réelle (°/s) pour chaque valeur du joystick, mesurée par [calibrate]. Par défaut : ce qu'on a vu dans
     * la capture (314 -> ~40°/s), en attendant la mesure.
     */
    @Volatile var table: List<Pair<Int, Double>> = listOf(0 to 0.0, 120 to 2.0, 314 to 40.0)
        private set

    fun setTable(t: List<Pair<Int, Double>>) {
        if (t.size >= 2) table = (listOf(0 to 0.0) + t.filter { it.first > 0 }).sortedBy { it.first }
    }

    /** La valeur du joystick la plus rapide (au-delà, la nacelle n'accélère plus, voire ralentit). */
    val bestStick: Int get() = table.maxByOrNull { it.second }?.first?.takeIf { it > 0 } ?: 314
    val bestSpeed: Double get() = table.maxOfOrNull { it.second }?.coerceAtLeast(5.0) ?: 40.0

    /** Valeur du joystick pour tourner à [degPerSec] (interpolée dans la table mesurée). */
    fun stickFor(degPerSec: Double): Int {
        val up = table.filter { it.first <= bestStick }.sortedBy { it.first }
        if (degPerSec >= bestSpeed) return bestStick
        for (i in 1 until up.size) {
            val (s0, v0) = up[i - 1]
            val (s1, v1) = up[i]
            if (degPerSec <= v1 && v1 > v0) return (s0 + (s1 - s0) * (degPerSec - v0) / (v1 - v0)).toInt().coerceAtLeast(120)
        }
        return bestStick
    }

    /** Vitesse réelle (°/s) pour une valeur du joystick. */
    fun speedOf(stick: Int): Double {
        val t = table.sortedBy { it.first }
        val a = abs(stick)
        for (i in 1 until t.size) {
            val (s0, v0) = t[i - 1]
            val (s1, v1) = t[i]
            if (a <= s1) return v0 + (v1 - v0) * (a - s0) / (s1 - s0).coerceAtLeast(1)
        }
        return t.last().second
    }

    /** Joystick max à utiliser, selon le réglage de vitesse. */
    fun maxStickNow(): Int = min(stickCap, stickFor(bestSpeed * speedFraction().coerceIn(0.2, 1.0)))

    /** Temps qu'il faut laisser pour tourner de [deg] degrés (avec de la marge). */
    private fun timeFor(deg: Double, stick: Int): Long =
        (1500 + 1000 * 1.8 * abs(deg) / speedOf(stick).coerceAtLeast(5.0)).toLong().coerceAtMost(20_000)

    /**
     * Vitesse joystick (sans le sens du joystick) pour un écart donné : à fond tant qu'on est loin, on freine sur la
     * distance parcourue en ~0,3 s à pleine vitesse (au moins 12°). Sous ~110 la SPG ne bouge presque pas.
     */
    private fun speed(err: Double, max: Int): Int {
        val m = abs(err)
        val brake = maxOf(12.0, speedOf(max) * 0.3)
        val s = min(max.toDouble(), 120.0 + (max - 120.0) * m / brake).toInt()
        return (sign(err) * s).toInt()
    }

    // ---- mouvements -------------------------------------------------------------------------------
    /**
     * Tourne de [panRight] degrés vers la droite (négatif = gauche) et/ou incline à [tilt] degrés (absolu, + = haut).
     * null = axe inchangé. Renvoie un résumé.
     */
    fun look(panRight: Double?, tilt: Double?, maxSpeed: Int = maxStickNow(), timeoutMs: Long? = null): String {
        cancelled = false
        if (!link.ready) return "nacelle non connectée"
        val start = cur() ?: return blindLook(panRight, tilt, maxSpeed)
        if (homePan == null) homePan = start.first
        val speedMax = min(maxSpeed, stickCap)
        val panTarget = panRight?.let { start.first + it }
        val tiltTarget = tilt?.coerceIn(TILT_MIN, TILT_MAX)
        val limit = timeoutMs ?: timeFor(maxOf(abs(panRight ?: 0.0), tiltTarget?.let { abs(it - start.second) } ?: 0.0), speedMax)
        moving = true
        gazeX = (panRight?.let { sign(it) } ?: 0.0).toFloat()
        gazeY = (tiltTarget?.let { sign(it - start.second) } ?: 0.0).toFloat()
        val ps = panStick()
        val ts = tiltStick()
        val t0 = now()
        try {
            while (!cancelled && now() - t0 < limit) {
                val c = cur() ?: break
                val ePan = panTarget?.let { wrap(it - c.first) } ?: 0.0
                val eTilt = tiltTarget?.let { it - c.second } ?: 0.0
                val panDone = abs(ePan) < 2.5
                val tiltDone = abs(eTilt) < 2.5
                if (panDone && tiltDone) break
                link.stick(if (panDone) 0 else speed(ePan, speedMax) * ps, if (tiltDone) 0 else speed(eTilt, speedMax) * ts)
                // Sécurité : poussé fort 0,6 s sans que rien ne bouge -> la nacelle refuse ces valeurs, on redescend à 314.
                if (speedMax > 314 && now() - t0 > 600 && now() - t0 < 700 &&
                    abs(wrap(c.first - start.first)) < 1.0 && abs(c.second - start.second) < 1.0) {
                    stickCap = 314
                    onLog("La nacelle ne réagit pas au-delà de 314 : vitesse max ramenée à 314.")
                    return look(panRight, tilt, 314)
                }
                sleep(40)
            }
        } finally {
            link.stick(0, 0)
            moving = false
        }
        sleep(120)
        val p = pose() ?: return "fait"
        return "tête à %.0f° %s, inclinée à %.0f°".format(abs(p.first), if (p.first >= 0) "à droite" else "à gauche", p.second)
    }

    /** Sans angles : on pousse le joystick le temps estimé (prudent). */
    private fun blindLook(panRight: Double?, tilt: Double?, maxSpeedAsked: Int): String {
        val maxSpeed = min(maxSpeedAsked, 314)
        moving = true
        try {
            panRight?.let {
                link.stick((sign(it) * rightSign() * maxSpeed).toInt(), 0)
                sleep((abs(it) / speedOf(maxSpeed).coerceAtLeast(5.0) * 1000).toLong())
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
            "derriere" -> look(wrap(180.0 - curPan).let { if (abs(it) > 179) 179.0 else it }, null)
            "devant" -> if (homePan == null) center() else look(-curPan, 0.0)
            else -> "direction inconnue : $direction"
        }
    }

    // ---- suivi continu (casque) -------------------------------------------------------------------
    @Volatile private var aimPan = 0.0
    @Volatile private var aimTilt = 0.0
    @Volatile private var aimAt = 0L
    @Volatile private var aiming = false

    /** Cible continue (relative à « devant ») : la tête la rejoint et la suit tant qu'on la met à jour. */
    fun aim(panRight: Double, tilt: Double) {
        aimPan = panRight
        aimTilt = tilt.coerceIn(TILT_MIN, TILT_MAX)
        aimAt = now()
    }

    /** Oublie la cible continue (un mouvement demandé prend la main). */
    fun stopAim() { aimAt = 0L }

    /** Un pas de suivi (appelé ~25 fois par seconde). true tant que la cible est récente. */
    fun aimStep(): Boolean {
        val active = now() - aimAt < 600 && link.ready
        if (!active) {
            if (aiming) { link.stick(0, 0); aiming = false; moving = false }
            return false
        }
        val p = pose()
        if (p == null) { link.stick(0, 0); return true }
        val max = maxStickNow()
        val ePan = wrap(aimPan - p.first)
        val eTilt = aimTilt - p.second
        val sp = if (abs(ePan) < 1.5) 0 else speed(ePan, max) * panStick()
        val st = if (abs(eTilt) < 1.5) 0 else speed(eTilt, max) * tiltStick()
        link.stick(sp, st)
        aiming = true
        moving = sp != 0 || st != 0
        gazeX = (sign(ePan) * min(1.0, abs(ePan) / 30)).toFloat()
        gazeY = (sign(eTilt) * min(1.0, abs(eTilt) / 30)).toFloat()
        return true
    }

    // ---- recherche --------------------------------------------------------------------------------
    /**
     * Cherche du regard (un visage, une personne, un objet : selon ce que suit la caméra) : d'abord là où il regarde,
     * puis un tour complet. [seenSince] : la cible a-t-elle été vue depuis cette heure ?
     */
    fun searchTarget(seenSince: (Long) -> Boolean, firstDir: Int = 1, stepDeg: Double = 40.0): Boolean {
        val t0 = now()
        return stepSearch({ seenSince(t0) }, firstDir = firstDir, stepDeg = stepDeg)
    }

    /**
     * Fait le tour par petits pas ([stepDeg], moins que le champ de la caméra pour ne rien rater), en s'arrêtant
     * [dwellMs] à chaque fois : les images sont nettes à l'arrêt (en tournant, elles sont floues et le détecteur rate).
     * D'abord à hauteur des yeux, puis plus bas. S'arrête dès que [found] dit oui ; la suite (centrer) est au suivi.
     */
    fun stepSearch(found: () -> Boolean, tilts: List<Double> = listOf(5.0, -30.0), stepDeg: Double = 40.0,
                   dwellMs: Long = 700, firstDir: Int = 1): Boolean {
        cancelled = false
        fun dwell(): Boolean {
            val t = now()
            while (!cancelled && now() - t < dwellMs) {
                if (found()) return true
                sleep(50)
            }
            return found()
        }
        if (dwell()) return true
        if (cur() == null) return false
        val step = stepDeg.coerceIn(15.0, 90.0)
        val steps = kotlin.math.ceil(360.0 / step).toInt() - 1
        var dir = if (firstDir < 0) -1 else 1
        for (tilt in tilts) {
            if (cancelled) return false
            look(null, tilt)
            if (dwell()) return true
            for (i in 1..steps) {
                if (cancelled) return false
                look(dir * step, null)
                if (dwell()) {
                    onLog("Trouvé après %.0f° (incliné à %.0f°).".format(i * step, tilt))
                    return true
                }
            }
            onLog("Tour à %.0f° d'inclinaison : rien.".format(tilt))
            dir = -dir
        }
        return false
    }

    /**
     * Fait un tour complet (360°) en tournant en continu pendant que la caméra analyse les images, d'abord à hauteur
     * des yeux, puis plus bas (sol, tables). S'arrête dès que [found] dit oui ; la suite (centrer) est au suivi.
     */
    fun spinSearch(found: () -> Boolean, tilts: List<Double> = listOf(5.0, -30.0), degPerSec: Double = 60.0, firstDir: Int = 1): Boolean {
        cancelled = false
        sleep(300) // une image ou deux là où il regarde déjà
        if (found()) return true
        if (cur() == null) return false
        val stick = stickFor(min(degPerSec, bestSpeed * 0.9))
        val limit = timeFor(360.0, stick) + 2000
        var dir = if (firstDir < 0) -1 else 1
        for (tilt in tilts) {
            if (cancelled) return false
            look(null, tilt)
            if (found()) return true
            var last = cur()?.first ?: return false
            var turned = 0.0
            val t0 = now()
            moving = true
            gazeX = dir.toFloat()
            try {
                while (!cancelled && turned < 365 && now() - t0 < limit) {
                    link.stick(stick * dir * panStick(), 0)
                    sleep(50)
                    val p = cur()?.first ?: continue
                    turned += abs(wrap(p - last))
                    last = p
                    if (found()) {
                        link.stick(0, 0)
                        onLog("Trouvé après %.0f° de tour (incliné à %.0f°).".format(turned, tilt))
                        return true
                    }
                }
            } finally {
                link.stick(0, 0)
                moving = false
            }
            onLog("Tour de %.0f° à %.0f° d'inclinaison : rien.".format(turned, tilt))
            dir = -dir
        }
        return false
    }

    /**
     * Fait le tour du regard par arrêts : d'abord sans bouger, puis chaque direction de [pans] (par rapport à « devant »)
     * pour chaque inclinaison de [tilts]. À chaque arrêt, [probe] (reçoit l'heure de l'arrêt) dit si c'est trouvé.
     */
    fun scan(pans: List<Double>, tilts: List<Double>, probe: (Long) -> Boolean): Boolean {
        cancelled = false
        if (probe(now())) return true
        for (tilt in tilts) {
            for (target in pans) {
                if (cancelled) return false
                val cur = pose()?.first ?: 0.0
                look(wrap(target - cur), tilt)
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
        look(panStickDeg * panStick(), (pose()?.second ?: 0.0) + tiltStickDeg * tiltStick())

    // ---- mesure -----------------------------------------------------------------------------------
    /**
     * Mesure la vitesse réelle de la tête pour plusieurs valeurs du joystick (gauche, droite, en alternance : la tête
     * revient à peu près au même endroit), et avec les capteurs du téléphone, le sens du joystick (droite, haut).
     * Prend ~10 s. La table sert ensuite à tout.
     */
    fun calibrate(): String {
        cancelled = false
        val ph = phone
        // Les vitesses et les sens se mesurent avec le téléphone s'il mesure, sinon avec la nacelle.
        val usePh = ph?.read() != null
        fun panNow(): Double? = if (usePh) ph!!.read()?.first else freshGimbal()?.pan
        if (panNow() == null) return "pas d'angles : mesure impossible"
        val res = ArrayList<Pair<Int, Double>>()
        var signVotes = 0
        var dir = 1
        moving = true
        val notes = StringBuilder()
        try {
            // Jusqu'à 6500 : la vitesse monte encore à 1000 (mesuré). On garde la plus rapide qui marche vraiment.
            for (v in listOf(300, 700, 1200, 2000, 3000, 4500, 6500)) {
                if (cancelled) break
                link.stick(v * dir, 0)
                sleep(350) // le temps d'accélérer
                val p1 = panNow() ?: break
                val t1 = now()
                sleep(700)
                val p2 = panNow() ?: break
                val t2 = now()
                link.stick(0, 0)
                sleep(300)
                val d = wrap(p2 - p1)
                if (abs(d) > 3) signVotes += if (sign(d) == sign(dir.toDouble())) 1 else -1
                res += v to abs(d) / ((t2 - t1) / 1000.0).coerceAtLeast(0.2)
                dir = -dir
            }
            if (usePh && !cancelled) {
                // Sens de l'inclinaison : un coup de joystick franc vers +, mesuré, puis retour.
                val tv = min(2000, res.maxByOrNull { it.second }?.first ?: 1200).coerceAtLeast(1000)
                val e1 = ph!!.read()?.second
                link.stick(0, tv)
                sleep(600)
                val e2 = ph.read()?.second
                link.stick(0, -tv)
                sleep(600)
                link.stick(0, 0)
                if (e1 != null && e2 != null && abs(e2 - e1) > 2 && signVotes != 0) {
                    val pan = if (signVotes > 0) 1 else -1
                    val tilt = if (e2 > e1) 1 else -1
                    phonePanSign = pan
                    phoneTiltSign = tilt
                    homePan = null
                    homeFromPhone = false
                    onPhoneSigns(pan, tilt)
                    notes.append(" ; capteurs du téléphone : joystick + = ${if (pan > 0) "droite" else "gauche"}, " +
                        if (tilt > 0) "haut" else "bas")
                } else {
                    notes.append(" ; sens non mesurés (mouvement trop faible : %.1f°)".format(if (e1 != null && e2 != null) abs(e2 - e1) else 0.0))
                }
            }
        } finally {
            link.stick(0, 0)
            moving = false
        }
        if (res.size < 2) return "mesure interrompue"
        setTable(res)
        stickCap = Int.MAX_VALUE
        return "vitesses mesurées : " + res.joinToString(", ") { "%d → %.0f°/s".format(it.first, it.second) } +
            " ; je garde %d (%.0f°/s)".format(bestStick, bestSpeed) + notes
    }

    // ---- gestes -----------------------------------------------------------------------------------
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
        look(-15.0, null, timeoutMs = 1200)
        sleep(300)
        look(15.0, null, timeoutMs = 1200)
        return "hésitation"
    }

    /** Balaye la pièce : gauche, devant, droite, puis devant. [atStop] est appelé à chaque arrêt (pour envoyer une image). */
    fun sweep(amplitude: Double = 90.0, atStop: (String) -> Unit): String {
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
