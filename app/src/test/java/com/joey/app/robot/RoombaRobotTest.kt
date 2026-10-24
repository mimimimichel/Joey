package com.joey.app.robot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Faux Roomba : comprend le protocole, fait tourner ses roues (impulsions) et répond aux lectures de capteurs. */
class FakeRoomba(private val baudOk: Int = 115_200) : SerialLink {
    var mode = 1 // passif
    var right = 0; var left = 0 // mm/s
    var countsL = 1000.0; var countsR = 64000.0 // proche du passage à 0, pour tester le rebouclage
    var bump = false; var cliff = false; var onBase = false
    var blocked = false // coincé : les roues ne tournent plus
    /** Le monde simulé : true si (x, y) est dans un obstacle (mur, meuble). Le pare-chocs s'enfonce dedans. */
    var wall: ((Double, Double) -> Boolean)? = null
    /** Un vide (escalier) : les capteurs de vide à l'avant (à 15 cm du centre) le sentent. */
    var hole: ((Double, Double) -> Boolean)? = null
    var maxInHole = 0.0
    var bumpAuto = false
    /** Côté du pare-chocs enfoncé (bits du paquet 7 : 1 droite, 2 gauche, 3 les deux). */
    var bumpBits = 3
    var bumps = 0
    var slipR = 1.0
    var mute = false    // fil qui bouge : il n'entend plus rien
    /** Comme le vrai le 24/10 : le mode complet sur sa base le fait taire (mode éteint) jusqu'au prochain « start ». */
    var silentAfterFullOnBase = false
    /** Le vrai (firmware stm32v2-v3.9.7) annonce son mode avec un cran de plus : passif 2, sécurité 3, complet 4. */
    var modeReportOffset = 0
    var off = false
    /** Comme le vrai le 24/10 à 17 h 04 : sur sa base, ses roues ne tournent pas en mode complet (seul CLEAN le sort). */
    var wheelsLockedOnBase = false
    /** Programme de nettoyage lancé (CLEAN) : il recule hors de sa base, brosses en marche. */
    var cleaning = false
    var cleans = 0
    var motorsOn = false
    var x = 0.0; var y = 0.0; var h = 0.0 // vraie position (m, °)
    val songs = ArrayList<Int>()
    var curBaud = 0
    private var last = System.nanoTime()
    private val out = ArrayList<Byte>()
    private val inBuf = ArrayList<Int>()

    @Synchronized private fun physics() {
        val now = System.nanoTime()
        val dt = (now - last) / 1e9
        last = now
        if (cleaning) { right = -150; left = -150 } else if (mode < 2 || (wheelsLockedOnBase && onBase)) { right = 0; left = 0 }
        val dl = if (blocked) 0.0 else left / 1000.0 * dt; val dr = if (blocked) 0.0 else right / 1000.0 * dt
        // [slipR] : la roue droite compte un peu trop (tapis, roue usée) : l'odométrie dérive comme sur le vrai.
        countsL += dl * 1000 / RoombaOI.MM_PER_COUNT; countsR += dr * slipR * 1000 / RoombaOI.MM_PER_COUNT
        val d = (dl + dr) / 2
        val dth = Math.toDegrees((dl - dr) / 0.235)
        val w = wall
        if (w != null) {
            // Le pare-chocs (le demi-cercle avant, à 17-18 cm du centre) touche-t-il un obstacle après ce mouvement ? Tous les
            // 10° : un pied de chaise de 4 cm ne passe pas entre deux points de contrôle.
            val nx = x + d * cos(Math.toRadians(h)); val ny = y + d * sin(Math.toRadians(h)); val nh = h + dth
            val hits = (-80..80 step 10).filter { a -> w(nx + 0.175 * cos(Math.toRadians(nh + a)), ny + 0.175 * sin(Math.toRadians(nh + a))) }
            val hit = hits.isNotEmpty()
            if (hit) bumpBits = when { hits.all { it > 0 } -> 1; hits.all { it < 0 } -> 2; else -> 3 }
            if (hit && d > 0) {
                if (!bumpAuto) bumps++
                bumpAuto = true
                // Il ne traverse pas : seules les roues tournent (pour l'odométrie, il a avancé : comme un vrai qui patine)
                h = nh
                return
            }
            if (!hit) bumpAuto = false
        }
        x += d * cos(Math.toRadians(h)); y += d * sin(Math.toRadians(h)); h += dth
        hole?.let { z ->
            // Les capteurs de vide sont devant ; le centre du robot ne doit jamais se retrouver au-dessus du vide.
            cliff = listOf(-30.0, 0.0, 30.0).any { a -> z(x + 0.15 * cos(Math.toRadians(h + a)), y + 0.15 * sin(Math.toRadians(h + a))) }
            if (z(x, y)) maxInHole = 1.0
            if (cliff && mode >= 2 && right + left > 0) mode = 1 // comme le vrai : avancer vers un vide est interdit, reculer non
        }
        if (cliff && mode >= 2 && right + left > 0) mode = 1 // le mode sécurité le rend passif (s'il avance vers le vide)
        if (onBase && x < -0.1) onBase = false
    }

