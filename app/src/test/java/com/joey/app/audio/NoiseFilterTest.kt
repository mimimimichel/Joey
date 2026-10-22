package com.joey.app.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phrases fantômes relevées dans le journal du 22/10, et vraies phrases de la même soirée. */
class NoiseFilterTest {
    @Test fun ghostsAreNoise() {
        for (g in listOf("今 お 金 に ね", "مثلا", "ትጋፍ በጣም", "كم دبل", "මේ", "<noise>", ".", "Ну все, вісім вікон."))
            assertTrue(g, NoiseFilter.isNoise(g))
    }

    @Test fun frenchIsNotNoise() {
        for (f in listOf("Et c'est qui à côté là.", "oui", "Regarde.", "Des oreilles de lapin.", "Quel temps il va faire demain ?", "OK"))
            assertFalse(f, NoiseFilter.isNoise(f))
    }
}
