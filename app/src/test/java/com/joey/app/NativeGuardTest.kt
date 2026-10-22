package com.joey.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Une étape qui a tué l'app (marque restée) est sautée au lancement suivant. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NativeGuardTest {
    @Test fun skipsStepThatCrashed() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        // Lancement précédent mort pendant « gestes_mains » : la marque est restée.
        File(ctx.filesDir, "garde_natif").apply { mkdirs() }.resolve("gestes_mains").writeText("1")
        NativeGuard.forgetProcess()
        NativeGuard.init(ctx)
        assertEquals(listOf("gestes_mains"), NativeGuard.justBanned)
        assertNull(NativeGuard.attempt("gestes_mains") { 1 })
        assertEquals(2, NativeGuard.attempt("objets_gpu") { 2 })
        NativeGuard.reset()
        assertEquals(0, File(ctx.filesDir, "garde_natif").listFiles()!!.size)
    }
}
