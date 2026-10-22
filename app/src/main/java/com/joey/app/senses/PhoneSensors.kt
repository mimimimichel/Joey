package com.joey.app.senses

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.joey.app.gimbal.Orientation
import kotlin.math.asin
import kotlin.math.atan2

/**
 * Les capteurs du téléphone (posé sur la nacelle) : où regarde la caméra.
 * - Gyroscope + accéléromètre (« rotation de jeu ») : cap relatif et élévation, rapides et sans dérive gênante,
 *   insensibles aux aimants des moteurs. C'est ce que la tête utilise pour se diriger.
 * - Boussole (rotation avec magnétomètre) : cap absolu (nord…), juste pour information : les moteurs de la nacelle
 *   peuvent la fausser.
 * La direction regardée est l'axe de la caméra : sortant de l'écran (caméra avant) ou du dos (caméra arrière).
 */
class PhoneSensors(context: Context, private val frontCamera: () -> Boolean) : Orientation {
    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val game: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val compassSensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    @Volatile private var heading = 0.0
    @Volatile private var elevation = 0.0
    @Volatile private var at = 0L
    @Volatile private var north = Double.NaN
    @Volatile private var northAt = 0L

    val available: Boolean get() = game != null

    private val r = FloatArray(9)
    private val rc = FloatArray(9)

    /** Direction de la caméra (cap en degrés, sens horaire vu de dessus = vers la droite ; élévation en degrés). */
    private fun direction(rot: FloatArray): Pair<Double, Double> {
        // Axe Z du téléphone (sortant de l'écran) exprimé dans le monde : 3e colonne de la matrice.
        val s = if (frontCamera()) 1.0 else -1.0
        val x = s * rot[2]; val y = s * rot[5]; val z = s * rot[8]
        val h = Math.toDegrees(atan2(x, y))
        val e = Math.toDegrees(asin(z.coerceIn(-1.0, 1.0)))
        return h to e
    }

    private val gameListener = object : SensorEventListener {
        override fun onSensorChanged(ev: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(r, ev.values)
            val (h, e) = direction(r)
            heading = h; elevation = e; at = System.currentTimeMillis()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private val compassListener = object : SensorEventListener {
        override fun onSensorChanged(ev: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rc, ev.values)
            north = (direction(rc).first + 360) % 360
            northAt = System.currentTimeMillis()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /** Appelé quand on secoue le téléphone (plusieurs à-coups forts en 1 s). */
    @Volatile var onShake: (() -> Unit)? = null
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val jolts = ArrayDeque<Long>()
    private var lastShake = 0L
    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(ev: SensorEvent) {
            val a = kotlin.math.sqrt(ev.values[0] * ev.values[0] + ev.values[1] * ev.values[1] + ev.values[2] * ev.values[2])
            // La nacelle bouge doucement (< 5 m/s²) ; une main qui secoue dépasse largement 15.
            if (a < 15f) return
            val now = System.currentTimeMillis()
            jolts.addLast(now)
            while (jolts.isNotEmpty() && now - jolts.first() > 1000) jolts.removeFirst()
            if (jolts.size >= 4 && now - lastShake > 4000) {
                lastShake = now
                jolts.clear()
                onShake?.invoke()
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        accel?.let { sm.registerListener(shakeListener, it, SensorManager.SENSOR_DELAY_GAME) }
        game?.let { sm.registerListener(gameListener, it, SensorManager.SENSOR_DELAY_GAME) }
        if (compassSensor != null && compassSensor != game) sm.registerListener(compassListener, compassSensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sm.unregisterListener(gameListener)
        sm.unregisterListener(compassListener)
        sm.unregisterListener(shakeListener)
    }

    override fun read(): Pair<Double, Double>? =
        if (System.currentTimeMillis() - at < 300) heading to elevation else null

    /** Cap boussole (0 = nord, 90 = est), ou null. */
    fun compass(): Double? = north.takeIf { !it.isNaN() && System.currentTimeMillis() - northAt < 2000 }

    /** « nord-est »… */
    fun compassText(): String? = compass()?.let { c ->
        val names = listOf("nord", "nord-est", "est", "sud-est", "sud", "sud-ouest", "ouest", "nord-ouest")
        names[(((c + 22.5) % 360) / 45).toInt()] + " (%.0f°)".format(c)
    }
}
