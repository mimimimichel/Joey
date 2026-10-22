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

    // ---- quotas : un modèle refusé (« quota dépassé, réessaie dans 13 h ») n'est plus appelé jusque-là ----
    private val downUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    /** Appelé quand un modèle devient indisponible (pour le journal, une seule fois). */
    @Volatile var onQuota: ((model: String, until: Long) -> Unit)? = null

    private fun usable(model: String) = System.currentTimeMillis() >= (downUntil[model] ?: 0L)
    private fun models() = MODELS.filter(::usable)

    /** Au moins un modèle de vision est utilisable maintenant. */
    fun available(): Boolean = MODELS.any(::usable)

    /** « vision Gemini épuisée jusqu'à 08:56 (quota gratuit) », ou null. */
    fun quotaText(): String? {
        if (available()) return null
        val until = downUntil.values.minOrNull() ?: return null
        return "vision Gemini épuisée jusqu'à " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRENCH).format(java.util.Date(until)) +
            " (quota gratuit atteint)"
    }

    /** Erreur d'un modèle : s'il s'agit d'un quota, on le met de côté le temps indiqué par Google. */
    private fun unavailable(model: String, code: Int, msg: String): IOException {
        if (code == 429) {
            val m = Regex("retry in (?:(\\d+)h)?(?:(\\d+)m)?(?:([\\d.]+)s)?").find(msg)
            val delayS = m?.let { (it.groupValues[1].toLongOrNull() ?: 0) * 3600 + (it.groupValues[2].toLongOrNull() ?: 0) * 60 +
                (it.groupValues[3].toDoubleOrNull() ?: 0.0).toLong() }?.takeIf { it > 0 }
                ?: Regex("\"retryDelay\"\\s*:\\s*\"(\\d+)s\"").find(msg)?.groupValues?.get(1)?.toLongOrNull()
                ?: if (msg.contains("PerDay") || msg.contains("free_tier")) 3600L else 60L
            val until = System.currentTimeMillis() + delayS * 1000
            val was = downUntil.put(model, until)
            if (was == null || was < System.currentTimeMillis()) runCatching { onQuota?.invoke(model, until) }
        }
        return ModelUnavailable("$code ${msg.take(160)}")
    }

    fun find(jpegBase64: String, target: String): Found {
        val key = apiKey()
        if (key.isBlank()) throw IllegalStateException("clé Gemini manquante")
        var last: Exception? = null
        for (attempt in MODELS.indices) {
            val model = MODELS[(modelIndex + attempt) % MODELS.size]
            if (!usable(model)) continue
            try {
                val f = call(model, key, jpegBase64, target)
                modelIndex = (modelIndex + attempt) % MODELS.size // on garde celui qui marche
                return f
            } catch (e: ModelUnavailable) {
                Log.w(TAG, "$model indisponible : ${e.message}")
                last = e
            }
        }
        throw IOException(quotaText() ?: "aucun modèle de vision Gemini disponible (${last?.message})")
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
        for (model in models()) {
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
        throw IOException(quotaText() ?: "vision Gemini indisponible (${last?.message})")
    }

    /** Dans quel lieu connu est le robot ? (nom du lieu ou « inconnu », certitude 0..1, raison). */
    fun whichPlace(jpegBase64: String, places: String): Triple<String, Double, String> {
        val schema = JSONObject("{\"type\":\"object\",\"properties\":{\"lieu\":{\"type\":\"string\"},\"certitude\":{\"type\":\"number\"},\"raison\":{\"type\":\"string\"}},\"required\":[\"lieu\",\"certitude\"]}")
        val prompt = "Image de la caméra d'un petit robot de maison. Voici les lieux qu'il a déjà relevés :\n$places\n\n" +
            "Dans lequel est-il ? Réponds avec le nom exact du lieu (ou « inconnu »), ta certitude (0 à 1) et une courte raison."
        val key = apiKey()
        var last: Exception? = null
        for (model in models()) {
            try {
                val j = generateWithImage(model, key, jpegBase64, prompt, schema)
                return Triple(j.optString("lieu", "inconnu"), j.optDouble("certitude", 0.0), j.optString("raison"))
            } catch (e: ModelUnavailable) {
                last = e
            }
        }
        throw IOException(quotaText() ?: "vision Gemini indisponible (${last?.message})")
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
        for (model in models().filter { it != MODELS[0] }) {
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
                    if (code !in 200..299) throw unavailable(model, code, text)
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
        throw IOException(quotaText() ?: "Gemini indisponible (${last?.message})")
    }

    // ---- Gemini Robotics-ER : comprendre l'espace -------------------------------------------------
    /** Robotics-ER refusé (quota…) : on passe aux modèles Flash jusqu'à cette heure. */
    @Volatile private var erDownUntil = 0L

    /** Robotics-ER d'abord (sauf s'il vient d'être refusé), puis les modèles Flash. */
    private fun <T> spatial(block: (model: String, key: String) -> T): T {
        val key = apiKey()
        if (key.isBlank()) throw IllegalStateException("clé Gemini manquante")
        var last: Exception? = null
        val models = models().filter { it != MODELS[0] || System.currentTimeMillis() >= erDownUntil }
        if (models.isEmpty()) throw IOException(quotaText() ?: "vision Gemini indisponible")
        for (model in models) {
            try {
                return block(model, key)
            } catch (e: ModelUnavailable) {
                if (model == MODELS[0]) erDownUntil = System.currentTimeMillis() + 5 * 60_000L
                Log.w(TAG, "$model indisponible : ${e.message}")
                last = e
            }
        }
        throw IOException(quotaText() ?: "vision Gemini indisponible (${last?.message})")
    }

    /** Un objet repéré : où dans l'image (x, y de 0 à 1), son cadre, sa distance estimée, et ce qu'on voit. */
    class Spot(val found: Boolean, val confidence: Double, val x: Double, val y: Double, val box: Box?,
               val distance: Double?, val description: String, val model: String)

    /**
     * Repère [target] sur l'image : point au centre de l'objet (ou, si [floor], là où il touche le sol, pour y aller
     * avec les roues), cadre, distance estimée depuis la caméra. found = false s'il n'est pas vraiment visible.
     */
    fun locate(jpegBase64: String, target: String, floor: Boolean = false): Spot = spatial { model, key ->
        val schema = JSONObject("""
        {"type":"object","properties":{
          "found":{"type":"boolean","description":"true seulement si l'objet est vraiment visible sur l'image"},
          "confidence":{"type":"number","description":"0 à 1"},
          "point":{"type":"array","items":{"type":"integer"},"description":"[y, x] entre 0 et 1000"},
          "box_2d":{"type":"array","items":{"type":"integer"},"description":"[ymin, xmin, ymax, xmax] entre 0 et 1000"},
          "distance_m":{"type":"number","description":"distance estimée entre la caméra et l'objet, en mètres"},
          "description":{"type":"string","description":"une phrase en français : l'objet et où il est, ou ce qu'on voit à la place"}
        },"required":["found","confidence","description"]}
        """.trimIndent())
        val where = if (floor) "Le point doit être sur le sol, juste devant l'objet (là où un robot à roues irait pour l'atteindre)."
            else "Le point doit être au centre de l'objet."
        val prompt = "Image de la caméra d'un petit robot de maison (hauteur ~40 cm). Objet cherché : « $target ».\n" +
            "Regarde toute l'image, y compris les bords et les petits objets. S'il est visible, found = true, pointe-le, " +
            "encadre-le et estime sa distance. $where Sinon found = false et dis ce que tu vois. N'invente rien : " +
            "en cas de doute, confidence basse."
        val j = generateWithImage(model, key, jpegBase64, prompt, schema)
        val pt = j.optJSONArray("point")
        val bx = j.optJSONArray("box_2d")
        val box = if (bx != null && bx.length() == 4) {
            val y0 = bx.optDouble(0) / 1000; val x0 = bx.optDouble(1) / 1000
            val y1 = bx.optDouble(2) / 1000; val x1 = bx.optDouble(3) / 1000
            Box(((x0 + x1) / 2).coerceIn(0.0, 1.0), ((y0 + y1) / 2).coerceIn(0.0, 1.0), kotlin.math.abs(x1 - x0), kotlin.math.abs(y1 - y0))
        } else null
        val (x, y) = when {
            pt != null && pt.length() == 2 -> (pt.optDouble(1) / 1000).coerceIn(0.0, 1.0) to (pt.optDouble(0) / 1000).coerceIn(0.0, 1.0)
            box != null -> box.cx to (if (floor) (box.cy + box.h / 2).coerceAtMost(1.0) else box.cy)
            else -> 0.5 to 0.5
        }
        val found = j.optBoolean("found") && (pt != null || box != null)
        Spot(found, j.optDouble("confidence", 0.0), x, y, box,
            j.optDouble("distance_m", Double.NaN).takeIf { !it.isNaN() && it > 0 && it < 30 }, j.optString("description"), model)
    }

    /** Ce qu'une personne montre du doigt. */
    class Pointing(val pointing: Boolean, val target: String, val inImage: Boolean, val x: Double, val y: Double,
                   val direction: String, val angle: Double?, val description: String)

    /**
     * Une personne montre quelque chose du doigt sur l'image : quoi, et où. Si l'objet est dans l'image, on a son point ;
     * sinon la direction à prendre, vue du robot (gauche, droite, haut, bas, derrière lui), et l'angle estimé.
     */
    fun pointedAt(jpegBase64: String): Pointing = spatial { model, key ->
        val schema = JSONObject("""
        {"type":"object","properties":{
          "montre":{"type":"boolean","description":"true si une personne montre clairement quelque chose (doigt ou bras tendu)"},
          "objet":{"type":"string","description":"ce qu'elle montre, nom court en français (« la lampe », « la porte »), ou « inconnu »"},
          "dans_image":{"type":"boolean","description":"true si ce qu'elle montre est visible sur l'image"},
          "point":{"type":"array","items":{"type":"integer"},"description":"[y, x] de ce qu'elle montre, entre 0 et 1000, s'il est dans l'image"},
          "direction":{"type":"string","enum":["gauche","droite","haut","bas","derriere_le_robot","devant"],"description":"si ce n'est pas dans l'image : de quel côté le robot doit tourner la tête pour le voir (gauche/droite DU ROBOT, c'est-à-dire de l'image)"},
          "angle_deg":{"type":"number","description":"si ce n'est pas dans l'image : de combien de degrés il faudrait tourner, à peu près"},
          "description":{"type":"string","description":"une phrase : ce que la personne montre"}
        },"required":["montre","objet","dans_image","description"]}
        """.trimIndent())
        val prompt = "Image de la caméra d'un petit robot de maison. La personne sur l'image montre peut-être quelque chose du " +
            "doigt ou du bras. Suis la direction de son bras et de son doigt (de l'épaule vers la main, et au-delà) et dis ce " +
            "qu'elle montre. Si c'est visible sur l'image, pointe-le. Sinon, dis de quel côté le robot doit tourner la tête : " +
            "la gauche du robot est la gauche de l'image. N'invente rien."
        val j = generateWithImage(model, key, jpegBase64, prompt, schema)
        val pt = j.optJSONArray("point")
        val inImage = j.optBoolean("dans_image") && pt != null && pt.length() == 2
        Pointing(j.optBoolean("montre"), j.optString("objet", "inconnu"), inImage,
            if (inImage) (pt!!.optDouble(1) / 1000).coerceIn(0.0, 1.0) else 0.5,
            if (inImage) (pt!!.optDouble(0) / 1000).coerceIn(0.0, 1.0) else 0.5,
            j.optString("direction", ""), j.optDouble("angle_deg", Double.NaN).takeIf { !it.isNaN() },
            j.optString("description"))
    }

    /**
     * Où regarde la personne (elle a tourné la tête, elle ne regarde pas la caméra) : ce qu'elle regarde, et où.
     * Même réponse que [pointedAt] (pointing = elle regarde clairement quelque chose).
     */
    fun gazeAt(jpegBase64: String): Pointing = spatial { model, key ->
        val schema = JSONObject("""
        {"type":"object","properties":{
          "regarde":{"type":"boolean","description":"true si la personne regarde clairement quelque chose (pas la caméra)"},
          "objet":{"type":"string","description":"ce qu'elle regarde, nom court en français, ou « inconnu »"},
          "dans_image":{"type":"boolean","description":"true si ce qu'elle regarde est visible sur l'image"},
          "point":{"type":"array","items":{"type":"integer"},"description":"[y, x] de ce qu'elle regarde, entre 0 et 1000"},
          "direction":{"type":"string","enum":["gauche","droite","haut","bas","derriere_le_robot","devant"],"description":"si ce n'est pas dans l'image : de quel côté le robot doit tourner la tête (gauche/droite DU ROBOT = de l'image)"},
          "angle_deg":{"type":"number","description":"de combien de degrés il faudrait tourner, à peu près"},
          "description":{"type":"string","description":"une phrase : ce qu'elle regarde"}
        },"required":["regarde","objet","dans_image","description"]}
        """.trimIndent())
        val prompt = "Image de la caméra d'un petit robot de maison. La personne a tourné la tête : suis la direction de son " +
            "regard (orientation du visage et des yeux) et dis ce qu'elle regarde. Si c'est visible sur l'image, pointe-le ; " +
            "sinon dis de quel côté le robot doit tourner la tête (la gauche du robot est la gauche de l'image). N'invente rien."
        val j = generateWithImage(model, key, jpegBase64, prompt, schema)
        val pt = j.optJSONArray("point")
        val inImage = j.optBoolean("dans_image") && pt != null && pt.length() == 2
        Pointing(j.optBoolean("regarde"), j.optString("objet", "inconnu"), inImage,
            if (inImage) (pt!!.optDouble(1) / 1000).coerceIn(0.0, 1.0) else 0.5,
            if (inImage) (pt!!.optDouble(0) / 1000).coerceIn(0.0, 1.0) else 0.5,
            j.optString("direction", ""), j.optDouble("angle_deg", Double.NaN).takeIf { !it.isNaN() },
            j.optString("description"))
    }

    /**
     * Deux vues de la même direction, à des moments différents : qu'est-ce qui a changé de notable ?
     * Renvoie null si ce n'est pas le même point de vue ou si rien de notable n'a changé.
     */
    fun compare(beforeJpeg: String, afterJpeg: String): String? = spatial { model, key ->
        val schema = JSONObject("{\"type\":\"object\",\"properties\":{\"meme_vue\":{\"type\":\"boolean\"}," +
            "\"changement\":{\"type\":\"boolean\"},\"description\":{\"type\":\"string\"}},\"required\":[\"meme_vue\",\"changement\"]}")
        val prompt = "Deux images de la caméra d'un petit robot de maison, prises dans la même direction à des moments " +
            "différents (1 = avant, 2 = maintenant). Est-ce le même point de vue (même endroit de la pièce) ? Si oui, y a-t-il " +
            "un changement notable : un objet nouveau, disparu ou déplacé, un meuble bougé, une décoration ? Ignore les " +
            "personnes, les animaux, la lumière, le cadrage et les petits détails. Décris le changement en une phrase courte, " +
            "en français (« un bouquet de fleurs sur la table »)."
        val gen = JSONObject().put("responseMimeType", "application/json").put("responseJsonSchema", schema).put("temperature", 0.2)
        if (model.startsWith("gemini-3")) gen.put("thinkingConfig", JSONObject().put("thinkingLevel", "LOW"))
        val parts = JSONArray()
            .put(JSONObject().put("text", "Image 1 (avant) :"))
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", beforeJpeg)))
            .put(JSONObject().put("text", "Image 2 (maintenant) :"))
            .put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", afterJpeg)))
            .put(JSONObject().put("text", prompt))
        val j = generateParts(model, key, parts, gen)
        if (j.optBoolean("meme_vue") && j.optBoolean("changement")) j.optString("description").takeIf { it.isNotBlank() } else null
    }

    private fun generateParts(model: String, key: String, parts: JSONArray, gen: JSONObject): JSONObject {
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", gen)
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$key")
            .openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"; conn.connectTimeout = 10_000; conn.readTimeout = 30_000; conn.doOutput = true
            conn.setRequestProperty("content-type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull() ?: text.take(200)
                if (code == 404 || code == 429 || code >= 500 || code == 400 && msg.contains("model", ignoreCase = true)) throw unavailable(model, code, msg)
                throw IOException("Gemini, erreur $code : $msg")
            }
            val p = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            val answer = buildString { if (p != null) for (i in 0 until p.length()) { val x = p.optJSONObject(i) ?: continue; if (!x.optBoolean("thought")) append(x.optString("text")) } }
            val a = answer.indexOf('{'); val b = answer.lastIndexOf('}')
            if (a < 0 || b <= a) throw IOException("réponse illisible")
            return JSONObject(answer.substring(a, b + 1))
        } finally {
            conn.disconnect()
        }
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
            if (!usable(model)) continue
            try {
                return generateWithImage(model, key, jpegBase64, prompt, schema).optString("reponse")
            } catch (e: ModelUnavailable) {
                last = e
            }
        }
        throw IOException(quotaText() ?: "vision Gemini indisponible (${last?.message})")
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
                if (code == 404 || code == 429 || code >= 500 || code == 400 && msg.contains("model", ignoreCase = true)) throw unavailable(model, code, msg)
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
        for (model in models().filter { it != MODELS[0] }) { // modèles Flash (Robotics-ER est pour les images)
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
                    if (code !in 200..299) throw unavailable(model, code, text)
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
        throw IOException(quotaText() ?: "Gemini indisponible (${last?.message})")
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
                if (code == 404 || code == 429 || code >= 500 || code == 400 && msg.contains("model", ignoreCase = true)) throw unavailable(model, code, msg)
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
