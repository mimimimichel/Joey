package com.joey.app.brain

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Le journal de bord de Joey, gardé sur le téléphone (un fichier par jour, 14 jours) : ce qui a été dit (toi, lui),
 * ce qu'il a fait (outils, mouvements, réactions), les mesures (temps de réponse) et les erreurs. Il ne sort du
 * téléphone que si tu l'exportes, pour qu'on puisse l'améliorer.
 */
class Journal(private val dir: File, private val now: () -> Long = { System.currentTimeMillis() }) {
    private val writer = Executors.newSingleThreadExecutor()
    private val day = SimpleDateFormat("yyyy-MM-dd", Locale.FRENCH)
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.FRENCH)

    init {
        dir.mkdirs()
        writer.execute {
            val limit = now() - 14 * 24 * 3600_000L
            dir.listFiles()?.filter { it.name.startsWith("journal_") && it.lastModified() < limit }?.forEach { it.delete() }
        }
    }

    /** Une ligne : [kind] (log, toi, joey, outil, mesure, erreur…) et le texte. */
    fun write(kind: String, text: String) {
        val t = now()
        val line = "${time.format(Date(t))} [$kind] ${text.replace('\n', ' ')}\n"
        writer.execute { runCatching { File(dir, "journal_${day.format(Date(t))}.txt").appendText(line) } }
    }

    /** Les [days] derniers jours, du plus ancien au plus récent, précédés de [header]. Bloquant. */
    fun export(out: File, days: Int, header: String): File {
        // On attend que tout soit écrit.
        runCatching { writer.submit {}.get() }
        val files = dir.listFiles()?.filter { it.name.startsWith("journal_") }?.sortedBy { it.name }?.takeLast(days).orEmpty()
        out.parentFile?.mkdirs()
        out.writeText(header.trimEnd() + "\n\n")
        for (f in files) {
            out.appendText("===== ${f.name.removePrefix("journal_").removeSuffix(".txt")} =====\n")
            out.appendText(f.readText())
            out.appendText("\n")
        }
        return out
    }
}
