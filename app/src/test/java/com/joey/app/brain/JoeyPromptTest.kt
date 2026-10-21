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
        assertEquals(listOf("regarder", "regarder_autour", "exprimer", "retenir", "etat"), names)
        val dir = a.getJSONObject(0).getJSONObject("parameters").getJSONObject("properties").getJSONObject("direction")
        assertEquals(6, dir.getJSONArray("enum").length())
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