    @Synchronized override fun write(b: ByteArray) {
        physics()
        if (curBaud != baudOk || mute) return // mauvaise vitesse (ou fil débranché) : il n'entend rien
        for (v in b) inBuf.add(v.toInt() and 0xFF)
        while (inBuf.isNotEmpty()) {
            val op = inBuf[0]
            if (off && op != 128) { inBuf.removeAt(0); continue } // éteint : il n'attend que « start »
            val need = when (op) { 128, 131, 132, 143 -> 1; 145 -> 5; 138 -> 2; 141 -> 2; 140 -> if (inBuf.size >= 3) 3 + 2 * inBuf[2] else 99
                149 -> if (inBuf.size >= 2) 2 + inBuf[1] else 99; else -> 1 }
            if (inBuf.size < need) return
            val c = inBuf.subList(0, need).toList()
            repeat(need) { inBuf.removeAt(0) }
            when (op) {
                128 -> { mode = 1; off = false }
                131 -> { mode = if (onBase) 1 else 2; cleaning = false }
                132 -> if (silentAfterFullOnBase && onBase) { off = true; mode = 0 } else { mode = 3; cleaning = false }
                135 -> { mode = 1; cleaning = true; cleans++; motorsOn = true }
                138 -> motorsOn = c[1] != 0
                143 -> mode = 1
                145 -> if (mode >= 2) { right = ((c[1] shl 8) or c[2]).toShort().toInt(); left = ((c[3] shl 8) or c[4]).toShort().toInt() }
                141 -> songs.add(c[1])
                149 -> out.addAll(sensors(c.drop(2)).toList())
            }
        }
    }

    var supportsExtra = true
    var buttons = 0
    var lightSignal = 0

    /** Réponse paquet par paquet, comme le vrai (un Roomba qui ne connaît pas un paquet ne répond rien d'utile). */
    private fun sensors(ids: List<Int>): ByteArray {
        val l = countsL.toInt() and 0xFFFF; val r = countsR.toInt() and 0xFFFF
        val out = ArrayList<Int>()
        for (id in ids) {
            when (id) {
                7 -> out.add(if (bump) 3 else if (bumpAuto) bumpBits else 0)
                // Groupe 1 (paquets 7 à 16) : pare-chocs, mur, 4 vides, mur virtuel, surintensité, saleté, octet libre.
                1 -> { out.add(if (bump) 3 else if (bumpAuto) bumpBits else 0); out.add(0); out.add(if (cliff) 1 else 0); repeat(3) { out.add(0) }
                    out.add(0); out.add(0); out.add(0); out.add(0) }
                9 -> out.add(if (cliff) 1 else 0); 10, 11, 12 -> out.add(0)
                21 -> out.add(if (onBase) 2 else 0)
                22 -> { out.add(0x3A); out.add(0x98) }
                23 -> { out.add(0xFF); out.add(0x38) }
                25 -> { out.add(0x07); out.add(0xD0) }
                26 -> { out.add(0x0B); out.add(0xB8) }
                34 -> out.add(if (onBase) 2 else 0)
                35 -> out.add(mode + modeReportOffset)
                43 -> { out.add(l shr 8); out.add(l and 0xFF) }
                44 -> { out.add(r shr 8); out.add(r and 0xFF) }
                45 -> out.add(0)
                else -> {
                    if (!supportsExtra) return ByteArray(3) // il ne comprend pas : réponse incohérente
                    val size = RoombaOI.EXTRA_PACKETS.first { it.first == id }.second
                    val v = when (id) { 18 -> buttons; 46, 47, 48, 49, 50, 51 -> lightSignal; 24 -> 25; else -> 0 }
                    if (size == 2) { out.add(v shr 8); out.add(v and 0xFF) } else out.add(v)
                }
            }
        }
        return ByteArray(out.size) { out[it].toByte() }
    }

