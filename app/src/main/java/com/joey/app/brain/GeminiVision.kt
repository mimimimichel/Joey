package com.joey.app.brain

import android.util.Log
import com.joey.app.senses.Box
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Réponse de Gemini : l'objet est-il sur l'image, et où. */
class Found(val found: Boolean, val confidence: Double, val box: Box?, val description: String)

/**
 * Repère sur une image un objet que le détecteur du téléphone ne connaît pas (« mes clés », « la plante verte »).
 * Repris de Bebop IA : Gemini Robotics-ER d'abord (spécialisé dans le repérage), puis des modèles Flash.
 */
class GeminiVision(private val apiKey: () -> String) {
    companion object {
        private const val TAG = "GeminiVision"
        val MODELS = listOf("gemini-robotics-er-2-preview", "gemini-3.8-flash", "gemini-3.5-flash", "gemini-2.5-flash")
        private val SCHEMA = JSONObject("""
        {"type":"object","properties":{
          "found":{"type":"boolean","description":"true seulement si l'objet cherché est vraiment visible sur l'image"},
          "confidence":{"type":"number","description":"0 à 1"},
          "box_2d":{"type":"array","items":{"type":"integer"},"description":"[ymin, xmin, ymax, xmax] de l'objet, entre 0 et 1000"},
          "description":{"type":"string","description":"une phrase en français : ce que tu vois (l'objet et où il est, ou ce qu'il y a à la place)"}
        },"required":["found","confidence","description"]}
        """.trimIndent())
    }

    @Volatile private var modelIndex = 0
    private class ModelUnavailable(msg: String) : IOException(msg)

    fun find(jpegBase64: String, target: String): Found {
        val key = apiKey()
        if (key.isBlank()) throw IllegalStateException("clé Gemini manquante")
        var last: Exception? = null
        for (attempt in MODELS.indices) {
            val model = MODELS[(modelIndex + attempt) % MODELS.size]
            try {
                val f = call(model, key, jpegBase64, target)
                modelIndex = (modelIndex + attempt) % MODELS.size // on garde celui qui marche
                return f
            } catch (e: ModelUnavailable) {
                Log.w(TAG, "$model indisponible : ${e.message}")
                last = e
            }
        }
        throw IOException("aucun modèle de vision Gemini disponible (${last?.message})")
    }

    private fun call(model: String, key: String, jpeg: String, target: String): Found {
        val prompt = "Image de la caméra d'un petit robot de maison. Objet cherché : « $target ».\n" +
            "Regarde attentivement toute l'image, y compris les bords et les petits objets. " +
            "Si tu vois cet objet (ou quelque chose qui y correspond clairement), found = true et encadre-le dans box_2d. " +
            "Sinon found = false et décris brièvement ce que tu vois. N'invente rien : en cas de doute, confidence basse."
        val gen = JSONObject().put("responseMimeType", "application/json").put("responseJsonSchema", SCHEMA).put("temperature", 0.2)
        if (model.startsWith("gemini-3")) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "LOW"))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
                .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", jpeg)))
                .put(JSONObject().put("text", prompt)))))
            .put("generationConfig", gen)
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull() ?: text.take(200)
                if (code == 404 || code == 429 || code >= 500 || code == 400 && msg.contains("model", ignoreCase = true)) throw ModelUnavailable("$code $msg")
                throw IOException("Gemini, erreur $code : $msg")
            }
            val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            val answer = buildString {
                if (parts != null) for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    if (!p.optBoolean("thought")) append(p.optString("text"))
                }
            }
            val a = answer.indexOf('{'); val b = answer.lastIndexOf('}')
            if (a < 0 || b <= a) return Found(false, 0.0, null, "réponse illisible")
            val d = JSONObject(answer.substring(a, b + 1))
            val box = d.optJSONArray("box_2d")
            if (!d.optBoolean("found") || box == null || box.length() != 4) return Found(false, d.optDouble("confidence", 0.0), null, d.optString("description"))
            val y0 = box.optDouble(0) / 1000; val x0 = box.optDouble(1) / 1000
            val y1 = box.optDouble(2) / 1000; val x1 = box.optDouble(3) / 1000
            return Found(true, d.optDouble("confidence", 0.6),
                Box(((x0 + x1) / 2).coerceIn(0.0, 1.0), ((y0 + y1) / 2).coerceIn(0.0, 1.0),
                    kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0)), d.optString("description"))
        } finally {
            conn.disconnect()
        }
    }
}
