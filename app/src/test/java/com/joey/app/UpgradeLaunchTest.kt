package com.joey.app

import android.app.Application
import android.content.Context
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Mise à jour depuis une vieille version (0.7) : réglages déjà là, l'app doit démarrer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UpgradeLaunchTest {
    @Test fun startsAfterUpgradeFrom07() {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as Application
        val names = listOf("joey", "settings", "prefs", app.packageName + "_preferences")
        for (n in names) app.getSharedPreferences(n, Context.MODE_PRIVATE).edit()
            .putBoolean("follow_confirmed", true).putInt("follow_pan_sign", -1).putInt("follow_tilt_sign", 1)
            .putInt("head_speed_pct", 100).putInt("phone_pan_sign", 1).putInt("phone_tilt_sign", -1)
            .putString("gemini_key", "x").putString("gimbal_address", "AA:BB:CC:DD:EE:FF")
            .putString("speed_table", "200:5.0,400:11.0,600:17.0,800:23.0,1000:29.0").commit()
        shadowOf(app).grantPermissions(
            android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA,
            android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT,
        )
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        org.robolectric.shadows.ShadowLooper.idleMainLooper(3, java.util.concurrent.TimeUnit.SECONDS)
        Thread.sleep(3000)
        c.pause().stop().destroy()
    }
}
