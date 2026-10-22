package com.joey.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeAssistantTest {
    private val states = """[
      {"entity_id":"light.salon","state":"off","attributes":{"friendly_name":"Plafonnier salon"}},
      {"entity_id":"light.chambre","state":"on","attributes":{"friendly_name":"Lampe chambre"}},
      {"entity_id":"cover.volet_salon","state":"open","attributes":{"friendly_name":"Volet salon"}},
      {"entity_id":"media_player.tv","state":"off","attributes":{"friendly_name":"Télé"}},
      {"entity_id":"sensor.temperature","state":"21","attributes":{"friendly_name":"Température"}}
    ]"""

    @Test fun findsTheRightDevice() {
        val all = HomeAssistant.parse(states)
        assertEquals(4, all.size) // le capteur n'est pas pilotable
        assertEquals("light.salon", HomeAssistant.match(all, "la lumière du salon")!!.id)
        assertEquals("cover.volet_salon", HomeAssistant.match(all, "le volet du salon")!!.id)
        assertEquals("media_player.tv", HomeAssistant.match(all, "la télé")!!.id)
        assertEquals("light.chambre", HomeAssistant.match(all, "light.chambre")!!.id)
        assertNull(HomeAssistant.match(all, "garage"))
    }
}
