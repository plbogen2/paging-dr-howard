package com.example.pagingdrhoward

import com.example.pagingdrhoward.data.FcmPayloadBuilder
import com.example.pagingdrhoward.util.SecurityUtils
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FcmPayloadBuilderTest {

    @Test
    fun `test buildJsonPayload constructs valid high-priority FCM payload with signature`() {
        val payload = FcmPayloadBuilder.PagePayload(
            targetToken = "test_device_token_123",
            senderName = "Dad",
            messageText = "Call home ASAP!",
            familyPassphrase = "SecretKey123!",
            timestamp = "1724694400000"
        )

        val jsonStr = FcmPayloadBuilder.buildJsonPayload(payload)
        val root = JSONObject(jsonStr)

        assertEquals("test_device_token_123", root.getString("to"))
        assertEquals("high", root.getString("priority"))

        val data = root.getJSONObject("data")
        assertEquals("Dad", data.getString("sender"))
        assertEquals("EMERGENCY_PAGE", data.getString("type"))
        assertTrue("encrypted flag should be true", data.getBoolean("encrypted"))
        assertTrue("signature should not be empty", data.getString("signature").isNotEmpty())

        // Verify decrypted message
        val decryptedMsg = SecurityUtils.decrypt("SecretKey123!", data.getString("message"))
        assertEquals("Call home ASAP!", decryptedMsg)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `test buildJsonPayload throws exception on blank target token`() {
        val payload = FcmPayloadBuilder.PagePayload(
            targetToken = "   ",
            senderName = "Dad",
            messageText = "Call home ASAP!",
            familyPassphrase = "SecretKey123!"
        )
        FcmPayloadBuilder.buildJsonPayload(payload)
    }

    @Test
    fun `test buildJsonPayload without passphrase produces unencrypted plaintext and empty signature`() {
        val payload = FcmPayloadBuilder.PagePayload(
            targetToken = "test_device_token_123",
            senderName = "Mom",
            messageText = "Dinner is ready",
            familyPassphrase = ""
        )

        val jsonStr = FcmPayloadBuilder.buildJsonPayload(payload)
        val data = JSONObject(jsonStr).getJSONObject("data")

        assertEquals("Dinner is ready", data.getString("message"))
        assertFalse("encrypted flag should be false", data.getBoolean("encrypted"))
        assertEquals("", data.getString("signature"))
    }
}
