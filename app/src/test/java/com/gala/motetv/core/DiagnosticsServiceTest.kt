package com.gala.motetv.core

import com.gala.motetv.storage.TvCredentialStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DiagnosticsServiceTest {

    private lateinit var diagnosticsService: DiagnosticsService

    @Before
    fun setUp() {
        val credentialStore = TvCredentialStore(null)
        diagnosticsService = DiagnosticsService(null as android.content.Context?, credentialStore)
    }

    @Test
    fun testValidIpAddresses() {
        assertTrue("Standard LAN IP should be valid", diagnosticsService.isValidIpAddress("192.168.1.100"))
        assertTrue("Class A IP should be valid", diagnosticsService.isValidIpAddress("10.0.0.1"))
        assertTrue("Broadcast limit IP should be valid", diagnosticsService.isValidIpAddress("255.255.255.255"))
    }

    @Test
    fun testInvalidIpAddressesWithOutOfRangeOctets() {
        assertFalse("Octet > 255 should be invalid", diagnosticsService.isValidIpAddress("256.100.1.1"))
        assertFalse("Octet 300 should be invalid", diagnosticsService.isValidIpAddress("192.168.1.300"))
        assertFalse("Negative octet should be invalid", diagnosticsService.isValidIpAddress("192.168.-1.1"))
    }

    @Test
    fun testIncompleteIpAddresses() {
        assertFalse("Incomplete IP with 3 octets should be invalid", diagnosticsService.isValidIpAddress("192.168.1"))
        assertFalse("Incomplete IP with 2 octets should be invalid", diagnosticsService.isValidIpAddress("10.0"))
        assertFalse("Empty string should be invalid", diagnosticsService.isValidIpAddress(""))
        assertFalse("String with spaces should be invalid", diagnosticsService.isValidIpAddress("192. 168. 1. 1"))
    }
}
