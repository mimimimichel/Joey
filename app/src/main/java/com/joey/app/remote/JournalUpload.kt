package com.joey.app.remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Envoie le journal de bord de Joey dans ta base Supabase (projet « joey »), par paquets toutes les 30 s, pour que
 * Claude puisse voir ce qui se passe et l'améliorer. La clé est publique (elle ne permet que d'ajouter des lignes,
 * pas de les lire). Sans Internet, les lignes attendent (5000 au plus).
 */
class JournalUpload(
    private val url: String,
    private val key: String,
    private val device: String,
    private val version: String,
    private val enabled: () -> Boolean,
) {
    private val session = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.FRENCH).format(Date())
    private val queue = ConcurrentLinkedQueue<JSONObject>()
    private val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    @Volatile var lastError: String? = null
        private set
    @Volatile var sent = 0
        private set

    val configured: Boolean get() = url.isNotBlank() && key.isNotBlank()

    fun add(at: Long, kind: String, text: String) {
        if (!configured || !enabled()) return
        queue.add(JSONObject().put("at", synchronized(iso) { iso.format(Date(at)) }).put("device", device).put("version", version)
            .put("session", session).put("kind", kind.take(32)).put("text", text.take(8000)))
        while (queue.size > 5000) queue.poll()
    }

    fun start() {
        if (!configured) return
        thread(name = "journal-envoi", isDaemon = true) {
            while (true) {
                Thread.sleep(30_000)
                runCatching { flush() }
            }
        }
    }

    /** Envoie ce qui attend (par paquets de 300). */
    fun flush() {
        while (queue.isNotEmpty()) {
            val batch = ArrayList<JSONObject>()
            while (batch.size < 300) batch.add(queue.poll() ?: break)
            if (batch.isEmpty()) return
            val body = JSONArray(batch).toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$url/rest/v1/journal").post(body)
                .header("apikey", key).header("Prefer", "return=minimal").build()
            val ok = runCatching { http.newCall(req).execute().use { r -> if (!r.isSuccessful) lastError = "HTTP ${r.code}"; r.isSuccessful } }
                .onFailure { lastError = it.message }.getOrDefault(false)
            if (!ok) { batch.asReversed().forEach { queue.add(it) }; return } // on réessaiera
            sent += batch.size
            lastError = null
        }
    }
}
