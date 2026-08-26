package com.example.pagingdrhoward.data

import com.example.pagingdrhoward.util.SecurityUtils
import org.json.JSONObject

object FcmPayloadBuilder {

    data class PagePayload(
        val targetToken: String,
        val senderName: String,
        val messageText: String,
        val familyPassphrase: String,
        val level: PageLevel = PageLevel.SOS,
        val timestamp: String = System.currentTimeMillis().toString()
    )

    /**
     * Builds FCM JSON payload string with PageLevel, HMAC signature, and AES encryption.
     */
    fun buildJsonPayload(payload: PagePayload): String {
        require(payload.targetToken.isNotBlank()) { "Target device token cannot be blank" }

        val encryptedMessage = if (payload.familyPassphrase.isNotBlank()) {
            SecurityUtils.encrypt(payload.familyPassphrase, payload.messageText)
        } else {
            payload.messageText
        }

        val rawPayloadStr = "${payload.senderName}|${payload.messageText}|${payload.level.code}|${payload.timestamp}"
        val signature = if (payload.familyPassphrase.isNotBlank()) {
            SecurityUtils.generateSignature(payload.familyPassphrase, rawPayloadStr)
        } else {
            ""
        }

        val jsonBody = JSONObject().apply {
            put("to", payload.targetToken)
            put("priority", "high")
            put("data", JSONObject().apply {
                put("sender", payload.senderName)
                put("message", encryptedMessage)
                put("level", payload.level.code)
                put("timestamp", payload.timestamp)
                put("signature", signature)
                put("encrypted", payload.familyPassphrase.isNotBlank())
                put("type", "EMERGENCY_PAGE")
            })
        }

        return jsonBody.toString()
    }
}
