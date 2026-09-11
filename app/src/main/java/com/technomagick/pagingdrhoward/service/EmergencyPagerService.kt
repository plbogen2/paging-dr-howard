package com.technomagick.pagingdrhoward.service

import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.technomagick.pagingdrhoward.EmergencyAlertActivity
import com.technomagick.pagingdrhoward.data.PageLevel
import com.technomagick.pagingdrhoward.util.AudioPlayer
import com.technomagick.pagingdrhoward.util.DndHelper

class EmergencyPagerService : Service() {
    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_ALARM = "com.technomagick.pagingdrhoward.START_ALARM"
        const val ACTION_STOP_ALARM = "com.technomagick.pagingdrhoward.STOP_ALARM"

        @Volatile
        private var currentActiveMessageKey: String? = null
        @Volatile
        private var lastAlarmTriggerTimeMs: Long = 0L

        @Volatile
        var isAlarmActive: Boolean = false
            private set
        @Volatile
        var activeSender: String = ""
            private set
        @Volatile
        var activeTopic: String = ""
            private set
        @Volatile
        var activeTimestamp: Long = 0L
            private set
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP_ALARM) {
            isAlarmActive = false
            activeSender = ""
            activeTopic = ""
            activeTimestamp = 0L
            currentActiveMessageKey = null
            lastAlarmTriggerTimeMs = 0L
            AudioPlayer.stopEmergencyAlarm(this)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val sender = intent?.getStringExtra("EXTRA_SENDER") ?: "Family Member"
        val senderTopic = intent?.getStringExtra("EXTRA_SENDER_TOPIC") ?: ""
        val message = intent?.getStringExtra("EXTRA_MESSAGE") ?: "URGENT: Please respond!"
        val levelCode = intent?.getStringExtra("EXTRA_LEVEL")
        val timestamp = intent?.getLongExtra("EXTRA_TIMESTAMP", 0L) ?: 0L
        val messageKey = intent?.getStringExtra("EXTRA_MESSAGE_KEY")
        val pageLevel = PageLevel.fromCode(levelCode)

        val now = System.currentTimeMillis()
        val isSameActiveMessage = !messageKey.isNullOrBlank() && messageKey == currentActiveMessageKey
        val isRapidDuplicate = (now - lastAlarmTriggerTimeMs) < 4000L

        if (isSameActiveMessage || isRapidDuplicate) {
            android.util.Log.d("EmergencyPagerService", "Ignoring duplicate alarm trigger for key: $messageKey (dt=${now - lastAlarmTriggerTimeMs}ms)")
            return START_STICKY
        }

        currentActiveMessageKey = messageKey
        lastAlarmTriggerTimeMs = now

        DndHelper.createEmergencyNotificationChannel(this)

        val fullScreenIntent = EmergencyAlertActivity.createIntent(this, sender, senderTopic, message, pageLevel, timestamp, messageKey)
        val fullScreenPendingIntent = PendingIntent.getActivity(
            this,
            0,
            fullScreenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissIntent = Intent(this, com.technomagick.pagingdrhoward.receiver.AlertActionReceiver::class.java).apply {
            action = com.technomagick.pagingdrhoward.receiver.AlertActionReceiver.ACTION_DISMISS_ALERT
            putExtra("EXTRA_SENDER_TOPIC", senderTopic)
            putExtra("EXTRA_TIMESTAMP", timestamp)
            putExtra("EXTRA_MESSAGE_KEY", messageKey)
        }
        val dismissPendingIntent = PendingIntent.getBroadcast(
            this,
            101,
            dismissIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val dismissAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_close_clear_cancel,
            "Acknowledge & Dismiss",
            dismissPendingIntent
        ).build()

        val carExtender = NotificationCompat.CarExtender()
            .setColor(if (pageLevel == PageLevel.SOS) 0xFFD32F2F.toInt() else 0xFFF57C00.toInt())

        val notification = NotificationCompat.Builder(this, DndHelper.CHANNEL_EMERGENCY_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("${pageLevel.title} from $sender")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .addAction(dismissAction)
            .extend(carExtender)
            .setOngoing(true)
            .setAutoCancel(false)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val foregroundServiceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            } else {
                0
            }
            startForeground(NOTIFICATION_ID, notification, foregroundServiceType)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        isAlarmActive = true
        activeSender = sender
        activeTopic = senderTopic
        activeTimestamp = timestamp

        AudioPlayer.startEmergencyAlarm(this, pageLevel)
        fullScreenIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(fullScreenIntent)

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isAlarmActive = false
        activeSender = ""
        activeTopic = ""
        activeTimestamp = 0L
    }
}
