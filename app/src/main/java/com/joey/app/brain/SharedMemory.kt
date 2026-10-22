package com.joey.app.brain

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Mémoire partagée entre les téléphones de la maison (S10, S8+…) : souvenirs et visages appris, dans la base Supabase
 * « joey ». Protégée par le code du foyer (envoyé dans l'en-tête x-joey-foyer) : sans lui, on ne lit ni n'écrit rien.
 * Synchronisation : on reçoit d'abord ce qui a changé ailleurs, puis on envoie ce qui a changé ici.
 */
class SharedMemory(
    private val url: String,
    private val key: String,
    private val foyer: () -> String,
    private val device: String,
    private val memory: Memory,
    private val people: People,
    private val lastSync: () -> String,
    private val setLastSync: (String) -> Unit,
    private val log: (String) -> Unit,
) {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
    private val json = "application/json".toMediaType()

    val configured: Boolean get() = url.isNotBlank() && key.isNotBlank() && foyer().length >= 12

    private fun hash(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun req(path: String) = Request.Builder().url("$url/rest/v1/$path").header("apikey", key).header("x-joey-foyer", foyer())

    private fun get(path: String): JSONArray = http.newCall(req(path).get().build()).execute().use { r ->
        if (!r.isSuccessful) throw java.io.IOException("lecture $path : HTTP ${r.code}")
        JSONArray(r.body?.string() ?: "[]")
    }

    private fun upsert(table: String, conflict: String, rows: JSONArray) {
        if (rows.length() == 0) return
        val b = req("$table?on_conflict=$conflict").header("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(rows.toString().toRequestBody(json)).build()
        http.newCall(b).execute().use { r -> if (!r.isSuccessful) throw java.io.IOException("écriture $table : HTTP ${r.code} ${r.body?.string()?.take(120)}") }
    }

    /** Une synchronisation complète. Renvoie un résumé (ou lève une erreur). */
    @Synchronized
    fun sync(): String {
        if (!configured) return "pas de code du foyer"
        val f = hash(foyer())
        val since = lastSync().ifEmpty { "1970-01-01T00:00:00Z" }
        var newest = since
        // 1. Ce qui a changé ailleurs.
        val rows = get("souvenirs?select=id,texte,temps,auto,supprime,appareil,modifie&modifie=gt.${enc(since)}&order=modifie.asc&limit=2000")
        val notes = ArrayList<Triple<Memory.Note, Boolean, String>>()
        for (i in 0 until rows.length()) {
            val o = rows.getJSONObject(i)
            newest = maxOf(newest, o.optString("modifie"))
            if (o.optString("appareil") == device) continue
            notes.add(Triple(Memory.Note(o.getString("texte"), o.getLong("temps"), o.optBoolean("auto"), o.getString("id")),
                o.optBoolean("supprime"), o.optString("appareil")))
        }
        val gotNotes = memory.mergeRemote(notes)
        val faces = get("visages?select=nom,empreintes,supprime,appareil,modifie&modifie=gt.${enc(since)}&limit=200")
        var gotFaces = 0
        for (i in 0 until faces.length()) {
            val o = faces.getJSONObject(i)
            newest = maxOf(newest, o.optString("modifie"))
            if (o.optString("appareil") == device) continue
            val arr = o.optJSONArray("empreintes") ?: JSONArray()
            val prints = (0 until arr.length()).map { k -> val v = arr.getJSONArray(k); FloatArray(v.length()) { v.getDouble(it).toFloat() } }
            if (people.mergeRemote(o.getString("nom"), prints, o.optBoolean("supprime"))) gotFaces++
        }
        // 2. Ce qui a changé ici.
        val mine = memory.pending()
        val tomb = memory.unsentTombstones()
        val up = JSONArray()
        mine.forEach { n -> up.put(JSONObject().put("foyer", f).put("id", n.id).put("texte", n.text.take(600)).put("temps", n.time)
            .put("auto", n.auto).put("supprime", false).put("appareil", device)) }
        tomb.forEach { id -> up.put(JSONObject().put("foyer", f).put("id", id).put("texte", "").put("temps", 0).put("supprime", true).put("appareil", device)) }
        upsert("souvenirs", "foyer,id", up)
        memory.markSent(mine.map { it.id }, tomb)
        val (changed, forgotten) = people.pending()
        val upF = JSONArray()
        changed.forEach { (name, prints) ->
            upF.put(JSONObject().put("foyer", f).put("nom", name).put("supprime", false).put("appareil", device)
                .put("empreintes", JSONArray().also { a -> prints.forEach { v -> a.put(JSONArray().also { x -> v.forEach { x.put(it.toDouble()) } }) } }))
        }
        forgotten.forEach { name -> upF.put(JSONObject().put("foyer", f).put("nom", name.replaceFirstChar { it.uppercase() })
            .put("supprime", true).put("empreintes", JSONArray()).put("appareil", device)) }
        upsert("visages", "foyer,nom", upF)
        people.markSent()
        setLastSync(newest)
        val parts = ArrayList<String>()
        if (gotNotes > 0) parts.add("$gotNotes souvenirs reçus")
        if (gotFaces > 0) parts.add("$gotFaces visages reçus")
        if (mine.isNotEmpty() || tomb.isNotEmpty()) parts.add("${mine.size + tomb.size} souvenirs envoyés")
        if (changed.isNotEmpty() || forgotten.isNotEmpty()) parts.add("${changed.size + forgotten.size} visages envoyés")
        return parts.joinToString(", ").ifEmpty { "à jour" }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
