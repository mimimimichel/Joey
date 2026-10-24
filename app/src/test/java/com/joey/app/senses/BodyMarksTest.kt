package com.joey.app.senses

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Les images de la séance du 24/10 (S8+ sur la nacelle, tête baissée sur les deux pastilles blanches du Roomba). */
class BodyMarksTest {
    private val w = 320; private val h = 240
    private fun px(name: String): IntArray {
        val b = javaClass.classLoader!!.getResourceAsStream("marques-$name.rgb")!!.readBytes()
        return IntArray(w * h) { k -> (0xFF shl 24) or ((b[3 * k].toInt() and 255) shl 16) or ((b[3 * k + 1].toInt() and 255) shl 8) or (b[3 * k + 2].toInt() and 255) }
    }
    private fun pan(name: String, pitch: Double) = BodyMarks.measure(px(name), w, h, pitch, 66.0, 51.0)

    @Test fun teteDansLAxe() {
        val a = pan("pastille-50", -51.0); val b = pan("pastille-70", -69.0)
        assertTrue("$a", a != null && kotlin.math.abs(a.panDeg) < 8)
        // Sur sa base (éclairage vif), seule la pastille de l'anneau est sûre : mesure avec une pastille, à ~10° près.
        assertTrue("$b", b != null && kotlin.math.abs(b.panDeg) < 11)
    }

    @Test fun teteTourneeADroite() {
        val r = pan("compromis-0", -59.0)
        assertTrue("$r", r != null && r.panDeg in 20.0..40.0)
    }

    @Test fun teteTourneeAGauchePuisLeCorpsTourne() {
        val before = pan("c2-0", -49.0)
        val after = pan("c2-1", -49.0)
        assertTrue("$before", before != null && before.panDeg in -30.0..-8.0)
        // Le corps a tourné de 45° à droite, la tête est restée : elle est bien plus à gauche du corps (~ -45°).
        assertTrue("$after", after != null && after.panDeg in -60.0..-30.0)
    }

    @Test fun pasDePastillesPasDeMesure() {
        assertNull(pan("c2-5", -30.0))
        assertNull(pan("00-depart", 0.0))
    }

    /**
     * La flèche imprimée (45 × 14 mm, à 6-10,5 cm devant le centre, dans l'axe), vue par une caméra 28 cm au-dessus du
     * centre du Roomba, inclinée de -60°, tournée de [pan] par rapport au corps : il retrouve l'angle.
     */
    private fun arrowScene(panDeg: Double, pitchDeg: Double = -60.0): IntArray {
        val fxp = (w / 2.0) / kotlin.math.tan(Math.toRadians(33.0)); val fyp = (h / 2.0) / kotlin.math.tan(Math.toRadians(25.5))
        val yaw = Math.toRadians(panDeg); val pt = Math.toRadians(pitchDeg)
        // Repère du corps : x devant, y à droite, z vers le haut ; caméra en (0, 0, 0.28) au-dessus du capot (z = 0).
        val f = doubleArrayOf(kotlin.math.cos(yaw) * kotlin.math.cos(pt), kotlin.math.sin(yaw) * kotlin.math.cos(pt), kotlin.math.sin(pt))
        val r = doubleArrayOf(kotlin.math.cos(yaw + Math.PI / 2), kotlin.math.sin(yaw + Math.PI / 2), 0.0)
        val d = doubleArrayOf(r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0])
        fun inArrow(x: Double, y: Double): Boolean {
            val u = (x - 0.060) * 1000; val v = y * 1000 // mm, depuis l'arrière de la flèche
            return (u in 0.0..33.0 && kotlin.math.abs(v) <= 3) || (u in 33.0..45.0 && kotlin.math.abs(v) <= 7 * (45 - u) / 12)
        }
        return IntArray(w * h) { k ->
            val px = k % w; val py = k / w
            val xc = (px + 0.5 - w / 2.0) / fxp; val yc = (py + 0.5 - h / 2.0) / fyp
            val ray = DoubleArray(3) { i -> f[i] + xc * r[i] + yc * d[i] }
            if (ray[2] >= -1e-6) 0xFFB4B4B4.toInt() // au-dessus de l'horizon : mur clair
            else {
                val t = 0.28 / -ray[2]; val x = t * ray[0]; val y = t * ray[1]
                when {
                    inArrow(x, y) -> 0xFFF0F0EC.toInt()
                    kotlin.math.hypot(x, y) < 0.17 -> 0xFF1C1C1C.toInt() // le Roomba noir
                    else -> 0xFFC8BEB0.toInt() // le parquet clair
                }
            }
        }
    }

    @Test fun laFlecheDonneLAngle() {
        for (p in listOf(-40.0, -15.0, 0.0, 10.0, 30.0)) {
            val res = BodyMarks.measure(arrowScene(p), w, h, -60.0, 66.0, 51.0, arrow = true)
            assertTrue("tête à $p° : $res", res != null && kotlin.math.abs(res.panDeg - p) < 4)
        }
    }

    /**
     * S10 (24/10, après-midi) : sa caméra est à ~10 cm de l'axe de la nacelle, le mât passe dans l'image, des fils et la
     * carte USB traînent sur le capot. Les angles attendus viennent des rotations de la nacelle entre les images
     * (f1 -> f4 : 39° ; f4 -> f8/f9 : 36°). Sans pastilles en vue : rien, jamais une fausse mesure.
     */
    private fun s10(name: String, pitch: Double) = BodyMarks.measure(px("s10-$name"), w, h, pitch, 68.0, 53.7, centered = false)

    @Test fun s10LesPastillesDonnentLAngle() {
        val f1 = s10("f1", -71.0); val f4 = s10("f4", -69.0); val f8 = s10("f8", -74.0); val f9 = s10("f9", -82.0)
        assertTrue("f1 $f1", f1 != null && f1.panDeg in 20.0..40.0)
        assertTrue("f4 $f4", f4 != null && f4.panDeg in -16.0..2.0)
        assertTrue("f8 $f8", f8 != null && f8.panDeg in -54.0..-34.0)
        assertTrue("f9 $f9", f9 != null && f9.panDeg in -54.0..-34.0)
        // Les écarts suivent les rotations de la nacelle (à 6° près).
        assertTrue("f1-f4 ${f1!!.panDeg - f4!!.panDeg}", kotlin.math.abs(f1.panDeg - f4.panDeg - 39) < 6)
        assertTrue("f4-f8 ${f4.panDeg - f8!!.panDeg}", kotlin.math.abs(f4.panDeg - f8.panDeg - 36) < 6)
    }

    @Test fun s10SansPastillesRien() {
        for ((n, p) in listOf("f10" to -55.0, "f13" to -58.0, "f14" to -71.0, "f17" to -74.0)) assertNull(n, s10(n, p))
    }

    /** Tête 70° environ à gauche du corps (après un « recentrage » de la nacelle, 24/10) : pastilles au bord droit. */
    @Test fun teteTresTourneeAGauche() {
        val r = pan("align-1", -79.0)
        assertTrue("$r", r == null || r.panDeg in -95.0..-45.0) // jamais un faux « dans l'axe »
    }
}
