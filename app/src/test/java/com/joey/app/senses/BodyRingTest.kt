package com.joey.app.senses

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.tan

/**
 * L'anneau d'orientation (hardware/repere-corps/anneau.py : Ø 180 à 228 mm, dard à l'avant, barre à droite, deux points
 * à l'arrière, trois points à gauche), vu par une caméra 28 cm au-dessus du capot, à 10 cm à gauche de l'axe de la
 * nacelle (comme le S8 et le S10), inclinée de -72°, la tête tournée de [pan] par rapport au corps.
 */
class BodyRingTest {
    private val w = 320; private val h = 240
    private val rIn = 0.090; private val rOut = 0.114; private val band = rOut - rIn; private val rMid = (rIn + rOut) / 2

    private fun inTri(px: Double, py: Double, a: DoubleArray, b: DoubleArray, c: DoubleArray): Boolean {
        fun s(p: DoubleArray, q: DoubleArray) = (px - q[0]) * (p[1] - q[1]) - (p[0] - q[0]) * (py - q[1])
        val d1 = s(a, b); val d2 = s(b, c); val d3 = s(c, a)
        val neg = d1 < 0 || d2 < 0 || d3 < 0; val pos = d1 > 0 || d2 > 0 || d3 > 0
        return !(neg && pos)
    }

    /** Le point (x devant, y à droite, sur le capot) est-il sur un symbole blanc ? */
    private fun onSymbol(x: Double, y: Double): Boolean {
        for ((name, deg) in listOf("avant" to 0.0, "droite" to 90.0, "arriere" to 180.0, "gauche" to -90.0)) {
            val t = Math.toRadians(deg)
            val lx = x * cos(t) + y * sin(t) - rMid
            val ly = -x * sin(t) + y * cos(t)
            if (abs(lx) > band || abs(ly) > band) continue
            val hit = when (name) {
                "avant" -> {
                    val hh = band * 0.42
                    inTri(lx, ly, doubleArrayOf(hh, 0.0), doubleArrayOf(-hh, band * 0.5), doubleArrayOf(-hh * 0.45, 0.0)) ||
                        inTri(lx, ly, doubleArrayOf(hh, 0.0), doubleArrayOf(-hh * 0.45, 0.0), doubleArrayOf(-hh, -band * 0.5))
                }
                "droite" -> abs(lx) <= band * 0.16 && abs(ly) <= band * 0.62
                "arriere" -> listOf(-0.38, 0.38).any { hypot(lx, ly - it * band) <= band * 0.2 }
                else -> listOf(-0.62, 0.0, 0.62).any { hypot(lx, ly - it * band) <= band * 0.17 }
            }
            if (hit) return true
        }
        return false
    }

    private fun scene(panDeg: Double, pitchDeg: Double = -72.0, reflet: Boolean = true, camH: Double = 0.28,
                      offset: Double = 0.10): IntArray {
        val fxp = (w / 2.0) / tan(Math.toRadians(33.0)); val fyp = (h / 2.0) / tan(Math.toRadians(25.5))
        val th = Math.toRadians(panDeg); val pt = Math.toRadians(pitchDeg)
        val f = doubleArrayOf(cos(th) * cos(pt), sin(th) * cos(pt), sin(pt))
        val r = doubleArrayOf(-sin(th), cos(th), 0.0)
        val d = doubleArrayOf(r[1] * f[2] - r[2] * f[1], r[2] * f[0] - r[0] * f[2], r[0] * f[1] - r[1] * f[0])
        // La caméra : 10 cm à gauche de l'axe de la nacelle (le mât), 28 cm au-dessus du capot.
        val camX = offset * sin(th); val camY = -offset * cos(th); val camZ = camH
        return IntArray(w * h) { k ->
            val px = k % w; val py = k / w
            val xc = (px + 0.5 - w / 2.0) / fxp; val yc = (py + 0.5 - h / 2.0) / fyp
            val ray = DoubleArray(3) { i -> f[i] + xc * r[i] + yc * d[i] }
            if (ray[2] >= -1e-6) 0xFFB4B4B4.toInt()
            else {
                val t = camZ / -ray[2]; val x = camX + t * ray[0]; val y = camY + t * ray[1]
                val rho = hypot(x, y)
                when {
                    rho < 0.022 -> 0xFF101010.toInt() // le mât
                    reflet && hypot(x - 0.035, y - 0.02) < 0.004 -> 0xFFE6E6E6.toInt() // un reflet sur le support
                    rho < 0.17 && onSymbol(x, y) -> 0xFFF0F0EC.toInt()
                    rho < 0.17 -> 0xFF1C1C1C.toInt() // le Roomba noir (et l'anneau noir)
                    else -> 0xFFC8BEB0.toInt() // le parquet clair
                }
            }
        }
    }

    @Test fun lAnneauDonneLAngleSurUnTourComplet() {
        var ok = 0
        val report = StringBuilder()
        for (p in -180 until 180 step 30) {
            val res = BodyMarks.measureRing(scene(p.toDouble()), w, h, -72.0, 66.0, 51.0)
            report.append("$p° -> ${res?.name} ${res?.panDeg?.let { "%.1f".format(it) }} ; ")
            if (res == null) continue
            val err = abs(((res.panDeg - p) % 360 + 540) % 360 - 180)
            assertTrue("tête à $p° : ${res.name} %.1f° (erreur %.1f°) — $report".format(res.panDeg, err), err < 12)
            ok++
        }
        println(report)
        assertTrue("seulement $ok angles sur 12 : $report", ok >= 10)
    }

    /** Le vrai montage diffère du modèle : caméra plus basse ou plus haute, décalage, inclinaison mal mesurée. */
    @Test fun lAnneauToléreLesErreursDeMontage() {
        val cases = listOf(Triple(0.24, 0.08, -70.0), Triple(0.32, 0.12, -75.0), Triple(0.26, 0.11, -68.0))
        for ((camH, off, truePitch) in cases) {
            var ok = 0; val report = StringBuilder()
            for (p in -180 until 180 step 30) {
                val res = BodyMarks.measureRing(scene(p.toDouble(), truePitch, camH = camH, offset = off), w, h, -72.0, 66.0, 51.0)
                report.append("$p° -> ${res?.name} ${res?.panDeg?.let { "%.1f".format(it) }} ; ")
                if (res == null) continue
                val err = abs(((res.panDeg - p) % 360 + 540) % 360 - 180)
                assertTrue("h=$camH décalage=$off inclinaison=$truePitch, tête à $p° : erreur %.1f° — $report".format(err), err < 15)
                ok++
            }
            println("h=$camH décalage=$off inclinaison=$truePitch : $report")
            assertTrue("h=$camH : seulement $ok angles : $report", ok >= 8)
        }
    }

    @Test fun sansAnneauRien() {
        // Le capot noir sans anneau (et le reflet) : aucun symbole.
        val img = scene(0.0).map { if (it == 0xFFF0F0EC.toInt()) 0xFF1C1C1C.toInt() else it }.toIntArray()
        assertNull(BodyMarks.measureRing(img, w, h, -72.0, 66.0, 51.0))
    }
}
