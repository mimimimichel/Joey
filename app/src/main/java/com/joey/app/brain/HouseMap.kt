package com.joey.app.brain

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer

/**
 * La carte de la maison, pièce par pièce, gardée sur le téléphone.
 * Pour chaque lieu : une description, et les éléments vus en faisant le tour du regard, avec leur cap boussole
 * (0 = nord, 90 = est), leur élévation et leur distance estimée (Gemini Robotics-ER). Les positions (x, y) des lieux
 * seront remplies par l'odométrie du Roomba quand il sera branché (plan de la maison).
 */
class HouseMap(private val file: File) {
    class Item(val name: String, val heading: Double?, val elevation: Double, val distanceM: Double?, val seenAt: Long)
    class Place(
        val name: String,
        var description: String,
        var kind: String,
        val items: MutableList<Item>,
        var updatedAt: Long,
        /** Position sur le plan (mètres), quand le Roomba saura où il est. */
        var x: Double? = null,
        var y: Double? = null,
    )

    private val places = ArrayList<Place>()

    init {
        runCatching {
            val a = JSONArray(file.readText())
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val its = o.getJSONArray("items")
                val list = ArrayList<Item>()
                for (j in 0 until its.length()) {
                    val it = its.getJSONObject(j)
                    list.add(Item(it.getString("name"), if (it.isNull("heading")) null else it.getDouble("heading"),
                        it.optDouble("elevation", 0.0), if (it.isNull("dist")) null else it.getDouble("dist"), it.getLong("seen")))
                }
                places.add(Place(o.getString("name"), o.optString("description"), o.optString("kind"), list, o.getLong("updated"),
                    if (o.isNull("x")) null else o.optDouble("x"), if (o.isNull("y")) null else o.optDouble("y")))
            }
        }
    }

    private fun save() {
        val a = JSONArray()
        places.forEach { p ->
            val its = JSONArray()
            p.items.forEach { its.put(JSONObject().put("name", it.name).put("heading", it.heading ?: JSONObject.NULL)
                .put("elevation", it.elevation).put("dist", it.distanceM ?: JSONObject.NULL).put("seen", it.seenAt)) }
            a.put(JSONObject().put("name", p.name).put("description", p.description).put("kind", p.kind).put("items", its)
                .put("updated", p.updatedAt).put("x", p.x ?: JSONObject.NULL).put("y", p.y ?: JSONObject.NULL))
        }
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(a.toString())
            tmp.renameTo(file)
        }
    }

    fun all(): List<Place> = synchronized(places) { places.toList() }

    fun place(name: String): Place? = synchronized(places) { places.firstOrNull { norm(it.name) == norm(name) } }

    /** Enregistre (ou remplace) le relevé d'un lieu. */
    fun put(name: String, kind: String, description: String, items: List<Item>, x: Double? = null, y: Double? = null) = synchronized(places) {
        val p = places.firstOrNull { norm(it.name) == norm(name) }
        if (p == null) {
            places.add(Place(name.trim(), description, kind, items.toMutableList(), System.currentTimeMillis(), x, y))
        } else {
            p.items.clear(); p.items.addAll(items)
            p.description = description; p.kind = kind; p.updatedAt = System.currentTimeMillis()
            if (x != null && y != null) { p.x = x; p.y = y }
        }
        save()
    }

    fun remove(name: String) = synchronized(places) { places.removeAll { norm(it.name) == norm(name) }; save() }

    private fun norm(s: String) = Normalizer.normalize(s.lowercase().trim(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    private fun words(s: String) = norm(s).split(Regex("[^a-z0-9]+")).filter { it.length > 2 && it !in setOf("les", "des", "une", "mes", "mon", "est") }

    /** Où a-t-on vu [what] ? (lieu, élément), les meilleurs d'abord. */
    fun find(what: String): List<Pair<Place, Item>> = synchronized(places) {
        val q = words(what)
        if (q.isEmpty()) return emptyList()
        places.flatMap { p -> p.items.map { p to it } }
            .map { pair -> pair to q.count { w -> words(pair.second.name).any { it.startsWith(w) || w.startsWith(it) && it.length >= 4 } } }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Pair<Place, Item>, Int>> { it.second }.thenByDescending { it.first.second.seenAt })
            .map { it.first }
            .take(5)
    }

    /** Résumé pour Gemini. */
    fun summary(): String = synchronized(places) {
        if (places.isEmpty()) return "carte vide : aucun lieu relevé"
        places.joinToString("\n") { p ->
            "- ${p.name} (${p.kind}) : ${p.description.take(150)} ; éléments : " +
                p.items.map { it.name }.distinct().take(25).joinToString(", ")
        }
    }

    companion object {
        fun compassName(h: Double): String {
            val names = listOf("nord", "nord-est", "est", "sud-est", "sud", "sud-ouest", "ouest", "nord-ouest")
            return names[(((h % 360 + 360) % 360 + 22.5) / 45).toInt() % 8]
        }
    }
}
