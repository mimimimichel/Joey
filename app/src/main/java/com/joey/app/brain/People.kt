package com.joey.app.brain

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Les gens de la maison que Joey reconnaît : pour chacun, quelques empreintes de visage (pas de photo).
 * Gardé sur le téléphone. Une personne n'est apprise que si elle le demande (outil apprendre_visage ou réglages).
 */
class People(private val file: File) {
    class Person(val name: String, val prints: MutableList<FloatArray>)

    private val people = ArrayList<Person>()
    /** Partage : visages changés à envoyer, et prénoms oubliés. */
    private val dirty = LinkedHashSet<String>()
    private val forgotten = LinkedHashSet<String>()
    @Volatile var onChange: (() -> Unit)? = null

    /** À envoyer : (prénom, empreintes) changés, et prénoms oubliés. */
    fun pending(): Pair<List<Pair<String, List<FloatArray>>>, List<String>> = synchronized(people) {
        people.filter { it.name.lowercase() in dirty }.map { it.name to it.prints.toList() } to forgotten.toList()
    }

    fun markSent() = synchronized(people) { dirty.clear(); forgotten.clear() }

    /** Visages venus de l'autre téléphone : empreintes réunies (sans doublons, 40 au plus), ou prénom oublié. */
    fun mergeRemote(name: String, prints: List<FloatArray>, removed: Boolean): Boolean = synchronized(people) {
        val n = name.trim().replaceFirstChar { it.uppercase() }
        if (removed) return@synchronized people.removeAll { it.name.equals(n, ignoreCase = true) }.also { if (it) save(silent = true) }
        val p = people.firstOrNull { it.name.equals(n, ignoreCase = true) } ?: Person(n, ArrayList()).also { people.add(it) }
        var added = 0
        for (v in prints) if (p.prints.none { dot(it, v) > 0.995f }) { p.prints.add(v); added++ }
        while (p.prints.size > 40) p.prints.removeAt(0)
        if (added > 0) { dirty.add(n.lowercase()); save(silent = true) }
        added > 0
    }

    init {
        runCatching {
            if (file.exists()) {
                val a = JSONArray(file.readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val ps = o.getJSONArray("prints")
                    val list = ArrayList<FloatArray>()
                    for (j in 0 until ps.length()) {
                        val v = ps.getJSONArray(j)
                        list.add(FloatArray(v.length()) { v.getDouble(it).toFloat() })
                    }
                    people.add(Person(o.getString("name"), list))
                    dirty.add(o.getString("name").lowercase()) // envoyé une fois à chaque démarrage (petit)
                }
            }
        }
    }

    fun names(): List<String> = synchronized(people) { people.map { it.name } }

    /**
     * Ajoute des empreintes à une personne (créée si besoin ; nom comparé sans majuscules). [quiet] : complément
     * automatique (il la reconnaît et affine) : partagé avec la synchro périodique, pas tout de suite.
     */
    fun learn(name: String, prints: List<FloatArray>, quiet: Boolean = false) = synchronized(people) {
        val n = name.trim().replaceFirstChar { it.uppercase() }
        val p = people.firstOrNull { it.name.equals(n, ignoreCase = true) } ?: Person(n, ArrayList()).also { people.add(it) }
        p.prints.addAll(prints)
        while (p.prints.size > 40) p.prints.removeAt(0)
        dirty.add(n.lowercase()); forgotten.remove(n.lowercase())
        save(silent = quiet)
    }

    fun forget(name: String): Boolean = synchronized(people) {
        val r = people.removeAll { it.name.equals(name.trim(), ignoreCase = true) }
        if (r) { forgotten.add(name.trim().lowercase()); dirty.remove(name.trim().lowercase()) }
        save()
        r
    }

    private fun save(silent: Boolean = false) {
        if (!silent) runCatching { onChange?.invoke() }
        val a = JSONArray()
        people.forEach { p ->
            val ps = JSONArray()
            p.prints.forEach { v -> ps.put(JSONArray().also { arr -> v.forEach { x -> arr.put(x.toDouble()) } }) }
            a.put(JSONObject().put("name", p.name).put("prints", ps))
        }
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(a.toString())
            tmp.renameTo(file)
        }
    }

    /**
     * Qui est-ce ? Le nom si l'empreinte ressemble assez à quelqu'un (similarité ≥ [threshold]) et nettement plus qu'aux
     * autres ; null sinon. Renvoie aussi la meilleure similarité. Seuil mesuré sur des photos (LFW) : deux personnes
     * différentes dépassent 0,40 dans 1 % des cas, la même personne a ~0,58 en moyenne avec UNE seule photo
     * (on en garde une douzaine par personne, donc mieux).
     */
    fun identify(print: FloatArray, threshold: Float = 0.50f): Pair<String?, Float> = synchronized(people) {
        val scores = people.map { p -> p.name to (p.prints.maxOfOrNull { dot(it, print) } ?: -1f) }.sortedByDescending { it.second }
        val best = scores.firstOrNull() ?: return null to 0f
        val second = scores.getOrNull(1)?.second ?: -1f
        return if (best.second >= threshold && best.second - second >= 0.05f) best.first to best.second else null to best.second
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
