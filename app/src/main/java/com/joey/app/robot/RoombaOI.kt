package com.joey.app.robot

import kotlin.math.PI
import kotlin.math.roundToInt

/**
 * Protocole de pilotage des Roomba série 500/600 (« Open Interface » d'iRobot, prise mini-DIN 7 broches).
 * Octets à envoyer sur la liaison série (115 200 bauds pour la série 600, 57 600 pour certains 500).
 */
object RoombaOI {
    const val START = 128
    const val SAFE = 131
    const val FULL = 132
    const val POWER_DOWN = 133
    const val SEEK_DOCK = 143
    const val CLEAN = 135
    /** Redémarre le Roomba (comme une batterie retirée) ; il écrit alors sa version en texte. */
    const val RESET = 7
    const val DRIVE_DIRECT = 145
    const val MOTORS = 138
    const val LEDS = 139
    const val SONG = 140
    const val PLAY = 141
    const val QUERY_LIST = 149

    /** Roues : 508,8 impulsions par tour, roue de 72 mm, 235 mm entre les roues. */
    const val MM_PER_COUNT = PI * 72.0 / 508.8
    const val WHEEL_BASE_MM = 235.0
    const val MAX_MM_S = 500

    fun start() = bytes(START)
    fun safe() = bytes(SAFE)
    fun full() = bytes(FULL)
    fun seekDock() = bytes(SEEK_DOCK)
    fun clean() = bytes(CLEAN)
    fun powerDown() = bytes(POWER_DOWN)

    /** Vitesse de chaque roue (mm/s, + = en avant), bornée à ±500. */
    fun driveDirect(rightMmS: Int, leftMmS: Int): ByteArray {
        val r = rightMmS.coerceIn(-MAX_MM_S, MAX_MM_S)
        val l = leftMmS.coerceIn(-MAX_MM_S, MAX_MM_S)
        return bytes(DRIVE_DIRECT, (r shr 8) and 0xFF, r and 0xFF, (l shr 8) and 0xFF, l and 0xFF)
    }

    /**
     * Vitesses des roues (mm/s) pour avancer à [forwardMS] m/s en tournant à [yawRateDegS] °/s
     * (+ = vers la droite, sens des aiguilles d'une montre vu de dessus) : (droite, gauche).
     */
    fun wheelSpeeds(forwardMS: Double, yawRateDegS: Double): Pair<Int, Int> {
        val v = forwardMS * 1000.0
        val w = Math.toRadians(yawRateDegS) * WHEEL_BASE_MM / 2
        // Tourner à droite : la roue gauche va plus vite que la droite
        return (v - w).roundToInt() to (v + w).roundToInt()
    }

    /** Petite mélodie : notes MIDI (31-127) et durées en 1/64 s. */
    fun song(number: Int, notes: List<Pair<Int, Int>>): ByteArray {
        val n = notes.take(16)
        val out = ArrayList<Int>()
        out.add(SONG); out.add(number); out.add(n.size)
        for ((note, dur) in n) { out.add(note.coerceIn(31, 127)); out.add(dur.coerceIn(1, 255)) }
        return bytes(*out.toIntArray())
    }

    fun play(number: Int) = bytes(PLAY, number)

    /** Aspiration : brosse latérale (bit 0), aspirateur (bit 1), brosse principale (bit 2). */
    fun motors(on: Boolean) = bytes(MOTORS, if (on) 0b111 else 0)

    /** Voyants : [debris] et [spot] (bleus), bouton CLEAN de la couleur [color] (0 vert -> 255 rouge), [intensity] 0-255. */
    fun leds(debris: Boolean, spot: Boolean, color: Int, intensity: Int) =
        bytes(LEDS, (if (debris) 1 else 0) or (if (spot) 2 else 0), color.coerceIn(0, 255), intensity.coerceIn(0, 255))

    // ---- capteurs ----------------------------------------------------------------------------------
    /**
     * Code infrarouge d'une base (160 à 175, bit 1 à zéro : 160, 161, 164, 165, 168, 169, 172, 173). 162 est le mur
     * virtuel du Roomba 600, pas la base.
     */
    fun isDockIr(code: Int) = code in 160..175 && code and 2 == 0

