package com.joey.app.senses

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.joey.app.gimbal.Orientation
import kotlin.math.abs
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

    /** Rotation téléphone -> monde la plus récente (pour remettre les accélérations dans le repère du monde). */
    private val world = FloatArray(9).also { it[0] = 1f; it[4] = 1f; it[8] = 1f }

    private val gameListener = object : SensorEventListener {
        override fun onSensorChanged(ev: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(r, ev.values)
            synchronized(world) { System.arraycopy(r, 0, world, 0, 9) }
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
    /** Soulevé, reposé, poussé, chute. */
    val motion = com.joey.app.life.BodyMotion()
    @Volatile var onMotion: ((com.joey.app.life.BodyMotion.Event) -> Unit)? = null

    private val shakeListener = object : SensorEventListener {
        override fun onSensorChanged(ev: SensorEvent) {
            // Dans le repère du monde (z vers le haut) : soulevé, poussé…
            val v = ev.values
            val (wx, wy, wz) = synchronized(world) {
                Triple(world[0] * v[0] + world[1] * v[1] + world[2] * v[2],
                    world[3] * v[0] + world[4] * v[1] + world[5] * v[2],
                    world[6] * v[0] + world[7] * v[1] + world[8] * v[2])
            }
            motion.feed(wx.toDouble(), wy.toDouble(), wz.toDouble())?.let { e -> onMotion?.invoke(e) }
            val a = kotlin.math.sqrt(ev.values[0] * ev.values[0] + ev.values[1] * ev.values[1] + ev.values[2] * ev.values[2])
            // La nacelle bouge doucement (< 5 m/s²) ; une main qui secoue dépasse largement 15.
            if (a < 15f) return
            val now = System.currentTimeMillis()
            motion.quietUntil = maxOf(motion.quietUntil, now + 1500) // à-coup violent : on secoue, ce n'est pas « soulevé »
            jolts.addLast(now)
            while (jolts.isNotEmpty() && now - jolts.first() > 1000) jolts.removeFirst()
            if (jolts.size >= 4 && now - lastShake > 4000) {
                lastShake = now
                motion.quietUntil = now + 3000 // secoué : ce n'est pas « soulevé »
                jolts.clear()
                onShake?.invoke()
            }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    /**
     * Comment le téléphone est tenu (0, 90, 180, 270), d'après la pesanteur, comme OrientationEventListener : sert à
     * redresser l'image de la caméra quel que soit le sens de l'écran (rotation auto coupée, téléphone en paysage…).
     */
    @Volatile var deviceOrientation = 0
        private set
    private val gravitySensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gravityListener = object : SensorEventListener {
        override fun onSensorChanged(ev: SensorEvent) {
            orientationFrom(ev.values[0], ev.values[1], ev.values[2], deviceOrientation)?.let { deviceOrientation = it }
            val g = kotlin.math.sqrt(ev.values[0] * ev.values[0] + ev.values[1] * ev.values[1] + ev.values[2] * ev.values[2])
            motion.feedRaw(g.toDouble())?.let { e -> onMotion?.invoke(e) }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    companion object {
        /**
         * Orientation (0/90/180/270) d'après l'accéléromètre (x, y, z du téléphone), avec une marge pour ne pas osciller
         * entre deux positions. null si le téléphone est presque à plat (on garde la précédente).
         */
        fun orientationFrom(ax: Float, ay: Float, az: Float, previous: Int): Int? {
            val x = -ax; val y = -ay; val z = -az
            if ((x * x + y * y) * 4 < z * z) return null
            var o = 90 - Math.round(Math.toDegrees(atan2(-y.toDouble(), x.toDouble()))).toInt()
            o = ((o % 360) + 360) % 360
            // Il faut dépasser la bissectrice de 15° pour changer.
            val d = abs(((o - previous + 540) % 360) - 180)
            if (d < 60) return previous
            return ((o + 45) / 90 % 4) * 90
        }
    }

    fun start() {
        gravitySensor?.let { sm.registerListener(gravityListener, it, SensorManager.SENSOR_DELAY_GAME) }
        accel?.let { sm.registerListener(shakeListener, it, SensorManager.SENSOR_DELAY_GAME) }
        game?.let { sm.registerListener(gameListener, it, SensorManager.SENSOR_DELAY_GAME) }
        if (compassSensor != null && compassSensor != game) sm.registerListener(compassListener, compassSensor, SensorManager.SENSOR_DELAY_UI)
    }

    fun stop() {
        sm.unregisterListener(gameListener)
        sm.unregisterListener(compassListener)
        sm.unregisterListener(shakeListener)
        sm.unregisterListener(gravityListener)
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
