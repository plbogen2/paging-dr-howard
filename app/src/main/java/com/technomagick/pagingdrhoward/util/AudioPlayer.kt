package com.technomagick.pagingdrhoward.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.technomagick.pagingdrhoward.data.PageLevel

import android.media.AudioFocusRequest
import android.os.Build

object AudioPlayer {
    private const val TAG = "AudioPlayer"
    private var mediaPlayer: MediaPlayer? = null
    private var originalVolume: Int = -1
    private var audioFocusRequest: AudioFocusRequest? = null
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

            // Request transient audio focus so Android Auto / car stereo pauses music playback
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .build()
                        )
                        .setOnAudioFocusChangeListener { /* no-op */ }
                        .build()
                    audioFocusRequest = focusRequest
                    audioManager.requestAudioFocus(focusRequest)
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to acquire audio focus", e)
            }

            // Save original alarm volume and set STREAM_ALARM to max for SOS, or high for HEY_LOOK
            originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val targetVolume = if (level == PageLevel.SOS) maxVolume else (maxVolume * 0.75).toInt()
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetVolume, 0)

            val prefs = context.getSharedPreferences(com.technomagick.pagingdrhoward.data.DefaultPagerRepository.PREF_NAME, Context.MODE_PRIVATE)
            val repo = com.technomagick.pagingdrhoward.data.DefaultPagerRepository(prefs)
            val useNavi = repo.isNaviSoundEnabled()

            var customResId: Int? = null
            if (level == PageLevel.HEY_LOOK) {
                if (useNavi) {
                    val naviRes = context.resources.getIdentifier("navi_hey_listen", "raw", context.packageName)
                    if (naviRes != 0) customResId = naviRes
                }
            } else {
                val sirenRes = context.resources.getIdentifier("pager_siren", "raw", context.packageName)
                if (sirenRes != 0) customResId = sirenRes
            }

            var alarmUri: Uri? = null
            if (customResId != null) {
                alarmUri = Uri.parse("android.resource://${context.packageName}/$customResId")
            }

            if (alarmUri == null) {
                val alarmType = if (level == PageLevel.HEY_LOOK) RingtoneManager.TYPE_NOTIFICATION else RingtoneManager.TYPE_ALARM
                alarmUri = RingtoneManager.getDefaultUri(alarmType) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
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
                setOnPreparedListener { it.start() }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error: what=$what extra=$extra")
                    false
                }
                prepareAsync()
            }
            Log.d(TAG, "Audio started for ${level.name} (uri=$alarmUri) at volume: $targetVolume")

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

            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    audioFocusRequest?.let {
                        audioManager.abandonAudioFocusRequest(it)
                        audioFocusRequest = null
                    }
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.abandonAudioFocus(null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to abandon audio focus", e)
            }

            if (originalVolume != -1) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
                originalVolume = -1
            }
            Log.d(TAG, "Alarm sound stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping alarm sound", e)
        }
    }
}
