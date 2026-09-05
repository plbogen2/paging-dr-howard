package com.example.pagingdrhoward.data

import org.json.JSONObject
import java.util.UUID

object PairingPayload {
    private const val PREFIX = "PAGING_PAIR:"

    fun generatePairingCode(name: String, topicId: String, publicKeyBase64: String, passphrase: String = ""): String {
        val json = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("name", name)
            put("topicId", topicId)
            put("publicKey", publicKeyBase64)
            put("passphrase", passphrase)
        }
        return "$PREFIX${json}"
    }

    fun parsePairingCode(rawCode: String): PairedContact? {
        val trimmed = rawCode.trim()
        val jsonStr = if (trimmed.startsWith(PREFIX)) {
            trimmed.substring(PREFIX.length)
        } else {
            trimmed
        }

        return try {
            val json = JSONObject(jsonStr)
            val topic = if (json.has("topicId")) json.getString("topicId") else json.getString("token")
            PairedContact(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.getString("name"),
                topicId = topic,
                publicKeyBase64 = json.optString("publicKey", ""),
                passphrase = json.optString("passphrase", "")
            )
        } catch (e: Exception) {
            null
        }
    }
}
