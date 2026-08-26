package com.example.pagingdrhoward

import com.example.pagingdrhoward.data.FcmPayloadBuilder
import com.example.pagingdrhoward.data.PageLevel
import com.example.pagingdrhoward.util.SecurityUtils
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FcmPayloadBuilderTest {

    @Test
    fun `test buildJsonPayload constructs valid HEY_LOOK multi-level payload`() {
        val payload = FcmPayloadBuilder.PagePayload(
            targetToken = "test_device_token_123",
            senderName = "Dad",
            messageText = "Check your phone when free",
            familyPassphrase = "SecretKey123!",
            level = PageLevel.HEY_LOOK,
            timestamp = "1724694400000"
        )

        val jsonStr = FcmPayloadBuilder.buildJsonPayload(payload)
        val root = JSONObject(jsonStr)

        assertEquals("test_device_token_123", root.getString("to"))
        assertEquals("high", root.getString("priority"))

        val data = root.getJSONObject("data")
        assertEquals("Dad", data.getString("sender"))
        assertEquals("HEY_LOOK", data.getString("level"))
        assertEquals("EMERGENCY_PAGE", data.getString("type"))
        assertTrue("encrypted flag should be true", data.getBoolean("encrypted"))
        assertTrue("signature should not be empty", data.getString("signature").isNotEmpty())

        val decryptedMsg = SecurityUtils.decrypt("SecretKey123!", data.getString("message"))
        assertEquals("Check your phone when free", decryptedMsg)
    }

    @Test
    fun `test buildJsonPayload constructs valid SOS multi-level payload`() {
        val payload = FcmPayloadBuilder.PagePayload(
            targetToken = "test_device_token_123",
            senderName = "Daughter",
            messageText = "SOS EMERGENCY",
            familyPassphrase = "SecretKey123!",
            level = PageLevel.SOS
        )

        val jsonStr = FcmPayloadBuilder.buildJsonPayload(payload)
        val data = JSONObject(jsonStr).getJSONObject("data")

        assertEquals("SOS", data.getString("level"))
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
}
