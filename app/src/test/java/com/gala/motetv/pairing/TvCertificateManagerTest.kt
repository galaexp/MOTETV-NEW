package com.gala.motetv.pairing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.cert.X509Certificate

class TvCertificateManagerTest {

    @Test
    fun testGenerateIdentityAndCertificateAttributes() {
        val identity = TvCertificateManager.generateIdentity(commonName = "MoteTV-Client")

        assertNotNull(identity.keyPair)
        assertNotNull(identity.keyPair.public)
        assertNotNull(identity.keyPair.private)
        assertEquals("RSA", identity.keyPair.public.algorithm)

        val cert: X509Certificate = identity.certificate
        assertNotNull(cert)
        assertTrue(cert.subjectDN.name.contains("CN=MoteTV-Client"))
        assertTrue(cert.issuerDN.name.contains("CN=MoteTV-Client"))

        // Verify certificate is self-signed with its own public key
        cert.verify(identity.keyPair.public)
    }

    @Test
    fun testSslContextCreation() {
        val identity = TvCertificateManager.generateIdentity(commonName = "MoteTV")
        val certManager = TvCertificateManager()
        val sslContext = certManager.createSslContext(identity)

        assertNotNull(sslContext)
        assertNotNull(sslContext.socketFactory)
    }
}
