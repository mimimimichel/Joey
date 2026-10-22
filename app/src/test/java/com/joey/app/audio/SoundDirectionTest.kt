package com.joey.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class SoundDirectionTest {
    /** Une « voix » (bruit modulé, par syllabes) entendue par deux micros, avec [delay] échantillons de retard à droite. */
    private fun voice(delay: Int, rightGain: Double = 1.0, seed: Int = 1): Pair<ShortArray, ShortArray> {
        val rnd = Random(seed)
        val n = 48_000
        val src = DoubleArray(n + 50) { i ->
            val syllable = if ((i / 6000) % 2 == 0) 1.0 else 0.05
            syllable * (rnd.nextDouble() - 0.5) * (1 + sin(2 * PI * 180 * i / 48000.0))
        }
        val bg = { rnd.nextDouble(-0.01, 0.01) }
        val l = ShortArray(n) { ((src[it + 25] + bg()) * 12000).toInt().toShort() }
        val r = ShortArray(n) { ((src[it + 25 - delay] * rightGain + bg()) * 12000).toInt().toShort() }
        return l to r
    }

    @Test fun voiceFromTheLeft() {
        val (l, r) = voice(delay = 8, rightGain = 0.8) // arrive d'abord à gauche, plus fort à gauche
        val (side, conf) = SoundDirection.estimate(l, r)
        assertEquals(-1, side)
        assertTrue(conf > 0.5)
    }

    @Test fun voiceFromTheRight() {
        val (l, r) = voice(delay = -8, rightGain = 1.25)
        assertEquals(1, SoundDirection.estimate(l, r).first)
    }

    @Test fun inFrontIsUndecided() {
        val (l, r) = voice(delay = 0)
        assertEquals(0, SoundDirection.estimate(l, r).first)
    }

    @Test fun silenceIsUndecided() {
        val rnd = Random(3)
        val l = ShortArray(48_000) { rnd.nextInt(-50, 50).toShort() }
        val r = ShortArray(48_000) { rnd.nextInt(-50, 50).toShort() }
        assertEquals(0, SoundDirection.estimate(l, r).first)
    }
}
