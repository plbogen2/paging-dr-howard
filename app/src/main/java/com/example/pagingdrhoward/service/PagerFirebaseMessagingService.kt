package com.example.pagingdrhoward.service

import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.pagingdrhoward.util.SecurityUtils
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class PagerFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM Device Token generated: $token")
        getSharedPreferences("pager_prefs", MODE_PRIVATE)
            .edit()
            .putString("fcm_token", token)
            .apply()
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "Incoming FCM Message received from: ${remoteMessage.from}")

        val prefs = getSharedPreferences("pager_prefs", MODE_PRIVATE)
        val familyPassphrase = prefs.getString("family_passphrase", "") ?: ""

        val data = remoteMessage.data
        val sender = data["sender"] ?: "Family Member"
        val rawMessage = data["message"] ?: "URGENT: Emergency Page!"
        val timestamp = data["timestamp"] ?: ""
        val signature = data["signature"] ?: ""
        val isEncrypted = data["encrypted"]?.toBoolean() ?: false

        // 1. If Family Security Key is configured, enforce cryptographic signature verification
        if (familyPassphrase.isNotBlank()) {
            if (signature.isBlank()) {
                Log.w(TAG, "REJECTED: Incoming page missing security signature.")
                return
            }

            // Decrypt message if encrypted
            val decryptedMessage = if (isEncrypted) {
                SecurityUtils.decrypt(familyPassphrase, rawMessage)
            } else {
                rawMessage
            }

            val verificationData = "$sender|$decryptedMessage|$timestamp"
            val isValid = SecurityUtils.verifySignature(familyPassphrase, verificationData, signature)

            if (!isValid) {
                Log.w(TAG, "REJECTED: Unauthorized emergency page attempt (signature mismatch).")
                return
            }

            // 2. Verified Page -> Trigger Emergency Service
            triggerEmergencyAlarm(sender, decryptedMessage)
        } else {
            // Unsecured mode (No passphrase set yet)
            triggerEmergencyAlarm(sender, rawMessage)
        }
    }

    private fun triggerEmergencyAlarm(sender: String, message: String) {
        val serviceIntent = Intent(this, EmergencyPagerService::class.java).apply {
            action = EmergencyPagerService.ACTION_START_ALARM
            putExtra("EXTRA_SENDER", sender)
            putExtra("EXTRA_MESSAGE", message)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    companion object {
        private const val TAG = "PagerFCMService"
    }
}
