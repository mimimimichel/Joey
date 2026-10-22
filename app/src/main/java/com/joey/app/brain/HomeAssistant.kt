package com.joey.app.brain

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer

/**
 * La maison connectée par Home Assistant (lumières, prises, volets, télé, chauffage…) : adresse du serveur et jeton
 * d'accès longue durée (Profil -> Sécurité -> Jetons d'accès). Rien n'est envoyé ailleurs qu'à ce serveur.
 */
class HomeAssistant(private val url: () -> String, private val token: () -> String) {
    class Entity(val id: String, val name: String, val state: String) {
        val domain get() = id.substringBefore('.')
    }

    val configured: Boolean get() = url().isNotBlank() && token().isNotBlank()

    private fun call(method: String, path: String, body: JSONObject? = null): String {
        val c = URL(url().trimEnd('/') + path).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 6000
            c.readTimeout = 10_000
            c.setRequestProperty("Authorization", "Bearer " + token().trim())
            c.setRequestProperty("content-type", "application/json")
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code == 401) throw IOException("jeton Home Assistant refusé")
            if (code !in 200..299) throw IOException("Home Assistant : erreur $code")
            return text
        } finally {
            c.disconnect()
        }
    }

    companion object {
        /** Les appareils qu'on pilote à la voix. */
        val DOMAINS = setOf("light", "switch", "cover", "media_player", "climate", "fan", "scene", "script", "lock", "vacuum")

        fun parse(states: String): List<Entity> {
            val a = JSONArray(states)
            return (0 until a.length()).mapNotNull { i ->
                val o = a.getJSONObject(i)
                val id = o.getString("entity_id")
                if (id.substringBefore('.') !in DOMAINS) return@mapNotNull null
                Entity(id, o.optJSONObject("attributes")?.optString("friendly_name")?.takeIf { it.isNotBlank() } ?: id, o.optString("state"))
            }
        }

        private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        private fun words(s: String) = norm(s).split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in setOf("le", "la", "les", "du", "de", "des") }

        /** L'appareil qui correspond le mieux à [what] (« lumière du salon »), ou null. */
        fun match(entities: List<Entity>, what: String): Entity? {
            entities.firstOrNull { it.id == what.trim() }?.let { return it }
            val q = words(what)
            val kindWords = mapOf("lumiere" to "light", "lampe" to "light", "plafonnier" to "light", "prise" to "switch",
                "volet" to "cover", "store" to "cover", "tele" to "media_player", "television" to "media_player", "chauffage" to "climate",
                "thermostat" to "climate", "ventilateur" to "fan", "scene" to "scene", "serrure" to "lock")
            val kinds = q.mapNotNull { kindWords[it] }.toSet()
            return entities.map { e ->
                val w = words(e.name) + words(e.id.substringAfter('.'))
                val score = q.count { qw -> w.any { it.startsWith(qw) || qw.startsWith(it) && it.length >= 3 } } +
                    (if (e.domain in kinds) 2 else 0)
                e to score
            }.filter { it.second > 0 }.maxByOrNull { it.second }?.first
        }
    }

    fun entities(): List<Entity> = parse(call("GET", "/api/states"))

    /** allumer / eteindre / basculer / ouvrir / fermer / regler (valeur : température °C ou luminosité %). */
    fun command(what: String, action: String, value: Double?): String {
        val all = entities()
        val e = match(all, what) ?: return "aucun appareil ne correspond à « $what »"
        val d = e.domain
        val (service, extra) = when (action) {
            "allumer" -> (if (d == "cover") "open_cover" else if (d == "scene" || d == "script") "turn_on" else "turn_on") to null
            "eteindre" -> (if (d == "cover") "close_cover" else "turn_off") to null
            "ouvrir" -> "open_cover" to null
            "fermer" -> "close_cover" to null
            "basculer" -> "toggle" to null
            "regler" -> when (d) {
                "climate" -> "set_temperature" to JSONObject().put("temperature", value ?: 20.0)
                "light" -> "turn_on" to JSONObject().put("brightness_pct", (value ?: 50.0).coerceIn(1.0, 100.0))
                "media_player" -> "volume_set" to JSONObject().put("volume_level", ((value ?: 30.0) / 100).coerceIn(0.0, 1.0))
                else -> "turn_on" to null
            }
            else -> return "action inconnue : $action"
        }
        val body = (extra ?: JSONObject()).put("entity_id", e.id)
        call("POST", "/api/services/$d/$service", body)
        return "${e.name} : $action fait"
    }
}
