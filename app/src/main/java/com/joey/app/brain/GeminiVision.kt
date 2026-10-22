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

    /** Relevé d'une vue pour la carte : type de pièce, description, et éléments pointés avec leur distance. */
    class Survey(val room: String, val description: String, val elements: List<Triple<String, Pair<Double, Double>, Double?>>)

    /** Gemini Robotics-ER d'abord : il pointe les éléments (x, y de 0 à 1) et estime leur distance. */
    fun survey(jpegBase64: String): Survey {
        val schema = JSONObject("""
        {"type":"object","properties":{
          "piece":{"type":"string","description":"type de pièce en français (salon, cuisine, chambre, couloir, salle de bain, bureau, entrée, extérieur…)"},
          "description":{"type":"string","description":"une phrase : ce qu'on voit dans cette direction"},
          "elements":{"type":"array","items":{"type":"object","properties":{
            "nom":{"type":"string","description":"nom court en français (« canapé gris », « porte », « fenêtre », « télécommande »)"},
            "point":{"type":"array","items":{"type":"integer"},"description":"[y, x] du centre de l'élément, de 0 à 1000"},
            "distance_m":{"type":"number","description":"distance estimée depuis la caméra, en mètres"}},
            "required":["nom","point"]}}
        },"required":["piece","description","elements"]}
        """.trimIndent())
        val prompt = "Image de la caméra d'un petit robot de maison, posé au sol ou sur un meuble. Pour faire une carte de la maison : " +
            "dis quel type de pièce c'est, décris la vue en une phrase, et pointe jusqu'à 15 éléments utiles pour s'orienter et " +
            "retrouver les choses (meubles, portes, fenêtres, appareils, objets posés, couloirs, escaliers), avec leur distance " +
            "estimée. N'invente rien."
        val key = apiKey()
        if (key.isBlank()) throw IllegalStateException("clé Gemini manquante")
        var last: Exception? = null
        for (model in MODELS) {
            try {
                val j = generateWithImage(model, key, jpegBase64, prompt, schema)
                val els = ArrayList<Triple<String, Pair<Double, Double>, Double?>>()
                val arr = j.optJSONArray("elements") ?: JSONArray()
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val pt = e.optJSONArray("point") ?: continue
                    if (pt.length() != 2) continue
                    els.add(Triple(e.optString("nom"), Pair(pt.optDouble(1) / 1000.0, pt.optDouble(0) / 1000.0),
                        e.optDouble("distance_m", Double.NaN).takeIf { !it.isNaN() && it > 0 && it < 30 }))
                }
                return Survey(j.optString("piece"), j.optString("description"), els)
            } catch (e: ModelUnavailable) {
                last = e
            }
        }
        throw IOException("vision Gemini indisponible (${last?.message})")
    }

    /** Dans quel lieu connu est le robot ? (nom du lieu ou « inconnu », certitude 0..1, raison). */
    fun whichPlace(jpegBase64: String, places: String): Triple<String, Double, String> {
        val schema = JSONObject("{\"type\":\"object\",\"properties\":{\"lieu\":{\"type\":\"string\"},\"certitude\":{\"type\":\"number\"},\"raison\":{\"type\":\"string\"}},\"required\":[\"lieu\",\"certitude\"]}")
        val prompt = "Image de la caméra d'un petit robot de maison. Voici les lieux qu'il a déjà relevés :\n$places\n\n" +
            "Dans lequel est-il ? Réponds avec le nom exact du lieu (ou « inconnu »), ta certitude (0 à 1) et une courte raison."
        val key = apiKey()
        var last: Exception? = null
        for (model in MODELS) {
            try {
                val j = generateWithImage(model, key, jpegBase64, prompt, schema)
                return Triple(j.optString("lieu", "inconnu"), j.optDouble("certitude", 0.0), j.optString("raison"))
            } catch (e: ModelUnavailable) {
                last = e
            }
        }
        throw IOException("vision Gemini indisponible (${last?.message})")
    }

    /** Qui parle dans [clip] (WAV), parmi les voix de référence ? (prénom ou « inconnu », certitude 0..1). */
    fun whoSpeaks(refs: List<Pair<String, ByteArray>>, clip: ByteArray): Pair<String, Double> {
        val key = apiKey()
        val schema = JSONObject("{\"type\":\"object\",\"properties\":{\"nom\":{\"type\":\"string\"},\"certitude\":{\"type\":\"number\"}},\"required\":[\"nom\",\"certitude\"]}")
        val parts = JSONArray().put(JSONObject().put("text", "Voici les voix de référence des gens d'une maison."))
        for ((name, wav) in refs) {
            parts.put(JSONObject().put("text", "Voix de $name :"))
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav").put("data", android.util.Base64.encodeToString(wav, android.util.Base64.NO_WRAP))))
        }
        parts.put(JSONObject().put("text", "Extrait à identifier :"))
        parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "audio/wav").put("data", android.util.Base64.encodeToString(clip, android.util.Base64.NO_WRAP))))
        parts.put(JSONObject().put("text", "Qui parle dans l'extrait ? Compare le timbre, la hauteur et la façon de parler (pas ce qui est dit). " +
            "Réponds par le prénom exact, ou « inconnu » si aucune voix ne correspond vraiment, avec ta certitude (0 à 1)."))
        var last: Exception? = null
        for (model in MODELS.drop(1)) {
            try {
                val gen = JSONObject().put("responseMimeType", "application/json").put("responseJsonSchema", schema).put("temperature", 0.1)
                if (model.startsWith("gemini-3")) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "LOW"))
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts))).put("generationConfig", gen)
                val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key").openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"; conn.connectTimeout = 10_000; conn.readTimeout = 40_000; conn.doOutput = true
                    conn.setRequestProperty("content-type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
                    if (code !in 200..299) throw IOException("$model : erreur $code")
                    val p = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    val answer = buildString { if (p != null) for (i in 0 until p.length()) { val x = p.optJSONObject(i) ?: continue; if (!x.optBoolean("thought")) append(x.optString("text")) } }
                    val j = JSONObject(answer.substring(answer.indexOf('{'), answer.lastIndexOf('}') + 1))
                    return j.optString("nom", "inconnu") to j.optDouble("certitude", 0.0)
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("Gemini indisponible (${last?.message})")
    }

    /** Question sur une image (en bonne résolution) : réponse courte en français. */
    fun ask(jpegBase64: String, question: String): String {
        val schema = JSONObject("{\"type\":\"object\",\"properties\":{\"reponse\":{\"type\":\"string\"}},\"required\":[\"reponse\"]}")
        val prompt = "Image (recadrée, en bonne résolution) de la caméra d'un petit robot de maison. Question : « $question ». " +
            "Réponds en français, en 1 à 3 phrases, d'après ce que l'image montre vraiment (lis les textes si besoin). " +
            "Si on ne peut pas savoir, dis-le."
        val key = apiKey()
        if (key.isBlank()) throw IllegalStateException("clé Gemini manquante")
        var last: Exception? = null
        for (attempt in MODELS.indices) {
            val model = MODELS[(modelIndex + attempt) % MODELS.size]
            try {
                return generateWithImage(model, key, jpegBase64, prompt, schema).optString("reponse")
            } catch (e: ModelUnavailable) {
                last = e
            }
        }
        throw IOException("vision Gemini indisponible (${last?.message})")
    }

    private fun generateWithImage(model: String, key: String, jpeg: String, prompt: String, schema: JSONObject): JSONObject {
        val gen = JSONObject().put("responseMimeType", "application/json").put("responseJsonSchema", schema).put("temperature", 0.2)
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
            conn.readTimeout = 25_000
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
            if (a < 0 || b <= a) throw IOException("réponse illisible")
            return JSONObject(answer.substring(a, b + 1))
        } finally {
            conn.disconnect()
        }
    }

    /** Texte seul -> JSON selon [schema] (sert à extraire les souvenirs d'une conversation). */
    fun json(prompt: String, schema: JSONObject): JSONObject {
        val key = apiKey()
        if (key.isBlank()) throw IllegalStateException("clé Gemini manquante")
        var last: Exception? = null
        for (model in MODELS.drop(1)) { // modèles Flash (Robotics-ER est pour les images)
            try {
                val gen = JSONObject().put("responseMimeType", "application/json").put("responseJsonSchema", schema).put("temperature", 0.2)
                if (model.startsWith("gemini-3")) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "LOW"))
                val body = JSONObject()
                    .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray()
                        .put(JSONObject().put("text", prompt)))))
                    .put("generationConfig", gen)
                val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
                    .openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 40_000
                    conn.doOutput = true
                    conn.setRequestProperty("content-type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val code = conn.responseCode
                    val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                    if (code !in 200..299) throw IOException("$model : erreur $code")
                    val parts = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    val answer = buildString {
                        if (parts != null) for (i in 0 until parts.length()) {
                            val p = parts.optJSONObject(i) ?: continue
                            if (!p.optBoolean("thought")) append(p.optString("text"))
                        }
                    }
                    val a = answer.indexOf('{'); val b = answer.lastIndexOf('}')
                    if (a < 0 || b <= a) throw IOException("réponse illisible")
                    return JSONObject(answer.substring(a, b + 1))
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                last = e
            }
        }
        throw IOException("Gemini indisponible (${last?.message})")
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
