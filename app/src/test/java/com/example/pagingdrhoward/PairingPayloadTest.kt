package com.example.pagingdrhoward

import com.example.pagingdrhoward.data.PairingPayload
import com.example.pagingdrhoward.util.CryptoManager
import org.junit.Assert.*
import org.junit.Test

class PairingPayloadTest {

    @Test
    fun `test generate and parse pairing code with ECDSA public key roundtrip`() {
        val name = "Daughter"
        val token = "fcm_token_daughter_456"
        val keyPair = CryptoManager.generateKeyPair()
        val publicKeyBase64 = CryptoManager.publicKeyToBase64(keyPair.public)

        val pairingCode = PairingPayload.generatePairingCode(name, token, publicKeyBase64)
        assertTrue(pairingCode.startsWith("PAGING_PAIR:"))

        val contact = PairingPayload.parsePairingCode(pairingCode)
        assertNotNull(contact)
        assertEquals("Daughter", contact?.name)
        assertEquals("fcm_token_daughter_456", contact?.fcmToken)
        assertEquals(publicKeyBase64, contact?.publicKeyBase64)
    }

    @Test
    fun `test parse invalid pairing code returns null`() {
        val invalidCode = "ThisIsInvalidCode"
        val contact = PairingPayload.parsePairingCode(invalidCode)
        assertNull(contact)
    }
}
