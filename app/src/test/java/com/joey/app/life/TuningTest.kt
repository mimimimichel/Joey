package com.joey.app.life

import com.joey.app.senses.ImageZoom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class TuningTest {
    private fun fresh() = java.io.File.createTempFile("reglages", ".json").also { it.delete() }

    @Test fun ajusteEtRetient() {
        val f = fresh()
        val t = Tuning(f)
        assertEquals(0.40, t.get("vitesse"), 1e-9)
        t.adjust("vitesse", "moins")
        assertEquals(0.40 / 1.2, t.get("vitesse"), 1e-9)
        t.adjust("proximite", "plus")
        assertEquals(0.82, t.get("proximite"), 1e-9)
        repeat(10) { t.adjust("vitesse", "plus") }
        assertEquals(0.50, t.get("vitesse"), 1e-9) // borné
        assertTrue(t.adjust("inconnu", "plus").startsWith("réglage inconnu"))
        val t2 = Tuning(f)
        assertEquals(0.50, t2.get("vitesse"), 1e-9)
        t2.adjust("vitesse", "normal")
        assertEquals(0.40, t2.get("vitesse"), 1e-9)
    }

    @Test fun reglesApprises() {
        val f = fresh()
        val t = Tuning(f)
        assertTrue(t.addRule("Quand Michael dit « stop », arrête tout de suite les roues"))
        assertFalse(t.addRule("quand michael dit « stop », arrête tout de suite les roues"))
        assertTrue(Tuning(f).promptText().contains("stop"))
        assertEquals(1, t.removeRule("stop").size)
        assertTrue(Tuning(f).rules().isEmpty())
    }

    private fun scene(w: Int, h: Int, zoom: Double): FloatArray {
        val cx = (w - 1) / 2.0; val cy = (h - 1) / 2.0
        return FloatArray(w * h) { i ->
            val x = (i % w - cx) / zoom; val y = (i / w - cy) / zoom
            (0.5 + 0.25 * sin(x * 0.37) * sin(y * 0.29) + 0.2 * sin(x * 0.11 + y * 0.07)).toFloat()
        }
    }

    @Test fun zoomDuneSceneQuiSeRapproche() {
        val w = 80; val h = 60
        val a = scene(w, h, 1.0)
        val closer = ImageZoom.estimate(a, scene(w, h, 1.12), w, h)
        assertTrue("$closer", abs(closer.scale - 1.12) <= 0.03 && closer.confident)
        val farther = ImageZoom.estimate(a, scene(w, h, 0.9), w, h)
        assertTrue("$farther", abs(farther.scale - 0.9) <= 0.03 && farther.confident)
        val same = ImageZoom.estimate(a, a, w, h)
        assertFalse("$same", same.confident)
    }
}
