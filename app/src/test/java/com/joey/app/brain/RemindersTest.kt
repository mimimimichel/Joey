package com.joey.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class RemindersTest {
    @Test fun timersHoursDailyAndCancel() {
        var t = Calendar.getInstance().apply { set(2026, 9, 22, 10, 0, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val f = java.io.File.createTempFile("rappels", ".json"); f.delete()
        val r = Reminders(f) { t }
        r.add("sortir le linge", 20.0, null, false)
        val wake = r.add("réveil", null, "7h", true)!!
        assertEquals(7, Calendar.getInstance().apply { timeInMillis = wake.at }.get(Calendar.HOUR_OF_DAY))
        assertTrue(wake.at > t) // 7 h est passé : demain
        assertNull(r.add("x", null, "pas une heure", false))
        assertNotNull(r.nextAt("18:30"))
        t += 21 * 60_000L
        assertEquals(listOf("sortir le linge"), r.due().map { it.text })
        assertTrue(r.due().isEmpty())
        t = wake.at + 1000
        assertEquals(listOf("réveil"), r.due().map { it.text })
        assertEquals(1, Reminders(f) { t }.all().size) // quotidien : reprogrammé au lendemain, et relu du fichier
        assertEquals(listOf("réveil"), r.cancel("réveil"))
        assertTrue(r.all().isEmpty())
    }
}
