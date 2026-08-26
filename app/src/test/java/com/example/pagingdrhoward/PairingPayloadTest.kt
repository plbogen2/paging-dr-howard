package com.example.pagingdrhoward

import com.example.pagingdrhoward.data.PairingPayload
import org.junit.Assert.*
import org.junit.Test

class PairingPayloadTest {

    @Test
    fun `test generate and parse pairing code bidirectional roundtrip`() {
        val name = "Daughter"
        val token = "fcm_token_daughter_456"
        val passphrase = "FamilyKey999!"

        val pairingCode = PairingPayload.generatePairingCode(name, token, passphrase)
        assertTrue(pairingCode.startsWith("PAGING_PAIR:"))

        val contact = PairingPayload.parsePairingCode(pairingCode)
        assertNotNull(contact)
        assertEquals("Daughter", contact?.name)
        assertEquals("fcm_token_daughter_456", contact?.fcmToken)
        assertEquals("FamilyKey999!", contact?.passphrase)
    }

    @Test
    fun `test parse invalid pairing code returns null`() {
        val invalidCode = "ThisIsInvalidCode"
        val contact = PairingPayload.parsePairingCode(invalidCode)
        assertNull(contact)
    }
}
