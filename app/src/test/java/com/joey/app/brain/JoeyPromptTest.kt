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
        assertEquals(listOf("regarder", "chercher", "tester_roues", "dormir", "regarder_personne", "regarder_ce_que_je_montre", "regarder_autour", "retenir", "apprendre_visage", "oublier_visage", "regarder_de_pres", "apprendre_voix", "qui_parle", "oublier_voix", "maison_appareils", "maison_commande", "pierre_feuille_ciseaux", "cache_cache_compter", "rappel", "rappels", "annuler_rappel", "journal_du_jour", "avancer", "tourner_corps", "viens_ici", "suis_moi", "arrete", "aller_vers", "aller_a", "rentrer_base", "cartographier", "ou_est", "ou_suis_je", "carte", "mode_garde", "journal_de_garde", "danser", "prendre_photo", "se_souvenir", "oublier", "etat", "ajuster", "se_degager", "explorer", "mode_autonome", "aller_voir", "revenir", "changer_voix", "retenir_regle", "oublier_regle", "demander_amelioration", "aspirer"), names)
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

    @Test fun peopleIdentify() {
        val f = java.io.File.createTempFile("people", ".json")
        f.delete()
        fun unit(vararg v: Float): FloatArray { val n = kotlin.math.sqrt(v.sumOf { (it * it).toDouble() }).toFloat(); return FloatArray(v.size) { v[it] / n } }
        val p = People(f)
        assertEquals(null to 0f, p.identify(unit(1f, 0f, 0f)))
        p.learn("michael", listOf(unit(1f, 0.1f, 0f), unit(1f, 0f, 0.1f)))
        p.learn("Léa", listOf(unit(0f, 1f, 0f)))
        assertEquals("Michael", p.identify(unit(1f, 0.05f, 0.05f)).first)
        assertEquals("Léa", p.identify(unit(0.1f, 1f, 0f)).first)
        assertEquals(null, p.identify(unit(0f, 0f, 1f)).first) // inconnu
        val p2 = People(f)
        assertEquals(listOf("Michael", "Léa"), p2.names())
        p2.forget("léa")
        assertEquals(listOf("Michael"), People(f).names())
    }

    @Test fun houseMapFindsThings() {
        val f = java.io.File.createTempFile("carte", ".json")
        f.delete()
        val m = HouseMap(f)
        val now = System.currentTimeMillis()
        m.put("Salon", "salon", "un canapé gris face à la télé", listOf(
            HouseMap.Item("canapé gris", 90.0, -10.0, 2.5, now), HouseMap.Item("télécommande", 120.0, -30.0, 1.2, now)))
        m.put("Cuisine", "cuisine", "plan de travail", listOf(HouseMap.Item("réfrigérateur", 200.0, 0.0, 3.0, now)))
        assertEquals("Salon", m.find("où est la télécommande").first().first.name)
        assertEquals("réfrigérateur", m.find("le réfrigérateur").first().second.name)
        assertEquals("est", HouseMap.compassName(90.0))
        assertEquals("nord", HouseMap.compassName(355.0))
        val m2 = HouseMap(f)
        assertEquals(2, m2.all().size)
        assertTrue(m2.summary().contains("télécommande"))
        m2.put("salon", "salon", "relevé à nouveau", listOf(HouseMap.Item("fenêtre", 0.0, 10.0, null, now)))
        assertEquals(2, HouseMap(f).all().size) // remplacé, pas ajouté
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
