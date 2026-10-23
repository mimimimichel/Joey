package com.joey.app.remote

import kotlin.math.abs
import kotlin.math.sign

/**
 * Manette (Xbox ou autre, en Bluetooth) : Android la voit comme un clavier + des axes, rien à installer.
 *
 * - stick gauche : rouler (haut = avancer, côtés = tourner) ; gâchette droite enfoncée = plus vite
 * - stick droit : la tête
 * - A : aspiration marche / arrêt   - B : tout arrêter   - Y : tête droite devant
 * - X : parler à Joey (comme le micro)   - LB : retour à la base
 *
 * Ici, seulement la traduction manette -> ordres (testable sans téléphone) ; [MainViewModel] les exécute.
 */
class Gamepad {
    enum class Button { A, B, X, Y, LB, RB, START, SELECT }

    /** Ordre de conduite : vitesse (m/s, + = avant) et rotation (°/s, + = droite). */
    data class Drive(val forward: Double, val yaw: Double) {
        val idle get() = forward == 0.0 && yaw == 0.0
    }

    companion object {
        const val DEAD = 0.18f
        const val SLOW_MS = 0.25
        const val FAST_MS = 0.5
        const val TURN_DEG_S = 90.0
        const val TURN_FAST_DEG_S = 160.0

        /** Zone morte, puis 0..1 de nouveau (pas de saut au bord de la zone morte). */
        fun shape(v: Float): Double {
            val a = abs(v)
            if (a < DEAD) return 0.0
            val x = ((a - DEAD) / (1 - DEAD)).coerceIn(0f, 1f).toDouble()
            // Courbe douce : précis près du centre, plein pot au bord.
            return sign(v.toDouble()) * x * x
        }
    }

    @Volatile var leftX = 0f; private set
    @Volatile var leftY = 0f; private set
    @Volatile var rightX = 0f; private set
    @Volatile var rightY = 0f; private set
    @Volatile var boost = 0f; private set

    /** Nouvelles valeurs des axes (Android : y vers le bas = positif). */
    fun axes(lx: Float, ly: Float, rx: Float, ry: Float, rightTrigger: Float) {
        leftX = lx; leftY = ly; rightX = rx; rightY = ry; boost = rightTrigger
    }

    /** Ce que demandent les roues en ce moment. */
    fun drive(): Drive {
        val fast = boost > 0.5f
        val f = -shape(leftY) * (if (fast) FAST_MS else SLOW_MS)
        val t = shape(leftX) * (if (fast) TURN_FAST_DEG_S else TURN_DEG_S)
        return Drive(if (f == 0.0) 0.0 else f, if (t == 0.0) 0.0 else t)
    }

    /** Ce que demande la tête : x = droite, y = haut, de -1 à 1 (0,0 = rien). */
    fun head(): Pair<Float, Float> = (shape(rightX).toFloat() + 0f) to ((-shape(rightY)).toFloat() + 0f) // + 0f : pas de « -0 »
}
