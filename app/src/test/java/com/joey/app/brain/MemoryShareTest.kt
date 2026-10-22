package com.joey.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Deux téléphones : ce que l'un apprend ou oublie arrive sur l'autre. */
class MemoryShareTest {
    private fun tmp() = File(createTempDir("mem"), "memoire.json")

    /** Ce que la base renverrait : les souvenirs à envoyer de [from], et ses oublis. */
    private fun transfer(from: Memory, to: Memory) {
        val rows = from.pending().map { Triple(it, false, "A") } +
            from.unsentTombstones().map { Triple(Memory.Note("", 0, false, it), true, "A") }
        from.markSent(from.pending().map { it.id }, from.unsentTombstones())
        to.mergeRemote(rows)
    }

    @Test fun learnAndForgetTravel() {
        val s10 = Memory(tmp()); val s8 = Memory(tmp())
        s10.add("Le chien de Michael s'appelle Aïnou.")
        s10.add("Johanna est une très bonne copine.")
        transfer(s10, s8)
        assertEquals(2, s8.size)
        assertTrue(s8.all().any { it.text.contains("Aïnou") })
        // Oublié sur le S10 : effacé aussi sur le S8+.
        s10.forget("Johanna")
        transfer(s10, s8)
        assertEquals(1, s8.size)
        // Rien de nouveau : rien ne bouge, pas de doublon.
        transfer(s10, s8)
        assertEquals(1, s8.size)
    }

    @Test fun sameFactTwiceIsNotDuplicated() {
        val a = Memory(tmp()); val b = Memory(tmp())
        a.add("Le chien de Michael s'appelle Aïnou.")
        b.add("Le chien de Michael s'appelle Aïnou.")
        transfer(a, b)
        assertEquals(1, b.size)
    }
}
