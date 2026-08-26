package com.example.pagingdrhoward.data

import android.content.Context
import android.content.SharedPreferences

interface PagerRepository {
    fun getFcmToken(): String?
    fun saveFcmToken(token: String)
    fun getFamilyPassphrase(): String
    fun saveFamilyPassphrase(passphrase: String)
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

    companion object {
        const val PREF_NAME = "pager_prefs"
        const val KEY_FCM_TOKEN = "fcm_token"
        const val KEY_FAMILY_PASSPHRASE = "family_passphrase"
    }
}
