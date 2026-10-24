package com.joey.app.life

import com.joey.app.life.AutonomyPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutonomyPolicyTest {
    private val now = 10 * 24 * 3600_000L
    private val hourAgo = now - 3600_000L
    private fun snap(
        hour: Int = 15, busy: Boolean = false, lastTalkAt: Long = 0, roomba: Int? = 90, phone: Int = 80, phoneCharging: Boolean = false,
        onBase: Boolean = false, charging: Boolean = false, mapFromBase: Boolean = true, personSince: Long = 0, lastGreetAt: Long = 0,
        lastFaceSeenAt: Long = hourAgo, lastExploreAt: Long = hourAgo, frontiersLeft: Boolean = true, lastDockFailAt: Long = 0,
        dockFailures: Int = 0, autonomyOn: Boolean = true,
    ) = AutonomyPolicy.Snapshot(now, hour, autonomyOn, busy, lastTalkAt, roomba, phone, phoneCharging, onBase, charging, mapFromBase,
        personSince, lastGreetAt, lastFaceSeenAt, lastExploreAt, frontiersLeft, lastDockFailAt, dockFailures)

    @Test fun rienSiLeModeEstCoupeOuSIlEstOccupe() {
        assertEquals(Action.None, AutonomyPolicy.decide(snap(autonomyOn = false, roomba = 5)))
        assertEquals(Action.None, AutonomyPolicy.decide(snap(busy = true, roomba = 5)))
    }

    @Test fun batterieBasseIlRentreMemeSiOnLuiParle() {
        assertTrue(AutonomyPolicy.decide(snap(roomba = 20, lastTalkAt = now - 1000)) is Action.Charge)
        assertTrue(AutonomyPolicy.decide(snap(phone = 15)) is Action.Charge)
        // Téléphone branché : pas besoin.
        assertTrue(AutonomyPolicy.decide(snap(phone = 15, phoneCharging = true)) !is Action.Charge)
        // Déjà sur sa base : rien à faire de ce côté.
        assertTrue(AutonomyPolicy.decide(snap(roomba = 20, onBase = true)) !is Action.Charge)
    }

    @Test fun apresUnAccostageRateIlAttendDePlusEnPlus() {
        // Raté il y a 2 min, une fois : il attend 5 min.
        assertTrue(AutonomyPolicy.decide(snap(roomba = 20, lastDockFailAt = now - 120_000, dockFailures = 1)) !is Action.Charge)
        assertTrue(AutonomyPolicy.decide(snap(roomba = 20, lastDockFailAt = now - 6 * 60_000, dockFailures = 1)) is Action.Charge)
        // Trois fois : 45 min.
        assertTrue(AutonomyPolicy.decide(snap(roomba = 20, lastDockFailAt = now - 30 * 60_000, dockFailures = 3)) !is Action.Charge)
        assertEquals(2 * 3600_000L, AutonomyPolicy.dockBackoffMs(20))
    }

    @Test fun laNuitIlRentreEtResteSurSaBase() {
        assertTrue(AutonomyPolicy.decide(snap(hour = 23)) is Action.Charge)
        assertEquals(Action.None, AutonomyPolicy.decide(snap(hour = 2, onBase = true)))
        // Sans carte qui part de sa base, il ne sait pas où elle est : il ne part pas la chercher la nuit.
        assertEquals(Action.None, AutonomyPolicy.decide(snap(hour = 23, mapFromBase = false)))
    }

    @Test fun ilSalueQuelquUnQuiArrive() {
        assertEquals(Action.Greet, AutonomyPolicy.decide(snap(personSince = now - 5_000)))
        // Déjà salué il y a 5 min : non.
        assertTrue(AutonomyPolicy.decide(snap(personSince = now - 5_000, lastGreetAt = now - 5 * 60_000)) != Action.Greet)
        // Là depuis longtemps : ce n'est plus une arrivée.
        assertTrue(AutonomyPolicy.decide(snap(personSince = now - 120_000)) != Action.Greet)
    }

    @Test fun ilSeRechargeAvantDeRepartir() {
        assertTrue(AutonomyPolicy.decide(snap(onBase = true, charging = true, roomba = 60)) is Action.Rest)
        assertTrue(AutonomyPolicy.decide(snap(onBase = true, charging = true, roomba = 85)) is Action.Explore)
    }

    @Test fun ilExploreTantQueSaCarteNEstPasFinie() {
        val a = AutonomyPolicy.decide(snap())
        assertTrue("$a", a is Action.Explore && a.seconds == 180)
        // Exploré il y a 5 min : il attend.
        assertEquals(Action.None, AutonomyPolicy.decide(snap(lastExploreAt = now - 5 * 60_000)))
        // Carte finie : une petite ronde par heure seulement.
        assertEquals(Action.None, AutonomyPolicy.decide(snap(frontiersLeft = false, lastExploreAt = now - 30 * 60_000)))
        val r = AutonomyPolicy.decide(snap(frontiersLeft = false, lastExploreAt = now - 2 * 3600_000L))
        assertTrue("$r", r is Action.Explore && r.seconds == 75)
        // Quelqu'un est là depuis peu, ou batterie moyenne : il n'explore pas.
        assertEquals(Action.None, AutonomyPolicy.decide(snap(lastFaceSeenAt = now - 60_000)))
        assertEquals(Action.None, AutonomyPolicy.decide(snap(roomba = 35)))
    }
}
