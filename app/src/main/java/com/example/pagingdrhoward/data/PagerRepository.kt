package com.example.pagingdrhoward.data

import android.content.SharedPreferences
import com.example.pagingdrhoward.util.CryptoManager
import java.security.PrivateKey
import java.security.PublicKey
import java.util.UUID

interface PagerRepository {
    fun getMyTopicId(): String
    fun getMyName(): String
    fun saveMyName(name: String)
    fun getMyPublicKeyBase64(): String
    fun getMyPrivateKey(): PrivateKey?
    fun getMyPublicKey(): PublicKey?
    fun getFamilyPassphrase(): String
    fun saveFamilyPassphrase(passphrase: String)
    fun getRelayServerUrl(): String
    fun saveRelayServerUrl(url: String)
    fun getPairedContacts(): List<PairedContact>
    fun savePairedContact(contact: PairedContact)
    fun deletePairedContact(contactId: String)
}

class DefaultPagerRepository(private val sharedPreferences: SharedPreferences) : PagerRepository {

    override fun getMyTopicId(): String {
        var topic = sharedPreferences.getString(KEY_MY_TOPIC_ID, null)
        if (topic.isNullOrBlank()) {
            topic = "pdh_" + UUID.randomUUID().toString().replace("-", "")
            sharedPreferences.edit().putString(KEY_MY_TOPIC_ID, topic).apply()
        }
        return topic
    }

    override fun getMyName(): String {
        return sharedPreferences.getString(KEY_MY_NAME, "Dad") ?: "Dad"
    }

    override fun saveMyName(name: String) {
        sharedPreferences.edit().putString(KEY_MY_NAME, name).apply()
    }

    private fun ensureKeyPair() {
        val pub = sharedPreferences.getString(KEY_MY_PUBLIC_KEY, null)
        val priv = sharedPreferences.getString(KEY_MY_PRIVATE_KEY, null)
        if (pub.isNullOrBlank() || priv.isNullOrBlank()) {
            val keyPair = CryptoManager.generateKeyPair()
            val pubBase64 = CryptoManager.publicKeyToBase64(keyPair.public)
            val privBase64 = CryptoManager.privateKeyToBase64(keyPair.private)
            sharedPreferences.edit()
                .putString(KEY_MY_PUBLIC_KEY, pubBase64)
                .putString(KEY_MY_PRIVATE_KEY, privBase64)
                .apply()
        }
    }

    override fun getMyPublicKeyBase64(): String {
        ensureKeyPair()
        return sharedPreferences.getString(KEY_MY_PUBLIC_KEY, "") ?: ""
    }

    override fun getMyPublicKey(): PublicKey? {
        val base64 = getMyPublicKeyBase64()
        return if (base64.isNotBlank()) {
            try {
                CryptoManager.publicKeyFromBase64(base64)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    override fun getMyPrivateKey(): PrivateKey? {
        ensureKeyPair()
        val base64 = sharedPreferences.getString(KEY_MY_PRIVATE_KEY, null)
        return if (!base64.isNullOrBlank()) {
            try {
                CryptoManager.privateKeyFromBase64(base64)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    override fun getFamilyPassphrase(): String {
        return sharedPreferences.getString(KEY_FAMILY_PASSPHRASE, "") ?: ""
    }

    override fun saveFamilyPassphrase(passphrase: String) {
        sharedPreferences.edit().putString(KEY_FAMILY_PASSPHRASE, passphrase).apply()
    }

    override fun getRelayServerUrl(): String {
        val url = sharedPreferences.getString(KEY_RELAY_SERVER_URL, "")?.trim() ?: ""
        return if (url.isNotBlank()) {
            if (!url.endsWith("/")) "$url/" else url
        } else {
            DEFAULT_RELAY_SERVER_URL
        }
    }

    override fun saveRelayServerUrl(url: String) {
        val trimmed = url.trim()
        val formatted = if (trimmed.isNotBlank() && !trimmed.endsWith("/")) "$trimmed/" else trimmed
        sharedPreferences.edit().putString(KEY_RELAY_SERVER_URL, formatted).apply()
    }

    override fun getPairedContacts(): List<PairedContact> {
        val jsonStr = sharedPreferences.getString(KEY_PAIRED_CONTACTS, null)
        return PairedContact.listFromJsonString(jsonStr)
    }

    override fun savePairedContact(contact: PairedContact) {
        val existing = getPairedContacts().toMutableList()
        val index = existing.indexOfFirst { it.id == contact.id || it.topicId == contact.topicId }
        if (index != -1) {
            existing[index] = contact
        } else {
            existing.add(contact)
        }
        val jsonStr = PairedContact.listToJsonString(existing)
        sharedPreferences.edit().putString(KEY_PAIRED_CONTACTS, jsonStr).apply()
    }

    override fun deletePairedContact(contactId: String) {
        val existing = getPairedContacts().filterNot { it.id == contactId }
        val jsonStr = PairedContact.listToJsonString(existing)
        sharedPreferences.edit().putString(KEY_PAIRED_CONTACTS, jsonStr).apply()
    }

    companion object {
        const val PREF_NAME = "pager_prefs"
        const val KEY_MY_TOPIC_ID = "my_topic_id"
        const val KEY_MY_NAME = "my_name"
        const val KEY_MY_PUBLIC_KEY = "my_public_key"
        const val KEY_MY_PRIVATE_KEY = "my_private_key"
        const val KEY_FAMILY_PASSPHRASE = "family_passphrase"
        const val KEY_RELAY_SERVER_URL = "relay_server_url"
        const val KEY_PAIRED_CONTACTS = "paired_contacts"
        const val DEFAULT_RELAY_SERVER_URL = "https://ntfy.sh/"
    }
}