    /** Groupe de paquets 7 à 16. */
    const val GROUP_7_16 = 1

    /** Paquets lus à chaque tour : (numéro, taille en octets). */
    val SENSOR_PACKETS = listOf(
        // Groupe 1 = paquets 7 à 16 (10 octets) : pare-chocs et roues soulevées, mur, 4 vides, mur virtuel, surintensité,
        // saleté, octet inutilisé. Demandés par leur groupe pour ne jamais envoyer l'octet 7, qui est aussi l'ordre
        // « reset » : un octet de tête perdu sur le fil redémarrait le Roomba (relecture du 24/10).
        GROUP_7_16 to 10,
        21 to 1,  // état de charge
        22 to 2,  // tension (mV)
        23 to 2,  // courant (mA, signé)
        25 to 2,  // charge (mAh)
        26 to 2,  // capacité (mAh)
        34 to 1,  // source de charge (bit 1 = base)
        35 to 1,  // mode : 0 éteint, 1 passif, 2 sécurité, 3 complet
        43 to 2,  // impulsions roue gauche
        44 to 2,  // impulsions roue droite
        45 to 1,  // détecteurs de proximité de l'avant (si le modèle en a)
    )
    val SENSOR_BYTES = SENSOR_PACKETS.sumOf { it.second }

    /**
     * Les autres capteurs du Roomba (série 600), lus en plus quand il les accepte (sinon on reste aux capteurs de base) :
     * mur à droite, mur virtuel, surintensité des moteurs, saleté, infrarouge (balises de la base), boutons, température
     * de la batterie, intensité du capteur de mur, intensités des 6 détecteurs de proximité, infrarouge gauche/droite,
     * roulette avant (il avance vraiment ?).
     */
    val EXTRA_PACKETS = listOf(
        17 to 1, 18 to 1, 24 to 1, 27 to 2,
        46 to 2, 47 to 2, 48 to 2, 49 to 2, 50 to 2, 51 to 2,
        52 to 1, 53 to 1, 58 to 1,
    )
    val EXTENDED_PACKETS = SENSOR_PACKETS + EXTRA_PACKETS
    val EXTENDED_BYTES = EXTENDED_PACKETS.sumOf { it.second }
    /** Direction (degrés, + = droite) des 6 détecteurs de proximité : gauche, avant gauche, centre gauche, centre droit… */
    val LIGHT_ANGLES = doubleArrayOf(-65.0, -32.0, -10.0, 10.0, 32.0, 65.0)

    fun querySensors(extended: Boolean = false): ByteArray {
        val list = if (extended) EXTENDED_PACKETS else SENSOR_PACKETS
        return bytes(QUERY_LIST, list.size, *list.map { it.first }.toIntArray())
    }
    fun sensorBytes(extended: Boolean) = if (extended) EXTENDED_BYTES else SENSOR_BYTES

    class Sensors(
        val bumpRight: Boolean,
        val bumpLeft: Boolean,
        val wheelDropRight: Boolean,
        val wheelDropLeft: Boolean,
        /** Vide sous l'un des 4 capteurs (bord de marche, robot soulevé). */
        val cliff: Boolean,
        val chargingState: Int,
        val voltageMv: Int,
        val currentMa: Int,
        val chargeMah: Int,
        val capacityMah: Int,
        val onHomeBase: Boolean,
        val oiMode: Int,
        val leftCounts: Int,
        val rightCounts: Int,
        val lightBumper: Int,
        // ---- capteurs en plus (absents si le Roomba ne les donne pas) ----
        /** Mur tout près sur sa droite. */
        val wall: Boolean? = null,
        val virtualWall: Boolean? = null,
        /** Surintensité : bit 4 roue gauche, bit 3 roue droite, bit 2 brosse principale, bit 0 brosse latérale. */
        val overcurrent: Int = 0,
        val dirt: Int = 0,
        /** Code infrarouge reçu (balises de la base : 160-175), 0 sinon. */
        val irOmni: Int = 0,
        /** Boutons : bit 0 CLEAN, 1 SPOT, 2 DOCK. */
        val buttons: Int = 0,
        val batteryTempC: Int? = null,
        val wallSignal: Int = 0,
        /** Intensité des 6 détecteurs de proximité (0-4095), dans l'ordre de LIGHT_ANGLES. */
        val lightSignals: IntArray? = null,
        val irLeft: Int = 0,
        val irRight: Int = 0,
        /** Roulette avant : bit 0 = elle tourne (il avance vraiment). */
        val stasis: Int? = null,
    ) {
        val wheelOvercurrent get() = overcurrent and 0x18 != 0
        /** La base (ses balises infrarouges) est en vue. */
        val dockInSight get() = listOf(irOmni, irLeft, irRight).any { isDockIr(it) }
        val bump get() = bumpLeft || bumpRight
        val wheelDrop get() = wheelDropLeft || wheelDropRight
        val batteryPct: Int? get() = if (capacityMah in 500..10_000) (chargeMah * 100 / capacityMah).coerceIn(0, 100) else null
        val charging get() = chargingState in 1..3
        val modeName get() = when (oiMode) { 0 -> "éteint"; 1 -> "passif"; 2 -> "sécurité"; 3 -> "complet"; else -> "?" }
    }

