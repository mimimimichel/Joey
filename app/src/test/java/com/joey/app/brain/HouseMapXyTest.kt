package com.joey.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HouseMapXyTest {
    @Test fun gardeLaPositionDesObjets() {
        val f = java.io.File.createTempFile("carte", ".json"); f.delete()
        val m = HouseMap(f)
        val now = System.currentTimeMillis()
        m.put("salon", "salon", "un salon", listOf(
            HouseMap.Item("canapé gris", 90.0, 0.0, 2.0, now, 1.5, -0.5),
            HouseMap.Item("lampe", 180.0, 10.0, null, now)), 0.0, 0.0)
        val m2 = HouseMap(f)
        val (_, sofa) = m2.find("canapé").first()
        assertEquals(1.5, sofa.x!!, 1e-9)
        assertEquals(-0.5, sofa.y!!, 1e-9)
        assertNull(m2.find("lampe").first().second.x)
    }
}
