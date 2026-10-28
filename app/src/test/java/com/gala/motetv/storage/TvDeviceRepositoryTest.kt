package com.gala.motetv.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TvDeviceRepositoryTest {

    private lateinit var credentialStore: TvCredentialStore
    private lateinit var repository: TvDeviceRepository

    @Before
    fun setUp() {
        credentialStore = TvCredentialStore(null) // Mock null context for unit testing
        repository = TvDeviceRepository(credentialStore)
    }

    @Test
    fun testIpAddressValidation() {
        assertTrue("Valid IPv4 192.168.1.100 should pass", repository.isValidIpAddress("192.168.1.100"))
        assertTrue("Valid IPv4 10.0.0.1 should pass", repository.isValidIpAddress("10.0.0.1"))
        assertFalse("Invalid IP with letters should fail", repository.isValidIpAddress("192.168.1.abc"))
        assertFalse("Invalid IP out of range should fail", repository.isValidIpAddress("256.256.256.256"))
        assertFalse("Empty string should fail", repository.isValidIpAddress(""))
    }

    @Test
    fun testSaveAndRetrieveProfile() {
        val result = repository.saveProfile("Living Room TV", "192.168.1.105", "Living Room")
        assertTrue("Save profile should succeed for valid IP", result.isSuccess)

        val profile = result.getOrNull()
        assertNotEqualsNull(profile)
        assertEquals("Living Room TV", profile?.name)
        assertEquals("192.168.1.105", profile?.host)

        val retrieved = repository.getSelectedProfile()
        assertEquals(profile?.id, retrieved?.id)
    }

    @Test
    fun testDuplicateIpPrevention() {
        val result1 = repository.saveProfile("TV 1", "192.168.1.150")
        assertTrue(result1.isSuccess)

        val result2 = repository.saveProfile("TV 2", "192.168.1.150")
        assertTrue("Duplicate IP for different profile should fail", result2.isFailure)
    }

    private fun assertNotEqualsNull(obj: Any?) {
        assertTrue("Object must not be null", obj != null)
    }
}
