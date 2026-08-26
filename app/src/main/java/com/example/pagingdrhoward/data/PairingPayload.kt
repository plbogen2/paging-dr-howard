package com.example.pagingdrhoward.data

import org.json.JSONObject
import java.util.UUID

object PairingPayload {
    private const val PREFIX = "PAGING_PAIR:"

    fun generatePairingCode(name: String, fcmToken: String, passphrase: String): String {
        val json = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("name", name)
            put("token", fcmToken)
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
            PairedContact(
                id = json.optString("id", UUID.randomUUID().toString()),
                name = json.getString("name"),
                fcmToken = json.getString("token"),
                passphrase = json.optString("passphrase", "")
            )
        } catch (e: Exception) {
            null
        }
    }
}
