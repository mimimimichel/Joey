package com.joey.app.gimbal

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

/**
 * Pendant un trajet, la tête (nacelle) garde l'axe du corps, un peu vers le sol : la caméra voit les obstacles avant
 * le choc. (Journal du 23/10 : la nacelle restait tournée vers là où elle regardait ; après chaque virage du Roomba la
 * caméra regardait de côté, la vision du sol se coupait et il touchait les murs une vingtaine de fois.)
 *
 * [bodyPan] : où regarde la tête par rapport à l'avant du corps (+ = droite), ou null ; [pose] : pose de la tête
 * (relative à son « devant ») ; [aim] : cible continue de la tête ; [recenter] : la nacelle se remet droit devant le corps
 * (sa propre commande, sans dérive) et le repère du corps est repris ; [refAgeMs] : âge de ce repère, null s'il n'y en a pas.
 */
class RoadGaze(
    private val bodyPan: () -> Double?,
    private val pose: () -> Pair<Double, Double>?,
    private val aim: (Double, Double) -> Unit,
    private val stopAim: () -> Unit,
    private val recenter: () -> Unit,
    private val refAgeMs: () -> Long?,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    /** Tête par rapport au corps d'après le compteur du câble (zéro = position neutre), si le repère manque. */
    private val twist: () -> Double? = { null },
) {
    /** Inclinaison de la tête pendant les trajets (vers le sol devant). */
    val tilt = -22.0
    private val depth = AtomicInteger(0)
    @Volatile private var recentering = false

    /** Un trajet est en cours : la tête regarde la route. */
    val active: Boolean get() = depth.get() > 0

    /** Pendant [body], la tête regarde la route ([onStart]/[onEnd] : au premier début, à la dernière fin). */
    fun <T> during(onStart: () -> Unit = {}, onEnd: () -> Unit = {}, body: () -> T): T {
        if (depth.getAndIncrement() == 0) {
            onStart()
            if (refAgeMs() == null) recenterNow()
        }
        try {
            return body()
        } finally {
            if (depth.decrementAndGet() == 0) { stopAim(); onEnd() }
        }
    }

    private fun recenterNow() {
        recentering = true
        try { stopAim(); recenter() } finally { recentering = false }
    }

    /** Souvent (dans la boucle de la tête) : vise l'avant du corps. */
    fun tick() {
        if (!active || recentering) return
        // Sans repère tête-corps, la tête suit au moins le corps d'après le câble (24/10 : figée dans la pièce pendant
        // les virages, le câble s'enroulait jusqu'à éteindre la nacelle).
        val bp = bodyPan() ?: twist() ?: return
        val p = pose() ?: return
        aim(Head.wrap(p.first - bp), tilt)
    }

    /**
     * Avant chaque essai d'un trajet : après un choc et un dégagement (le corps a tourné), la tête se recentre ; puis on
     * attend (2,5 s au plus) qu'elle regarde bien devant, pour ne pas repartir à l'aveugle.
     */
    fun legStart(attempt: Int) {
        if (!active) return
        val age = refAgeMs()
        // Recalage seulement sans repère ou s'il a plus de 5 min : après un choc et un dégagement, le repère tête-corps
        // reste bon (les roues mesurent la rotation). Le recalage par les pastilles prend 5 à 10 s tête baissée, sans voir
        // devant (24/10 : trois recalages en une minute d'exploration, trois chocs).
        if (age == null || age > 300_000) recenterNow()
        var waited = 0L
        while (waited < 2500) {
            val bp = bodyPan() ?: break
            val t = pose()?.second ?: break
            if (abs(bp) < 12 && abs(t - tilt) < 10) break
            sleep(100)
            waited += 100
        }
    }
}
