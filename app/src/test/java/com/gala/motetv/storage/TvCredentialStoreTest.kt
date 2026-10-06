package com.gala.motetv.storage

import com.gala.motetv.pairing.TvCertificateManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvCredentialStoreTest {

    @Test
    fun testGenerateAndFingerprintCalculation() {
        val store = TvCredentialStore(null)
        val identity = store.getOrCreateIdentity("tv_mi_stick_living_room")

        assertNotNull(identity)
        assertNotNull(identity.keyPair.public)
        assertNotNull(identity.keyPair.private)
        assertNotNull(identity.certificate)

        val fp = store.computeFingerprint(identity.certificate)
        assertNotNull(fp)
        assertTrue(fp.contains(":"))
        assertEquals(32 * 3 - 1, fp.length) // 32 hex bytes separated by ':' -> 95 chars
    }
}
