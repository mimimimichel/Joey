package com.joey.app.senses

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Base64
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * Caméra du téléphone sans aperçu à l'écran (l'écran sert de visage) : ~10 images/s analysées pour trouver
 * un visage ou un objet ([onFrame]), et une image toutes les 0,5 s gardée en JPEG pour Gemini.
 */
class Camera : Eyes {
    @Volatile private var latest: String? = null
    @Volatile private var lastAt = 0L
    @Volatile private var lastFaceAt = 0L
    /** Appelé ~10 fois par seconde avec l'image redressée (jusqu'à 1280 px). Sur le fil de la caméra. */
    @Volatile var onFrame: ((Bitmap) -> Unit)? = null
    @Volatile private var latestBitmap: Bitmap? = null
    @Volatile private var latestBitmapAt = 0L

    /** Dernière image redressée (null si trop vieille). */
    fun frame(maxAgeMs: Long = 1000): Bitmap? = latestBitmap?.takeIf { System.currentTimeMillis() - latestBitmapAt < maxAgeMs }
    /** Heure de la dernière image. */
    val frameAt: Long get() = latestBitmapAt
    private val worker = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null

    private fun scaled(b: Bitmap, max: Int): Bitmap {
        val m = maxOf(b.width, b.height)
        if (m <= max) return b
        val k = max.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true)
    }

    /**
     * Recadrage en pleine résolution de la dernière image, pour voir un détail : zone « centre », « gauche », « droite »,
     * « haut », « bas » ou « tout ». JPEG base64 de bonne qualité.
     */
    fun zoomJpeg(zone: String): String? {
        val b = frame(2000) ?: return null
        val w = b.width; val h = b.height
        val (x0, y0, x1, y1) = when (zone) {
            "centre" -> listOf(w / 4, h / 4, w * 3 / 4, h * 3 / 4)
            "gauche" -> listOf(0, h / 6, w / 2, h * 5 / 6)
            "droite" -> listOf(w / 2, h / 6, w, h * 5 / 6)
            "haut" -> listOf(w / 6, 0, w * 5 / 6, h / 2)
            "bas" -> listOf(w / 6, h / 2, w * 5 / 6, h)
            else -> listOf(0, 0, w, h)
        }
        val crop = Bitmap.createBitmap(b, x0, y0, x1 - x0, y1 - y0)
        val bos = ByteArrayOutputStream()
        crop.compress(Bitmap.CompressFormat.JPEG, 88, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    override fun captureJpegBase64(): String? = latest?.takeIf { System.currentTimeMillis() - lastAt < 3000 }

    fun start(owner: LifecycleOwner, ctx: android.content.Context, front: Boolean, onError: (String) -> Unit) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                p.unbindAll()
                // 1280 x 960 (au lieu de 640 x 480 par défaut) : un visage à 3-4 m reste assez grand pour être vu.
                val res = androidx.camera.core.resolutionselector.ResolutionSelector.Builder()
                    .setResolutionStrategy(androidx.camera.core.resolutionselector.ResolutionStrategy(
                        android.util.Size(1280, 960),
                        androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(res)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(worker) { img ->
                    try {
                        val now = System.currentTimeMillis()
                        val wantFace = now - lastFaceAt >= 90
                        val wantJpeg = now - lastAt >= 500
                        if (wantFace || wantJpeg) {
                            val bmp = img.toBitmap()
                            val rot = img.imageInfo.rotationDegrees
                            val scale = minOf(1f, 1280f / maxOf(bmp.width, bmp.height))
                            val m = Matrix().apply { postScale(scale, scale); postRotate(rot.toFloat()) }
                            val upright = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                            if (wantFace) {
                                lastFaceAt = now
                                latestBitmap = upright
                                latestBitmapAt = now
                                onFrame?.invoke(upright)
                            }
                            if (wantJpeg) {
                                // Pour la conversation : 768 px suffisent (Gemini les réduit de toute façon) ;
                                // le détail passe par « regarder de près » (recadrage en pleine résolution).
                                val small = scaled(upright, 768)
                                val bos = ByteArrayOutputStream()
                                small.compress(Bitmap.CompressFormat.JPEG, 70, bos)
                                latest = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
                                lastAt = now
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("Camera", "image", e)
                    } finally {
                        img.close()
                    }
                }
                val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                p.bindToLifecycle(owner, selector, analysis)
            } catch (e: Exception) {
                onError("Caméra indisponible : ${e.message}")
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun stop() {
        runCatching { provider?.unbindAll() }
        latest = null
    }
}
