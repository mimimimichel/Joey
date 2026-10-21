package com.joey.app.gimbal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpgProtocolTest {
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
    private fun unhex(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    @Test fun framesMatchTheFeiyuApp() {
        assertEquals("a55a001005ff1b005f00ec35", hex(SpgProtocol.joystick(27, 95)))
        assertEquals("a55a001005ff00000000d52e", hex(SpgProtocol.STOP))
        assertEquals("a55a00400205024bdf", hex(SpgProtocol.RECENTER))
        assertEquals("a55a004001065a4e", hex(SpgProtocol.PING))
        assertEquals("a55a020601669960", hex(SpgProtocol.readParam(0x66, 2)))
    }

    @Test fun negativeJoystick() {
        // Joystick physique vu dans la capture : pan -141
        val m = SpgProtocol.parse(unhex("a55a001005ff73ff00001f38")) as SpgProtocol.Stick
        assertEquals(-141, m.pan)
        assertEquals(0, m.tilt)
        assertEquals("a55a001005ff73ff00001f38", hex(SpgProtocol.joystick(-141, 0)))
    }

    @Test fun anglesDecoded() {
        val a = SpgProtocol.parse(unhex("a55a03100701b1b9ffff77ccc8a8")) as SpgProtocol.Angles
        assertEquals(-179.99, a.tilt, 1e-6)
        assertEquals(-0.01, a.roll, 1e-6)
        assertEquals(-131.93, a.pan, 1e-6)
    }

    @Test fun badCrcRejected() {
        assertNull(SpgProtocol.parse(unhex("a55a03100701b1b9ffff77ccc8a9")))
    }

    @Test fun wholeCaptureParsesAndReencodes() {
        val lines = javaClass.classLoader!!.getResourceAsStream("spg_session1.txt")!!.bufferedReader().readLines()
        var parsed = 0
        var reencoded = 0
        for (l in lines) {
            if (l.startsWith("#")) continue
            val p = l.split(" ")
            if (p.size != 3 || !p[2].startsWith("a55a")) continue
            val m = SpgProtocol.parse(unhex(p[2]))
            assertNotNull("trame refusée : $l", m)
            parsed++
            if (p[1] == "TX" && m is SpgProtocol.Stick) {
                assertEquals(p[2], hex(SpgProtocol.joystick(m.pan, m.tilt)))
                reencoded++
            }
        }
        assertTrue(parsed > 5000)
        assertEquals(112, reencoded)
    }
}
