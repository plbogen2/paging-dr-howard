package com.example.pagingdrhoward.data

import org.json.JSONArray
import org.json.JSONObject

data class PairedContact(
    val id: String,
    val name: String,
    val topicId: String,
    val publicKeyBase64: String = "",
    val passphrase: String = "",
    val relayServerUrl: String = "",
    val addedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("topicId", topicId)
            put("publicKeyBase64", publicKeyBase64)
            put("passphrase", passphrase)
            put("relayServerUrl", relayServerUrl)
            put("addedAt", addedAt)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): PairedContact {
            return PairedContact(
                id = json.getString("id"),
                name = json.getString("name"),
                topicId = json.optString("topicId", json.optString("fcmToken", "")),
                publicKeyBase64 = json.optString("publicKeyBase64", ""),
                passphrase = json.optString("passphrase", ""),
                relayServerUrl = json.optString("relayServerUrl", json.optString("serverUrl", "")),
                addedAt = json.optLong("addedAt", System.currentTimeMillis())
            )
        }

        fun listToJsonString(contacts: List<PairedContact>): String {
            val array = JSONArray()
            contacts.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun listFromJsonString(jsonString: String?): List<PairedContact> {
            if (jsonString.isNullOrBlank()) return emptyList()
            return try {
                val array = JSONArray(jsonString)
                val list = mutableListOf<PairedContact>()
                for (i in 0 until array.length()) {
                    list.add(fromJson(array.getJSONObject(i)))
                }
                list
            } catch (e: Exception) {
                emptyList()
            }
        }
    }
}
