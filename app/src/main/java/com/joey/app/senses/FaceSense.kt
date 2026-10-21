package com.joey.app.senses

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.TimeUnit

/** Le visage le plus grand de l'image : centre de -1 à 1 (x vers la droite, y vers le bas) et taille (part de la largeur). */
data class SeenFace(val x: Float, val y: Float, val size: Float)

/** Détection des visages sur le téléphone (ML Kit, rapide, sans Internet). Bloquant : à appeler hors du fil principal. */
class FaceSense {
    // Créé à la première image (pas au lancement de l'app).
    private val detector by lazy {
        FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.08f)
            .build()
        )
    }

    fun find(upright: Bitmap): SeenFace? {
        val faces = runCatching {
            Tasks.await(detector.process(InputImage.fromBitmap(upright, 0)), 500, TimeUnit.MILLISECONDS)
        }.getOrNull() ?: return null
        val f = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: return null
        val b = f.boundingBox
        val w = upright.width.toFloat()
        val h = upright.height.toFloat()
        return SeenFace(
            x = (b.exactCenterX() / w) * 2f - 1f,
            y = (b.exactCenterY() / h) * 2f - 1f,
            size = b.width() / w,
        )
    }

    fun close() = runCatching { detector.close() }
}
