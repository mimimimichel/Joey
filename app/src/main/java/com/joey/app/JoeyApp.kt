package com.joey.app

import android.app.Application

/** Le tout premier code lancé : on surveille les plantages dès ici. */
class JoeyApp : Application() {
    /** Rapport du plantage précédent, lu avant que quoi que ce soit d'autre démarre. */
    var lastCrash: String? = null

    override fun onCreate() {
        super.onCreate()
        val stepsBefore = runCatching { java.io.File(filesDir, "dernieres_etapes.txt").readText() }.getOrNull().orEmpty()
        NativeGuard.init(this)
        val nativeCrash = if (NativeGuard.justBanned.isEmpty()) null else
            "Joey ${BuildConfig.VERSION_NAME} — Android ${android.os.Build.VERSION.RELEASE} " +
                "(${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL})\n" +
                "Plantage natif pendant : ${NativeGuard.justBanned.joinToString()}. Cette étape est désactivée, " +
                "Joey démarre sans.\n\nDernières étapes :\n$stepsBefore"
        lastCrash = CrashReport.take(this) ?: nativeCrash
        CrashReport.install(this)
        CrashReport.mark("application")
    }
}
