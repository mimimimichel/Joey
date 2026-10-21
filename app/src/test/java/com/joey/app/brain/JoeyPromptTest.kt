package com.joey.app.brain

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JoeyPromptTest {
    @Test fun toolsSerialize() {
        val a = GeminiLive.toJson(JoeyPrompt.TOOLS) as JSONArray
        assertEquals(JoeyPrompt.TOOLS.size, a.length())
        val names = (0 until a.length()).map { a.getJSONObject(it).getString("name") }
        assertEquals(listOf("regarder", "chercher", "regarder_autour", "exprimer", "retenir", "se_souvenir", "oublier", "etat"), names)
        val dir = a.getJSONObject(0).getJSONObject("parameters").getJSONObject("properties").getJSONObject("direction")
        assertEquals(6, dir.getJSONArray("enum").length())
    }

    @Test fun memorySearchDedupForget() {
        val f = java.io.File.createTempFile("mem", ".json")
        f.delete()
        val m = Memory(f)
        assertTrue(m.add("Michael a un chien qui s'appelle Rocky"))
        assertTrue(m.add("Les clés sont dans le bol de l'entrée", auto = true))
        assertTrue(m.add("Michael aime le café serré"))
        // Même chose dite autrement : pas de doublon
        assertEquals(false, m.add("Michael a un chien, il s'appelle Rocky"))
        assertEquals(3, m.size)
        assertEquals(listOf("Les clés sont dans le bol de l'entrée"), m.search("où sont mes clés").map { it.text })
        assertEquals(2, m.search("Michael").size)
        assertTrue(m.search("chienne").isNotEmpty() || m.search("chien").isNotEmpty())
        val gone = m.forget("café")
        assertEquals(listOf("Michael aime le café serré"), gone)
        val m2 = Memory(f)
        assertEquals(2, m2.size)
        assertTrue(m2.all().any { it.auto })
        assertTrue(m2.promptText().contains("Rocky"))
    }

    @Test fun memoryRoundTrip() {
        val f = java.io.File.createTempFile("mem", ".json")
        f.delete()
        val m = Memory(f)
        assertEquals("", m.promptText())
        m.add("Michael aime le café serré")
        val m2 = Memory(f)
        assertEquals(1, m2.size)
        assertTrue(m2.promptText().contains("café serré"))
    }
}
