package com.technomagick.pagingdrhoward.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.technomagick.pagingdrhoward.EmergencyAlertActivity
import com.technomagick.pagingdrhoward.data.DefaultPagerRepository
import com.technomagick.pagingdrhoward.network.PushSender
import com.technomagick.pagingdrhoward.service.EmergencyPagerService
import com.technomagick.pagingdrhoward.util.AudioPlayer
import com.technomagick.pagingdrhoward.util.CryptoManager
import com.technomagick.pagingdrhoward.util.LogHelper

class AlertActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == ACTION_DISMISS_ALERT) {
            val senderTopic = intent.getStringExtra("EXTRA_SENDER_TOPIC") ?: ""
            val timestamp = intent.getLongExtra("EXTRA_TIMESTAMP", 0L)
            val messageKey = intent.getStringExtra("EXTRA_MESSAGE_KEY") ?: ""

            LogHelper.i(TAG, "Dismiss action received via notification / Android Auto for topic=$senderTopic key=$messageKey")
            performDismiss(context, senderTopic, timestamp, messageKey)
        }
    }

    companion object {
        const val ACTION_DISMISS_ALERT = "com.technomagick.pagingdrhoward.ACTION_DISMISS_ALERT"
        private const val TAG = "AlertActionReceiver"

        fun performDismiss(context: Context, senderTopic: String, alertTimestamp: Long, messageKey: String = "") {
            // 1. Immediately silence audio and stop emergency pager service
            AudioPlayer.stopEmergencyAlarm(context)

            val stopServiceIntent = Intent(context, EmergencyPagerService::class.java).apply {
                action = EmergencyPagerService.ACTION_STOP_ALARM
            }
            context.startService(stopServiceIntent)

            // 2. Dismiss full screen activity if currently open on phone screen
            try {
                EmergencyAlertActivity.dismissCurrentAlert(context)
            } catch (e: Throwable) {
                LogHelper.w(TAG, "Failed to dismiss EmergencyAlertActivity window", e)
            }

            // 3. Persist dismissal timestamp and message key
            val prefs = context.getSharedPreferences(DefaultPagerRepository.PREF_NAME, Context.MODE_PRIVATE)
            val repository = DefaultPagerRepository(prefs)

            val effectiveDismissTimestamp = maxOf(alertTimestamp, System.currentTimeMillis())
            repository.saveLastDismissedAlertTimestamp(effectiveDismissTimestamp)
            if (messageKey.isNotBlank()) {
                repository.markMessageDismissed(messageKey)
            }

            // 4. Purge the message from relay database now that user dismissed
            if (messageKey.isNotBlank()) {
                PushSender.deleteMessage(
                    serverUrl = repository.getRelayServerUrl(),
                    topicId = repository.getMyTopicId(),
                    messageKey = messageKey
                )
            }

            // 5. Send acknowledgment receipt back to sender's topic
            if (senderTopic.isNotBlank()) {
                val contact = repository.getPairedContacts().find { it.topicId == senderTopic }
                val peerPublicKey = if (contact != null && contact.publicKeyBase64.isNotBlank()) {
                    try {
                        CryptoManager.publicKeyFromBase64(contact.publicKeyBase64)
                    } catch (e: Exception) {
                        null
                    }
                } else null

                PushSender.sendAlertAck(
                    targetTopicId = senderTopic,
                    acknowledgerName = repository.getMyName(),
                    myTopicId = repository.getMyTopicId(),
                    myPublicKeyBase64 = repository.getMyPublicKeyBase64(),
                    myPrivateKey = repository.getMyPrivateKey(),
                    peerPublicKey = peerPublicKey,
                    serverUrl = repository.getRelayServerUrl()
                )
            }
        }
    }
}
