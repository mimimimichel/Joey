package com.joey.app.brain

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class JournalTest {
    @Test fun writesAndExports() {
        val dir = createTempDir("journal")
        val j = Journal(dir)
        j.write("toi", "Joey, cherche le chien")
        j.write("outil", "chercher {objet=le chien}")
        j.write("joey", "Je le vois !")
        val out = j.export(File(dir, "out/export.txt"), days = 3, header = "Joey test")
        val text = out.readText()
        assertTrue(text.startsWith("Joey test"))
        assertTrue(text.contains("[toi] Joey, cherche le chien"))
        assertTrue(text.indexOf("[outil]") < text.indexOf("[joey] Je le vois"))
        dir.deleteRecursively()
    }
}
