package com.joey.app.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/**
 * Les vraies cartes de profondeur de la séance du 24/10 (MiDaS small sur les images du S8+, calculées sur le PC) :
 * le calcul doit retrouver le pied de chaise et le canapé.
 */
class DepthGroundTest {
    private fun load(name: String): FloatArray {
        val bytes = javaClass.classLoader!!.getResourceAsStream("profondeur-$name.f32")!!.readBytes()
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    private val dg = DepthGround(0.40)

    @Test fun piedDeChaiseJusteDevant() {
        val r = dg.analyse(load("13-obstacle"), 256, 256, -25.0, 66.0, 51.0)
        assertTrue(r.ok)
        assertTrue("place libre devant : ${r.clearAhead}", r.clearAhead in 0.28..0.45)
    }

    @Test fun canapeAGaucheLibreADroite() {
        val r = dg.analyse(load("18-fin-explo"), 256, 256, -22.0, 66.0, 51.0)
        assertTrue(r.ok)
        // Le canapé est à ~0,4-0,5 m sur l'image ; la distance tirée de sa profondeur est un peu plus prudente que la ligne.
        assertTrue("${r.clearAhead}", r.clearAhead in 0.33..0.55)
        assertTrue("canapé à gauche : ${r.rays}", r.rays.filter { it.bearing < -10 }.all { it.distance < 0.6 })
        assertTrue("libre à droite : ${r.rays}", r.rays.filter { it.bearing in 8.0..28.0 }.all { it.distance > 1.5 })
    }

    @Test fun chaisesEtTableAUnMetre() {
        val r = dg.analyse(load("10-apres-roues"), 256, 256, -21.0, 66.0, 51.0)
        assertTrue("${r.clearAhead}", r.clearAhead in 0.6..0.95)
    }

    @Test fun voieLibreLoinDevant() {
        val r = dg.analyse(load("11-avant-30cm"), 256, 256, -1.0, 66.0, 51.0)
        assertTrue("${r.clearAhead}", r.clearAhead > 2.0)
    }

    /** Un sol parfait, puis un poteau au milieu à 0,8 m : il le trouve à sa distance. */
    @Test fun sceneSynthetique() {
        val w = 64; val h = 64; val pitch = -22.0
        val tanHalf = tan(Math.toRadians(25.5))
        val inv = FloatArray(w * h)
        // Un poteau vertical à 0,8 m : sa profondeur est celle du sol à son pied, sur toute sa hauteur (profondeur le long
        // de l'axe de la caméra : elle varie peu sur un objet vertical, on la garde constante).
        val footAlpha = kotlin.math.atan(0.40 / 0.8)
        val footInv = sin(footAlpha) / (0.40 * cos(footAlpha - Math.toRadians(-pitch)))
        for (r in 0 until h) {
            val beta = atan(((r + 0.5 - h / 2.0) / (h / 2.0)) * tanHalf)
            val alpha = Math.toRadians(-pitch) + beta
            val ground = if (alpha > 0.02) sin(alpha) / (0.40 * cos(beta)) else 0.0
            val d = if (alpha > 0.02) 0.40 / tan(alpha) else Double.POSITIVE_INFINITY
            for (c in 0 until w) {
                val pole = c in 28..35 && d > 0.8
                inv[r * w + c] = (100 * (if (pole) footInv else ground) + 5).toFloat()
            }
        }
        val res = dg.analyse(inv, w, h, pitch, 66.0, 51.0, columns = 8)
        assertTrue(res.ok)
        // Il le voit un peu après son pied (il faut quelques lignes nettement plus proches que le sol) : 0,8 à 1 m.
        assertTrue("${res.clearAhead}", res.clearAhead in 0.75..1.0)
        // À côté du poteau : le sol est libre.
        assertTrue("${res.rays}", res.rays.first().distance.isInfinite())
    }

    /** Face à un mur (rien qui ressemble au sol en bas) : il dit qu'il ne sait pas. */
    @Test fun faceAUnMurIlNeSaitPas() {
        val w = 32; val h = 32
        val inv = FloatArray(w * h) { 500f }
        val res = dg.analyse(inv, w, h, -22.0, 66.0, 51.0, columns = 4)
        assertTrue(!res.ok || res.clearAhead.isNaN() || res.clearAhead < 0.5)
    }
}
