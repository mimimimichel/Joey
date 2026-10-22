package com.joey.app.senses

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sin

/** Deux personnes sur le canapé : qui Joey regarde-t-il ? */
class FaceChooserTest {
    private var t = 0L
    private val c = FaceChooser { t }

    // Michel à gauche (plus grand, plus proche), Johanna à droite.
    private fun faces(michelMouth: Float, johannaMouth: Float) = listOf(
        SeenFace(-0.4f, 0f, 0.14f, id = 1, mouth = michelMouth),
        SeenFace(0.45f, 0f, 0.11f, id = 2, mouth = johannaMouth),
    )

    private fun run(ms: Long, speaking: Boolean, johannaTalks: Boolean, michelTalks: Boolean = false): SeenFace? {
        var r: SeenFace? = null
        val end = t + ms
        while (t < end) {
            val wobble = (0.06 * sin(t / 90.0)).toFloat()
            r = c.choose(faces(0.30f + if (michelTalks) wobble else 0f, 0.30f + if (johannaTalks) wobble else 0f), speaking)
            t += 100
        }
        return r
    }

    @Test fun startsWithTheClosest() = assertEquals(1, run(500, speaking = false, johannaTalks = false)?.id)

    @Test fun turnsToWhoeverSpeaks() {
        run(2000, speaking = false, johannaTalks = false)
        assertEquals(2, run(2000, speaking = true, johannaTalks = true)?.id)
        // Elle se tait, il la garde (il ne saute pas d'un visage à l'autre).
        assertEquals(2, run(2000, speaking = false, johannaTalks = false)?.id)
        // Michel reprend la parole : retour vers lui.
        assertEquals(1, run(2500, speaking = true, johannaTalks = false, michelTalks = true)?.id)
    }

    @Test fun mouthMovingWithoutVoiceIsNotSpeaking() {
        run(1000, speaking = false, johannaTalks = false)
        assertEquals(1, run(2000, speaking = false, johannaTalks = true)?.id) // elle mange, elle ne parle pas
    }

    @Test fun lookAtTheOther() {
        run(1000, speaking = false, johannaTalks = false)
        c.want("autre")
        assertEquals(2, run(300, speaking = false, johannaTalks = false)?.id)
    }

    @Test fun lookAtByName() {
        run(500, speaking = false, johannaTalks = false)
        val f = c.needsName()!!
        c.setName(f, if (f.id == 2) "Johanna" else "Michel")
        val g = c.needsName()!!
        c.setName(g, if (g.id == 2) "Johanna" else "Michel")
        c.want("johanna")
        assertEquals(2, run(300, speaking = false, johannaTalks = false)?.id)
        assertEquals("Johanna", c.targetName())
    }
}
