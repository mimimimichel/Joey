package com.joey.app.senses

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.objectdetector.ObjectDetector

/**
 * Détecteur d'objets sur le téléphone (MediaPipe, EfficientDet-Lite2 entraîné sur COCO, ~80 catégories : personne,
 * chien, chat, chaise, plante…). Repris de Bebop IA. Processeur graphique si possible. Chargé au premier usage.
 */
class ObjectSense(private val context: Context, private val log: (String) -> Unit = {}) {
    companion object { const val MODEL = "efficientdet_lite2.tflite" }

    private var detector: ObjectDetector? = null
    private var failed = false

    private fun create(delegate: Delegate): ObjectDetector {
        val base = BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(delegate).build()
        val opts = ObjectDetector.ObjectDetectorOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.IMAGE)
            .setMaxResults(15)
            .setScoreThreshold(0.35f)
            .build()
        return ObjectDetector.createFromOptions(context, opts)
    }

    private fun detector(): ObjectDetector? {
        detector?.let { return it }
        if (failed) return null
        detector = runCatching { com.joey.app.NativeGuard.attempt("objets_gpu") { create(Delegate.GPU) } }.onFailure { Log.w("ObjectSense", "GPU indisponible", it) }.getOrNull()
            ?: runCatching { com.joey.app.NativeGuard.attempt("objets_cpu") { create(Delegate.CPU) } ?: error("a déjà fait planter Joey") }.onFailure {
                Log.e("ObjectSense", "détecteur indisponible", it)
                log("Détecteur d'objets indisponible : ${it.message}")
                failed = true
            }.getOrNull()
        return detector
    }

    /** Objets vus sur cette image (redressée). Bloquant (~30-80 ms). */
    @Synchronized
    fun detect(bmp: Bitmap): List<Seen> {
        val d = detector() ?: return emptyList()
        return try {
            val img = if (bmp.config != Bitmap.Config.ARGB_8888) bmp.copy(Bitmap.Config.ARGB_8888, false) else bmp
            val W = img.width.toFloat()
            val H = img.height.toFloat()
            d.detect(BitmapImageBuilder(img).build()).detections().mapNotNull { det ->
                val c = det.categories().firstOrNull() ?: return@mapNotNull null
                val bb = det.boundingBox()
                Seen(c.categoryName(), c.score().toDouble(),
                    Box((bb.centerX() / W).toDouble(), (bb.centerY() / H).toDouble(), (bb.width() / W).toDouble(), (bb.height() / H).toDouble()))
            }
        } catch (e: Exception) {
            Log.w("ObjectSense", "analyse impossible", e)
            emptyList()
        }
    }

    /** Résumé en français (« 2 personnes, un chien, une chaise »). */
    fun describe(seen: List<Seen>): String {
        if (seen.isEmpty()) return "rien de reconnaissable"
        return seen.groupBy { it.label }.entries.sortedByDescending { e -> e.value.maxOf { it.score } }
            .joinToString(", ") { (label, l) -> if (l.size > 1) "${l.size} × ${CocoClasses.french(label)}" else CocoClasses.french(label) }
    }
}
