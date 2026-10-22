package com.joey.app.senses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Empreinte d'un visage (MobileFaceNet, sur le téléphone) : 192 nombres ; deux photos de la même personne donnent
 * des empreintes proches. Aucune image n'est gardée ni envoyée : seulement ces nombres.
 */
class FaceId(private val context: Context) {
    companion object {
        const val MODEL = "mobile_face_net.tflite"
        private const val SIZE = 112
    }

    private var interpreter: Interpreter? = null
    private var failed = false

    private fun load(): Interpreter? {
        interpreter?.let { return it }
        if (failed) return null
        return runCatching {
            val fd = context.assets.openFd(MODEL)
            val buf: MappedByteBuffer = FileInputStream(fd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            com.joey.app.NativeGuard.attempt("visages_id") { Interpreter(buf, Interpreter.Options().setNumThreads(2)) }
                ?: error("a déjà fait planter Joey")
        }.onFailure { Log.e("FaceId", "modèle", it); failed = true }.getOrNull().also { interpreter = it }
    }

    /** Empreinte (normalisée) du visage [box] dans [image], ou null. */
    @Synchronized
    fun embed(image: Bitmap, box: Rect): FloatArray? {
        val itp = load() ?: return null
        // Un peu de marge autour du visage, en carré.
        val side = (maxOf(box.width(), box.height()) * 1.15f).toInt()
        val cx = box.centerX(); val cy = box.centerY()
        val l = (cx - side / 2).coerceIn(0, image.width - 1)
        val t = (cy - side / 2).coerceIn(0, image.height - 1)
        val w = side.coerceAtMost(image.width - l)
        val h = side.coerceAtMost(image.height - t)
        if (w < 24 || h < 24) return null
        val face = Bitmap.createScaledBitmap(Bitmap.createBitmap(image, l, t, w, h), SIZE, SIZE, true)
        val input = ByteBuffer.allocateDirect(SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        val px = IntArray(SIZE * SIZE)
        face.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        for (p in px) {
            input.putFloat(((p shr 16 and 0xFF) - 128f) / 128f)
            input.putFloat(((p shr 8 and 0xFF) - 128f) / 128f)
            input.putFloat(((p and 0xFF) - 128f) / 128f)
        }
        val out = Array(1) { FloatArray(192) }
        return runCatching { itp.run(input, out); normalize(out[0]) }.getOrNull()
    }

    private fun normalize(v: FloatArray): FloatArray {
        var s = 0f
        for (x in v) s += x * x
        val n = sqrt(s).coerceAtLeast(1e-6f)
        return FloatArray(v.size) { v[it] / n }
    }
}
