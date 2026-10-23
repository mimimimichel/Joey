package com.joey.app.life

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Ce que Joey apprend de ce qu'on lui dit, sans attendre une nouvelle version :
 * - des réglages bornés qu'il ajuste lui-même (« tu vas trop vite », « viens plus près », « tu tournes trop »),
 *   ou que ses auto-tests corrigent (sens des roues, angle des virages) ;
 * - des règles de conduite qu'on lui apprend (« quand je dis X, fais Y »), rappelées au début de chaque conversation.
 * Tout est gardé dans un fichier du téléphone.
 */
class Tuning(private val file: File) {
    /** Un réglage : valeur par défaut, bornes, pas (multiplicatif si [ratio]), et ce qu'il veut dire. */
    class Knob(val name: String, val def: Double, val min: Double, val max: Double, val step: Double, val ratio: Boolean,
               val meaning: String, val plus: String, val minus: String)

    companion object {
        val KNOBS = listOf(
            Knob("vitesse", 0.40, 0.10, 0.50, 1.2, true, "vitesse max des roues quand la voie est libre (m/s ; il ralentit seul " +
                "près des obstacles, dans l'inconnu et dans les virages)",
                "plus vite", "plus lentement"),
            Knob("proximite", 0.75, 0.55, 0.92, 0.07, false, "jusqu'où il s'approche quand il suit ou vient (taille de la " +
                "personne dans l'image)", "plus près", "plus loin"),
            Knob("virage", 1.8, 0.8, 3.0, 1.25, true, "vivacité pour se tourner vers quelqu'un en roulant",
                "tourne plus franchement", "tourne plus doucement"),
            Knob("echelle_rotation", 1.0, 0.6, 1.4, 1.06, true, "correction des angles mesurés par les roues (si « 90° » " +
                "fait plus ou moins qu'un quart de tour)", "il tournait trop (ses angles comptent plus)",
                "il ne tournait pas assez"),
        )
        fun knob(name: String) = KNOBS.firstOrNull { it.name == name.trim().lowercase() }
    }

    private val values = HashMap<String, Double>()
    private val rules = ArrayList<String>()

    init {
        load()
        // v0.53 : la vitesse est devenue une vitesse de croisière (il ralentit seul où il faut) ; les anciens réglages
        // prudents (0,25-0,31, « super lent ») passent à la nouvelle vitesse d'origine.
        if ((values["vitesse"] ?: 1.0) < 0.35 && values["vitesse_v2"] == null) { values.remove("vitesse"); values["vitesse_v2"] = 1.0; save() }
    }

    @Synchronized private fun load() {
        if (!file.exists()) return
        runCatching {
            val o = JSONObject(file.readText())
            o.optJSONObject("reglages")?.let { r -> r.keys().forEach { k -> values[k] = r.getDouble(k) } }
            o.optJSONArray("regles")?.let { a -> for (i in 0 until a.length()) rules.add(a.getString(i)) }
        }
    }

    @Synchronized private fun save() {
        val o = JSONObject()
        o.put("reglages", JSONObject().also { r -> values.forEach { (k, v) -> r.put(k, v) } })
        o.put("regles", JSONArray(rules))
        runCatching { file.writeText(o.toString(1)) }
    }

    @Synchronized fun get(name: String): Double {
        val k = knob(name) ?: return 0.0
        return (values[k.name] ?: k.def).coerceIn(k.min, k.max)
    }

    /**
     * [how] : « plus », « moins », « normal » (valeur par défaut) ; ou [value] exacte. Renvoie ce qui a changé.
     */
    @Synchronized fun adjust(name: String, how: String?, value: Double? = null): String {
        val k = knob(name) ?: return "réglage inconnu : $name (connus : ${KNOBS.joinToString { it.name }})"
        val before = get(k.name)
        val after = when {
            value != null -> value
            how?.trim()?.lowercase() == "normal" -> k.def
            how?.trim()?.lowercase() == "plus" -> if (k.ratio) before * k.step else before + k.step
            how?.trim()?.lowercase() == "moins" -> if (k.ratio) before / k.step else before - k.step
            else -> return "dis « plus », « moins » ou « normal »"
        }.coerceIn(k.min, k.max)
        values[k.name] = after
        save()
        val edge = if (after == k.max || after == k.min) " (c'est la limite)" else ""
        return "${k.name} : %.2f -> %.2f$edge".format(before, after)
    }

    /** Une correction mesurée par un auto-test (pas de pas fixe). */
    @Synchronized fun set(name: String, v: Double) {
        val k = knob(name) ?: return
        values[k.name] = v.coerceIn(k.min, k.max)
        save()
    }

    @Synchronized fun addRule(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty() || rules.any { it.equals(t, true) }) return false
        rules.add(t)
        while (rules.size > 30) rules.removeAt(0)
        save()
        return true
    }

    @Synchronized fun removeRule(part: String): List<String> {
        val gone = rules.filter { it.contains(part.trim(), true) }
        rules.removeAll(gone.toSet())
        if (gone.isNotEmpty()) save()
        return gone
    }

    @Synchronized fun rules(): List<String> = rules.toList()

    /** Pour la consigne de départ : les règles apprises et les réglages qui ne sont pas d'origine. */
    @Synchronized fun promptText(): String {
        val sb = StringBuilder()
        if (rules.isNotEmpty()) {
            sb.append("\n\nCe que Michael t'a appris (à respecter, c'est prioritaire) :\n")
            rules.forEach { sb.append("- ").append(it).append('\n') }
        }
        val changed = KNOBS.filter { values[it.name] != null && values[it.name] != it.def }
        if (changed.isNotEmpty()) {
            sb.append("\nTes réglages appris (outil « ajuster ») : ")
            sb.append(changed.joinToString { "${it.name} %.2f".format(get(it.name)) })
            sb.append('\n')
        }
        return sb.toString()
    }

    /** Pour l'outil « etat » / « mes_reglages ». */
    @Synchronized fun describe(): String = KNOBS.joinToString(" ; ") { "${it.name} = %.2f (${it.meaning})".format(get(it.name)) }
}
