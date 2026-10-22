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
data class SeenFace(
    val x: Float, val y: Float, val size: Float, val box: android.graphics.Rect? = null,
    /** Numéro de suivi ML Kit (le même visage d'une image à l'autre), ou null. */
    val id: Int? = null,
    /** Ouverture de la bouche (bas de la bouche - base du nez, rapporté à la hauteur du visage), ou null. */
    val mouth: Float? = null,
    /** Probabilité de sourire (0..1), ou null. */
    val smile: Float? = null,
    /** Tête tournée (degrés, autour de l'axe vertical) : 0 = elle regarde la caméra. */
    val yaw: Float = 0f,
)

/** Détection des visages sur le téléphone (ML Kit, rapide, sans Internet). Bloquant : à appeler hors du fil principal. */
class FaceSense {
    // Créé à la première image (pas au lancement de l'app).
    private val detector by lazy {
        FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setMinFaceSize(0.035f) // image de 1280 px : visages de ~45 px et plus (quelqu'un à 3-4 m)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL) // nez et bouche : qui parle ?
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // sourire : il sourit aussi
            .enableTracking() // le même numéro pour le même visage d'une image à l'autre
            .build()
        )
    }

    /** Taille du dernier visage trouvé (part de la largeur). */
    @Volatile var lastSize: Float? = null
        private set

    /** Tous les visages de l'image, du plus grand au plus petit. */
    fun findAll(upright: Bitmap): List<SeenFace> {
        val faces = runCatching {
            Tasks.await(detector.process(InputImage.fromBitmap(upright, 0)), 500, TimeUnit.MILLISECONDS)
        }.getOrNull() ?: return emptyList()
        val w = upright.width.toFloat()
        val h = upright.height.toFloat()
        val list = faces.sortedByDescending { it.boundingBox.width() * it.boundingBox.height() }.map { f ->
            val b = f.boundingBox
            val nose = f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.NOSE_BASE)?.position
            val mouth = f.getLandmark(com.google.mlkit.vision.face.FaceLandmark.MOUTH_BOTTOM)?.position
            SeenFace(
                x = (b.exactCenterX() / w) * 2f - 1f,
                y = (b.exactCenterY() / h) * 2f - 1f,
                size = b.width() / w,
                box = android.graphics.Rect(b),
                id = f.trackingId,
                mouth = if (nose != null && mouth != null && b.height() > 0) (mouth.y - nose.y) / b.height() else null,
                smile = f.smilingProbability,
                yaw = f.headEulerAngleY,
            )
        }
        lastSize = list.firstOrNull()?.size
        return list
    }

    /** Le plus grand visage de l'image. */
    fun find(upright: Bitmap): SeenFace? = findAll(upright).firstOrNull()

    fun close() = runCatching { detector.close() }
}
