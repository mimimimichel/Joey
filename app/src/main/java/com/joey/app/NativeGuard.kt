package com.joey.app

import android.content.Context
import java.io.File

/**
 * Garde-fou contre les plantages « natifs » (carte graphique, modèles de vision) : ceux-là tuent l'app sans laisser de
 * rapport. Avant une étape risquée on pose une marque, on l'enlève si tout s'est bien passé. Si l'app meurt pendant
 * l'étape, la marque reste : au lancement suivant cette étape est sautée (ou faite autrement, ex. sans GPU).
 */
object NativeGuard {
    private var dir: File? = null
    private var bannedFile: File? = null
    private val banned = HashSet<String>()
    /** Étapes qui ont planté au lancement précédent (pour le dire). */
    val justBanned = ArrayList<String>()

    fun init(ctx: Context) {
        if (dir != null) return
        val d = File(ctx.applicationContext.filesDir, "garde_natif").apply { mkdirs() }
        dir = d
        bannedFile = File(d.parentFile, "natif_interdit.txt")
        runCatching { bannedFile!!.readLines().filter { it.isNotBlank() }.forEach { banned.add(it) } }
        d.listFiles()?.forEach { f ->
            if (banned.add(f.name)) justBanned.add(f.name)
            f.delete()
        }
        if (justBanned.isNotEmpty()) runCatching { bannedFile!!.writeText(banned.joinToString("\n")) }
    }

    /** Pour les tests : oublie l'état du processus. */
    internal fun forgetProcess() { dir = null; banned.clear(); justBanned.clear() }

    fun allowed(key: String) = synchronized(banned) { key !in banned }

    /** Lance [block] sous surveillance ; null si l'étape a déjà fait planter l'app. */
    fun <T> attempt(key: String, block: () -> T): T? {
        if (!allowed(key)) return null
        val f = dir?.let { File(it, key) }
        runCatching { f?.writeText("1") }
        CrashReport.mark("natif : $key")
        try {
            return block()
        } finally {
            f?.delete()
        }
    }

    /** Tout réautoriser (après une mise à jour qui corrige le problème). */
    fun reset() = synchronized(banned) { banned.clear(); runCatching { bannedFile?.delete() } }
}
