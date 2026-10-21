package com.joey.app

import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** L'application démarre sans planter, avec ou sans les permissions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchTest {
    @Test fun startsWithoutPermissions() {
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        assertNotNull(a)
        assertFalse(a.isFinishing)
    }

    @Test fun startsWithPermissions() {
        val app = org.robolectric.RuntimeEnvironment.getApplication() as Application
        shadowOf(app).grantPermissions(
            android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA,
            android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT,
        )
        val c = Robolectric.buildActivity(MainActivity::class.java).setup()
        c.pause().stop().destroy()
    }
}
