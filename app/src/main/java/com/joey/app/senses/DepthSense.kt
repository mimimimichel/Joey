package com.joey.app.senses

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Profondeur estimée sur le téléphone : MiDaS v2.1 small (256 × 256, licence MIT, Intel ISL ; version LiteRT de
 * litert-community/MiDaS-small). Rend une profondeur inverse relative (plus grand = plus proche), lue par [DepthGround].
 * Chargé au premier usage ; protégé par NativeGuard (un plantage natif ne recommence pas au lancement suivant).
 */
class DepthSense(private val context: Context, private val log: (String) -> Unit = {}) {
    companion object {
        const val MODEL = "midas_small_256_fp16.tflite"
        const val SIZE = 256
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
    }

    private var interpreter: Interpreter? = null
    private var failed = false
    private val input = ByteBuffer.allocateDirect(4 * SIZE * SIZE * 3).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(4 * SIZE * SIZE).order(ByteOrder.nativeOrder())
    private val px = IntArray(SIZE * SIZE)
    /** Durée de la dernière estimation (ms). */
    @Volatile var lastMs = 0L
        private set

    private fun load(): Interpreter? {
        interpreter?.let { return it }
        if (failed) return null
        interpreter = runCatching {
            val fd = context.assets.openFd(MODEL)
            val buf = FileInputStream(fd.fileDescriptor).channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            com.joey.app.NativeGuard.attempt("profondeur") { Interpreter(buf, Interpreter.Options().setNumThreads(4)) }
                ?: error("a déjà fait planter Joey")
        }.onFailure {
            Log.e("DepthSense", "profondeur indisponible", it)
            log("Profondeur (MiDaS) indisponible : ${it.message}")
            failed = true
        }.getOrNull()
        return interpreter
    }

    val available: Boolean get() = !failed

    /** Profondeur inverse relative de l'image (redressée), [SIZE] × [SIZE], ou null. Bloquant (~0,2-0,5 s sur le S8+). */
    @Synchronized
    fun estimate(bmp: Bitmap): FloatArray? {
        val interp = load() ?: return null
        return runCatching {
            val small = Bitmap.createScaledBitmap(bmp, SIZE, SIZE, true)
            small.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
            if (small !== bmp) small.recycle()
            input.rewind()
            for (c in px) {
                input.putFloat((((c shr 16) and 255) / 255f - MEAN[0]) / STD[0])
                input.putFloat((((c shr 8) and 255) / 255f - MEAN[1]) / STD[1])
                input.putFloat(((c and 255) / 255f - MEAN[2]) / STD[2])
            }
            input.rewind(); output.rewind()
            val t0 = System.currentTimeMillis()
            interp.run(input, output)
            lastMs = System.currentTimeMillis() - t0
            output.rewind()
            FloatArray(SIZE * SIZE).also { a -> output.asFloatBuffer().get(a) }
        }.onFailure { Log.w("DepthSense", "estimation impossible", it) }.getOrNull()
    }
}
