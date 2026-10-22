package com.joey.app.senses

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Plusieurs personnes devant Joey : laquelle regarder bien en face ?
 * 1. Celle qu'on lui a demandé de regarder (« regarde Johanna », « l'autre », « à ta gauche »), pendant un moment.
 * 2. Sinon, celle qui parle : quand on entend une voix, c'est le visage dont la bouche bouge le plus.
 * 3. Sinon, celle qu'il regardait déjà (il ne saute pas d'un visage à l'autre), ou à défaut la plus proche.
 */
class FaceChooser(private val now: () -> Long = { System.currentTimeMillis() }) {
    private class Track(var id: Int?, var face: SeenFace, var seenAt: Long) {
        val mouths = ArrayDeque<Pair<Long, Float>>()
        var name: String? = null
        var nameAt = -1L // jamais reconnu
    }

    private val tracks = ArrayList<Track>()
    private var target: Track? = null
    private var targetSince = 0L
    private var speakerCandidate: Track? = null
    private var speakerSince = 0L

    /** Demande en cours : un prénom, « gauche », « droite » ou « autre ». */
    @Volatile private var wanted: String? = null
    @Volatile private var wantedUntil = 0L
    private var wantedAvoid: Track? = null

    /** Combien de visages en ce moment. */
    val count: Int get() = synchronized(tracks) { tracks.size }

    /** Les prénoms reconnus parmi les visages visibles. */
    fun names(): List<String> = synchronized(tracks) { tracks.mapNotNull { it.name } }

    /** Le prénom du visage regardé, s'il est connu. */
    fun targetName(): String? = synchronized(tracks) { target?.name }

    /** « Regarde Johanna / l'autre / à ta gauche » : vaut pendant [ms]. */
    fun want(who: String, ms: Long = 20_000) = synchronized(tracks) {
        wanted = who.trim().lowercase()
        wantedUntil = now() + ms
        wantedAvoid = target
    }

    /** Le visage [face] (de la dernière image) est [name]. */
    fun setName(face: SeenFace, name: String?) = synchronized(tracks) {
        val t = tracks.firstOrNull { it.face === face } ?: return@synchronized
        t.name = name
        t.nameAt = now()
    }

    /** Visage à qui donner un nom (jamais reconnu, ou il y a longtemps), ou null. */
    fun needsName(maxAgeMs: Long = 5000): SeenFace? = synchronized(tracks) {
        tracks.filter { it.nameAt < 0 || now() - it.nameAt > maxAgeMs }.minByOrNull { it.nameAt }?.face
    }

    /** Ce visage est-il le prénom demandé ? (insensible aux accents et majuscules) */
    private fun same(a: String?, b: String) = a != null && norm(a) == norm(b)
    private fun norm(s: String) = java.text.Normalizer.normalize(s.lowercase().trim(), java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")

    /** Agitation de la bouche sur la dernière seconde (écart type de l'ouverture). */
    private fun activity(t: Track, at: Long): Float {
        val m = t.mouths.filter { at - it.first < 1200 }.map { it.second }
        if (m.size < 4) return 0f
        val mean = m.average()
        return sqrt(m.sumOf { (it - mean) * (it - mean) } / m.size).toFloat()
    }

    /**
     * Une image : [faces] (confirmés), [speaking] si une voix humaine vient d'être entendue.
     * Renvoie le visage à regarder, ou null s'il n'y en a pas.
     */
    fun choose(faces: List<SeenFace>, speaking: Boolean): SeenFace? = synchronized(tracks) {
        val t = now()
        // 1. Associer les visages aux pistes : même numéro ML Kit, sinon le plus proche.
        val used = HashSet<Track>()
        for (f in faces) {
            val tr = tracks.firstOrNull { it !in used && f.id != null && it.id == f.id }
                ?: tracks.filter { it !in used && t - it.seenAt < 1500 }
                    .minByOrNull { abs(it.face.x - f.x) + abs(it.face.y - f.y) }
                    ?.takeIf { abs(it.face.x - f.x) + abs(it.face.y - f.y) < 0.3f }
            if (tr != null) {
                tr.face = f; tr.seenAt = t; if (f.id != null) tr.id = f.id
                used.add(tr)
            } else {
                val n = Track(f.id, f, t); tracks.add(n); used.add(n)
            }
            val u = used.last { it.face === f }
            f.mouth?.let { u.mouths.addLast(t to it) }
            while (u.mouths.isNotEmpty() && t - u.mouths.first().first > 2000) u.mouths.removeFirst()
        }
        tracks.removeAll { t - it.seenAt > 1500 }
        val visible = tracks.filter { it.seenAt == t }
        if (visible.isEmpty()) return@synchronized null
        val cur = target?.takeIf { it in visible }
        fun pick(tr: Track): SeenFace {
            if (tr !== target) { target = tr; targetSince = t }
            return tr.face
        }

        // 2. Une demande (« regarde Johanna ») : elle passe avant tout.
        val w = wanted
        if (w != null && t < wantedUntil) {
            val asked = when (w) {
                "gauche", "à gauche", "a gauche" -> visible.minByOrNull { it.face.x }
                "droite", "à droite", "a droite" -> visible.maxByOrNull { it.face.x }
                "autre", "l'autre" -> visible.filter { it !== wantedAvoid }.maxByOrNull { it.face.size }
                else -> visible.firstOrNull { same(it.name, w) }
            }
            if (asked != null) {
                // Trouvée : on la garde comme cible (la demande est remplie, elle reste regardée).
                if (w in setOf("gauche", "à gauche", "a gauche", "droite", "à droite", "a droite", "autre", "l'autre")) wanted = null
                else if (asked === target) wantedUntil = minOf(wantedUntil, t + 4000)
                return@synchronized pick(asked)
            }
        }

        // 3. Quelqu'un parle : le visage dont la bouche bouge nettement plus que les autres.
        if (speaking && visible.size > 1) {
            val ranked = visible.map { it to activity(it, t) }.sortedByDescending { it.second }
            val (best, a) = ranked[0]
            val second = ranked[1].second
            if (a > 0.012f && a > second * 1.6f) {
                if (best !== speakerCandidate) { speakerCandidate = best; speakerSince = t }
                // Il faut que ça dure un peu (0,6 s), et qu'il ait regardé la précédente au moins 1,5 s.
                if (best !== cur && t - speakerSince >= 600 && (cur == null || t - targetSince >= 1500)) {
                    return@synchronized pick(best)
                }
            }
        }

        // 4. Il garde celle qu'il regardait, sinon la plus proche (le plus grand visage).
        pick(cur ?: visible.maxByOrNull { it.face.size }!!)
    }
}
