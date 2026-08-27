package com.example.pagingdrhoward.util

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoManager {

    private const val EC_ALGORITHM = "EC"
    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val ECDH_ALGORITHM = "ECDH"

    /**
     * Generates an Elliptic Curve (EC secp256r1) KeyPair for the device.
     */
    fun generateKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance(EC_ALGORITHM)
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        return kpg.generateKeyPair()
    }

    /**
     * Converts a PublicKey to Base64 X.509 encoded string.
     */
    fun publicKeyToBase64(publicKey: PublicKey): String {
        return Base64.getEncoder().encodeToString(publicKey.encoded)
    }

    /**
     * Restores a PublicKey object from Base64 string.
     */
    fun publicKeyFromBase64(base64Str: String): PublicKey {
        val keyBytes = Base64.getDecoder().decode(base64Str)
        val keySpec = X509EncodedKeySpec(keyBytes)
        val keyFactory = KeyFactory.getInstance(EC_ALGORITHM)
        return keyFactory.generatePublic(keySpec)
    }

    /**
     * Signs data using sender's Private Key via ECDSA.
     */
    fun sign(privateKey: PrivateKey, data: String): String {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(privateKey)
        signature.update(data.toByteArray(StandardCharsets.UTF_8))
        val sigBytes = signature.sign()
        return Base64.getEncoder().encodeToString(sigBytes)
    }

    /**
     * Verifies ECDSA signature using sender's Public Key.
     */
    fun verify(publicKey: PublicKey, data: String, signatureBase64: String): Boolean {
        return try {
            val sigBytes = Base64.getDecoder().decode(signatureBase64)
            val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
            signature.initVerify(publicKey)
            signature.update(data.toByteArray(StandardCharsets.UTF_8))
            signature.verify(sigBytes)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Computes unique shared AES key between My Private Key & Peer's Public Key using Elliptic Curve Diffie-Hellman (ECDH).
     */
    fun deriveSharedAesKey(myPrivateKey: PrivateKey, peerPublicKey: PublicKey): SecretKey {
        val ka = KeyAgreement.getInstance(ECDH_ALGORITHM)
        ka.init(myPrivateKey)
        ka.doPhase(peerPublicKey, true)
        val rawSharedSecret = ka.generateSecret()
        
        val digest = MessageDigest.getInstance("SHA-256")
        val aesKeyBytes = digest.digest(rawSharedSecret)
        return SecretKeySpec(aesKeyBytes, "AES")
    }

    /**
     * Encrypts message using ECDH shared key.
     */
    fun encryptWithSharedKey(sharedKey: SecretKey, plainText: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val iv = ByteArray(16)
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.ENCRYPT_MODE, sharedKey, ivSpec)
        val encryptedBytes = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
        return Base64.getEncoder().encodeToString(encryptedBytes)
    }

    /**
     * Decrypts message using ECDH shared key.
     */
    fun decryptWithSharedKey(sharedKey: SecretKey, cipherTextBase64: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        val iv = ByteArray(16)
        val ivSpec = IvParameterSpec(iv)
        cipher.init(Cipher.DECRYPT_MODE, sharedKey, ivSpec)
        val decodedBytes = Base64.getDecoder().decode(cipherTextBase64)
        return String(cipher.doFinal(decodedBytes), StandardCharsets.UTF_8)
    }
}
