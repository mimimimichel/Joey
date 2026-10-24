package com.joey.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Le téléphone redémarre (coupure, batterie vide puis rechargée) : Joey se relance tout seul, sans qu'on touche le
 * téléphone (autonomie : reprendre après une coupure). Android 9 (S8+) le permet ; à partir d'Android 10, le système
 * bloque le lancement d'une activité depuis l'arrière-plan : on le note seulement.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != "android.intent.action.QUICKBOOT_POWERON") return
        val prefs = context.getSharedPreferences("joey", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("autostart", true)) return
        runCatching {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure { Log.w("JoeyBoot", "lancement au démarrage impossible (Android ${Build.VERSION.RELEASE})", it) }
    }
}
