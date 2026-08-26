package com.example.pagingdrhoward.service

import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.pagingdrhoward.data.DefaultPagerRepository
import com.example.pagingdrhoward.data.PageLevel
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
        val levelCode = data["level"] ?: PageLevel.SOS.code
        val pageLevel = PageLevel.fromCode(levelCode)
        val timestamp = data["timestamp"] ?: ""
        val signature = data["signature"] ?: ""
        val senderToken = data["senderToken"] ?: ""
        val isEncrypted = data["encrypted"]?.toBoolean() ?: false

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

            val verificationData = "$sender|$decryptedMessage|${pageLevel.code}|$timestamp"
            val isValid = SecurityUtils.verifySignature(familyPassphrase, verificationData, signature)

            if (!isValid) {
                Log.w(TAG, "REJECTED: Unauthorized emergency page attempt (signature mismatch).")
                return
            }

            if (senderToken.isNotBlank()) {
                val contact = PairedContact(
                    id = UUID.randomUUID().toString(),
                    name = sender,
                    fcmToken = senderToken,
                    passphrase = familyPassphrase
                )
                repository.savePairedContact(contact)
            }

            triggerEmergencyAlarm(sender, decryptedMessage, pageLevel)
        } else {
            triggerEmergencyAlarm(sender, rawMessage, pageLevel)
        }
    }

    private fun triggerEmergencyAlarm(sender: String, message: String, level: PageLevel) {
        val serviceIntent = Intent(this, EmergencyPagerService::class.java).apply {
            action = EmergencyPagerService.ACTION_START_ALARM
            putExtra("EXTRA_SENDER", sender)
            putExtra("EXTRA_MESSAGE", message)
            putExtra("EXTRA_LEVEL", level.code)
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