    /**
     * Lit la réponse à [querySensors] ; null si la taille ou les valeurs sont incohérentes (mauvaise vitesse de liaison).
     * [modeOffset] : certains Roomba (firmware release-stm32v2-v3.9.7, 24/10/2026) annoncent leur mode avec un cran de
     * plus (passif 2, sécurité 3, complet 4) ; le mode rendu est toujours celui de la norme (passif 1…).
     */
    fun parseSensors(b: ByteArray, extended: Boolean = false, modeOffset: Int = 0): Sensors? {
        if (b.size != sensorBytes(extended)) return null
        // Position de chaque paquet dans la réponse, d'après la liste demandée.
        val off = HashMap<Int, Int>()
        var o = 0
        for ((id, size) in if (extended) EXTENDED_PACKETS else SENSOR_PACKETS) { off[id] = o; o += size }
        fun u8(i: Int) = b[i].toInt() and 0xFF
        fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
        fun s16(i: Int) = u16(i).toShort().toInt()
        fun p8(id: Int) = u8(off.getValue(id))
        fun p16(id: Int) = u16(off.getValue(id))
        val g = off.getValue(GROUP_7_16) // 7 pare-chocs, 8 mur, 9-12 vides, 13 mur virtuel, 14 surintensité, 15 saleté, 16 libre
        val bumps = u8(g)
        val mode = p8(35) - modeOffset
        val chargingState = p8(21)
        // Contrôles de cohérence : bits inutilisés à zéro, mode et état de charge dans leurs plages
        if (bumps and 0xF0 != 0 || mode !in 0..3 || chargingState > 5) return null
        for (i in 1..6) if (u8(g + i) > 1) return null // mur, 4 vides, mur virtuel : 0 ou 1
        if (u8(g + 7) and 0xE0 != 0) return null
        if (extended) {
            if (p16(27) > 1023 || p8(58) > 3) return null
            for (id in 46..51) if (p16(id) > 4095) return null
        }
        return Sensors(
            bumpRight = bumps and 1 != 0,
            bumpLeft = bumps and 2 != 0,
            wheelDropRight = bumps and 4 != 0,
            wheelDropLeft = bumps and 8 != 0,
            cliff = (2..5).any { u8(g + it) == 1 },
            chargingState = chargingState,
            voltageMv = p16(22),
            currentMa = s16(off.getValue(23)),
            chargeMah = p16(25),
            capacityMah = p16(26),
            onHomeBase = p8(34) and 2 != 0,
            oiMode = mode,
            leftCounts = p16(43),
            rightCounts = p16(44),
            lightBumper = p8(45),
            wall = u8(g + 1) == 1,
            virtualWall = u8(g + 6) == 1,
            overcurrent = u8(g + 7),
            dirt = u8(g + 8),
            irOmni = if (extended) p8(17) else 0,
            buttons = if (extended) p8(18) else 0,
            batteryTempC = if (extended) b[off.getValue(24)].toInt() else null,
            wallSignal = if (extended) p16(27) else 0,
            lightSignals = if (extended) IntArray(6) { p16(46 + it) } else null,
            irLeft = if (extended) p8(52) else 0,
            irRight = if (extended) p8(53) else 0,
            stasis = if (extended) p8(58) else null,
        )
    }

