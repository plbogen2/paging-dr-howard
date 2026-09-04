package com.example.pagingdrhoward.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.provider.Settings
import android.util.Log

object DndHelper {
    const val CHANNEL_ID = "emergency_page_channel"
    const val CHANNEL_NAME = "Emergency Pages"
    private const val TAG = "DndHelper"

    /**
     * Checks if Do Not Disturb Policy Access permission has been granted by the user.
     * Safe against Fire OS and non-standard Android framework exceptions.
     */
    fun hasDndAccess(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                notificationManager?.isNotificationPolicyAccessGranted == true
            } else {
                true
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Failed to check DND access on device", e)
            false
        }
    }

    /**
     * Navigates user to system settings to grant Do Not Disturb Access.
     * Safe fallback for Fire OS where ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS may not exist.
     */
    fun openDndSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                // Fallback for Fire OS / modified Android skins
                val fallbackIntent = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(fallbackIntent)
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Unable to launch DND settings screen", e)
        }
    }

    /**
     * Registers a notification channel configured specifically to bypass DND.
     * Safe against device-specific AudioAttributes or sound URI failures.
     */
    fun createEmergencyNotificationChannel(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

                val audioAttributes = try {
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .build()
                } catch (e: Throwable) {
                    null
                }

                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Critical alerts that override Do Not Disturb and silent mode"
                    try {
                        setBypassDnd(true)
                    } catch (e: Throwable) {
                        Log.w(TAG, "setBypassDnd not supported on this OS", e)
                    }
                    if (audioAttributes != null) {
                        setSound(Settings.System.DEFAULT_ALARM_ALERT_URI, audioAttributes)
                    }
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500)
                }

                notificationManager.createNotificationChannel(channel)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to create emergency notification channel", e)
        }
    }
}
