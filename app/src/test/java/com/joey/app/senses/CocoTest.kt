package com.joey.app.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CocoTest {
    @Test fun frenchRequestsMapToDetectorLabels() {
        assertTrue("dog" in CocoClasses.forTarget("le chien")!!)
        assertTrue("person" in CocoClasses.forTarget("personne")!!)
        assertTrue("person" in CocoClasses.forTarget("moi")!!)
        assertTrue("chair" in CocoClasses.forTarget("une chaise")!!)
        assertTrue("potted plant" in CocoClasses.forTarget("la plante")!!)
        assertEquals(setOf("backpack", "handbag", "suitcase"), CocoClasses.forTarget("mon sac à dos"))
    }

    @Test fun unknownThingsGoToGemini() {
        assertNull(CocoClasses.forTarget("mes clés"))
        assertNull(CocoClasses.forTarget("le tableau bleu"))
    }

    @Test fun frenchNames() {
        assertEquals("chien", CocoClasses.french("dog"))
    }
}
