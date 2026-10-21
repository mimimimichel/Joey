package com.joey.app.audio

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class BeatDetectorTest {
    @Test fun findsTheBeatOfAKickDrum() {
        var t = 0L
        val d = BeatDetector(now = { t })
        var beats = 0
        // 10 s à 120 BPM : un « boum » de 60 ms toutes les 500 ms, sur un fond de musique plus faible.
        val chunk = ShortArray(1600) // 100 ms
        var sample = 0L
        repeat(100) {
            for (i in chunk.indices) {
                val ms = (sample * 1000 / 16000) % 500
                val kick = if (ms < 60) 0.6 * sin(2 * PI * 60 * sample / 16000.0) else 0.0
                val bg = 0.05 * sin(2 * PI * 440 * sample / 16000.0)
                chunk[i] = ((kick + bg) * 32767).toInt().toShort()
                sample++
                if (i % 16 == 0) t = sample * 1000 / 16000
            }
            d.feed(chunk, chunk.size) { beats++ }
        }
        assertTrue("temps trouvés : $beats", beats in 17..22)
        assertTrue("tempo ${d.periodMs}", abs((d.periodMs ?: 0) - 500) < 40)
    }

    @Test fun silenceHasNoBeat() {
        val d = BeatDetector(now = { 0L })
        var beats = 0
        d.feed(ShortArray(16000), 16000) { beats++ }
        assertTrue(beats == 0)
    }
}
