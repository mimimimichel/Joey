package com.joey.app

import com.joey.app.remote.Gamepad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadTest {
    @Test fun repos_au_centre() {
        val p = Gamepad()
        p.axes(0.1f, -0.12f, 0.05f, 0f, 0f)
        assertTrue(p.drive().idle)
        assertEquals(0f to 0f, p.head())
    }

    @Test fun stick_gauche_en_haut_avance() {
        val p = Gamepad()
        p.axes(0f, -1f, 0f, 0f, 0f)
        assertEquals(Gamepad.SLOW_MS, p.drive().forward, 1e-9)
        p.axes(0f, -1f, 0f, 0f, 1f)
        assertEquals(Gamepad.FAST_MS, p.drive().forward, 1e-9)
        p.axes(0f, 1f, 0f, 0f, 0f)
        assertTrue(p.drive().forward < 0)
    }

    @Test fun stick_gauche_a_droite_tourne_a_droite() {
        val p = Gamepad()
        p.axes(1f, 0f, 0f, 0f, 0f)
        assertEquals(Gamepad.TURN_DEG_S, p.drive().yaw, 1e-9)
        assertEquals(0.0, p.drive().forward, 1e-9)
    }

    @Test fun stick_droit_vers_le_haut_leve_la_tete() {
        val p = Gamepad()
        p.axes(0f, 0f, 0.6f, -1f, 0f)
        val (x, y) = p.head()
        assertTrue(x > 0f && x < 1f)
        assertEquals(1f, y, 1e-6f)
    }

    @Test fun pas_de_saut_au_bord_de_la_zone_morte() {
        assertTrue(Gamepad.shape(Gamepad.DEAD + 0.01f) < 0.01)
    }
}
