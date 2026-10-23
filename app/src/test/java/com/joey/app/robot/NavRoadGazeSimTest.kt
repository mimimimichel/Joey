package com.joey.app.robot

import com.joey.app.gimbal.Head
import com.joey.app.gimbal.RoadGaze
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Joey avec sa nacelle, dans un monde simulé : la tête est stabilisée (elle garde sa direction quand le corps tourne,
 * comme la vraie), tourne à 40°/s au plus, et la caméra ne voit le sol devant le corps que si elle regarde à peu près
 * dans son axe (±20°) et vers le bas. Séance réelle du 23/10 : la tête restait de côté après les virages et il touchait
 * les murs. Avec [RoadGaze], elle suit l'avant du corps : il doit contourner le meuble presque sans le toucher.
 */
class NavRoadGazeSimTest {
    private val sofa = { x: Double, y: Double -> x in 1.0..1.5 && y in -0.5..0.5 }
    // Un mur en L : il faut tourner franchement pour en sortir.
    private val corner = { x: Double, y: Double -> (x in 1.2..1.4 && y in -1.0..0.8) || (y in 0.8..1.0 && x in 0.2..1.4) }

    /** La nacelle simulée : cap dans le monde (comme f.h), inclinaison, cible continue. */
    private class SimHead(@Volatile var yaw: Double) {
        @Volatile var tilt = 0.0
        @Volatile var home = yaw
        @Volatile var aimYaw = Double.NaN
        @Volatile var aimTilt = 0.0
        @Volatile var aimAt = 0L
        @Volatile var ref: Triple<Double, Double, Long>? = null // (cap tête, cap des roues, heure) quand droit devant
        fun step(dt: Double) {
            if (System.currentTimeMillis() - aimAt > 600 || aimYaw.isNaN()) return
            val max = 40.0 * dt
            val e = Head.wrap(aimYaw - yaw)
            yaw += e.coerceIn(-max, max)
            tilt += (aimTilt - tilt).coerceIn(-max, max)
        }
    }

    private class Run(val bumps: Int, val dist: Double, val res: String, val seen: Int)

    private fun run(world: (Double, Double) -> Boolean, roadGaze: Boolean, tx: Double, ty: Double): Run {
        val f = FakeRoomba()
        f.wall = world
        val r = RoombaRobot(f)
        r.connect()
        Thread.sleep(200)
        val g = NavGrid()
        val n = Navigator(r, g) { println("[nav ${if (roadGaze) "route" else "fixe"}] %.2f,%.2f cap %.0f : %s".format(f.x, f.y, f.h, it)) }
        r.onContact = { k, s -> n.contact(k, s) }
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        var seen = 0
        val head = SimHead(f.h)
        head.tilt = -22.0
        fun bodyPan(): Double? {
            val ref = head.ref ?: return null
            return Head.wrap(head.yaw - (ref.first + Head.wrap(r.heading - ref.second)))
        }
        val gaze = RoadGaze(
            bodyPan = { bodyPan() },
            pose = { Head.wrap(head.yaw - head.home) to head.tilt },
            aim = { p, t -> head.aimYaw = head.home + p; head.aimTilt = t; head.aimAt = System.currentTimeMillis() },
            stopAim = { head.aimAt = 0L },
            // La commande « recentrer » de la nacelle : droit devant le corps (le vrai), en 0,3 s ici.
            recenter = { Thread.sleep(300); head.yaw = f.h; head.home = head.yaw; head.ref = Triple(head.yaw, r.heading, System.currentTimeMillis()) },
            refAgeMs = { head.ref?.let { System.currentTimeMillis() - it.third } },
        )
        // Sans le regard « route » : la tête est mise droit devant au départ, puis ne bouge plus (stabilisée).
        head.ref = Triple(head.yaw, r.heading, System.currentTimeMillis())
        thread(isDaemon = true) { while (running.get()) { n.tick(); Thread.sleep(100) } }
        // La boucle de la tête (25 fois par seconde).
        thread(isDaemon = true) {
            while (running.get()) {
                if (roadGaze) gaze.tick()
                head.step(0.04)
                Thread.sleep(40)
            }
        }
        // La caméra : le sol devant, seulement si elle regarde dans l'axe du corps (estimé) et vers le bas.
        fun ray(worldBearing: Double): Double {
            val a = Math.toRadians(worldBearing)
            var d = 0.0
            while (d < 3.0) { if (world(f.x + d * cos(a), f.y + d * sin(a))) return d; d += 0.02 }
            return Double.POSITIVE_INFINITY
        }
        var last = listOf<Pair<Double, Double>>()
        r.visionClear = {
            last.filter { (b, d) -> abs(b) < 8 || abs(kotlin.math.tan(Math.toRadians(b))) * minOf(d, 3.0) <= 0.19 }.minOfOrNull { it.second }
        }
        thread(isDaemon = true) {
            while (running.get()) {
                val pan = bodyPan()
                if (pan == null || abs(pan) > 20 || head.tilt > -10) last = emptyList() else {
                    // Ce que voit vraiment la caméra (sa vraie direction), rapporté à l'avant du corps.
                    val trueOff = Head.wrap(head.yaw - f.h)
                    last = (-30..30 step 4).map { b -> b.toDouble() to ray(f.h + trueOff + b) }
                    n.vision(r.heading + pan, last)
                    seen++
                }
                Thread.sleep(150)
            }
        }
        try {
            val res = if (roadGaze) gaze.during { n.navigateTo(tx, ty, tries = 12, budget = 20.0) { gaze.legStart(it) } }
            else n.navigateTo(tx, ty, tries = 12, budget = 20.0)
            return Run(f.bumps, hypot(f.x - tx, f.y - ty), res, seen)
        } finally { running.set(false); r.disconnect() }
    }

    @Test(timeout = 240_000)
    fun laTeteRegardeLaRouteEtIlNeTouchePasLeCanape() {
        val fixed = run(sofa, roadGaze = false, tx = 2.3, ty = 0.0)
        val road = run(sofa, roadGaze = true, tx = 2.3, ty = 0.0)
        println("canapé — tête fixe : ${fixed.bumps} chocs, ${fixed.seen} images utiles ; tête sur la route : ${road.bumps} chocs, ${road.seen} images utiles")
        assertTrue("arrivé : ${road.res} (à %.2f m)".format(road.dist), road.dist < 0.6)
        assertTrue("chocs avec la tête sur la route : ${road.bumps}", road.bumps <= 1)
        assertTrue("la caméra sert plus souvent (${road.seen} contre ${fixed.seen})", road.seen >= fixed.seen)
    }

    @Test(timeout = 240_000)
    fun sortDUnCoinSansSAveugler() {
        val road = run(corner, roadGaze = true, tx = 0.6, ty = 1.6)
        println("coin — tête sur la route : ${road.bumps} chocs, ${road.seen} images utiles, ${road.res}")
        assertTrue("arrivé : ${road.res} (à %.2f m)".format(road.dist), road.dist < 0.6)
        // La caméra sert tout le long (la tête suit le corps dans les virages) ; il peut frôler le bout du mur avec son
        // flanc (hors du champ de la caméra, ±30°) : quelques contacts au plus.
        assertTrue("images utiles : ${road.seen}", road.seen >= 30)
        assertTrue("chocs : ${road.bumps}", road.bumps <= 4)
    }
}
