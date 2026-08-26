package com.example.pagingdrhoward

import com.example.pagingdrhoward.util.SecurityUtils
import org.junit.Assert.*
import org.junit.Test

class SecurityUtilsTest {

    private val secretPassphrase = "FamilySecretKey123!@#"
    private val wrongPassphrase = "WrongSecretKey456!"
    private val testMessage = "URGENT: Emergency Page 🚨 - Call Dad ASAP! 📞"
    private val senderName = "Dad"
    private val timestamp = "1724694400000"

    @Test
    fun `test HMAC signature generation and verification success`() {
        val payload = "$senderName|$testMessage|$timestamp"
        val signature = SecurityUtils.generateSignature(secretPassphrase, payload)

        assertTrue("Signature should not be empty", signature.isNotEmpty())

        val isValid = SecurityUtils.verifySignature(secretPassphrase, payload, signature)
        assertTrue("Signature verification should succeed with correct passphrase", isValid)
    }

    @Test
    fun `test HMAC signature verification fails with wrong passphrase`() {
        val payload = "$senderName|$testMessage|$timestamp"
        val signature = SecurityUtils.generateSignature(secretPassphrase, payload)

        val isValid = SecurityUtils.verifySignature(wrongPassphrase, payload, signature)
        assertFalse("Signature verification should fail with incorrect passphrase", isValid)
    }

    @Test
    fun `test HMAC signature verification fails with tampered payload`() {
        val payload = "$senderName|$testMessage|$timestamp"
        val tamperedPayload = "$senderName|Tampered Message!|$timestamp"
        val signature = SecurityUtils.generateSignature(secretPassphrase, payload)

        val isValid = SecurityUtils.verifySignature(secretPassphrase, tamperedPayload, signature)
        assertFalse("Signature verification should fail if payload was modified", isValid)
    }

    @Test
    fun `test HMAC signature fails on blank inputs`() {
        val signature = SecurityUtils.generateSignature("", "data")
        val isValid = SecurityUtils.verifySignature("", "data", signature)

        assertFalse("Signature verification should return false for blank passphrase", isValid)
    }

    @Test
    fun `test AES encryption and decryption roundtrip with Unicode and Emojis`() {
        val encryptedText = SecurityUtils.encrypt(secretPassphrase, testMessage)
        assertNotEquals("Encrypted text should differ from original plaintext", testMessage, encryptedText)

        val decryptedText = SecurityUtils.decrypt(secretPassphrase, encryptedText)
        assertEquals("Decrypted message should match original plaintext", testMessage, decryptedText)
    }

    @Test
    fun `test AES decryption with wrong passphrase fails`() {
        val encryptedText = SecurityUtils.encrypt(secretPassphrase, testMessage)
        val decryptedText = SecurityUtils.decrypt(wrongPassphrase, encryptedText)

        assertNotEquals("Decrypted message with wrong passphrase should not match original plaintext", testMessage, decryptedText)
    }

    @Test
    fun `test AES decryption handles invalid base64 gracefully`() {
        val invalidBase64 = "ThisIsNotBase64!!!=="
        val decrypted = SecurityUtils.decrypt(secretPassphrase, invalidBase64)

        assertEquals("Decryption error should fallback gracefully", invalidBase64, decrypted)
    }
}
