package com.joey.app.remote

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.concurrent.thread

/**
 * Journal de garde : ce que Joey a remarqué (personne inconnue, chien qui fait une bêtise…), avec une photo.
 * Gardé sur le téléphone (60 derniers). Les alertes peuvent partir sur le téléphone de Michael par ntfy.sh
 * (application gratuite, sans compte : il suffit de s'abonner au même « sujet »).
 */
class Watch(private val dir: File, private val ntfyTopic: () -> String?, private val log: (String) -> Unit) {
    class Event(val id: Long, val time: Long, val text: String, val alert: Boolean, val hasPhoto: Boolean)

    private val events = ArrayList<Event>()
    private val lastByKind = HashMap<String, Long>()

    init {
        dir.mkdirs()
        runCatching {
            val a = JSONArray(File(dir, "journal.json").readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                events.add(Event(o.getLong("id"), o.getLong("time"), o.getString("text"), o.optBoolean("alert"), o.optBoolean("photo")))
            }
        }
    }

    /**
     * Note un événement (au plus un par [kind] toutes les [everyMs]). [alert] : prévenir Michael (ntfy).
     * Renvoie true s'il a été noté.
     */
    fun record(kind: String, text: String, jpeg: ByteArray?, alert: Boolean, everyMs: Long = 5 * 60_000L): Boolean {
        val now = System.currentTimeMillis()
        synchronized(events) {
            if (now - (lastByKind[kind] ?: 0L) < everyMs) return false
            lastByKind[kind] = now
            val e = Event(now, now, text, alert, jpeg != null)
            jpeg?.let { runCatching { File(dir, "$now.jpg").writeBytes(it) } }
            events.add(e)
            while (events.size > 60) {
                val old = events.removeAt(0)
                File(dir, "${old.id}.jpg").delete()
            }
            save()
        }
        log((if (alert) "🚨 " else "🛡 ") + text)
        if (alert) notify(text, jpeg)
        return true
    }

    private fun save() {
        val a = JSONArray()
        events.forEach { a.put(JSONObject().put("id", it.id).put("time", it.time).put("text", it.text).put("alert", it.alert).put("photo", it.hasPhoto)) }
        runCatching { File(dir, "journal.json").writeText(a.toString()) }
    }

    fun recent(n: Int = 20): List<Event> = synchronized(events) { events.takeLast(n).reversed() }

    fun photo(id: Long): ByteArray? = runCatching { File(dir, "$id.jpg").readBytes() }.getOrNull()

    fun json(): String = JSONArray().also { a ->
        recent(30).forEach { a.put(JSONObject().put("id", it.id).put("time", it.time).put("text", it.text).put("alert", it.alert).put("photo", it.hasPhoto)) }
    }.toString()

    fun clear() = synchronized(events) {
        events.forEach { File(dir, "${it.id}.jpg").delete() }
        events.clear()
        save()
    }

    /** Alerte sur le téléphone de Michael via ntfy.sh (photo en pièce jointe si possible). */
    fun notify(text: String, jpeg: ByteArray?) {
        val topic = ntfyTopic()?.trim()?.takeIf { it.isNotEmpty() } ?: return
        thread(isDaemon = true) {
            runCatching {
                val c = URL("https://ntfy.sh/" + URLEncoder.encode(topic, "UTF-8")).openConnection() as HttpURLConnection
                c.requestMethod = "PUT"
                c.doOutput = true
                c.connectTimeout = 10_000
                c.readTimeout = 20_000
                // Message en en-tête (encodage RFC 2047 pour les accents), photo en corps de requête.
                c.setRequestProperty("Title", "=?UTF-8?B?" + Base64.encodeToString("Joey".toByteArray(), Base64.NO_WRAP) + "?=")
                c.setRequestProperty("Message", "=?UTF-8?B?" + Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP) + "?=")
                c.setRequestProperty("Tags", "robot")
                if (jpeg != null) {
                    c.setRequestProperty("Filename", "joey.jpg")
                    c.outputStream.use { it.write(jpeg) }
                } else {
                    c.outputStream.use { it.write(text.toByteArray()) }
                }
                val code = c.responseCode
                c.disconnect()
                if (code !in 200..299) log("Alerte ntfy refusée ($code)")
            }.onFailure { log("Alerte ntfy impossible : ${it.message}") }
        }
    }
}
