package com.example.pagingdrhoward.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.util.Log

object AudioPlayer {
    private const val TAG = "AudioPlayer"
    private var mediaPlayer: MediaPlayer? = null
    private var originalVolume: Int = -1

    /**
     * Starts playing emergency alarm audio at maximum volume using STREAM_ALARM.
     */
    fun startEmergencyAlarm(context: Context) {
        if (mediaPlayer?.isPlaying == true) return

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // 1. Save original alarm volume and set STREAM_ALARM to maximum
            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

            // 2. Select default alarm tone (or fallback to ringtone)
            var alarmUri: Uri? = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }

            // 3. Configure MediaPlayer with AudioAttributes.USAGE_ALARM
            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, alarmUri!!)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
            Log.d(TAG, "Emergency alarm audio started at volume level: $maxVolume")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting emergency alarm sound", e)
        }
    }

    /**
     * Stops alarm playback and restores original volume settings.
     */
    fun stopEmergencyAlarm(context: Context) {
        try {
            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
            mediaPlayer = null

            // Restore original volume if saved
            if (originalVolume != -1) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
                originalVolume = -1
            }
            Log.d(TAG, "Emergency alarm sound stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping emergency alarm sound", e)
        }
    }
}
