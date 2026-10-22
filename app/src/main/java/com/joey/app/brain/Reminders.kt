package com.joey.app.brain

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Calendar

/**
 * Rappels et minuteurs de Joey (« rappelle-moi de sortir le linge dans 20 min », « réveille-moi à 7 h »),
 * gardés sur le téléphone. Un rappel peut revenir chaque jour.
 */
class Reminders(private val file: File, private val now: () -> Long = { System.currentTimeMillis() }) {
    class Reminder(val id: Long, val at: Long, val text: String, val daily: Boolean)

    private val list = ArrayList<Reminder>()

    init {
        runCatching {
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                list.add(Reminder(o.getLong("id"), o.getLong("at"), o.getString("text"), o.optBoolean("daily")))
            }
        }
    }

    private fun save() {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("at", it.at).put("text", it.text).put("daily", it.daily)) }
        runCatching { file.writeText(a.toString()) }
    }

    fun all(): List<Reminder> = synchronized(list) { list.sortedBy { it.at } }

    /**
     * Ajoute un rappel : dans [inMinutes] minutes, ou à [hhmm] (« 7:00 », « 18h30 » ; demain si l'heure est passée).
     * Renvoie le rappel, ou null si l'heure est illisible.
     */
    fun add(text: String, inMinutes: Double?, hhmm: String?, daily: Boolean): Reminder? {
        val t = now()
        val at = when {
            inMinutes != null -> t + (inMinutes * 60_000).toLong()
            hhmm != null -> nextAt(hhmm) ?: return null
            else -> return null
        }
        val r = Reminder(t, at, text.trim(), daily)
        synchronized(list) { list.add(r); save() }
        return r
    }

    /** Prochaine occurrence de « HH:MM » (ou « HHhMM », « HH h »). */
    fun nextAt(hhmm: String): Long? {
        val m = Regex("(\\d{1,2})\\s*[:hH]\\s*(\\d{2})?").find(hhmm) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toIntOrNull() ?: 0
        if (h > 23 || min > 59) return null
        val c = Calendar.getInstance().apply {
            timeInMillis = now()
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now()) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }

    /** Annule les rappels qui parlent de [what] (ou tous si « tout »). Renvoie leur texte. */
    fun cancel(what: String): List<String> = synchronized(list) {
        val w = what.lowercase().trim()
        val gone = list.filter { w == "tout" || w == "tous" || it.text.lowercase().contains(w) }
        list.removeAll(gone.toSet())
        save()
        gone.map { it.text }
    }

    /** Rappels arrivés à échéance (retirés, ou reprogrammés au lendemain s'ils sont quotidiens). */
    fun due(): List<Reminder> = synchronized(list) {
        val t = now()
        val d = list.filter { it.at <= t }
        if (d.isEmpty()) return emptyList()
        list.removeAll(d.toSet())
        d.filter { it.daily }.forEach { list.add(Reminder(it.id, it.at + 24 * 3600_000L, it.text, true)) }
        save()
        d
    }
}
