package com.joey.app

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/** Garde la trace du dernier plantage pour l'afficher au lancement suivant (et pouvoir la copier). */
object CrashReport {
    private fun file(ctx: Context) = File(ctx.filesDir, "dernier_plantage.txt")
    private var stepsFile: File? = null
    private val steps = ArrayDeque<String>()

    /** Note une étape (écrite tout de suite : survit au plantage). */
    fun mark(step: String) {
        val f = stepsFile ?: return
        synchronized(steps) {
            steps.addLast("${System.currentTimeMillis() % 100_000} [${Thread.currentThread().name}] $step")
            while (steps.size > 25) steps.removeFirst()
            runCatching { f.writeText(steps.joinToString("\n")) }
        }
    }

    fun install(ctx: Context) {
        val app = ctx.applicationContext
        stepsFile = File(app.filesDir, "dernieres_etapes.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val info = "Joey ${BuildConfig.VERSION_NAME} — Android ${android.os.Build.VERSION.RELEASE} " +
                    "(${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}) — fil « ${thread.name} »\n\n"
                file(app).writeText(info + sw.toString().take(12_000))
            }
            previous?.uncaughtException(thread, e)
        }
    }

    /** Le rapport du dernier plantage (puis l'efface), ou null. */
    fun take(ctx: Context): String? {
        val f = file(ctx)
        if (!f.exists()) return null
        val text = runCatching { f.readText() }.getOrNull()
        val st = runCatching { File(ctx.filesDir, "dernieres_etapes.txt").readText() }.getOrNull().orEmpty()
        f.delete()
        return text?.plus(if (st.isBlank()) "" else "\n\nDernières étapes :\n$st")
    }
}
