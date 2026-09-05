package com.example.pagingdrhoward.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.pagingdrhoward.data.PageLevel

object AudioPlayer {
    private const val TAG = "AudioPlayer"
    private var mediaPlayer: MediaPlayer? = null
    private var originalVolume: Int = -1
    private val autoStopHandler = Handler(Looper.getMainLooper())
    private var autoStopRunnable: Runnable? = null
    private const val MAX_ALARM_DURATION_MS = 60_000L // Safety timeout: auto-stop after 60 seconds

    /**
     * Starts playing emergency alarm audio configured for specific PageLevel.
     */
    fun startEmergencyAlarm(context: Context, level: PageLevel = PageLevel.SOS) {
        if (mediaPlayer?.isPlaying == true) stopEmergencyAlarm(context)

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // Save original alarm volume and set STREAM_ALARM to max for SOS, or high for HEY_LOOK
            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val targetVolume = if (level == PageLevel.SOS) maxVolume else (maxVolume * 0.75).toInt()
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetVolume, 0)

            val alarmType = if (level == PageLevel.HEY_LOOK) RingtoneManager.TYPE_NOTIFICATION else RingtoneManager.TYPE_ALARM
            var alarmUri: Uri? = RingtoneManager.getDefaultUri(alarmType)
            if (alarmUri == null) {
                alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, alarmUri!!)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = level.isLoopingSound
                prepare()
                start()
            }
            Log.d(TAG, "Audio started for ${level.name} at volume: $targetVolume")

            // Schedule safety auto-stop after 60 seconds so alarm doesn't loop infinitely if unattended
            autoStopRunnable?.let { autoStopHandler.removeCallbacks(it) }
            autoStopRunnable = Runnable {
                Log.d(TAG, "Safety auto-stop timeout reached.")
                stopEmergencyAlarm(context)
            }
            autoStopHandler.postDelayed(autoStopRunnable!!, MAX_ALARM_DURATION_MS)

        } catch (e: Exception) {
            Log.e(TAG, "Error starting alarm sound", e)
        }
    }

    /**
     * Stops alarm playback and restores original volume settings.
     */
    fun stopEmergencyAlarm(context: Context) {
        try {
            autoStopRunnable?.let {
                autoStopHandler.removeCallbacks(it)
                autoStopRunnable = null
            }

            mediaPlayer?.let { player ->
                if (player.isPlaying) {
                    player.stop()
                }
                player.release()
            }
            mediaPlayer = null

            if (originalVolume != -1) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
                originalVolume = -1
            }
            Log.d(TAG, "Alarm sound stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping alarm sound", e)
        }
    }
}
