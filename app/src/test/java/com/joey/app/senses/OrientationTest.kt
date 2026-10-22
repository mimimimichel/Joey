package com.joey.app.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Sens du téléphone d'après la pesanteur (pour redresser l'image), comme OrientationEventListener. */
class OrientationTest {
    @Test fun portrait() = assertEquals(0, PhoneSensors.orientationFrom(0f, 9.8f, 0f, 0))
    @Test fun landscapeLeft() = assertEquals(90, PhoneSensors.orientationFrom(-9.8f, 0f, 0f, 0))
    @Test fun landscapeRight() = assertEquals(270, PhoneSensors.orientationFrom(9.8f, 0f, 0f, 0))
    @Test fun upsideDown() = assertEquals(180, PhoneSensors.orientationFrom(0f, -9.8f, 0f, 0))
    @Test fun flatKeepsPrevious() = assertNull(PhoneSensors.orientationFrom(0.5f, 0.5f, 9.8f, 90))
    @Test fun hysteresis() {
        // 50° : encore « portrait » si on y était, pas encore « paysage ».
        val a = Math.toRadians(50.0)
        assertEquals(0, PhoneSensors.orientationFrom((-9.8 * Math.sin(a)).toFloat(), (9.8 * Math.cos(a)).toFloat(), 0f, 0))
    }
}
