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
    private var currentActiveMessageKey: String? = null
    private var lastAlarmTriggerTimeMs: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action

        if (action == ACTION_STOP_ALARM) {
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
        val isRapidDuplicate = (now - lastAlarmTriggerTimeMs) < 3000L && currentActiveMessageKey != null

        if (isSameActiveMessage || isRapidDuplicate) {
            android.util.Log.d("EmergencyPagerService", "Ignoring duplicate alarm trigger for key: $messageKey")
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

        val notification = NotificationCompat.Builder(this, DndHelper.CHANNEL_EMERGENCY_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("${pageLevel.title} from $sender")
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setFullScreenIntent(fullScreenPendingIntent, true)
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

        AudioPlayer.startEmergencyAlarm(this, pageLevel)
        startActivity(fullScreenIntent)

        return START_STICKY
    }

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_ALARM = "com.technomagick.pagingdrhoward.START_ALARM"
        const val ACTION_STOP_ALARM = "com.technomagick.pagingdrhoward.STOP_ALARM"
    }
}