    /** Écart d'impulsions entre deux lectures (les compteurs repassent par 0 après 65 535). */
    fun countDelta(prev: Int, now: Int): Int {
        var d = now - prev
        if (d > 32767) d -= 65536
        if (d < -32768) d += 65536
        return d
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}

/**
 * Position estimée par les roues : x devant au départ, y à droite (m), cap en degrés (+ = à droite).
 */
class Odometry {
    var x = 0.0; private set
    var y = 0.0; private set
    var headingDeg = 0.0; private set
    var travelledM = 0.0; private set
    /** Le visage regarde vers l'arrière du Roomba : « devant » pour Joey = derrière pour le Roomba. */
    @Volatile var reversed = false
    /** Correction des angles (auto-test : « 90° » des roues = combien en vrai). */
    @Volatile var turnScale = 1.0
    private var lastL: Int? = null
    private var lastR: Int? = null
    /**
     * Cap mesuré par un gyroscope fixé au corps (le téléphone, quand il n'est pas sur la nacelle), en degrés, + = droite,
     * ou null. Plus juste que les roues (qui patinent sur un tapis) : s'il est là, c'est lui qui donne les rotations.
     */
    @Volatile var gyroHeading: (() -> Double?)? = null
    private var lastGyro: Double? = null
    /** Rotations prises au gyroscope (pour le journal / les tests). */
    @Volatile var gyroUsed = false
        private set

    fun reset() { x = 0.0; y = 0.0; headingDeg = 0.0; travelledM = 0.0 }

    /** Recale la position (après un recalage sur la carte, une étiquette…), sans toucher à la distance parcourue. */
    fun setPose(nx: Double, ny: Double, nh: Double) { x = nx; y = ny; headingDeg = com.joey.app.gimbal.Head.wrap(nh) }

    /** Nouveaux compteurs ; renvoie (distance m, rotation °) depuis la lecture précédente. */
    fun update(leftCounts: Int, rightCounts: Int): Pair<Double, Double> {
        val pl = lastL; val pr = lastR
        lastL = leftCounts; lastR = rightCounts
        if (pl == null || pr == null) return 0.0 to 0.0
        val dl = RoombaOI.countDelta(pl, leftCounts) * RoombaOI.MM_PER_COUNT / 1000.0
        val dr = RoombaOI.countDelta(pr, rightCounts) * RoombaOI.MM_PER_COUNT / 1000.0
        // Saut aberrant (lecture ratée) : ignoré
        if (kotlin.math.abs(dl) > 0.3 || kotlin.math.abs(dr) > 0.3) return 0.0 to 0.0
        val d = (dl + dr) / 2 * (if (reversed) -1 else 1)
        // Roue gauche plus loin que la droite : le robot tourne à droite
        val wheelTh = Math.toDegrees((dl - dr) / (RoombaOI.WHEEL_BASE_MM / 1000.0)) * turnScale
        // Le gyroscope, s'il est là et cohérent (pas de saut absurde entre deux lectures) : il remplace les roues.
        val g = runCatching { gyroHeading?.invoke() }.getOrNull()
        val lg = lastGyro
        lastGyro = g
        val gyroTh = if (g != null && lg != null) com.joey.app.gimbal.Head.wrap(g - lg) else null
        val dth = if (gyroTh != null && kotlin.math.abs(gyroTh) < 30) gyroTh else wheelTh
        gyroUsed = gyroTh != null && kotlin.math.abs(gyroTh) < 30
        val mid = Math.toRadians(headingDeg + dth / 2)
        x += d * kotlin.math.cos(mid)
        y += d * kotlin.math.sin(mid)
        headingDeg = com.joey.app.gimbal.Head.wrap(headingDeg + dth)
        travelledM += kotlin.math.abs(d)
        return d to dth
    }
}
