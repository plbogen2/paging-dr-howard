package com.technomagick.pagingdrhoward.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.technomagick.pagingdrhoward.MainActivity
import com.technomagick.pagingdrhoward.data.DefaultPagerRepository
import com.technomagick.pagingdrhoward.data.PageLevel
import com.technomagick.pagingdrhoward.data.PairedContact
import com.technomagick.pagingdrhoward.network.PushSender
import com.technomagick.pagingdrhoward.util.CryptoManager
import com.technomagick.pagingdrhoward.util.DndHelper
import okhttp3.*
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class PushListenerService : Service() {

    private var eventSource: EventSource? = null
    private var reconnectAttempt = 0
    private var currentServerIndex = 0
    private var serviceStartTimeMs = System.currentTimeMillis()
    private var isConnected = false
    private var isConnecting = false
    private var currentListeningTopic: String? = null
    private var currentListeningServer: String? = null

    private val sseClient = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    override fun onBind(intent: Intent?): IBinder? = null

    private lateinit var coreEngine: com.technomagick.pagingdrhoward.shared.PagerCoreEngine

    override fun onCreate() {
        super.onCreate()
        serviceStartTimeMs = System.currentTimeMillis()
        val prefs = getSharedPreferences(DefaultPagerRepository.PREF_NAME, Context.MODE_PRIVATE)
        val repository = DefaultPagerRepository(prefs)
        coreEngine = com.technomagick.pagingdrhoward.shared.PagerCoreEngine(
            myTopicId = repository.getMyTopicId(),
            myName = repository.getMyName(),
            relayServerUrl = repository.getRelayServerUrl(),
            startTimeMs = serviceStartTimeMs
        )
        DndHelper.createEmergencyNotificationChannel(this)
    }

    private fun handleEngineEvent(event: com.technomagick.pagingdrhoward.shared.EngineEvent, repository: DefaultPagerRepository) {
        when (event) {
            is com.technomagick.pagingdrhoward.shared.EngineEvent.AlertTriggered -> {
                Log.i(TAG, "Engine alert triggered from ${event.senderName} (${event.level.code})")
                val pageLevel = PageLevel.fromCode(event.level.code)
                val serviceIntent = Intent(this, EmergencyPagerService::class.java).apply {
                    action = EmergencyPagerService.ACTION_START_ALARM
                    putExtra("EXTRA_SENDER", event.senderName)
                    putExtra("EXTRA_SENDER_TOPIC", event.senderTopicId)
                    putExtra("EXTRA_MESSAGE", event.messageText)
                    putExtra("EXTRA_LEVEL", pageLevel.code)
                    putExtra("EXTRA_TIMESTAMP", event.timestamp)
                    if (!event.messageKey.isNullOrBlank()) {
                        putExtra("EXTRA_MESSAGE_KEY", event.messageKey)
                    }
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            }
            is com.technomagick.pagingdrhoward.shared.EngineEvent.PageAckReceived -> {
                Log.i(TAG, "Engine received PAGE_ACK from ${event.senderName}")
                val key = event.messageKey
                if (!key.isNullOrBlank()) {
                    PushSender.deleteMessage(repository.getRelayServerUrl(), repository.getMyTopicId(), key)
                }
                val ackNotification = NotificationCompat.Builder(this, DndHelper.CHANNEL_STATUS_ID)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Page Acknowledged ✔")
                    .setContentText("${event.senderName} confirmed receipt of your page.")
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .build()
                val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                notificationManager.notify((System.currentTimeMillis() % 10000).toInt(), ackNotification)
            }
            is com.technomagick.pagingdrhoward.shared.EngineEvent.PairingReceived -> {
                Log.i(TAG, "Engine received pairing from ${event.senderName}")
                val existing = repository.getPairedContacts().find { it.topicId == event.senderTopicId }
                val contact = PairedContact(
                    id = existing?.id ?: event.senderTopicId,
                    name = event.senderName.ifBlank { existing?.name ?: "Family Member" },
                    topicId = event.senderTopicId,
                    publicKeyBase64 = event.senderPublicKey.ifBlank { existing?.publicKeyBase64 ?: "" },
                    passphrase = existing?.passphrase ?: ""
                )
                repository.savePairedContact(contact)
                val key = event.messageKey
                if (!key.isNullOrBlank()) {
                    PushSender.deleteMessage(repository.getRelayServerUrl(), repository.getMyTopicId(), key)
                }
                if (event.requiresReply) {
                    val peerPublicKey = if (event.senderPublicKey.isNotBlank()) {
                        try { CryptoManager.publicKeyFromBase64(event.senderPublicKey) } catch (e: Exception) { null }
                    } else null
                    PushSender.sendPairingHandshake(
                        targetTopicId = event.senderTopicId,
                        myName = repository.getMyName(),
                        myTopicId = repository.getMyTopicId(),
                        myPublicKeyBase64 = repository.getMyPublicKeyBase64(),
                        myPrivateKey = repository.getMyPrivateKey(),
                        peerPublicKey = peerPublicKey,
                        isReply = true,
                        serverUrl = repository.getRelayServerUrl()
                    )
                }
            }
            is com.technomagick.pagingdrhoward.shared.EngineEvent.NameUpdateReceived -> {
                Log.i(TAG, "Engine received name update from ${event.newName}")
                val existing = repository.getPairedContacts().find { it.topicId == event.senderTopicId }
                if (existing != null) {
                    repository.savePairedContact(existing.copy(name = event.newName))
                }
                val key = event.messageKey
                if (!key.isNullOrBlank()) {
                    PushSender.deleteMessage(repository.getRelayServerUrl(), repository.getMyTopicId(), key)
                }
            }
            is com.technomagick.pagingdrhoward.shared.EngineEvent.PurgeRequired -> {
                Log.d(TAG, "Engine requested purge for key: ${event.messageKey}")
                PushSender.deleteMessage(repository.getRelayServerUrl(), repository.getMyTopicId(), event.messageKey)
            }
            is com.technomagick.pagingdrhoward.shared.EngineEvent.Ignored -> {
                // Do nothing
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createForegroundNotification()
        startForeground(NOTIFICATION_ID, notification)

        startSseListener()
        return START_STICKY
    }

    private fun startSseListener() {
        val prefs = getSharedPreferences(DefaultPagerRepository.PREF_NAME, Context.MODE_PRIVATE)
        val repository = DefaultPagerRepository(prefs)
        val myTopicId = repository.getMyTopicId()

        val userServer = repository.getRelayServerUrl()
        val serverCandidates = mutableListOf<String>()
        if (userServer.isNotBlank()) serverCandidates.add(userServer)
        PushSender.FALLBACK_SERVERS.forEach { fb ->
            if (!serverCandidates.contains(fb)) serverCandidates.add(fb)
        }

        val base = serverCandidates[currentServerIndex % serverCandidates.size]
        val cleanTopic = myTopicId.trim().replace(Regex("^https?:/+[^/]+/"), "").replace(Regex("[^a-zA-Z0-9_-]"), "_")

        // If already connecting or connected to this exact server and topic, avoid tearing down stream
        if ((isConnected || isConnecting) && eventSource != null && currentListeningTopic == cleanTopic && currentListeningServer == base) {
            Log.d(TAG, "Already connecting or connected to push stream on $base ($cleanTopic), ignoring redundant startSseListener call")
            return
        }

        try {
            eventSource?.cancel()
        } catch (e: Exception) {
            // Ignored
        }
        eventSource = null
        isConnected = false
        isConnecting = true
        currentListeningTopic = cleanTopic
        currentListeningServer = base

        val sseUrl = if (base.contains("firebaseio.com")) {
            val cleanBase = if (base.endsWith("/")) base else "$base/"
            "${cleanBase}channels/$cleanTopic.json"
        } else {
            "$base$cleanTopic/sse"
        }
        val request = Request.Builder()
            .url(sseUrl)
            .addHeader("Accept", "text/event-stream")
            .addHeader("User-Agent", PushSender.USER_AGENT)
            .build()

        val factory = EventSources.createFactory(sseClient)
        eventSource = factory.newEventSource(request, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                Log.d(TAG, "Connected to ntfy push stream on $base ($myTopicId)")
                isConnected = true
                isConnecting = false
                reconnectAttempt = 0 // Reset backoff on successful connection
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                Log.d(TAG, "Received push event (type=$type): $data")
                try {
                    // Sync any recent dismissed keys and timestamp persisted by EmergencyAlertActivity
                    repository.getDismissedMessageKeys().forEach { key ->
                        coreEngine.markMessageDismissed(key)
                    }
                    val lastDismissed = repository.getLastDismissedAlertTimestamp()
                    if (lastDismissed > coreEngine.lastDismissedAlertTimestamp) {
                        coreEngine.lastDismissedAlertTimestamp = lastDismissed
                    }

                    val events = coreEngine.processRawEvent(data, System.currentTimeMillis())
                    for (event in events) {
                        handleEngineEvent(event, repository)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error processing push event", e)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                isConnected = false
                isConnecting = false
                schedulePoliteReconnect("Stream closed")
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                isConnected = false
                isConnecting = false
                // If 429 Too Many Requests, cycle server
                if (response?.code == 429) {
                    currentServerIndex++
                    Log.w(TAG, "Server $base returned 429 (Too Many Requests). Switching to next relay...")
                } else if (reconnectAttempt >= 2) {
                    // Failover after 2 consecutive connection failures on this server
                    currentServerIndex++
                    Log.w(TAG, "Connection failed on $base, failing over to next relay...")
                }
                schedulePoliteReconnect("Connection failure: ${t?.localizedMessage ?: response?.code}")
            }
        })
    }

    private fun schedulePoliteReconnect(reason: String) {
        reconnectAttempt++
        // Exponential backoff: 5s, 10s, 20s, up to 60s max + random jitter (0-3s)
        val backoffSeconds = (5L * (1L shl (reconnectAttempt - 1).coerceAtMost(4))).coerceAtMost(60L)
        val jitterMs = (Math.random() * 3000).toLong()
        val delayMs = (backoffSeconds * 1000) + jitterMs

        Log.d(TAG, "$reason. Polite backoff #$reconnectAttempt: reconnecting in ${delayMs / 1000}s...")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            startSseListener()
        }, delayMs)
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
        const val ACTION_START_LISTENING = "com.technomagick.pagingdrhoward.START_LISTENING"
        private const val TAG = "PushListenerService"
    }
}
