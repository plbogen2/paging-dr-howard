package com.example.pagingdrhoward

import com.example.pagingdrhoward.data.PairingPayload
import com.example.pagingdrhoward.util.CryptoManager
import org.junit.Assert.*
import org.junit.Test

class PairingPayloadTest {

    @Test
    fun `test generate and parse pairing code with topicId and ECDSA public key roundtrip`() {
        val name = "Daughter"
        val topicId = "pdh_daughter_9876543210abcdef"
        val keyPair = CryptoManager.generateKeyPair()
        val publicKeyBase64 = CryptoManager.publicKeyToBase64(keyPair.public)

        val pairingCode = PairingPayload.generatePairingCode(name, topicId, publicKeyBase64)
        assertTrue(pairingCode.startsWith("PAGING_PAIR:"))

        val contact = PairingPayload.parsePairingCode(pairingCode)
        assertNotNull(contact)
        assertEquals("Daughter", contact?.name)
        assertEquals(topicId, contact?.topicId)
        assertEquals(publicKeyBase64, contact?.publicKeyBase64)
    }

    @Test
    fun `test parse invalid pairing code returns null`() {
        val invalidCode = "ThisIsInvalidCode"
        val contact = PairingPayload.parsePairingCode(invalidCode)
        assertNull(contact)
    }
}
