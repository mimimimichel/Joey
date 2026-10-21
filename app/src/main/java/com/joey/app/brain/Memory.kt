package com.joey.app.brain

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Ce que Joey a choisi de retenir (« Michael aime le café serré »). Relu au début de chaque conversation. */
class Memory(private val file: File) {
    class Note(val text: String, val time: Long)

    private val notes = ArrayList<Note>()

    init {
        runCatching {
            if (file.exists()) {
                val a = JSONArray(file.readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    notes.add(Note(o.getString("text"), o.getLong("time")))
                }
            }
        }
    }

    val size: Int get() = synchronized(notes) { notes.size }

    fun add(text: String) {
        val t = text.trim().take(500)
        if (t.isEmpty()) return
        synchronized(notes) {
            notes.add(Note(t, System.currentTimeMillis()))
            while (notes.size > 200) notes.removeAt(0)
            save()
        }
    }

    fun clear() = synchronized(notes) { notes.clear(); save() }

    private fun save() {
        val a = JSONArray()
        notes.forEach { a.put(JSONObject().put("text", it.text).put("time", it.time)) }
        runCatching { file.writeText(a.toString()) }
    }

    /** Texte ajouté au prompt système. */
    fun promptText(): String = synchronized(notes) {
        if (notes.isEmpty()) return ""
        val fmt = SimpleDateFormat("d MMM", Locale.FRENCH)
        "\n\nCe que tu as retenu des fois précédentes (du plus ancien au plus récent) :\n" +
            notes.takeLast(80).joinToString("\n") { "- (${fmt.format(Date(it.time))}) ${it.text}" }
    }
}
