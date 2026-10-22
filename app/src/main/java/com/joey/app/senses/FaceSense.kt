package com.joey.app.senses

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit

/**
 * Le visage le plus grand de l'image : centre de -1 à 1 (x vers la droite, y vers le bas), taille (part de la largeur),
 * et sa boîte en pixels (pour le reconnaître).
 */
data class SeenFace(val x: Float, val y: Float, val size: Float, val box: android.graphics.Rect? = null)

/** Détection des visages sur le téléphone (ML Kit, rapide, sans Internet). Bloquant : à appeler hors du fil principal. */
class FaceSense {
    // Créé à la première image (pas au lancement de l'app).
    private val detector by lazy {
        FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.035f) // image de 1280 px : visages de ~45 px et plus (quelqu'un à 3-4 m)
            .build()
        )
    }

    /** Taille du dernier visage trouvé (part de la largeur). */
    @Volatile var lastSize: Float? = null
        private set

    fun find(upright: Bitmap): SeenFace? {
        val faces = runCatching {
            Tasks.await(detector.process(InputImage.fromBitmap(upright, 0)), 500, TimeUnit.MILLISECONDS)
        }.getOrNull() ?: return null
        val f = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: run { lastSize = null; return null }
        val b = f.boundingBox
        val w = upright.width.toFloat()
        val h = upright.height.toFloat()
        return SeenFace(
            x = (b.exactCenterX() / w) * 2f - 1f,
            y = (b.exactCenterY() / h) * 2f - 1f,
            size = b.width() / w,
            box = android.graphics.Rect(b),
        ).also { lastSize = it.size }
    }

    fun close() = runCatching { detector.close() }
}
