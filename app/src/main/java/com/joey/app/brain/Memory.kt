package com.joey.app.brain

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * La mémoire de Joey, sur le téléphone (fichier privé de l'app) : ce qu'il a choisi de retenir pendant la
 * conversation, et ce que Gemini a extrait des conversations (« Michael aime le café serré »).
 * Les souvenirs récents sont donnés au début de chaque conversation ; tous peuvent être cherchés (outil se_souvenir).
 */
class Memory(private val file: File) {
    /** [auto] : extrait automatiquement d'une conversation (sinon : retenu par Joey lui-même). */
    /** [id] : identifiant stable (pour partager la mémoire entre les téléphones). */
    class Note(val text: String, val time: Long, val auto: Boolean = false, val id: String = makeId(text, time))

    companion object {
        fun makeId(text: String, time: Long): String =
            java.security.MessageDigest.getInstance("SHA-1").digest("$time|$text".toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
    }

    private val notes = ArrayList<Note>()
    /** Partage : souvenirs à envoyer, et souvenirs effacés (pour les effacer aussi sur l'autre téléphone). */
    private val dirty = LinkedHashSet<String>()
    private val deleted = LinkedHashSet<String>()
    private val syncFile = File(file.path + ".partage")
    /** Appelé quand la mémoire change (pour partager bientôt). */
    @Volatile var onChange: (() -> Unit)? = null

    init {
        runCatching {
            if (file.exists()) {
                val a = JSONArray(file.readText())
                for (i in 0 until a.length()) {
                    val o = a.getJSONObject(i)
                    val t = o.getString("text"); val tm = o.getLong("time")
                    notes.add(Note(t, tm, o.optBoolean("auto", false), o.optString("id").ifEmpty { makeId(t, tm) }))
                }
            }
        }
        runCatching {
            if (syncFile.exists()) {
                val o = JSONObject(syncFile.readText())
                o.optJSONArray("dirty")?.let { a -> for (i in 0 until a.length()) dirty.add(a.getString(i)) }
                o.optJSONArray("deleted")?.let { a -> for (i in 0 until a.length()) deleted.add(a.getString(i)) }
            } else notes.forEach { dirty.add(it.id) } // première fois : tout est à partager
        }
    }

    private fun saveSync() {
        runCatching { syncFile.writeText(JSONObject().put("dirty", JSONArray(dirty.toList())).put("deleted", JSONArray(deleted.toList().takeLast(3000))).toString()) }
    }

    private fun gone(n: Note) { deleted.add(n.id); dirty.remove(n.id) }

    /** À envoyer : souvenirs nouveaux ou modifiés, et souvenirs effacés (id). */
    fun pending(): List<Note> = synchronized(notes) { notes.filter { it.id in dirty } }

    /** Envoyés avec succès. */
    fun markSent(ids: Collection<String>, tombstones: Collection<String>) = synchronized(notes) {
        dirty.removeAll(ids.toSet()); sentTombstones.addAll(tombstones); saveSync()
    }
    private val sentTombstones = HashSet<String>()
    fun unsentTombstones(): List<String> = synchronized(notes) { deleted.filter { it !in sentTombstones } }

    /** Souvenirs venus de l'autre téléphone. Renvoie le nombre de changements. */
    fun mergeRemote(rows: List<Triple<Note, Boolean, String>>): Int = synchronized(notes) {
        var n = 0
        for ((note, removed, _) in rows) {
            if (removed) {
                if (notes.removeAll { it.id == note.id }) n++
                deleted.add(note.id); sentTombstones.add(note.id)
            } else if (notes.none { it.id == note.id } && note.id !in deleted && notes.none { similar(it.text, note.text) }) {
                notes.add(note); n++
            }
        }
        if (n > 0) { notes.sortBy { it.time }; save(silent = true) }
        saveSync()
        n
    }

    val size: Int get() = synchronized(notes) { notes.size }

    fun all(): List<Note> = synchronized(notes) { notes.toList() }

    /** Ajoute un souvenir (ignoré s'il est déjà connu, à la formulation près). Renvoie true s'il est nouveau. */
    fun add(text: String, auto: Boolean = false): Boolean {
        val t = text.trim().take(500)
        if (t.isEmpty()) return false
        synchronized(notes) {
            if (notes.any { similar(it.text, t) }) return false
            val note = Note(t, System.currentTimeMillis(), auto)
            notes.add(note)
            dirty.add(note.id)
            while (notes.size > 1000) notes.removeAt(0)
            save()
        }
        return true
    }

    fun remove(note: Note) = synchronized(notes) { if (notes.remove(note)) gone(note); save() }

    /**
     * Ménage (proposé par Gemini, appliqué ici) : retire les souvenirs [remove] (numéros dans [all]) et remplace chaque
     * groupe de [merges] par une seule phrase. Une copie de l'ancienne mémoire est gardée à côté (souvenirs.avant-menage).
     * Renvoie (retirés, fusionnés).
     */
    fun applyCleanup(snapshot: List<Note>, remove: Set<Int>, merges: List<Pair<Set<Int>, String>>): Pair<Int, Int> = synchronized(notes) {
        runCatching { if (file.exists()) file.copyTo(File(file.parentFile, file.name + ".avant-menage"), overwrite = true) }
        val out = HashSet<Note>()
        remove.mapNotNull { snapshot.getOrNull(it) }.forEach { out.add(it) }
        var merged = 0
        for ((group, text) in merges) {
            val members = group.mapNotNull { snapshot.getOrNull(it) }
            if (members.size < 2 || text.isBlank()) continue
            out.addAll(members)
            val m = Note(text.trim().take(500), members.maxOf { it.time }, members.all { it.auto })
            notes.add(m); dirty.add(m.id)
            merged++
        }
        out.forEach { gone(it) }
        notes.removeAll { n -> out.any { it === n } }
        save()
        out.size to merged
    }

    /** Oublie les souvenirs qui parlent de [what]. Renvoie ceux effacés. */
    fun forget(what: String): List<String> = synchronized(notes) {
        val hits = search(what, 20)
        hits.forEach { gone(it) }
        notes.removeAll { n -> hits.any { it === n } }
        save()
        hits.map { it.text }
    }

    fun clear() = synchronized(notes) { notes.forEach { gone(it) }; notes.clear(); save() }

    private fun save(silent: Boolean = false) {
        val a = JSONArray()
        notes.forEach { a.put(JSONObject().put("text", it.text).put("time", it.time).put("auto", it.auto).put("id", it.id)) }
        saveSync()
        if (!silent) runCatching { onChange?.invoke() }
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(a.toString())
            tmp.renameTo(file)
        }
    }

    // ---- recherche ------------------------------------------------------------------------------
    private fun norm(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
    private val stop = setOf("le", "la", "les", "un", "une", "des", "de", "du", "et", "a", "au", "aux", "en", "est", "il", "elle",
        "je", "tu", "on", "que", "qui", "quoi", "ou", "mon", "ma", "mes", "ton", "ta", "tes", "son", "sa", "ses", "ce", "cette",
        "pour", "par", "sur", "dans", "avec", "pas", "ne", "se", "sont", "l", "d", "j", "qu", "s", "n")
    private fun words(s: String) = norm(s).split(Regex("[^a-z0-9]+")).filter { it.length > 1 && it !in stop }.toSet()

    private fun similar(a: String, b: String): Boolean {
        val wa = words(a); val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return norm(a) == norm(b)
        val inter = wa.intersect(wb).size.toDouble()
        return inter / minOf(wa.size, wb.size) >= 0.8 && inter / maxOf(wa.size, wb.size) >= 0.6
    }

    /** Souvenirs qui parlent de [query] (mots en commun, début de mot accepté : « chien » trouve « chienne »). */
    fun search(query: String, max: Int = 15): List<Note> = synchronized(notes) {
        val q = words(query)
        if (q.isEmpty()) return notes.takeLast(max)
        notes.map { n ->
            val w = words(n.text)
            n to q.count { qw -> w.any { it == qw || it.startsWith(qw) || qw.startsWith(it) && it.length >= 4 } }
        }.filter { it.second > 0 }
            .sortedWith(compareByDescending<Pair<Note, Int>> { it.second }.thenByDescending { it.first.time })
            .take(max).map { it.first }
    }

    private val fmt = SimpleDateFormat("d MMM yyyy", Locale.FRENCH)
    fun dated(n: Note) = "(${fmt.format(Date(n.time))}) ${n.text}"

    /** Texte ajouté au prompt système : les souvenirs les plus récents. */
    /**
     * Un souvenir sur l'état passager de son corps (« la nacelle est connectée », « roues débranchées », batterie…) :
     * faux le lendemain, il ne doit pas le croire (l'outil « etat » dit la vérité).
     */
    private fun transient(n: Note) = Regex("(?i)(nacelle|bluetooth|roues?|roomba|batterie|câble|connect|branch)").containsMatchIn(n.text)

    fun promptText(): String = synchronized(notes) {
        val kept = notes.filterNot(::transient)
        if (kept.isEmpty()) return ""
        val recent = kept.takeLast(120)
        "\n\nTes souvenirs (${kept.size} en tout ; voici les ${recent.size} plus récents, du plus ancien au plus récent ; " +
            "pour les autres, outil « se_souvenir ») :\n" + recent.joinToString("\n") { "- " + dated(it) }
    }
}
