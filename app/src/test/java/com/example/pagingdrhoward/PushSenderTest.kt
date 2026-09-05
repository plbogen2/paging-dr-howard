package com.example.pagingdrhoward

import com.example.pagingdrhoward.data.PageLevel
import com.example.pagingdrhoward.network.PushSender
import com.example.pagingdrhoward.util.CryptoManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PushSenderTest {

    @Test
    fun `test buildPayloadJson creates encrypted and signed emergency page payload`() {
        val senderKeyPair = CryptoManager.generateKeyPair()
        val recipientKeyPair = CryptoManager.generateKeyPair()

        val msg = PushSender.PageMessage(
            type = "PAGE",
            senderName = "Dad",
            senderTopicId = "pdh_dad_12345",
            senderPublicKeyBase64 = CryptoManager.publicKeyToBase64(senderKeyPair.public),
            level = PageLevel.SOS,
            messageText = "SOS Emergency! Need help right now!"
        )

        val jsonStr = PushSender.buildPayloadJson(msg, senderKeyPair.private, recipientKeyPair.public)
        val json = JSONObject(jsonStr)

        assertEquals("PAGE", json.getString("type"))
        assertEquals("Dad", json.getString("senderName"))
        assertEquals("pdh_dad_12345", json.getString("senderTopicId"))
        assertEquals("SOS", json.getString("level"))
        assertNotNull(json.getString("ciphertext"))
        assertNotEquals("SOS Emergency! Need help right now!", json.getString("ciphertext")) // Must be encrypted

        // Verify digital signature
        val signature = json.getString("signature")
        assertTrue(signature.isNotBlank())
        val dataToVerify = "pdh_dad_12345:SOS:${json.getLong("timestamp")}:${json.getString("ciphertext")}"
        assertTrue(CryptoManager.verify(senderKeyPair.public, dataToVerify, signature))

        // Verify recipient can decrypt ciphertext
        val sharedKey = CryptoManager.deriveSharedAesKey(recipientKeyPair.private, senderKeyPair.public)
        val decrypted = CryptoManager.decryptWithSharedKey(sharedKey, json.getString("ciphertext"))
        assertEquals("SOS Emergency! Need help right now!", decrypted)
    }

    @Test
    fun `test buildPayloadJson creates valid Hey Look payload`() {
        val senderKeyPair = CryptoManager.generateKeyPair()
        val recipientKeyPair = CryptoManager.generateKeyPair()

        val msg = PushSender.PageMessage(
            type = "PAGE",
            senderName = "Mom",
            senderTopicId = "pdh_mom_54321",
            senderPublicKeyBase64 = CryptoManager.publicKeyToBase64(senderKeyPair.public),
            level = PageLevel.HEY_LOOK,
            messageText = "Hey look! Check phone."
        )

        val jsonStr = PushSender.buildPayloadJson(msg, senderKeyPair.private, recipientKeyPair.public)
        val json = JSONObject(jsonStr)

        assertEquals("PAGE", json.getString("type"))
        assertEquals("HEY_LOOK", json.getString("level"))
        assertEquals("Mom", json.getString("senderName"))
    }
}
