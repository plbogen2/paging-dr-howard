package com.example.pagingdrhoward.data

import org.json.JSONArray
import org.json.JSONObject

data class PairedContact(
    val id: String,
    val name: String,
    val fcmToken: String,
    val passphrase: String,
    val addedAt: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("fcmToken", fcmToken)
            put("passphrase", passphrase)
            put("addedAt", addedAt)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): PairedContact {
            return PairedContact(
                id = json.getString("id"),
                name = json.getString("name"),
                fcmToken = json.getString("fcmToken"),
                passphrase = json.optString("passphrase", ""),
                addedAt = json.optLong("addedAt", System.currentTimeMillis())
            )
        }

        fun listToJsonString(contacts: List<PairedContact>): String {
            val array = JSONArray()
            contacts.forEach { array.put(it.toJson()) }
            return array.toString()
        }

        fun listFromJsonString(jsonString: String?): List<PairedContact> {
            if (jsonString.isNull_or_blank()) return emptyList()
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

private fun String?.isNull_or_blank(): Boolean = this == null || this.trim().isEmpty()
