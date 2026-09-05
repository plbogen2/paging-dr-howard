package com.example.pagingdrhoward.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.pagingdrhoward.MainActivity
import com.example.pagingdrhoward.data.DefaultPagerRepository
import com.example.pagingdrhoward.data.PageLevel
import com.example.pagingdrhoward.data.PairedContact
import com.example.pagingdrhoward.network.PushSender
import com.example.pagingdrhoward.util.CryptoManager
import com.example.pagingdrhoward.util.DndHelper
import okhttp3.*
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PushListenerService : Service() {

    private var eventSource: EventSource? = null
    private val processedMessageSignatures = mutableSetOf<String>()
    private var serviceStartTimeMs = System.currentTimeMillis()

    private val sseClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceStartTimeMs = System.currentTimeMillis()
        DndHelper.createEmergencyNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)

        startSseListener()
        return START_STICKY
    }

    private fun startSseListener() {
        eventSource?.cancel()

        val prefs = getSharedPreferences(DefaultPagerRepository.PREF_NAME, Context.MODE_PRIVATE)
        val repository = DefaultPagerRepository(prefs)
        val myTopicId = repository.getMyTopicId()

        // ntfy /sse endpoint automatically streams new events in real time. Do not pass invalid since=now.
        val sseUrl = "${PushSender.NTFY_BASE_URL}$myTopicId/sse"
        val request = Request.Builder()
            .url(sseUrl)
            .build()

        val factory = EventSources.createFactory(sseClient)
        eventSource = factory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.d(TAG, "Connected to ntfy push stream on topic: $myTopicId")
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                Log.d(TAG, "Received push event (type=$type): $data")
                try {
                    val json = JSONObject(data)
                    val eventType = json.optString("event", "message")
                    // Ignore open / keepalive events from ntfy.sh
                    if (eventType != "message") return

                    val rawMessage = json.optString("message", "")
                    if (rawMessage.isNotBlank()) {
                        processIncomingPayload(rawMessage, repository)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing push event", e)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                Log.d(TAG, "Push stream closed, reconnecting in 3s...")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startSseListener()
                }, 3000)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                Log.w(TAG, "Push stream connection failure: ${t?.localizedMessage}, reconnecting in 5s...")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    startSseListener()
                }, 5000)
            }
        })
    }

    private fun processIncomingPayload(payloadStr: String, repository: DefaultPagerRepository) {
        try {
            val json = JSONObject(payloadStr)
            val type = json.optString("type", "PAGE")
            val senderName = json.optString("senderName", "Family Member")
            val senderTopicId = json.optString("senderTopicId", "")
            val senderPubKeyBase64 = json.optString("senderPublicKey", "")
            val levelCode = json.optString("level", PageLevel.SOS.code)
            val timestamp = json.optLong("timestamp", 0L)
            val ciphertext = json.optString("ciphertext", "")
            val signature = json.optString("signature", "")
            val pageLevel = PageLevel.fromCode(levelCode)

            // Replay protection: Ignore messages older than 5 minutes or future timestamps > 5 minutes off
            val now = System.currentTimeMillis()
            if (timestamp > 0 && (Math.abs(now - timestamp) > 300_000 || timestamp < serviceStartTimeMs - 60_000)) {
                Log.d(TAG, "Ignored stale message from timestamp $timestamp (current: $now)")
                return
            }

            // Deduplication: Avoid processing identical signature repeatedly
            val dedupeKey = if (signature.isNotBlank()) signature else "$senderTopicId:$timestamp"
            if (processedMessageSignatures.contains(dedupeKey)) {
                Log.d(TAG, "Ignored already processed message dedupeKey: $dedupeKey")
                return
            }
            processedMessageSignatures.add(dedupeKey)
            if (processedMessageSignatures.size > 200) {
                processedMessageSignatures.clear()
            }

            if (type == "PAIRING_HANDSHAKE") {
                // Auto-save contact into address book for bidirectional pairing
                if (senderTopicId.isNotBlank()) {
                    val contact = PairedContact(
                        id = senderTopicId,
                        name = senderName,
                        topicId = senderTopicId,
                        publicKeyBase64 = senderPubKeyBase64
                    )
                    repository.savePairedContact(contact)
                    Log.i(TAG, "Mutual pairing handshake saved contact: $senderName ($senderTopicId)")
                }
                return
            }

            // Verify ECDSA signature if public key is available
            var isSignatureValid = false
            if (senderPubKeyBase64.isNotBlank() && signature.isNotBlank()) {
                try {
                    val senderPubKey = CryptoManager.publicKeyFromBase64(senderPubKeyBase64)
                    val dataToVerify = "$senderTopicId:$levelCode:$timestamp:$ciphertext"
                    isSignatureValid = CryptoManager.verify(senderPubKey, dataToVerify, signature)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed signature verification", e)
                    isSignatureValid = false
                }
            }

            // Reject unsigned or invalid incoming pages to prevent false alarms
            if (!isSignatureValid) {
                Log.w(TAG, "Rejected page: Signature validation failed from $senderName")
                return
            }

            // Decrypt message text if encrypted
            var decryptedMessage = ciphertext
            val myPrivateKey = repository.getMyPrivateKey()
            if (myPrivateKey != null && senderPubKeyBase64.isNotBlank()) {
                try {
                    val senderPubKey = CryptoManager.publicKeyFromBase64(senderPubKeyBase64)
                    val sharedKey = CryptoManager.deriveSharedAesKey(myPrivateKey, senderPubKey)
                    decryptedMessage = CryptoManager.decryptWithSharedKey(sharedKey, ciphertext)
                } catch (e: Exception) {
                    Log.w(TAG, "Decryption error, falling back to raw ciphertext", e)
                }
            }

            // Trigger emergency full-volume alert & wake screen
            val serviceIntent = Intent(this, EmergencyPagerService::class.java).apply {
                action = EmergencyPagerService.ACTION_START_ALARM
                putExtra("EXTRA_SENDER", senderName)
                putExtra("EXTRA_MESSAGE", decryptedMessage)
                putExtra("EXTRA_LEVEL", pageLevel.code)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling incoming payload", e)
        }
    }

    private fun createForegroundNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, DndHelper.CHANNEL_STATUS_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Paging Dr. Howard 📟")
            .setContentText("Ready & Listening for Family Emergency Pages")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        eventSource?.cancel()
    }

    companion object {
        const val NOTIFICATION_ID = 1002
        const val ACTION_START_LISTENING = "com.example.pagingdrhoward.START_LISTENING"
        private const val TAG = "PushListenerService"
    }
}
