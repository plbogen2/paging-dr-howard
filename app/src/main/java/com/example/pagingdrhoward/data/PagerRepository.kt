package com.example.pagingdrhoward.data

import android.content.SharedPreferences

interface PagerRepository {
    fun getFcmToken(): String?
    fun saveFcmToken(token: String)
    fun getFamilyPassphrase(): String
    fun saveFamilyPassphrase(passphrase: String)
    fun getPairedContacts(): List<PairedContact>
    fun savePairedContact(contact: PairedContact)
    fun deletePairedContact(contactId: String)
}

class DefaultPagerRepository(private val sharedPreferences: SharedPreferences) : PagerRepository {

    override fun getFcmToken(): String? {
        return sharedPreferences.getString(KEY_FCM_TOKEN, null)
    }

    override fun saveFcmToken(token: String) {
        sharedPreferences.edit().putString(KEY_FCM_TOKEN, token).apply()
    }

    override fun getFamilyPassphrase(): String {
        return sharedPreferences.getString(KEY_FAMILY_PASSPHRASE, "") ?: ""
    }

    override fun saveFamilyPassphrase(passphrase: String) {
        sharedPreferences.edit().putString(KEY_FAMILY_PASSPHRASE, passphrase).apply()
    }

    override fun getPairedContacts(): List<PairedContact> {
        val jsonStr = sharedPreferences.getString(KEY_PAIRED_CONTACTS, null)
        return PairedContact.listFromJsonString(jsonStr)
    }

    override fun savePairedContact(contact: PairedContact) {
        val existing = getPairedContacts().toMutableList()
        // Replace if contact with same ID or FCM token exists, otherwise append
        val index = existing.indexOfFirst { it.id == contact.id || it.fcmToken == contact.fcmToken }
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
        const val KEY_FCM_TOKEN = "fcm_token"
        const val KEY_FAMILY_PASSPHRASE = "family_passphrase"
        const val KEY_PAIRED_CONTACTS = "paired_contacts"
    }
}
