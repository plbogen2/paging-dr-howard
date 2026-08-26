package com.example.pagingdrhoward

import com.example.pagingdrhoward.util.CryptoManager
import org.junit.Assert.*
import org.junit.Test

class CryptoManagerTest {

    @Test
    fun `test key pair generation and public key serialization`() {
        val keyPair = CryptoManager.generateKeyPair()
        assertNotNull(keyPair.private)
        assertNotNull(keyPair.public)

        val base64Public = CryptoManager.publicKeyToBase64(keyPair.public)
        assertTrue(base64Public.isNotEmpty())

        val restoredPublic = CryptoManager.publicKeyFromBase64(base64Public)
        assertEquals(keyPair.public, restoredPublic)
    }

    @Test
    fun `test ECDSA signature generation and verification`() {
        val keyPair = CryptoManager.generateKeyPair()
        val data = "Dad|Emergency Page|SOS|1724694400000"

        val signature = CryptoManager.sign(keyPair.private, data)
        assertTrue(signature.isNotEmpty())

        val isValid = CryptoManager.verify(keyPair.public, data, signature)
        assertTrue("Signature should verify with corresponding Public Key", isValid)
    }

    @Test
    fun `test ECDSA signature verification fails on tampered data`() {
        val keyPair = CryptoManager.generateKeyPair()
        val data = "Dad|Emergency Page|SOS|1724694400000"
        val tamperedData = "Dad|Tampered Page|SOS|1724694400000"

        val signature = CryptoManager.sign(keyPair.private, data)

        val isValid = CryptoManager.verify(keyPair.public, tamperedData, signature)
        assertFalse("Signature verification must fail on tampered payload", isValid)
    }

    @Test
    fun `test ECDH key exchange symmetry and AES encryption roundtrip`() {
        // Device A (Dad) and Device B (Daughter)
        val aliceKeyPair = CryptoManager.generateKeyPair()
        val bobKeyPair = CryptoManager.generateKeyPair()

        // Derive shared secret bidirectionally:
        // Alice computes shared key using Alice's Private + Bob's Public
        val aliceSharedSecret = CryptoManager.deriveSharedAesKey(aliceKeyPair.private, bobKeyPair.public)

        // Bob computes shared key using Bob's Private + Alice's Public
        val bobSharedSecret = CryptoManager.deriveSharedAesKey(bobKeyPair.private, aliceKeyPair.public)

        // Both shared keys must be cryptographically identical!
        assertArrayEquals("ECDH shared keys derived bidirectionally must match", aliceSharedSecret.encoded, bobSharedSecret.encoded)

        // Test AES Encryption: Alice encrypts, Bob decrypts
        val message = "Urgent: Call home ASAP! 🚨"
        val cipherText = CryptoManager.encryptWithSharedKey(aliceSharedSecret, message)
        val decrypted = CryptoManager.decryptWithSharedKey(bobSharedSecret, cipherText)

        assertEquals("Bob should successfully decrypt message encrypted by Alice", message, decrypted)
    }
}
