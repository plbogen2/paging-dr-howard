package com.example.pagingdrhoward.service

import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.pagingdrhoward.data.DefaultPagerRepository
import com.example.pagingdrhoward.data.PairedContact
import com.example.pagingdrhoward.util.SecurityUtils
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.util.UUID

class PagerFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM Device Token generated: $token")
        getSharedPreferences(DefaultPagerRepository.PREF_NAME, MODE_PRIVATE)
            .edit()
            .putString(DefaultPagerRepository.KEY_FCM_TOKEN, token)
            .apply()
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d(TAG, "Incoming FCM Message received from: ${remoteMessage.from}")

        val prefs = getSharedPreferences(DefaultPagerRepository.PREF_NAME, MODE_PRIVATE)
        val repository = DefaultPagerRepository(prefs)
        val familyPassphrase = repository.getFamilyPassphrase()

        val data = remoteMessage.data
        val messageType = data["type"] ?: "EMERGENCY_PAGE"
        val sender = data["sender"] ?: "Family Member"
        val rawMessage = data["message"] ?: "URGENT: Emergency Page!"
        val timestamp = data["timestamp"] ?: ""
        val signature = data["signature"] ?: ""
        val senderToken = data["senderToken"] ?: ""
        val isEncrypted = data["encrypted"]?.toBoolean() ?: false

        // Handle Bidirectional Handshake Auto-Pairing
        if (messageType == "PAIRING_HANDSHAKE" && senderToken.isNotBlank()) {
            val contact = PairedContact(
                id = UUID.randomUUID().toString(),
                name = sender,
                fcmToken = senderToken,
                passphrase = familyPassphrase
            )
            repository.savePairedContact(contact)
            Log.d(TAG, "Bidirectional pairing completed! Added contact: ${contact.name}")
            return
        }

        // If Family Security Key is configured, enforce cryptographic signature verification
        if (familyPassphrase.isNotBlank()) {
            if (signature.isBlank()) {
                Log.w(TAG, "REJECTED: Incoming page missing security signature.")
                return
            }

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

            // Auto-save/update sender token if provided for 1-tap reply
            if (senderToken.isNotBlank()) {
                val contact = PairedContact(
                    id = UUID.randomUUID().toString(),
                    name = sender,
                    fcmToken = senderToken,
                    passphrase = familyPassphrase
                )
                repository.savePairedContact(contact)
            }

            triggerEmergencyAlarm(sender, decryptedMessage)
        } else {
            // Unsecured mode
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
