package com.joey.app.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class TagPoseTest {
    private val w = 1280; private val h = 960; private val hf = 66.0; private val vf = 51.0
    private val camH = 0.40; private val tagH = 0.40

    /** Une étiquette sur un mur, la caméra à divers endroits : il retrouve distance, décalage et angle. */
    @Test fun mesureSurDesEtiquettesProjetees() {
        val tag = TagPose.Placed(1, 3.0, 0.0, 180.0) // mur devant (x = 3 m), l'étiquette regarde vers lui
        for ((cam, exp) in listOf(
            TagPose.Pose(1.0, 0.0, 0.0) to TagPose.View(2.0, 0.0, 0.0),        // bien en face, à 2 m
            TagPose.Pose(1.5, 0.4, -10.0) to null,                             // décalé, tourné
            TagPose.Pose(2.2, -0.6, 25.0) to null,
        )) {
            val corners = TagPose.project(tag, cam, camH, tagH, w, h, hf, vf)
            assertNotNull(corners)
            val v = TagPose.measure(corners!!, w, h, hf, vf)
            assertNotNull(v); v!!
            exp?.let { assertEquals(it.distance, v.distance, 0.01); assertEquals(it.lateral, v.lateral, 0.01); assertEquals(it.yawDeg, v.yawDeg, 0.3) }
            // Aller-retour : l'étiquette connue redonne la pose de la caméra.
            val back = TagPose.locate(tag, v)
            assertTrue("$cam -> $back", TagPose.gap(cam, back) < 0.02)
            assertEquals(cam.headingDeg, back.headingDeg, 0.5)
        }
    }

    /** Première fois : sa pose (près de la base) place l'étiquette ; plus tard, ailleurs, elle le recale. */
    @Test fun enregistreEnsuiteRecale() {
        val truth = TagPose.Placed(4, -1.0, 2.5, -90.0) // sur un mur à droite, regarde vers -y
        val cam1 = TagPose.Pose(-0.8, 0.6, 85.0)
        val v1 = TagPose.measure(TagPose.project(truth, cam1, camH, tagH, w, h, hf, vf)!!, w, h, hf, vf)!!
        val placed = TagPose.register(4, cam1, v1, "couloir")
        assertEquals(truth.x, placed.x, 0.02); assertEquals(truth.y, placed.y, 0.02); assertEquals(truth.outDeg, placed.outDeg, 0.5)
        // Plus tard : sa carte a dérivé (il se croit ailleurs) ; l'étiquette donne la vraie pose.
        val cam2 = TagPose.Pose(-1.6, 1.2, 70.0)
        val v2 = TagPose.measure(TagPose.project(truth, cam2, camH, tagH, w, h, hf, vf)!!, w, h, hf, vf)!!
        val got = TagPose.locate(placed, v2)
        assertTrue("$got", TagPose.gap(got, cam2) < 0.04)
        assertEquals(cam2.headingDeg, got.headingDeg, 1.0)
    }

    /**
     * Coins bruités (±1 pixel, comme un vrai détecteur) à 2,5 m. Sans autre information, la distance reste précise mais
     * l'angle de vue ne l'est pas (limite physique : l'étiquette ne fait que ~60 pixels). Avec le cap que Joey croit
     * avoir (à 4° près), la position est précise.
     */
    @Test fun robusteAuBruitDesCoins() {
        val tag = TagPose.Placed(2, 3.0, 0.5, 180.0)
        val cam = TagPose.Pose(0.5, 0.2, 5.0)
        val trueYaw = TagPose.wrap(cam.headingDeg - (tag.outDeg + 180.0))
        val rnd = Random(7)
        var worstDist = 0.0; var worstPos = 0.0; var worstYaw = 0.0
        repeat(50) {
            val c = TagPose.project(tag, cam, camH, tagH, w, h, hf, vf)!!.map { (u, v) -> (u + rnd.nextDouble(-1.0, 1.0)) to (v + rnd.nextDouble(-1.0, 1.0)) }
            val free = TagPose.measure(c, w, h, hf, vf)!!
            worstDist = maxOf(worstDist, kotlin.math.abs(kotlin.math.hypot(free.distance, free.lateral) - kotlin.math.hypot(2.5, 0.3)))
            val v = TagPose.measure(c, w, h, hf, vf, yawPrior = trueYaw + rnd.nextDouble(-4.0, 4.0))!!
            val p = TagPose.locate(tag, v)
            worstPos = maxOf(worstPos, TagPose.gap(p, cam)); worstYaw = maxOf(worstYaw, kotlin.math.abs(TagPose.wrap(p.headingDeg - cam.headingDeg)))
        }
        assertTrue("écart de distance sans cap %.3f m".format(worstDist), worstDist < 0.08)
        // Erreur de cap de 4° à 2,5 m : 2,5 × tan(4°) ≈ 17 cm sur le côté ; c'est la limite (bien mieux que la dérive des roues).
        assertTrue("écart de position avec cap %.3f m".format(worstPos), worstPos < 0.20)
        assertTrue("écart de cap %.1f°".format(worstYaw), worstYaw < 5.0)
    }
}
