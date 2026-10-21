package com.joey.app.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeWordTest {
    @Test fun recognizesJoeyAsTheFrenchRecognizerWritesIt() {
        for (t in listOf("Joey", "joey tu es là", "hé Joie", "Joy viens voir", "Joé", "dis jo ey".replace(" ey", "ey"))) {
            assertTrue(t, WakeWord.matches(t))
        }
        for (t in listOf("bonjour", "on joue aux cartes", "jouer", "Joseph")) assertFalse(t, WakeWord.matches(t))
    }
}
