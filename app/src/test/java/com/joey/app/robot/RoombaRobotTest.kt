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
        if (mode < 2) { right = 0; left = 0 }
        val dl = left / 1000.0 * dt; val dr = right / 1000.0 * dt
        countsL += dl * 1000 / RoombaOI.MM_PER_COUNT; countsR += dr * 1000 / RoombaOI.MM_PER_COUNT
        val d = (dl + dr) / 2
        val dth = Math.toDegrees((dl - dr) / 0.235)
        x += d * cos(Math.toRadians(h)); y += d * sin(Math.toRadians(h)); h += dth
        if (cliff && mode >= 2) mode = 1 // le mode sécurité le rend passif
        if (onBase && x < -0.1) onBase = false
    }

    @Synchronized override fun write(b: ByteArray) {
        physics()
        if (curBaud != baudOk) return // mauvaise vitesse : il n'entend rien
        for (v in b) inBuf.add(v.toInt() and 0xFF)
        while (inBuf.isNotEmpty()) {
            val op = inBuf[0]
            val need = when (op) { 128, 131, 132, 143 -> 1; 145 -> 5; 141 -> 2; 140 -> if (inBuf.size >= 3) 3 + 2 * inBuf[2] else 99
                149 -> if (inBuf.size >= 2) 2 + inBuf[1] else 99; else -> 1 }
            if (inBuf.size < need) return
            val c = inBuf.subList(0, need).toList()
            repeat(need) { inBuf.removeAt(0) }
            when (op) {
                128 -> mode = 1
                131 -> mode = if (onBase) 1 else 2
                132 -> mode = 3
                143 -> mode = 1
                145 -> if (mode >= 2) { right = ((c[1] shl 8) or c[2]).toShort().toInt(); left = ((c[3] shl 8) or c[4]).toShort().toInt() }
                141 -> songs.add(c[1])
                149 -> out.addAll(sensors().toList())
            }
        }
    }

    private fun sensors(): ByteArray {
        val l = countsL.toInt() and 0xFFFF; val r = countsR.toInt() and 0xFFFF
        val v = intArrayOf(
            if (bump) 3 else 0, if (cliff) 1 else 0, 0, 0, 0,
            if (onBase) 2 else 0, 0x3A, 0x98, 0xFF, 0x38, 0x07, 0xD0, 0x0B, 0xB8,
            if (onBase) 2 else 0, mode, l shr 8, l and 0xFF, r shr 8, r and 0xFF, 0,
        )
        return ByteArray(v.size) { v[it].toByte() }
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

    @Test fun refusesOverACliff() {
        val (f, r) = connected()
        f.cliff = true
        Thread.sleep(200)
        val res = r.drive(0.5)
        assertTrue(res, res.contains("vide"))
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
}
