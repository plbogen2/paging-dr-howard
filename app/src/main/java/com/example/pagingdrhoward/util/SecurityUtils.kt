package com.example.pagingdrhoward.util

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object SecurityUtils {

    /**
     * Generates a 256-bit SecretKeySpec from a user passphrase using SHA-256.
     */
    private fun deriveKey(passphrase: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256")
        val keyBytes = digest.digest(passphrase.toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Calculates HMAC-SHA256 signature for payload verification.
     */
    fun generateSignature(passphrase: String, data: String): String {
        try {
            val keySpec = SecretKeySpec(passphrase.toByteArray(StandardCharsets.UTF_8), "HmacSHA256")
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(keySpec)
            val hmacBytes = mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
            return Base64.getEncoder().encodeToString(hmacBytes)
        } catch (e: Exception) {
            return ""
        }
    }

    /**
     * Verifies HMAC-SHA256 signature.
     */
    fun verifySignature(passphrase: String, data: String, expectedSignature: String): Boolean {
        if (passphrase.isBlank() || expectedSignature.isBlank()) return false
        val computed = generateSignature(passphrase, data)
        return MessageDigest.isEqual(computed.toByteArray(), expectedSignature.toByteArray())
    }

    /**
     * Encrypts plaintext message using AES-CBC with PKCS5Padding.
     */
    fun encrypt(passphrase: String, plainText: String): String {
        return try {
            val keySpec = deriveKey(passphrase)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val iv = ByteArray(16)
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            val encryptedBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            Base64.getEncoder().encodeToString(encryptedBytes)
        } catch (e: Exception) {
            plainText
        }
    }

    /**
     * Decrypts AES ciphertext message.
     */
    fun decrypt(passphrase: String, cipherText: String): String {
        return try {
            val keySpec = deriveKey(passphrase)
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val iv = ByteArray(16)
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec)
            val decodedBytes = Base64.getDecoder().decode(cipherText)
            String(cipher.doFinal(decodedBytes), StandardCharsets.UTF_8)
        } catch (e: Exception) {
            cipherText
        }
    }
}
