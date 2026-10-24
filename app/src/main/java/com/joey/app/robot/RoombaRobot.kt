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
    /** Dernière lecture réussie des capteurs. */
    @Volatile var sensorsAt = 0L
        private set
    /** La liaison répond (capteurs lus il y a moins de 0,7 s) : sinon, on ne roule pas à l'aveugle. */
    val linkOk: Boolean get() = System.currentTimeMillis() - sensorsAt < 700
    @Volatile var running = false
        private set
    @Volatile private var cancel = false
    @Volatile private var failures = 0
    /** Un contact : « pare-chocs », « vide » ou « proche », et le côté (-1 gauche, 0 devant, 1 droite). */
    @Volatile var onContact: ((String, Int) -> Unit)? = null
    /** Un obstacle tout proche devant le pare-chocs (détecteurs de proximité ; leur intensité si on l'a). */
    val obstacleNear: Boolean get() = ((sensors?.lightBumper ?: 0) and 0x3F) != 0 ||
        (sensors?.lightSignals?.let { sig -> (1..4).any { sig[it] > 120 } } ?: false)
    /**
     * Ce que la caméra voit de sol libre droit devant Joey (m), ou null si elle ne sait pas (tête tournée, sol pas vu,
     * image trop vieille). Sert à ralentir puis s'arrêter AVANT le choc.
     */
    @Volatile var visionClear: () -> Double? = { null }

    /**
     * Prudence quand la caméra ne dit rien (tête pas encore baissée, sol pas reconnu) : il roule doucement, pour toucher
     * en douceur ce qu'il n'a pas vu. (24/10 : parti à 0,4 m/s avant la première mesure, il a heurté un pied de chaise.)
     */
    @Volatile var cautiousWhenBlind = false
    private fun blindCap(): Double? = if (cautiousWhenBlind && visionClear() == null) BLIND_SPEED else null

    /** Vitesse permise par la vision (m/s) en avançant côté visage ; null = pas d'avis. */
    private fun visionCap(): Double? = visionClear()?.let { c -> if (c.isInfinite()) null else ((c - 0.22) * 0.8).coerceAtLeast(0.0) }

    /** Un obstacle détecté (direction en degrés, + = droite ; distance au pare-chocs en m), pour la carte. */
    @Volatile var onProximity: ((Double, Double) -> Unit)? = null
    /** Un bouton du Roomba vient d'être appuyé : « clean », « spot » ou « dock ». */
    @Volatile var onButton: ((String) -> Unit)? = null
    /** Boutons ignorés pendant ce temps après la connexion ou une coupure (faux appuis du 24/10). */
    @Volatile internal var buttonQuietMs = 5000L
    /** Dernière fois que les balises infrarouges de la base ont été vues. */
    @Volatile var dockSeenAt = 0L
        private set
    private var fineAt = 0L

    /** Intensité d'un détecteur de proximité -> distance approximative (m), ou null si rien. */
    private fun lightDistance(sig: Int): Double? = when {
        sig > 800 -> 0.03
        sig > 250 -> 0.08
        sig > 100 -> 0.15
        sig > 40 -> 0.25
        else -> null
    }

    /** Repart de zéro (posé sur sa base : la base devient l'origine de la carte). */
    fun resetOdometry() = odo.reset()
    /** Recale sa position sur la carte (recalage d'un tour d'horizon, étiquette). */
    fun setPose(x: Double, y: Double, headingDeg: Double) = odo.setPose(x, y, headingDeg)
    /** Le garder éveillé (mode sécurité au repos) : sinon il s'endort au bout de 5 min. */
    @Volatile var keepAwake = true
    private var passiveSince = 0L
    /** Au bout de combien de temps de repos (mode passif) on le remet en mode sécurité pour qu'il ne s'endorme pas. */
    @Volatile internal var awakeAfterMs = 180_000L
    /** Il cherche sa base tout seul (mode passif, mais il roule) : on ne le dérange pas jusque-là. */
    @Volatile private var seekingDockUntil = 0L
    /** Il cherche sa base tout seul (il tourne beaucoup sur lui-même). */
    val seekingDock: Boolean get() = System.currentTimeMillis() < seekingDockUntil

    /** Dernière fois qu'il a vu un code infrarouge de sa base (160 à 175). */
    @Volatile private var lastDockIrAt = 0L
    private var awakeLogged = false
    /** La liaison vient de se couper (false) ou de revenir (true). */
    @Volatile var onLink: ((Boolean) -> Unit)? = null
    @Volatile private var currentBaud = 115_200
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
    /** Cap d'un gyroscope fixé au corps (le téléphone sans nacelle), ou null : voir [Odometry.gyroHeading]. */
    var gyroHeading: (() -> Double?)?
        get() = odo.gyroHeading
        set(v) { odo.gyroHeading = v }
    val gyroUsed: Boolean get() = odo.gyroUsed

    /** Correction des angles mesurés par les roues. */
    var turnScale: Double
        get() = odo.turnScale
        set(v) { odo.turnScale = v }

    private var lastPathCheck = 0L

    /** Vitesse max en intérieur (m/s). */
    var maxSpeed = 0.4
    /** Vitesse quand la caméra ne voit pas devant (m/s) : un choc à cette vitesse est doux. */
    val BLIND_SPEED = 0.2

    val x get() = odo.x
    val y get() = odo.y
    val heading get() = odo.headingDeg
    val battery: Int? get() = sensors?.batteryPct
    val onBase: Boolean get() = sensors?.onHomeBase == true
    val charging: Boolean get() = sensors?.charging == true

    // ---- liaison ------------------------------------------------------------------------------------
    private fun send(b: ByteArray) = synchronized(io) { link.write(b) }

    /** Tous ses capteurs (proximité, mur, boutons…) : seulement si ce Roomba les accepte, sinon ceux de base. */
    @Volatile var extended = false
        private set

    fun readSensors(): RoombaOI.Sensors? = synchronized(io) {
        link.clearInput()
        link.write(RoombaOI.querySensors(extended))
        val raw = link.read(RoombaOI.sensorBytes(extended), 250) ?: return null
        RoombaOI.parseSensors(raw, extended, modeOffset)
    }

    /**
     * Décalage du mode annoncé (0 selon la norme, 1 pour ce Roomba : passif annoncé 2). Mesuré à la connexion, juste après
     * « start » (qui met toujours en passif). Sans lui, ses réponses en mode complet (annoncé 4) passaient pour du bruit :
     * la liaison semblait coupée à chaque sortie de base (24/10, trois fois sur trois).
     */
    @Volatile var modeOffset = 0
        private set

    /** Essaie la liste complète des capteurs ; si le Roomba ne la comprend pas, on reste à celle de base. */
    private fun tryExtended(quiet: Boolean = false) {
        extended = true
        var ok = 0
        repeat(3) { if (readSensors() != null) ok++ }
        if (ok < 2) {
            extended = false
            runCatching { sleeper(100); link.clearInput() }
            if (!quiet) log("Roomba : capteurs de base seulement (ce modèle ne donne pas les autres)")
        } else if (!quiet) log("Roomba : tous ses capteurs lus (proximité, mur, boutons, balises de la base…)")
    }

    /**
     * Repassé aux capteurs de base après une coupure (fil qui bouge) : une fois la liaison revenue et stable, on réessaie
     * la liste complète. (Journal du 23/10 : une coupure de 38 s au démarrage, et toute la séance sans ses détecteurs de
     * proximité : il touchait les murs au lieu de ralentir avant.)
     */
    @Volatile private var extendedLost = false
    private var extendedRetries = 0
    private var linkGoodSince = 0L
    @Volatile internal var extendedRetryMs = 30_000L

    /**
     * Impulsion basse de 1 s sur le fil de réveil (BRC). Une impulsion de 50 à 500 ms compte pour un changement de vitesse
     * (trois de suite : 19 200 bauds) ; 1 s n'en compte jamais (exemple donné par iRobot : 1 s par minute le garde éveillé).
     */
    fun wakePulse() {
        lastWake = System.currentTimeMillis()
        runCatching { link.setWakeLine(true); sleeper(1000); link.setWakeLine(false) }
    }

    /** La même impulsion dans un fil à part : la lecture des capteurs ne s'arrête pas 1 s (relecture du 24/10). */
    private fun wakePulseAsync() {
        lastWake = System.currentTimeMillis()
        Thread { wakePulse() }.apply { isDaemon = true; start() }
    }

    /** Réveille le Roomba, trouve la vitesse de liaison, lance la lecture des capteurs. Lève une exception claire sinon. */
    fun connect() {
        runCatching { link.setWakeLine(false) }
        wakePulse()
        sleeper(300)
        var s: RoombaOI.Sensors? = null
        var baud = 0
        // 19 200 aussi : trois impulsions sur le fil de réveil en 2 s font passer le Roomba à cette vitesse (spécification
        // Open Interface) ; l'arrêt et la relance de l'app (mise à jour) peuvent en produire (24/10 : « la carte répond mais
        // pas le Roomba » après presque chaque mise à jour).
        for (b in listOf(115_200, 57_600, 19_200)) {
            link.setBaud(b)
            sleeper(100)
            send(RoombaOI.start())
            sleeper(150)
            modeOffset = 1 // accepte passif annoncé 1 ou 2, puis on règle le décalage
            repeat(3) { if (s == null) s = readSensors() }
            modeOffset = 0
            if (s != null) { baud = b; break }
        }
        // Juste après « start », il est passif : passif annoncé 1 (norme, lu comme 0 avec le décalage 1) ou 2 (ce Roomba).
        s?.let { first ->
            modeOffset = if (first.oiMode == 1) 1 else 0
            s = readSensors() ?: first
            if (modeOffset == 1) log("Roomba : il annonce son mode avec un cran de plus (passif = 2) : j'en tiens compte")
        }
        val ok = s ?: throw IllegalStateException(
            "la carte USB répond mais pas le Roomba. Vérifie : interrupteur de la carte sur 5V, TX -> trou 3, RX -> trou 4, " +
                "GND -> trou 6, et que le Roomba est allumé (appuie une fois sur CLEAN pour le réveiller, puis réessaie)")
        tryExtended()
        sensors = readSensors() ?: ok
        sensorsAt = System.currentTimeMillis()
        currentBaud = baud
        odo.update(sensors!!.leftCounts, sensors!!.rightCounts)
        defineBabble()
        log("Roomba : réponse à $baud bauds, batterie ${ok.batteryPct ?: "?"} %, mode ${ok.modeName}")
        running = true
        connectedAt = System.currentTimeMillis()
        Thread(::pollLoop, "roomba-capteurs").apply { isDaemon = true; start() }
        Thread(::driveLoop, "roomba-roues").apply { isDaemon = true; start() }
        sing(Tune.HELLO)
    }

    private var idleRef: Triple<Double, Double, Long>? = null
    private var buttonsPrev = 0
    private var buttonsSent = 0
    @Volatile private var connectedAt = 0L
    /**
     * Plus aucune réponse depuis 8 s malgré les réveils : la liaison série elle-même est bloquée (24/10 : seule une relance
     * de l'app, qui rouvre la carte USB, l'a débloquée). L'app rouvre alors la carte (une fois par panne).
     */
    @Volatile var onDead: (() -> Unit)? = null
    private var failSince = 0L
    private var deadReported = false
    @Volatile private var retakeAt = 0L

    /**
     * Reprendre la main : mode sécurité, roues à zéro, brosses coupées (un nettoyage lancé par erreur s'arrête). Sur sa base,
     * il reste en mode passif (sinon il ne charge plus).
     */
    private fun retakeControl(why: String, onBase: Boolean) {
        val now = System.currentTimeMillis()
        if (now - retakeAt < 2000) return
        retakeAt = now
        runCatching {
            send(RoombaOI.start()); sleeper(30)
            if (!onBase) { send(RoombaOI.safe()); sleeper(30); send(RoombaOI.driveDirect(0, 0)); send(RoombaOI.motors(false)); driving = true }
            vacuuming = false
        }
        log("Roomba : je reprends la main ($why)")
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
                if (failures == 1) failSince = now
                if (!deadReported && failures > 10 && now - failSince > 8_000) {
                    deadReported = true
                    log("Roomba : plus de réponse depuis 8 s, je rouvre la carte USB")
                    runCatching { onDead?.invoke() }
                }
                // Réveil : souvent au début, puis toutes les ~30 s (il était éteint : 92 messages en 3 min dans le journal).
                if (failures == 3) runCatching { wheels(0.0, 0.0) } // ne jamais rouler à l'aveugle
                // Silence soudain : il a pu redémarrer (mode éteint, 115 200 bauds) et attend « start » à la même vitesse.
                // On le renvoie tout de suite, avant de chercher une autre vitesse (24/10 : 10 s de coupure sinon).
                if (failures == 3 || failures == 6 || failures == 12) runCatching {
                    send(RoombaOI.start()); if (driving && sensors?.onHomeBase != true) send(RoombaOI.safe())
                }
                if (failures == 10) runCatching { onLink?.invoke(false) }
                if (failures == 10 || (failures > 10 && failures % 100 == 0)) {
                    if (failures == 10 || failures % 3000 == 0) log("Roomba : pas de réponse, je le réveille (est-il allumé ? un fil a bougé ?)")
                    wakePulseAsync()
                }
                // Réveillé (par le fil de réveil, ou par quelqu'un sur CLEAN), il attend « start » pour répondre.
                if (failures % 20 == 0) runCatching { send(RoombaOI.start()); if (driving) send(RoombaOI.safe()) }
                // La liste complète des capteurs ne passe plus : on revient à celle de base.
                if (extended && failures == 15) {
                    extended = false; extendedLost = true
                    log("Roomba : je repasse aux capteurs de base (je réessaierai les autres quand la liaison sera revenue)")
                }
                // Il a pu changer de vitesse de liaison (impulsions sur le fil de réveil) : on essaie les autres.
                if (failures % 40 == 20) {
                    val bauds = listOf(115_200, 57_600, 19_200)
                    val next = bauds[(bauds.indexOf(currentBaud).coerceAtLeast(0) + 1) % bauds.size]
                    runCatching { link.setBaud(next) }
                    currentBaud = next
                    sleeper(100)
                    if (runCatching { readSensors() }.getOrNull() != null) {
                        log("Roomba : liaison retrouvée à $next bauds")
                        runCatching { send(RoombaOI.start()); if (driving) send(RoombaOI.safe()) }
                        defineBabble()
                    }
                }
                continue
            }
            if (failures >= 3) log("Roomba : il répond de nouveau après $failures lectures manquées (mode ${s.modeName}, " +
                (if (s.onHomeBase) "sur sa base" else "hors de sa base") + ")")
            if (failures >= 10) {
                // Liaison revenue : pendant la coupure il a pu recevoir un ordre tronqué, et lire un octet de vitesse comme
                // une commande (24/10 : il a lancé son nettoyage « spot » et n'obéissait plus). Il reprend la main.
                retakeControl("liaison revenue", s.onHomeBase)
                runCatching { onLink?.invoke(true) }
            }
            if (failures > 0) linkGoodSince = now
            failures = 0
            deadReported = false
            if (extendedLost && !extended && now - linkGoodSince > extendedRetryMs && extendedRetries < 5) {
                extendedRetries++
                tryExtended(quiet = true)
                if (extended) { extendedLost = false; log("Roomba : de nouveau tous ses capteurs (proximité, mur…)") }
                else linkGoodSince = now
                continue
            }
            val prev = sensors
            sensors = s
            sensorsAt = now
            bumpSince = if (s.bump) (if (bumpSince == 0L) now else bumpSince) else 0L
            // Contacts (pour la carte) : pare-chocs, vide, obstacle tout proche (détecteurs de proximité, si le modèle en a).
            runCatching {
                if (s.bump && prev?.bump != true)
                    onContact?.invoke("pare-chocs", if (s.bumpLeft && !s.bumpRight) -1 else if (s.bumpRight && !s.bumpLeft) 1 else 0)
                if (s.cliff && prev?.cliff != true && driving) onContact?.invoke("vide", 0)
                val lb = s.lightBumper and 0x3F
                if (lb != 0 && ((prev?.lightBumper ?: 0) and 0x3F) == 0)
                    onContact?.invoke("proche", if (lb and 0x3C == 0 && lb and 0x03 != 0) -1 else if (lb and 0x0F == 0) 1 else 0)
                // Près de sa base, son faisceau compte comme « mur virtuel » (24/10 : dix à côté de la base, aucun mur virtuel dans
                // la maison) : on ne le note pas, sinon la carte interdit l'abord de la base.
                if (RoombaOI.isDockIr(s.irOmni)) lastDockIrAt = now
                if (s.virtualWall == true && prev?.virtualWall != true && now - lastDockIrAt > 3000) onContact?.invoke("mur virtuel", 0)
                // Les capteurs fins, 5 fois par seconde au plus (pour la carte) : proximité (6 directions) et mur à droite.
                if (now - fineAt > 200) {
                    fineAt = now
                    s.lightSignals?.forEachIndexed { k, sig -> lightDistance(sig)?.let { d -> onProximity?.invoke(RoombaOI.LIGHT_ANGLES[k], d) } }
                    if (s.wall == true || s.wallSignal > 30) onProximity?.invoke(90.0, if (s.wallSignal > 200) 0.05 else 0.12)
                }
                // Boutons du Roomba : des commandes pour Joey. Un appui ne compte que s'il est lu deux fois de suite, et pas
                // dans les 5 s qui suivent le retour de la liaison (24/10 : un appui DOCK fantôme, lu juste après une
                // reconnexion, a lancé la recherche de la base et l'aspiration).
                val held = s.buttons and buttonsPrev
                val pressed = held and buttonsSent.inv()
                buttonsPrev = s.buttons
                buttonsSent = held
                if (now - linkGoodSince > buttonQuietMs && now - connectedAt > buttonQuietMs) {
                    if (pressed and 1 != 0) onButton?.invoke("clean")
                    if (pressed and 2 != 0) onButton?.invoke("spot")
                    // Pendant qu'il rentre seul, le Roomba annonce son bouton DOCK (24/10) : ce n'est pas quelqu'un.
                    if (pressed and 4 != 0 && now > seekingDockUntil) onButton?.invoke("dock")
                }
            }
            // Roues en surintensité (coincées, même si elles patinent) : il est coincé.
            if (s.wheelOvercurrent && (abs(vel[0]) > 0.04 || driving)) stalledAt = now
            if (s.dockInSight) dockSeenAt = now
            // En mode passif (au repos), le Roomba s'endort au bout de 5 min, et sans le fil de réveil on ne peut plus le
            // rallumer. En mode sécurité il ne s'endort pas : on l'y remet au bout de 3 min de repos (pas sur sa base).
            if (s.oiMode == 1 && !s.onHomeBase && !s.charging && keepAwake && now > seekingDockUntil) {
                if (passiveSince == 0L) passiveSince = now
                else if (now - passiveSince > awakeAfterMs) {
                    runCatching { send(RoombaOI.start()); sleeper(50); send(RoombaOI.safe()) }
                    driving = true
                    passiveSince = 0L
                    if (!awakeLogged) { awakeLogged = true; log("Roomba : je le garde éveillé (sinon il s'endort au bout de 5 min)") }
                }
            } else passiveSince = 0L
            if (s.bump) lastBumpSide = if (s.bumpLeft && !s.bumpRight) -1 else if (s.bumpRight && !s.bumpLeft) 1 else 0
            odo.update(s.leftCounts, s.rightCounts)
            // Il roule alors que personne ne le lui demande (nettoyage lancé par erreur, bouton CLEAN…) : il reprend la main.
            val idle = now - movingAt > 1500 && now > seekingDockUntil
            if (idle) {
                val ref = idleRef
                if (ref == null || now - ref.third > 1000) idleRef = Triple(odo.travelledM, odo.headingDeg, now)
                else if (odo.travelledM - ref.first > 0.08 || abs(com.joey.app.gimbal.Head.wrap(odo.headingDeg - ref.second)) > 25) {
                    retakeControl("je roulais tout seul (nettoyage lancé par erreur ?)", s.onHomeBase)
                    idleRef = null
                }
            } else idleRef = null
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
            if (!driving && now - lastWake > 55_000) wakePulseAsync()
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
        seekingDockUntil = 0L // on roule : il ne cherche plus sa base
        if (!driving || s.oiMode != 2) {
            safetyNote = null
            send(RoombaOI.start())
            if (s.onHomeBase) leaveBase()?.let { return it }
            send(RoombaOI.safe())
            sleeper(80)
            driving = true
        }
        return null
    }

    /**
     * Façon de quitter la base : « auto » (mode complet, et le programme CLEAN du Roomba si le mode complet le fait
     * taire), « complet » ou « nettoyage ». (24/10 : trois sorties sur trois, la liaison s'est tue juste après le mode
     * complet sur la base, sans que les roues tournent.)
     */
    @Volatile var undock = "auto"

    /** Quitte la base en reculant. null si c'est fait, sinon pourquoi pas. */
    private fun leaveBase(): String? {
        log("Roomba : je quitte ma base")
        if (undock != "nettoyage") {
            send(RoombaOI.full())
            sleeper(100)
            val s = readSensors()
            if (s != null) {
                // Recule (sens du Roomba, quel que soit le montage du visage). Le mode complet coupe ses sécurités : on
                // s'arrête si on l'arrête ou si on le soulève.
                val moved = backOff(2500)
                val after = readSensors() ?: sensors
                if (after?.onHomeBase != true) return null
                // Toujours sur ses contacts, roues immobiles (24/10, 17 h 04 : posé à la main sur la base puis redémarré,
                // il ne roulait pas en mode complet) : son propre programme sait quitter la base.
                log("Roomba : toujours sur ma base après le recul en mode complet (roues : %.2f m)".format(moved))
                if (undock == "complet") return "je n'arrive pas à quitter ma base (mes roues ne tournent pas en mode complet)"
            } else {
                log("Roomba : il s'est tu dès le mode complet sur sa base, je lui renvoie « start »")
                val back = restart(3000) ?: return "le Roomba ne répond plus après le mode complet sur sa base"
                log("Roomba : il répond de nouveau (mode ${back.modeName}, ${if (back.onHomeBase) "toujours sur sa base" else "hors de sa base"})")
                if (!back.onHomeBase) return null
                if (undock == "complet") return "je n'arrive pas à quitter ma base (le Roomba se tait en mode complet)"
            }
        }
        // Son propre programme de nettoyage le fait reculer hors de la base ; on reprend la main dès qu'il l'a quittée
        // (brosses et aspirateur tournent une seconde environ).
        log("Roomba : je quitte ma base avec son programme CLEAN, puis je reprends la main")
        send(RoombaOI.clean())
        val end = System.currentTimeMillis() + 6000
        var off = false
        while (System.currentTimeMillis() < end) {
            sleeper(100)
            val s = readSensors() ?: continue
            if (!s.onHomeBase) { off = true; break }
        }
        send(RoombaOI.start()); sleeper(30)
        send(RoombaOI.safe()); sleeper(30)
        send(RoombaOI.motors(false))
        send(RoombaOI.driveDirect(0, 0))
        vacuuming = false
        if (!off) {
            // 24/10, 18 h 19 et 18 h 22 : après quelques minutes de charge, ni le mode complet ni CLEAN ne font tourner
            // ses moteurs (le voyant CLEAN s'allume, aucun bruit). Dernier recours : on le redémarre, puis on recule.
            if (System.currentTimeMillis() - resetTriedAt < 10 * 60_000L) return "je n'arrive pas à quitter ma base (toujours sur ses contacts après 6 s)"
            resetTriedAt = System.currentTimeMillis()
            log("Roomba : ses moteurs ne répondent pas sur la base, je le redémarre")
            synchronized(io) { link.clearInput(); link.write(byteArrayOf(RoombaOI.RESET.toByte())) }
            sleeper(1500)
            val back = restart(30_000) ?: return "le Roomba ne répond plus après son redémarrage"
            defineBabble()
            if (!back.onHomeBase) return null
            send(RoombaOI.full()); sleeper(100)
            val moved = backOff(2500)
            val after = readSensors() ?: sensors
            log("Roomba : après le redémarrage, recul de %.2f m".format(moved))
            if (after?.onHomeBase == true) return "je n'arrive pas à quitter ma base, même après l'avoir redémarré"
            send(RoombaOI.safe())
            return null
        }
        // Encore un peu en arrière, pour dégager la base.
        backOff(1200)
        return null
    }

    /** Dernier redémarrage de secours (un toutes les 10 min au plus : on ne boucle pas). */
    @Volatile private var resetTriedAt = 0L

    /** Recule pendant [ms] ; s'arrête sur [stop] ou si une roue se soulève. Rend la distance parcourue (roues). */
    private fun backOff(ms: Long): Double {
        val g0 = stopGen
        val d0 = odo.travelledM
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end && stopGen == g0 && sensors?.wheelDrop != true) {
            wheels(if (reversed) 0.12 else -0.12, 0.0); sleeper(100)
        }
        wheels(0.0, 0.0)
        return abs(odo.travelledM - d0)
    }

    /** Renvoie « start » jusqu'à ce qu'il réponde (il a pu redémarrer : 115 200 bauds, mode éteint). */
    private fun restart(ms: Long): RoombaOI.Sensors? {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            send(RoombaOI.start())
            sleeper(200)
            readSensors()?.let { return it }
        }
        return null
    }

    /**
     * Version du logiciel du Roomba. L'ordre « reset » le redémarre (comme une batterie retirée) et il écrit un message
     * d'accueil en texte. (Avant release-3.8.2 / stm32-3.7.7, le fil de réveil ne le réveille pas sur sa base.)
     */
    fun firmware(): String = synchronized(io) {
        link.clearInput()
        link.write(byteArrayOf(RoombaOI.RESET.toByte()))
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + 6000
        while (System.currentTimeMillis() < end) {
            val b = link.read(1, 300) ?: continue
            val c = b[0].toInt() and 0xFF
            if (c == 10 || c == 13 || c in 32..126) sb.append(c.toChar())
        }
        runCatching { link.setBaud(115_200) }
        currentBaud = 115_200
        sleeper(300)
        link.write(RoombaOI.start())
        sleeper(100)
        driving = false
        defineBabble()
        val text = sb.toString().replace('\r', '\n').lines().map { it.trim() }.filter { it.isNotEmpty() }
        text.firstOrNull { "release" in it || "tags" in it } ?: text.joinToString(" | ").take(400).ifEmpty { "aucun texte reçu" }
    }

    /**
     * Le fil de réveil (BRC, trou 5) marche-t-il ? Spécification iRobot : après la mise en marche, attendre 2 s, puis trois
     * impulsions basses de 50 à 500 ms font passer le Roomba à 19 200 bauds. On le redémarre (reset), on envoie les trois
     * impulsions et on regarde à quelle vitesse il répond. Puis on le remet à 115 200 bauds (ordre 129, code 11).
     */
    fun brcTest(): String = synchronized(io) {
        fun answers(baud: Int): Boolean {
            runCatching { link.setBaud(baud) }
            sleeper(100)
            link.clearInput()
            link.write(RoombaOI.start())
            sleeper(150)
            return (0 until 3).any { readSensors() != null }
        }
        link.clearInput()
        link.write(byteArrayOf(RoombaOI.RESET.toByte()))
        // Il redémarre : on attend qu'il réponde de nouveau (24/10 : plus de 3 s ; les impulsions envoyées pendant son
        // démarrage ne comptaient pas), puis les 2 s de la spécification.
        val t0 = System.currentTimeMillis()
        sleeper(1500)
        var back = false
        // 24/10 : ~15 s (il recalibre sa batterie : « battery-current-zero »).
        while (!back && System.currentTimeMillis() - t0 < 30_000) { back = answers(115_200); if (!back) sleeper(300) }
        val bootMs = System.currentTimeMillis() - t0
        if (!back) { currentBaud = 115_200; driving = false; return@synchronized "il ne répond plus après le redémarrage (30 s) : test impossible" }
        sleeper(2000)
        repeat(3) { link.setWakeLine(true); sleeper(200); link.setWakeLine(false); sleeper(200) }
        lastWake = System.currentTimeMillis()
        sleeper(400)
        val at19200 = answers(19_200)
        if (at19200) { link.write(byteArrayOf(129.toByte(), 11)); sleeper(200) }
        val at115200 = answers(115_200)
        currentBaud = 115_200
        driving = false
        defineBabble()
        val verdict = if (at19200) "le fil de réveil MARCHE : après trois impulsions, il répondait à 19 200 bauds (remis à 115 200)"
        else "le fil de réveil ne fait rien : après trois impulsions, il ne répondait pas à 19 200 bauds" +
            (if (at115200) " mais toujours à 115 200 (le fil n'arrive pas jusqu'à lui, ou la carte ne pilote pas RTS)" else "")
        "$verdict (redémarrage : ${bootMs / 1000.0} s)" + if (at115200) "" else " ; ATTENTION : il ne répond plus à 115 200 bauds"
    }

    /** Diagnostic : envoie une demande de capteurs ([ids], groupes compris) et rend les octets reçus en 400 ms, en hexadécimal. */
    fun rawQuery(ids: List<Int>): String = synchronized(io) {
        link.clearInput()
        link.write(byteArrayOf(RoombaOI.QUERY_LIST.toByte(), ids.size.toByte(), *ids.map { it.toByte() }.toByteArray()))
        val got = ArrayList<Int>()
        val end = System.currentTimeMillis() + 400
        while (System.currentTimeMillis() < end) { val b = link.read(1, 50) ?: continue; got.add(b[0].toInt() and 0xFF) }
        "${got.size} octets : " + got.joinToString(" ")
    }

    @Volatile private var singAt = 0L
    /** Augmente à chaque [stop] : une boucle en cours (recul hors de la base) voit qu'on l'a arrêtée. */
    @Volatile private var stopGen = 0

    /** Arrête tout (les roues ; il reste en mode sécurité). */
    fun stop() {
        stopGen++
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
            if (!linkOk) { wheels(0.0, 0.0); return "arrêté : je ne sens plus mes roues (liaison avec le Roomba coupée)" }
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
        var progM = 0.0
        var progAt = t0
        var rampV = 0.08
        try {
            while (!cancel && System.currentTimeMillis() < end) {
                val done = odo.travelledM - start
                val left = abs(dist) - done
                if (left < 0.01) break
                // Coincé : les roues n'avancent plus depuis 1,5 s alors qu'on leur demande d'avancer.
                val nowMs = System.currentTimeMillis()
                if (done - progM > 0.01) { progM = done; progAt = nowMs }
                else if (nowMs - progAt > 1500) {
                    result = "bloqué après %.2f m : mes roues n'avancent plus (coincé ?)".format(done)
                    stalledAt = nowMs
                    break
                }
                val s = sensors
                if (!linkOk) { result = "arrêté après %.2f m : je ne sens plus mes roues (liaison avec le Roomba coupée)".format(done); break }
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
                // Un obstacle tout près (détecteurs de proximité) : il ralentit pour le toucher en douceur, ou l'éviter.
                var vmax = if ((sign > 0) != reversed && obstacleNear) 0.08 else maxSpeed
                val vcap = if (sign > 0) visionCap() else null
                if (vcap != null && vcap < 0.03) {
                    result = "obstacle vu devant (%.0f cm) après %.2f m, arrêté avant de le toucher".format((visionClear() ?: 0.0) * 100, done)
                    break
                }
                if (vcap != null) vmax = minOf(vmax, maxOf(0.06, vcap))
                if (sign > 0) blindCap()?.let { vmax = minOf(vmax, it) }
                // Accélère en douceur (pas de saut de 0 à la vitesse max), ralentit à l'approche.
                rampV = minOf(rampV + 0.035, (left * 1.5).coerceIn(0.06, maxOf(0.06, vmax)))
                val v = rampV * sign
                val yaw = (Head.wrap(h0 - odo.headingDeg) * 2).coerceIn(-20.0, 20.0)
                wheels(v, yaw)
                sleeper(50)
            }
        } finally {
            wheels(0.0, 0.0)
        }
        return if (cancel) safetyNote?.let { "arrêté : $it" } ?: "interrompu" else result
    }

    /**
     * Suit un trajet (points de l'odométrie) d'un mouvement continu, en courbes : il vise un point un peu devant lui
     * sur le trajet et corrige sa direction en roulant (pas d'arrêt à chaque virage ; il ne tourne sur place que si le
     * virage est franc). Vitesse modulée : [cruise] en ligne droite là où [speedFactor] (0..1, ce que dit la carte à cet
     * endroit) le permet, plus lent dans les virages, près d'un obstacle et à l'arrivée ; accélérations douces.
     */
    fun followPath(path: List<Pair<Double, Double>>, cruise: Double = maxSpeed,
                   /** La suite du chemin est-elle toujours libre ? (x, y, segment visé) ; non : il s'arrête pour recalculer. */
                   pathCheck: ((Double, Double, Int) -> Boolean)? = null,
                   speedFactor: (Double, Double, Double) -> Double = { _, _, _ -> 1.0 }): String {
        ready()?.let { return it }
        cancel = false
        if (path.isEmpty()) return "rien à faire"
        val goal = path.last()
        val total = path.zipWithNext().sumOf { (a, b) -> hypot(b.first - a.first, b.second - a.second) } +
            hypot(path.first().first - odo.x, path.first().second - odo.y)
        val start = odo.travelledM
        val t0 = System.currentTimeMillis()
        val end = t0 + ((total / 0.06 + 6) * 1000).toLong()
        var v = 0.0
        var seg = 0
        var progM = 0.0; var progAt = t0
        var result = "arrivé"
        try {
            while (!cancel && System.currentTimeMillis() < end) {
                val x = odo.x; val y = odo.y; val h = odo.headingDeg
                val toGoal = hypot(goal.first - x, goal.second - y)
                if (toGoal < 0.08 || (seg >= path.size - 1 && toGoal < 0.12)) break
                if (!linkOk) { result = "arrêté : je ne sens plus mes roues (liaison avec le Roomba coupée)"; break }
                // Un obstacle vient d'apparaître sur la carte en travers de la suite du chemin : on recalcule (comme Nav2).
                val nowCheck = System.currentTimeMillis()
                if (pathCheck != null && nowCheck - lastPathCheck > 400) {
                    lastPathCheck = nowCheck
                    if (!pathCheck(x, y, seg)) { wheels(0.0, 0.0); result = "chemin bouché devant, je recalcule"; break }
                }
                val s = sensors
                if (s != null && s.oiMode == 1 && System.currentTimeMillis() - t0 > 400) {
                    result = "arrêté : " + (safetyNote ?: if (s.cliff) "vide détecté sous moi (bord de marche ?)" else "le Roomba a repris la main")
                    break
                }
                if (s != null && s.bump) {
                    wheels(0.0, 0.0)
                    val side = if (s.bumpLeft && s.bumpRight) "devant" else if (s.bumpLeft) "à gauche" else "à droite"
                    log("Roomba : pare-chocs touché $side")
                    result = "obstacle touché $side après %.2f m".format(odo.travelledM - start)
                    break
                }
                // Coincé : plus d'avance depuis 1,5 s.
                val now = System.currentTimeMillis()
                val done = odo.travelledM - start
                if (done - progM > 0.01) { progM = done; progAt = now }
                else if (now - progAt > 1500 && v > 0.05) { stalledAt = now; result = "bloqué après %.2f m : mes roues n'avancent plus".format(done); break }
                // Point visé : le premier point du trajet à plus de 35 cm (on avance d'un segment quand on l'a dépassé).
                while (seg < path.size - 1 && hypot(path[seg].first - x, path[seg].second - y) < 0.35) seg++
                val target = path[seg]
                val err = Head.wrap(Math.toDegrees(atan2(target.second - y, target.first - x)) - h)
                if (abs(err) > 65 && v < 0.12) {
                    // Virage franc : il pivote sur place, puis repart.
                    v = 0.0
                    wheels(0.0, (err * 2.5).coerceIn(-100.0, 100.0).let { if (abs(it) < 25) Math.signum(it) * 25 else it })
                    progAt = now
                    sleeper(50)
                    continue
                }
                var want = cruise * speedFactor(x, y, h).coerceIn(0.2, 1.0) * (1 - abs(err) / 100).coerceAtLeast(0.25)
                want = minOf(want, 0.06 + toGoal * 0.9)                 // ralentit à l'approche du but
                if (obstacleNear) want = minOf(want, 0.08)              // un obstacle tout près : doucement
                // La caméra voit un obstacle droit devant : il ralentit, et s'arrête avant de le toucher.
                val vcap = visionCap()
                if (vcap != null && vcap < 0.03 && abs(err) < 30) {
                    wheels(0.0, 0.0)
                    result = "obstacle vu devant (%.0f cm), arrêté avant de le toucher".format((visionClear() ?: 0.0) * 100)
                    break
                }
                if (vcap != null) want = minOf(want, maxOf(0.06, vcap))
                blindCap()?.let { want = minOf(want, it) }
                v = if (want > v) minOf(want, v + 0.035) else maxOf(want, v - 0.06) // ~0,7 m/s² pour accélérer
                wheels(v, (err * 2.2).coerceIn(-110.0, 110.0))
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
            if (!linkOk) { f = 0.0; yaw = 0.0 } // liaison coupée : on ne roule pas à l'aveugle
            // Coincé en roulant (suivre, venir, manette) : on lui demande d'avancer, rien ne bouge depuis 1,5 s.
            val nowMs = System.currentTimeMillis()
            if (abs(f) > 0.04 || abs(yaw) > 15) {
                if (moveStart == 0L) { moveStart = nowMs; moveFrom = odo.travelledM; turnFrom = odo.headingDeg }
                else if (nowMs - moveStart > 1500) {
                    val moved = odo.travelledM - moveFrom
                    val turnedDeg = abs(Head.wrap(odo.headingDeg - turnFrom))
                    if (moved < 0.02 && turnedDeg < 3) stalledAt = nowMs
                    moveStart = nowMs; moveFrom = odo.travelledM; turnFrom = odo.headingDeg
                }
            } else moveStart = 0L
            if ((f > 0) != reversed && f != 0.0 && sensors?.bump == true) f = 0.0 // pare-chocs : plus d'avance, il peut encore tourner
            if ((f > 0) != reversed && obstacleNear) f = f.coerceIn(-0.08, 0.08) // tout près d'un obstacle : doucement
            if (f > 0) visionCap()?.let { cap -> f = minOf(f, cap) }           // la caméra voit un obstacle devant
            runCatching { wheels(f, yaw) }
            active = true
        }
    }

    // ---- coincé : le voir, et s'en sortir ---------------------------------------------------------------------
    @Volatile private var stalledAt = 0L
    @Volatile private var bumpSince = 0L
    /** Dernier côté touché par le pare-chocs : -1 gauche, 1 droite, 0 devant. */
    @Volatile var lastBumpSide = 0
        private set
    private var moveStart = 0L
    private var moveFrom = 0.0
    private var turnFrom = 0.0

    /** Coincé en ce moment : roues qui n'avancent plus, ou pare-chocs enfoncé depuis plus d'une demi-seconde. */
    val stuck: Boolean get() {
        val now = System.currentTimeMillis()
        return now - stalledAt < 1500 || (bumpSince != 0L && now - bumpSince > 500)
    }

    /**
     * Se dégager : recule un peu (à l'opposé du pare-chocs), puis tourne du côté opposé à ce qui a touché (essais
     * suivants : de plus en plus, et en alternant si c'était droit devant). [attempt] : 0, 1, 2…
     */
    fun unstick(attempt: Int = 0): String {
        ready()?.let { return it }
        stop()
        stalledAt = 0L
        sleeper(150)
        val side = sensors?.let { if (it.bumpLeft && !it.bumpRight) -1 else if (it.bumpRight && !it.bumpLeft) 1 else null } ?: lastBumpSide
        // Reculer = s'éloigner du pare-chocs (le côté « devant » du Roomba, même si son visage regarde derrière).
        val back = drive(if (reversed) 0.15 + 0.05 * attempt else -(0.15 + 0.05 * attempt))
        // Touché droit devant, ou déjà deux essais : demi-tour, il repart par où il est venu (la voie y est libre). Michael,
        // 24/10 : « là où il n'est pas coincé, il faudrait qu'il manœuvre : faire demi-tour et repartir dans l'autre sens ».
        val uTurn = side == 0 || attempt >= 2
        val angle = if (uTurn) 180.0 else 40.0 + 20.0 * attempt
        val dir = when { side < 0 -> 1.0; side > 0 -> -1.0; attempt % 2 == 0 -> 1.0; else -> -1.0 }
        val t = turn(dir * angle)
        return "je me dégage : $back, puis ${if (uTurn) "demi-tour ($t)" else t} (${if (dir > 0) "vers la droite" else "vers la gauche"})"
    }

    // ---- base de charge --------------------------------------------------------------------------------
    fun seekDock(): String {
        stop()
        driving = false
        // Il cherche sa base en mode passif : le maintien en éveil ne doit pas l'interrompre (8 min pour la trouver).
        seekingDockUntil = System.currentTimeMillis() + 8 * 60_000L
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

    /** Silence (mode test) : ni mélodies ni babil. */
    @Volatile var muted = false

    fun sing(t: Tune) {
        if (!running || muted) return
        // Écrire une mélodie le rend muet ~2 s (24/10 : 6 lectures manquées après chaque mélodie) : jamais en roulant, il
        // s'arrêterait net faute de capteurs.
        val now = System.currentTimeMillis()
        if (now - movingAt < 1500) return
        // Une toutes les 4 s au plus (des appuis répétés sur l'écran le rendaient muet en continu), et jamais dans le fil
        // de l'appelant (l'écran attendait le verrou de la liaison).
        if (now - singAt < 4000) return
        singAt = now
        Thread { runCatching { send(RoombaOI.song(2, t.notes)); send(RoombaOI.play(2)) } }.apply { isDaemon = true; start() }
    }

    /**
     * Babil : deux petites « syllabes » de robot (grave, aiguë) enregistrées UNE fois dans le Roomba (emplacements 3
     * et 4) ; ensuite on ne fait que les jouer (2 octets). Avant, chaque bip réécrivait une mélodie : ça surchargeait
     * le Roomba, qui ne répondait plus pendant que Joey parlait (et Joey roulait à l'aveugle).
     */
    private fun defineBabble() {
        runCatching {
            send(RoombaOI.song(3, listOf(74 to 4, 70 to 4, 77 to 5)))
            send(RoombaOI.song(4, listOf(86 to 4, 91 to 4, 84 to 5)))
        }
    }

    @Volatile private var chirpAt = 0L
    /** Une syllabe de babil, grave ou aiguë selon [note] ; pas plus de 3 par seconde, et jamais si la liaison peine. */
    fun chirp(note: Int) {
        val now = System.currentTimeMillis()
        if (!running || muted || !linkOk || now - chirpAt < 330) return
        chirpAt = now
        runCatching { send(RoombaOI.play(if (note < 80) 3 else 4)) }
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
