package com.joey.app.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/** Deux micros de sensibilités différentes : il apprend l'écart quand on parle en face, puis trouve le bon côté. */
class StereoDirectionTest {
    private var t = 0L
    private var saved = 0

    /** Une voix (bruit modulé) : le micro du côté de la voix l'entend plus fort et un peu plus tôt. */
    private fun chunk(gainL: Double, gainR: Double, delayR: Int): Pair<ShortArray, ShortArray> {
        val n = 1600
        val src = DoubleArray(n + 20) { i -> 8000 * sin(i * 0.37) * sin(i * 0.011 + t / 50.0) + 3000 * sin(i * 1.3) }
        val l = ShortArray(n) { (src[it + 10] * gainL).toInt().toShort() }
        val r = ShortArray(n) { (src[it + 10 - delayR] * gainR).toInt().toShort() }
        return l to r
    }

    private fun speak(d: StereoDirection, gainL: Double, gainR: Double, delayR: Int, faceX: Float?, ms: Int) {
        repeat(ms / 100) {
            val (l, r) = chunk(gainL, gainR, delayR)
            d.feed(l, r, l.size); t += 100
            faceX?.let { d.learn(it) }
        }
    }

    @Test fun learnsBalanceThenSide() {
        val d = StereoDirection(now = { t }, save = { _, _, _, _ -> saved++ })
        // Bruit de fond calme.
        repeat(10) { d.feed(ShortArray(1600) { ((it % 7) - 3).toShort() }, ShortArray(1600) { ((it % 5) - 2).toShort() }, 1600); t += 100 }
        // Le micro droit est moins sensible (0,6) : en face, il entend moins fort. Il apprend cet écart.
        speak(d, 1.0, 0.6, 0, faceX = 0f, ms = 3000)
        assertTrue("réglé", d.calibrated >= 10)
        // En face après réglage : pas de côté.
        speak(d, 1.0, 0.6, 0, faceX = null, ms = 1500)
        assertEquals(0, d.side().first)
        // La voix vient de la droite de l'image (visage à droite) : plus fort et plus tôt à droite.
        speak(d, 0.7, 0.6 * 1.6, -3, faceX = 0.6f, ms = 1500)
        val s = d.side()
        assertTrue("confiance ${s.second}", s.second > 0.3)
        // Le sens appris (visage à droite) correspond.
        speak(d, 0.7, 0.6 * 1.6, -3, faceX = null, ms = 1500)
        assertEquals(1, d.side().first)
    }

    @Test fun copiedChannelsAreMono() {
        val d = StereoDirection(now = { t })
        repeat(10) { d.feed(ShortArray(1600) { ((it % 7) - 3).toShort() }, ShortArray(1600) { ((it % 7) - 3).toShort() }, 1600); t += 100 }
        repeat(30) { val (l, _) = chunk(1.0, 1.0, 0); d.feed(l, l.copyOf(), l.size); t += 100 }
        assertTrue(d.mono)
        assertEquals(0, d.side().first)
    }
}