    @Synchronized override fun read(n: Int, timeoutMs: Int): ByteArray? {
        if (out.size < n) return null
        val r = ByteArray(n) { out[it] }
        repeat(n) { out.removeAt(0) }
        return r
    }
    @Synchronized override fun clearInput() { out.clear() }
    override fun setWakeLine(low: Boolean) {}
    override fun setBaud(baud: Int) { curBaud = baud }
    override fun close() {}
}


class RoombaRobotTest {
    private fun connected(): Pair<FakeRoomba, RoombaRobot> {
        val f = FakeRoomba()
        val r = RoombaRobot(f)
        r.connect()
        Thread.sleep(200)
        return f to r
    }

    @Test fun encodingAndOdometry() {
        assertEquals(listOf(145, 0xFF, 0x38, 0x01, 0xF4), RoombaOI.driveDirect(-200, 500).map { it.toInt() and 0xFF })
        val (rr, l) = RoombaOI.wheelSpeeds(0.0, 90.0)
        assertTrue(l > 0 && rr < 0) // tourner à droite : roue gauche plus rapide
        assertEquals(null, RoombaOI.parseSensors(ByteArray(RoombaOI.SENSOR_BYTES) { 0x7F }))
    }

    @Test fun connectsAndSings() {
        val (f, r) = connected()
        assertTrue(r.running)
        assertEquals(115_200, f.curBaud)
        assertTrue(f.songs.isNotEmpty())
        r.disconnect()
    }

    @Test fun connectsAt57600Too() {
        val f = FakeRoomba(baudOk = 57_600)
        val r = RoombaRobot(f)
        r.connect()
        assertEquals(57_600, f.curBaud)
        r.disconnect()
    }

    @Test fun drivesHalfAMeterAndTurns() {
        val (f, r) = connected()
        r.drive(0.5)
        assertTrue("x ${f.x}", abs(f.x - 0.5) < 0.06)
        r.turn(90.0)
        assertTrue("cap ${f.h}", abs(f.h - 90) < 6)
        r.park()
        r.disconnect()
    }

    @Test fun goesToAPoint() {
        val (f, r) = connected()
        r.goTo(0.4, 0.4)
        assertTrue("pos ${f.x},${f.y}", hypot(f.x - 0.4, f.y - 0.4) < 0.1)
        r.disconnect()
    }

    @Test fun bumperStops() {
        val (f, r) = connected()
        Thread { Thread.sleep(600); f.bump = true }.start()
        val res = r.drive(2.0)
        assertTrue(res, res.contains("obstacle"))
        assertTrue("x ${f.x}", f.x < 1.0)
        r.disconnect()
    }

    @Test fun fullTurn() {
        val (f, r) = connected()
        val res = r.turn(360.0)
        assertTrue(res, res.startsWith("tourné"))
        assertTrue("cap ${f.h}", abs(f.h - 360) < 8)
        r.disconnect()
    }

    @Test fun reversedMountDrivesTowardTheFace() {
        val (f, r) = connected()
        r.reversed = true
        r.drive(0.3)
        assertTrue("x ${f.x}", f.x < -0.24) // le Roomba recule : le visage est à l'arrière
        assertTrue("odo ${r.x}", abs(r.x - 0.3) < 0.06) // et pour Joey, il a bien avancé
        r.disconnect()
    }

    @Test fun vacuumOnOff() {
        val (_, r) = connected()
        assertTrue(r.vacuum(true).contains("marche"))
        assertTrue(r.vacuuming)
        r.vacuum(false)
        assertTrue(!r.vacuuming)
        r.disconnect()
    }

    @Test fun readsAllSensorsWhenItCan() {
        val (f, r) = connected()
        assertTrue(r.extended)
        f.lightSignal = 300
        Thread.sleep(250)
        assertTrue(r.obstacleNear)
        var pressed = ""
        r.buttonQuietMs = 0
        r.onButton = { pressed = it }
        f.buttons = 1
        Thread.sleep(250)
        assertEquals("clean", pressed)
        r.disconnect()
    }

