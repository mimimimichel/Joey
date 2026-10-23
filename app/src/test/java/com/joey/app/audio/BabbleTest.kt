package com.joey.app.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class BabbleTest {
    private fun tone(hz: Double, ms: Int, amp: Double, sr: Int = 24_000): ByteArray {
        val n = sr * ms / 1000
        val b = ByteArray(n * 2)
        for (i in 0 until n) {
            val v = (sin(2 * PI * hz * i / sr) * amp * 32767).toInt()
            b[2 * i] = (v and 0xFF).toByte(); b[2 * i + 1] = (v shr 8).toByte()
        }
        return b
    }

    @Test fun syllabesDonnentDesNotes() {
        val b = Babble(24_000)
        val notes = ArrayList<Int>()
        repeat(4) {
            notes += b.feed(tone(120.0, 150, 0.0))   // silence
            notes += b.feed(tone(120.0, 150, 0.4))   // syllabe
        }
        assertTrue("$notes", notes.size >= 3)
        // 120 Hz ≈ si2 (MIDI 47) + 2 octaves = 71
        assertTrue("$notes", notes.all { it in 69..75 })
    }

    @Test fun silenceNeDonneRien() {
        val b = Babble(24_000)
        assertTrue(b.feed(tone(120.0, 1000, 0.005)).isEmpty())
    }
}
