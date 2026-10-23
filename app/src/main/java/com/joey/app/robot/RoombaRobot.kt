package com.joey.app.robot

import com.joey.app.gimbal.Head
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Liaison série avec le Roomba (carte USB-série branchée au téléphone, ou fausse liaison pour les tests). */
interface SerialLink {
    fun write(b: ByteArray)
    /** Lit exactement [n] octets, ou null si rien de complet n'arrive en [timeoutMs]. */
    fun read(n: Int, timeoutMs: Int): ByteArray?
    fun clearInput()
    /** Ligne de réveil du Roomba (broche 5, BRC) : true = tirée vers le bas. */
    fun setWakeLine(low: Boolean)
    fun setBaud(baud: Int)
    fun close()
}

/**
 * Les roues de Joey : un Roomba série 600 piloté par sa prise de commande (repris du pilote de Bebop IA, sans le drone).
 * Mode sécurité pour rouler (il s'arrête tout seul au bord d'une marche ou s'il est soulevé), odométrie par les roues,
 * pare-chocs, retour à la base, petites mélodies et voyants pour s'exprimer.
 * Position : x devant au départ, y à droite (m), cap en degrés (+ = à droite).
 */
class RoombaRobot(
    private val link: SerialLink,
    private val log: (String) -> Unit = {},
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val io = Any()
    private val odo = Odometry()
    @Volatile var sensors: RoombaOI.Sensors? = null
        private set
    @Volatile var running = false
        private set
    @Volatile private var cancel = false
    @Volatile private var failures = 0
    @Volatile private var lastWake = 0L
    /** En train de rouler (mode sécurité demandé). */
    @Volatile var driving = false
        private set
    /** Dernier arrêt de sécurité (vide, roue soulevée), pour l'expliquer. */
    @Volatile var safetyNote: String? = null
        private set
    @Volatile private var vel = DoubleArray(2) // m/s, °/s
    @Volatile private var velUntil = 0L
    /** Le visage (téléphone) regarde vers l'arrière du Roomba : « avancer » = rouler côté visage. */
    @Volatile var reversed = false
        set(v) { field = v; odo.reversed = v }
    /** Correction des angles mesurés par les roues. */
    var turnScale: Double
        get() = odo.turnScale
        set(v) { odo.turnScale = v }

    /** Vitesse max en intérieur (m/s). */
    var maxSpeed = 0.25

    val x get() = odo.x
    val y get() = odo.y
    val heading get() = odo.headingDeg
    val battery: Int? get() = sensors?.batteryPct
    val onBase: Boolean get() = sensors?.onHomeBase == true
    val charging: Boolean get() = sensors?.charging == true

    // ---- liaison ------------------------------------------------------------------------------------
    private fun send(b: ByteArray) = synchronized(io) { link.write(b) }

    fun readSensors(): RoombaOI.Sensors? = synchronized(io) {
        link.clearInput()
        link.write(RoombaOI.querySensors())
        val raw = link.read(RoombaOI.SENSOR_BYTES, 250) ?: return null
        RoombaOI.parseSensors(raw)
    }

    fun wakePulse() {
        lastWake = System.currentTimeMillis()
        runCatching { link.setWakeLine(true); sleeper(500); link.setWakeLine(false) }
    }

    /** Réveille le Roomba, trouve la vitesse de liaison, lance la lecture des capteurs. Lève une exception claire sinon. */
    fun connect() {
        runCatching { link.setWakeLine(false) }
        wakePulse()
        sleeper(300)
        var s: RoombaOI.Sensors? = null
        var baud = 0
        for (b in listOf(115_200, 57_600)) {
            link.setBaud(b)
            sleeper(100)
            send(RoombaOI.start())
            sleeper(150)
            repeat(3) { if (s == null) s = readSensors() }
            if (s != null) { baud = b; break }
        }
        val ok = s ?: throw IllegalStateException(
            "la carte USB répond mais pas le Roomba. Vérifie : interrupteur de la carte sur 5V, TX -> trou 3, RX -> trou 4, " +
                "GND -> trou 6, et que le Roomba est allumé (appuie une fois sur CLEAN pour le réveiller, puis réessaie)")
        sensors = ok
        odo.update(ok.leftCounts, ok.rightCounts)
        log("Roomba : réponse à $baud bauds, batterie ${ok.batteryPct ?: "?"} %, mode ${ok.modeName}")
        running = true
        Thread(::pollLoop, "roomba-capteurs").apply { isDaemon = true; start() }
        Thread(::driveLoop, "roomba-roues").apply { isDaemon = true; start() }
        sing(Tune.HELLO)
    }

    fun disconnect() {
        running = false
        runCatching { send(RoombaOI.driveDirect(0, 0)); send(RoombaOI.start()) }
        runCatching { link.close() }
    }

    // ---- capteurs, odométrie, sécurité -------------------------------------------------------------------
    private fun pollLoop() {
        while (running) {
            sleeper(60)
            val s = runCatching { readSensors() }.getOrNull()
            val now = System.currentTimeMillis()
            if (s == null) {
                failures++
                // Réveil : souvent au début, puis toutes les ~30 s (il était éteint : 92 messages en 3 min dans le journal).
                if (failures == 10 || (failures > 10 && failures % 300 == 0)) {
                    if (failures == 10 || failures % 3000 == 0) log("Roomba : pas de réponse, je le réveille (est-il allumé ?)")
                    wakePulse(); runCatching { send(RoombaOI.start()) }
                }
                continue
            }
            failures = 0
            val prev = sensors
            sensors = s
            odo.update(s.leftCounts, s.rightCounts)
            // En route, il repasse en mode passif de lui-même (vide, roue soulevée, base) : on arrête tout.
            if (driving && prev?.oiMode == 2 && s.oiMode == 1) {
                safetyNote = when {
                    s.cliff -> "vide détecté sous moi (bord de marche ?)"
                    s.wheelDrop -> "roue soulevée (on m'a soulevé, ou je suis coincé)"
                    s.onHomeBase -> "posé sur ma base"
                    else -> "le Roomba a repris la main"
                }
                log("Roomba : arrêt de sécurité, $safetyNote")
                vacuuming = false
                stop()
            }
            // Au repos (mode passif), il s'endort au bout de 5 min : petite impulsion chaque minute.
            if (!driving && now - lastWake > 55_000) wakePulse()
        }
    }

    /** Dernière fois que les roues ont tourné (ou viennent de s'arrêter). */
    @Volatile var movingAt = 0L
        private set

    private fun wheels(forwardMS: Double, yawDegS: Double) {
        val f = if (reversed) -forwardMS else forwardMS
        if (forwardMS != 0.0 || yawDegS != 0.0 || System.currentTimeMillis() - movingAt < 500) movingAt = System.currentTimeMillis()
        val (r, l) = RoombaOI.wheelSpeeds(f, yawDegS)
        send(RoombaOI.driveDirect(r, l))
    }

    /** Prêt à rouler (mode sécurité ; quitte la base en reculant si besoin). null si c'est bon, sinon pourquoi pas. */
    fun ready(): String? {
        val s = sensors ?: readSensors() ?: return "le Roomba ne répond plus"
        // Seulement les roues soulevées : le mode sécurité du Roomba s'arrête tout seul au bord d'un vide, et le
        // « vide » vu à l'arrêt était souvent faux (tapis sombre, robot penché) : il refusait de rouler pour rien.
        if (s.wheelDrop) return "je ne roule pas : roue soulevée (on m'a soulevé ?)"
        if (!driving || s.oiMode != 2) {
            safetyNote = null
            send(RoombaOI.start())
            if (s.onHomeBase) {
                // Sur sa base, le mode sécurité le rendrait passif aussitôt : on recule d'abord en mode complet.
                log("Roomba : je quitte ma base")
                send(RoombaOI.full())
                sleeper(100)
                val end = System.currentTimeMillis() + 2500
                // Recule (sens du Roomba, quel que soit le montage du visage).
                while (System.currentTimeMillis() < end) { wheels(if (reversed) 0.12 else -0.12, 0.0); sleeper(100) }
                wheels(0.0, 0.0)
            }
            send(RoombaOI.safe())
            sleeper(80)
            driving = true
        }
        return null
    }

    /** Arrête tout (les roues ; il reste en mode sécurité). */
    fun stop() {
        cancel = true
        velUntil = 0L
        runCatching { wheels(0.0, 0.0) }
    }

    /** Fin de balade : roues arrêtées, mode passif (ses boutons remarchent). */
    fun park() {
        stop()
        if (vacuuming) vacuum(false)
        driving = false
        runCatching { send(RoombaOI.start()) }
    }

    // ---- déplacements -----------------------------------------------------------------------------------
    /** Tourne sur place de [deg] degrés (+ = à droite) d'après les roues. Renvoie un résumé. */
    fun turn(deg: Double): String {
        ready()?.let { return it }
        cancel = false
        if (abs(deg) < 1.5) return "déjà dans la bonne direction"
        // On compte ce qui a été tourné (et pas un cap à atteindre) : « un tour complet » (360°) marche aussi.
        var turned = 0.0
        var prev = odo.headingDeg
        val end = System.currentTimeMillis() + ((abs(deg) / 20.0 + 3) * 1000).toLong()
        while (!cancel && System.currentTimeMillis() < end) {
            val h = odo.headingDeg
            turned += Head.wrap(h - prev)
            prev = h
            val err = deg - turned
            if (abs(err) < 2.0) break
            val rate = (err * 3).coerceIn(-90.0, 90.0).let { if (abs(it) < 18) Math.signum(it) * 18 else it }
            wheels(0.0, rate)
            sleeper(50)
        }
        wheels(0.0, 0.0)
        return if (cancel) "interrompu" else "tourné de %.0f°".format(deg)
    }

    /** Avance (ou recule si négatif) de [dist] mètres en gardant son cap ; s'arrête au pare-chocs. */
    fun drive(dist: Double, blocked: () -> Boolean = { false }): String {
        ready()?.let { return it }
        cancel = false
        if (abs(dist) < 0.01) return "rien à faire"
        val start = odo.travelledM
        val h0 = odo.headingDeg
        val sign = Math.signum(dist)
        val end = System.currentTimeMillis() + ((abs(dist) / 0.05 + 3) * 1000).toLong()
        var result = "avancé de %.2f m".format(dist)
        val t0 = System.currentTimeMillis()
        try {
            while (!cancel && System.currentTimeMillis() < end) {
                val done = odo.travelledM - start
                val left = abs(dist) - done
                if (left < 0.01) break
                val s = sensors
                // Le Roomba s'est arrêté de lui-même (mode sécurité : vide, roue soulevée) : on le dit.
                if (s != null && s.oiMode == 1 && System.currentTimeMillis() - t0 > 400) {
                    result = "arrêté après %.2f m : ".format(done) + (safetyNote ?: when {
                        s.cliff -> "vide détecté sous moi (bord de marche ?)"
                        s.wheelDrop -> "roue soulevée"
                        else -> "le Roomba a repris la main"
                    })
                    break
                }
                // Le pare-chocs est à l'avant du Roomba (derrière Joey s'il est monté à l'envers).
                if ((sign > 0) != reversed && s != null && s.bump) {
                    wheels(0.0, 0.0)
                    val side = if (reversed) "derrière moi" else if (s.bumpLeft && s.bumpRight) "devant" else if (s.bumpLeft) "à gauche" else "à droite"
                    log("Roomba : pare-chocs touché $side")
                    val back = System.currentTimeMillis() + 500
                    while (System.currentTimeMillis() < back) { wheels(if (reversed) 0.1 else -0.1, 0.0); sleeper(50) }
                    result = "obstacle touché $side après %.2f m (j'ai reculé un peu)".format(done)
                    break
                }
                if (sign > 0 && blocked()) {
                    result = "arrêté après %.2f m : obstacle vu devant".format(done)
                    break
                }
                val v = (left * 1.5).coerceIn(0.06, maxSpeed) * sign
                val yaw = (Head.wrap(h0 - odo.headingDeg) * 2).coerceIn(-20.0, 20.0)
                wheels(v, yaw)
                sleeper(50)
            }
        } finally {
            wheels(0.0, 0.0)
        }
        return if (cancel) safetyNote?.let { "arrêté : $it" } ?: "interrompu" else result
    }

    /** Va au point (x, y) de l'odométrie : se tourne vers lui, puis avance. */
    fun goTo(tx: Double, ty: Double, blocked: () -> Boolean = { false }): String {
        val dx = tx - odo.x
        val dy = ty - odo.y
        val dist = hypot(dx, dy)
        if (dist < 0.1) return "j'y suis déjà"
        val dir = Math.toDegrees(atan2(dy, dx))
        turn(Head.wrap(dir - odo.headingDeg))
        if (cancel) return "interrompu"
        return drive(dist, blocked)
    }

    // ---- vitesses continues (suivre quelqu'un) -----------------------------------------------------------
    /** Avance à [forward] m/s en tournant à [yaw] °/s (+ = droite), pendant [holdMs] si rien d'autre n'arrive. */
    fun velocity(forward: Double, yaw: Double, holdMs: Long = 400) {
        vel = doubleArrayOf(forward.coerceIn(-maxSpeed, maxSpeed), yaw.coerceIn(-90.0, 90.0))
        velUntil = System.currentTimeMillis() + holdMs
    }

    private fun driveLoop() {
        var active = false
        while (running) {
            sleeper(80)
            val until = velUntil
            if (until == 0L || System.currentTimeMillis() > until) {
                if (active) { runCatching { wheels(0.0, 0.0) }; active = false }
                if (until != 0L && System.currentTimeMillis() > until) velUntil = 0L
                continue
            }
            if (!active && ready() != null) { velUntil = 0L; continue }
            var (f, yaw) = vel
            if ((f > 0) != reversed && f != 0.0 && sensors?.bump == true) f = 0.0 // pare-chocs : plus d'avance, il peut encore tourner
            runCatching { wheels(f, yaw) }
            active = true
        }
    }

    // ---- base de charge --------------------------------------------------------------------------------
    fun seekDock(): String {
        stop()
        driving = false
        runCatching { send(RoombaOI.start()); send(RoombaOI.seekDock()) }.onFailure { return "le Roomba ne répond pas" }
        return "je rentre à ma base de charge (je la cherche tout seul, ça peut prendre une ou deux minutes)"
    }

    // ---- s'exprimer : mélodies et voyants ---------------------------------------------------------------
    enum class Tune(val notes: List<Pair<Int, Int>>) {
        HELLO(listOf(72 to 10, 76 to 10, 79 to 10, 84 to 20)),
        HAPPY(listOf(72 to 8, 76 to 8, 79 to 8, 84 to 16)),
        SAD(listOf(76 to 16, 72 to 16, 67 to 24)),
        SURPRISED(listOf(67 to 6, 79 to 6, 91 to 14)),
        ANGRY(listOf(43 to 12, 41 to 12, 43 to 20)),
        HESITATE(listOf(74 to 6, 74 to 6, 71 to 14)),
        SLEEPY(listOf(64 to 20, 60 to 30)),
        YES(listOf(72 to 8, 79 to 12)),
        NO(listOf(72 to 8, 65 to 12)),
        R2D2(listOf(84 to 4, 88 to 4, 79 to 4, 91 to 6, 86 to 4, 95 to 8)),
    }

    fun sing(t: Tune) {
        if (!running) return
        runCatching { send(RoombaOI.song(2, t.notes)); send(RoombaOI.play(2)) }
    }

    private var chirpSlot = 3
    /** Une seule note courte (babil pendant que Joey parle) ; deux emplacements de mélodie en alternance. */
    fun chirp(note: Int, dur64: Int = 7) {
        if (!running) return
        chirpSlot = if (chirpSlot == 3) 4 else 3
        runCatching { send(RoombaOI.song(chirpSlot, listOf(note to dur64))); send(RoombaOI.play(chirpSlot)) }
    }

    // ---- aspiration ----------------------------------------------------------------------------------------
    /** Brosses et aspirateur en marche. */
    @Volatile var vacuuming = false
        private set

    /** Aspiration marche / arrêt (mode sécurité). Renvoie un résumé. */
    fun vacuum(on: Boolean): String {
        if (on) ready()?.let { return it }
        runCatching { send(RoombaOI.motors(on)) }.onFailure { return "le Roomba ne répond plus" }
        vacuuming = on
        return if (on) "aspiration en marche" else "aspiration arrêtée"
    }

    /** Manette : comme [velocity], mais jusqu'à [limit] m/s (plus vite que les déplacements tout seuls). */
    fun manual(forward: Double, yaw: Double, limit: Double = 0.5) {
        vel = doubleArrayOf(forward.coerceIn(-limit, limit), yaw.coerceIn(-180.0, 180.0))
        velUntil = System.currentTimeMillis() + 300
    }

    /** Couleur du bouton CLEAN (0 vert -> 255 rouge) et voyants bleus. */
    fun lights(color: Int, intensity: Int = 255, debris: Boolean = false, spot: Boolean = false) {
        if (!running) return
        runCatching { send(RoombaOI.leds(debris, spot, color, intensity)) }
    }
}