    @Test fun fallsBackToBasicSensors() {
        val f = FakeRoomba()
        f.supportsExtra = false
        val r = RoombaRobot(f)
        r.connect()
        Thread.sleep(200)
        assertTrue(!r.extended)
        assertTrue(r.linkOk)
        r.drive(0.2)
        assertTrue("x ${f.x}", f.x > 0.15)
        r.disconnect()
    }

    @Test fun gyroCorrectsWheelErrors() {
        val (f, r) = connected()
        r.turnScale = 1.3 // roues mal réglées (tapis) : « 90° » des roues = 69° en vrai
        r.turn(90.0)
        val wheelsOnly = f.h
        assertTrue("roues seules : $wheelsOnly", abs(wheelsOnly - 90) > 12)
        // Avec le gyroscope du téléphone fixé au corps : le vrai quart de tour.
        r.gyroHeading = { f.h }
        Thread.sleep(150)
        val start = f.h
        r.turn(90.0)
        assertTrue("avec le gyroscope : ${f.h - start}", abs(f.h - start - 90) < 5)
        assertTrue(r.gyroUsed)
        r.disconnect()
    }

    @Test fun getsAllSensorsBackAfterACableGlitch() {
        val (f, r) = connected()
        r.extendedRetryMs = 500
        assertTrue("tous les capteurs au départ", r.extended)
        f.mute = true // le fil bouge : plus rien
        val t0 = System.currentTimeMillis()
        while (r.extended && System.currentTimeMillis() - t0 < 8000) Thread.sleep(50)
        assertTrue("repassé aux capteurs de base", !r.extended)
        f.mute = false
        val t1 = System.currentTimeMillis()
        while (!r.extended && System.currentTimeMillis() - t1 < 8000) Thread.sleep(50)
        assertTrue("de nouveau tous ses capteurs", r.extended)
        r.disconnect()
    }

    @Test fun keepsAwakeButLetsItSeekItsDock() {
        val (f, r) = connected()
        r.awakeAfterMs = 300
        f.mode = 1 // il est retombé en mode passif (au repos) : il s'endormirait au bout de 5 min
        Thread.sleep(1200)
        assertEquals("gardé éveillé (mode sécurité)", 2, f.mode)
        r.seekDock() // il cherche sa base en mode passif : on ne doit pas l'interrompre
        Thread.sleep(1200)
        assertEquals("laissé chercher sa base", 1, f.mode)
        r.disconnect()
    }

    @Test fun seesItIsStuckAndGetsFree() {
        val (f, r) = connected()
        f.blocked = true
        val res = r.drive(0.5)
        assertTrue(res, res.contains("bloqué"))
        assertTrue(r.stuck)
        f.blocked = false
        val u = r.unstick(0)
        assertTrue(u, u.contains("dégage"))
        assertTrue("x ${f.x}", f.x < 0) // il a reculé
        // Bloqué de face (pas de côté touché) : demi-tour, il repart par où il est venu.
        assertTrue("cap ${f.h} / $u", abs(abs(f.h) - 180) < 12 && u.contains("demi-tour"))
        r.disconnect()
    }

    /** Touché sur un côté : il tourne de l'autre côté (40°), sans demi-tour. */
    @Test fun touchedOnTheSideTurnsAway() {
        val (f, r) = connected()
        f.wall = { x, y -> x > 0.3 && y > 0.0 } // obstacle devant à droite
        val res = r.drive(0.6)
        assertTrue(res, res.contains("touché") || res.contains("bloqué") || res.contains("obstacle"))
        val u = r.unstick(0)
        assertTrue("cap ${f.h} / $u", abs(abs(f.h) - 40) < 10 && !u.contains("demi-tour"))
        r.disconnect()
    }

    @Test fun followsAPathSmoothly() {
        val (f, r) = connected()
        val t0 = System.currentTimeMillis()
        // Un trajet en L : 1 m devant, puis 0,8 m à droite.
        val res = r.followPath(listOf(1.0 to 0.0, 1.0 to 0.8), cruise = 0.4)
        val ms = System.currentTimeMillis() - t0
        assertTrue(res, res == "arrivé")
        assertTrue("pos ${f.x},${f.y}", hypot(f.x - 1.0, f.y - 0.8) < 0.2)
        assertTrue("trop lent : $ms ms", ms < 12_000) // ~1,8 m à 0,4 m/s, virage compris (avant : arrêt, pivot, arrêt…)
        r.disconnect()
    }

