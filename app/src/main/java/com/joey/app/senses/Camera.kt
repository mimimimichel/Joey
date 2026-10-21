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
    /** Appelé ~10 fois par seconde avec l'image redressée (640 px). Sur le fil de la caméra. */
    @Volatile var onFrame: ((Bitmap) -> Unit)? = null
    @Volatile private var latestBitmap: Bitmap? = null
    @Volatile private var latestBitmapAt = 0L

    /** Dernière image redressée (null si trop vieille). */
    fun frame(maxAgeMs: Long = 1000): Bitmap? = latestBitmap?.takeIf { System.currentTimeMillis() - latestBitmapAt < maxAgeMs }
    /** Heure de la dernière image. */
    val frameAt: Long get() = latestBitmapAt
    private val worker = Executors.newSingleThreadExecutor()
    private var provider: ProcessCameraProvider? = null

    override fun captureJpegBase64(): String? = latest?.takeIf { System.currentTimeMillis() - lastAt < 3000 }

    fun start(owner: LifecycleOwner, ctx: android.content.Context, front: Boolean, onError: (String) -> Unit) {
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            try {
                val p = future.get()
                provider = p
                p.unbindAll()
                val analysis = ImageAnalysis.Builder()
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
                            val scale = 640f / maxOf(bmp.width, bmp.height)
                            val m = Matrix().apply { postScale(scale, scale); postRotate(rot.toFloat()) }
                            val upright = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                            if (wantFace) {
                                lastFaceAt = now
                                latestBitmap = upright
                                latestBitmapAt = now
                                onFrame?.invoke(upright)
                            }
                            if (wantJpeg) {
                                val bos = ByteArrayOutputStream()
                                upright.compress(Bitmap.CompressFormat.JPEG, 70, bos)
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