    @Test fun neverDrivesBlind() {
        val (f, r) = connected()
        Thread { Thread.sleep(500); f.mute = true }.start()
        val t0 = System.currentTimeMillis()
        val res = r.drive(2.0)
        assertTrue(res, res.contains("liaison"))
        assertTrue("trop long", System.currentTimeMillis() - t0 < 5000) // avant : il roulait 43 s à l'aveugle
        r.disconnect()
    }

    @Test fun refusesOverACliff() {
        val (f, r) = connected()
        f.cliff = true
        Thread.sleep(200)
        val res = r.drive(0.5)
        assertTrue(res, res.contains("vide"))
        r.disconnect()
    }

    /** 24/10 : sur sa base, le mode complet le fait taire. Il le relance, quitte la base par CLEAN, coupe les brosses. */
    @Test fun quitteSaBaseMemeSiLeModeCompletLeFaitTaire() {
        val f = FakeRoomba()
        f.onBase = true; f.silentAfterFullOnBase = true
        val logs = java.util.Collections.synchronizedList(ArrayList<String>())
        val r = RoombaRobot(f, log = { logs.add(it) })
        r.connect()
        Thread.sleep(200)
        val res = r.drive(0.1)
        assertTrue("$res / $logs", !f.onBase && f.cleans == 1)
        assertTrue("brosses coupées", !f.motorsOn && !f.cleaning)
        assertTrue("$logs", logs.any { "CLEAN" in it })
        r.disconnect()
    }

    /** Le vrai Roomba du 24/10 : mode annoncé avec un cran de plus. Il le voit, et quitte sa base en mode complet. */
    @Test fun modeAnnonceDecaleDUnCran() {
        val f = FakeRoomba()
        f.onBase = true; f.modeReportOffset = 1
        val logs = java.util.Collections.synchronizedList(ArrayList<String>())
        val r = RoombaRobot(f, log = { logs.add(it) })
        r.connect()
        Thread.sleep(200)
        assertEquals(1, r.modeOffset)
        assertEquals(1, r.sensors?.oiMode)
        val res = r.drive(0.1)
        assertTrue("$res / $logs", !f.onBase && f.cleans == 0 && logs.none { "s'est tu" in it || "coupée" in it })
        assertEquals(2, r.sensors?.oiMode)
        r.disconnect()
    }

    @Test fun modeAnnonceSelonLaNorme() {
        val (f, r) = connected()
        assertEquals(0, r.modeOffset)
        assertEquals(1, r.sensors?.oiMode)
        r.disconnect()
    }

    /** 24/10, 17 h 04 : roues immobiles sur la base en mode complet ; il sort par CLEAN et coupe les brosses. */
    @Test fun quitteSaBaseParCleanSiLesRouesNeTournentPas() {
        val f = FakeRoomba()
        f.onBase = true; f.wheelsLockedOnBase = true
        val logs = java.util.Collections.synchronizedList(ArrayList<String>())
        val r = RoombaRobot(f, log = { logs.add(it) })
        r.connect()
        Thread.sleep(200)
        val res = r.drive(0.1)
        assertTrue("$res / $logs", !f.onBase && f.cleans == 1 && !f.motorsOn && !f.cleaning)
        assertTrue("$logs", logs.any { "toujours sur ma base" in it })
        r.disconnect()
    }

    @Test fun leavesItsBaseFirst() {
        val f = FakeRoomba()
        f.onBase = true
        val r = RoombaRobot(f)
        r.connect()
        Thread.sleep(200)
        r.drive(0.2)
        assertTrue("x ${f.x}", f.x < 0) // il a reculé pour quitter la base avant d'avancer
        assertTrue(!f.onBase)
        r.disconnect()
    }

    /** Le point d'approche de la base est du côté où il en sort (il accoste en avançant, il repart en reculant). */
    @Test fun lePointDApprocheDeLaBaseEstDuBonCote() {
        for (rev in listOf(false, true)) {
            val f = FakeRoomba()
            f.onBase = true
            val r = RoombaRobot(f)
            r.reversed = rev
            r.connect()
            Thread.sleep(200)
            r.resetOdometry() // sur sa base : l'origine de la carte
            r.drive(if (rev) -0.05 else 0.05) // quitter la base (il recule d'abord), puis un petit pas
            val n = Navigator(r, NavGrid())
            val (ax, _) = n.dockApproach()
            assertTrue("monté à l'envers=$rev : sorti en x=${r.x}, approche en x=$ax", r.x * ax > 0)
            r.disconnect()
        }
    }
}
