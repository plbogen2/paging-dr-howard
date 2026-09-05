package com.example.pagingdrhoward.network

import android.util.Log
import com.example.pagingdrhoward.data.PageLevel
import com.example.pagingdrhoward.util.CryptoManager
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.PrivateKey
import java.security.PublicKey

object PushSender {
    private const val TAG = "PushSender"
    const val DEFAULT_NTFY_BASE_URL = "https://ntfy.tedomum.fr/"
    const val NTFY_BASE_URL = "https://ntfy.tedomum.fr/"
    const val USER_AGENT = "PagingDrHoward/1.0 (Android Emergency Pager; +https://github.com/plbogen2/paging-dr-howard)"

    val FALLBACK_SERVERS = listOf(
        "https://ntfy.tedomum.fr/",
        "https://ntfy.adminforge.de/",
        "https://ntfy.sh/"
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    data class PageMessage(
        val type: String = "PAGE",
        val senderName: String,
        val senderTopicId: String,
        val senderPublicKeyBase64: String,
        val level: PageLevel,
        val messageText: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Builds and signs a JSON payload for transmission over ntfy.sh public push relay.
     */
    fun buildPayloadJson(
        message: PageMessage,
        senderPrivateKey: PrivateKey?,
        recipientPublicKey: PublicKey?
    ): String {
        var cipherText = message.messageText
        if (senderPrivateKey != null && recipientPublicKey != null) {
            try {
                val sharedAesKey = CryptoManager.deriveSharedAesKey(senderPrivateKey, recipientPublicKey)
                cipherText = CryptoManager.encryptWithSharedKey(sharedAesKey, message.messageText)
            } catch (e: Exception) {
                Log.w(TAG, "ECDH encryption fallback to plaintext", e)
            }
        }

        val dataToSign = "${message.senderTopicId}:${message.level.code}:${message.timestamp}:$cipherText"
        val signature = if (senderPrivateKey != null) {
            try {
                CryptoManager.sign(senderPrivateKey, dataToSign)
            } catch (e: Exception) {
                ""
            }
        } else {
            ""
        }

        return JSONObject().apply {
            put("type", message.type)
            put("senderName", message.senderName)
            put("senderTopicId", message.senderTopicId)
            put("senderPublicKey", message.senderPublicKeyBase64)
            put("level", message.level.code)
            put("timestamp", message.timestamp)
            put("ciphertext", cipherText)
            put("signature", signature)
        }.toString()
    }

    /**
     * Sends an encrypted, high-priority emergency page to target contact's private topic on ntfy.
     * Tries preferred serverUrl first; if network fails/times out, fails over to alternate public relays.
     */
    fun sendPage(
        targetTopicId: String,
        senderName: String,
        senderTopicId: String,
        senderPublicKeyBase64: String,
        senderPrivateKey: PrivateKey?,
        recipientPublicKey: PublicKey?,
        pageLevel: PageLevel = PageLevel.SOS,
        messageText: String = "",
        serverUrl: String = DEFAULT_NTFY_BASE_URL,
        onResult: (Boolean, String) -> Unit
    ) {
        val topic = targetTopicId.trim().substringAfterLast("/")
        if (topic.isBlank()) {
            onResult(false, "Recipient topic address is required")
            return
        }

        val msg = PageMessage(
            type = "PAGE",
            senderName = senderName,
            senderTopicId = senderTopicId,
            senderPublicKeyBase64 = senderPublicKeyBase64,
            level = pageLevel,
            messageText = messageText.ifBlank { if (pageLevel == PageLevel.HEY_LOOK) "Hey look! Check your phone." else "EMERGENCY: Urgent assistance needed!" }
        )

        val jsonPayload = buildPayloadJson(msg, senderPrivateKey, recipientPublicKey)
        val cleanTitle = "${pageLevel.name.replace('_', ' ')} from $senderName".filter { it.code in 32..126 }

        val serverCandidates = mutableListOf<String>()
        val primary = if (serverUrl.isNotBlank()) (if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/") else DEFAULT_NTFY_BASE_URL
        serverCandidates.add(primary)
        FALLBACK_SERVERS.forEach { fb ->
            if (!serverCandidates.contains(fb)) serverCandidates.add(fb)
        }

        val completedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val hasSucceeded = java.util.concurrent.atomic.AtomicBoolean(false)
        val totalServers = serverCandidates.size

        serverCandidates.forEach { base ->
            val url = "$base$topic"
            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Priority", if (pageLevel == PageLevel.SOS) "5" else "4")
                .addHeader("Title", cleanTitle.ifBlank { "Emergency Alert" })
                .addHeader("Tags", if (pageLevel == PageLevel.SOS) "rotating_light,sos" else "eyes,bell")
                .addHeader("Content-Type", "application/json")
                .post(jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.w(TAG, "Push dispatch failed on $base: ${e.localizedMessage}")
                    if (completedCount.incrementAndGet() == totalServers && !hasSucceeded.get()) {
                        onResult(false, "Network error: Unable to reach any push relays. Please check internet connection.")
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        Log.d(TAG, "Page successfully sent via $base (code: ${response.code})")
                        if (hasSucceeded.compareAndSet(false, true)) {
                            onResult(true, "${pageLevel.title} sent successfully!")
                        }
                    } else {
                        Log.w(TAG, "Server $base returned error code ${response.code}")
                    }
                    if (completedCount.incrementAndGet() == totalServers && !hasSucceeded.get()) {
                        onResult(false, "Error delivering page across push relays.")
                    }
                }
            })
        }
    }

    /**
     * Sends an automatic bidirectional pairing handshake to the other device so both devices add each other mutually.
     */
    fun sendPairingHandshake(
        targetTopicId: String,
        myName: String,
        myTopicId: String,
        myPublicKeyBase64: String,
        myPrivateKey: PrivateKey?,
        peerPublicKey: PublicKey?,
        serverUrl: String = DEFAULT_NTFY_BASE_URL,
        onResult: (Boolean) -> Unit = {}
    ) {
        val topic = targetTopicId.trim().substringAfterLast("/")
        if (topic.isBlank()) return

        val msg = PageMessage(
            type = "PAIRING_HANDSHAKE",
            senderName = myName,
            senderTopicId = myTopicId,
            senderPublicKeyBase64 = myPublicKeyBase64,
            level = PageLevel.HEY_LOOK,
            messageText = "HANDSHAKE"
        )

        val jsonPayload = buildPayloadJson(msg, myPrivateKey, peerPublicKey)
        val cleanSender = myName.filter { it.code in 32..126 }

        val serverCandidates = mutableListOf<String>()
        val primary = if (serverUrl.isNotBlank()) (if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/") else DEFAULT_NTFY_BASE_URL
        serverCandidates.add(primary)
        FALLBACK_SERVERS.forEach { fb ->
            if (!serverCandidates.contains(fb)) serverCandidates.add(fb)
        }

        fun trySendCandidate(candidateIndex: Int) {
            if (candidateIndex >= serverCandidates.size) {
                onResult(false)
                return
            }
            val currentBase = serverCandidates[candidateIndex]
            val request = Request.Builder()
                .url("$currentBase$topic")
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Priority", "3")
                .addHeader("Title", "Pairing Handshake from ${cleanSender.ifBlank { "Family" }}")
                .addHeader("Content-Type", "application/json")
                .post(jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    trySendCandidate(candidateIndex + 1)
                }
                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        onResult(true)
                    } else {
                        trySendCandidate(candidateIndex + 1)
                    }
                }
            })
        }

        trySendCandidate(0)
    }

    /**
     * Broadcasts a display name change to a paired contact so their local address book updates automatically.
     */
    fun sendNameUpdate(
        targetTopicId: String,
        newName: String,
        myTopicId: String,
        myPublicKeyBase64: String,
        myPrivateKey: PrivateKey?,
        peerPublicKey: PublicKey?,
        serverUrl: String = DEFAULT_NTFY_BASE_URL,
        onResult: (Boolean) -> Unit = {}
    ) {
        val topic = targetTopicId.trim().substringAfterLast("/")
        if (topic.isBlank()) return

        val msg = PageMessage(
            type = "NAME_UPDATE",
            senderName = newName,
            senderTopicId = myTopicId,
            senderPublicKeyBase64 = myPublicKeyBase64,
            level = PageLevel.HEY_LOOK,
            messageText = "NAME_UPDATE"
        )

        val jsonPayload = buildPayloadJson(msg, myPrivateKey, peerPublicKey)
        val cleanSender = newName.filter { it.code in 32..126 }

        val serverCandidates = mutableListOf<String>()
        val primary = if (serverUrl.isNotBlank()) (if (serverUrl.endsWith("/")) serverUrl else "$serverUrl/") else DEFAULT_NTFY_BASE_URL
        serverCandidates.add(primary)
        FALLBACK_SERVERS.forEach { fb ->
            if (!serverCandidates.contains(fb)) serverCandidates.add(fb)
        }

        fun trySendCandidate(candidateIndex: Int) {
            if (candidateIndex >= serverCandidates.size) {
                onResult(false)
                return
            }
            val currentBase = serverCandidates[candidateIndex]
            val request = Request.Builder()
                .url("$currentBase$topic")
                .addHeader("User-Agent", USER_AGENT)
                .addHeader("Priority", "2")
                .addHeader("Title", "Name Update from ${cleanSender.ifBlank { "Family" }}")
                .addHeader("Content-Type", "application/json")
                .post(jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    trySendCandidate(candidateIndex + 1)
                }
                override fun onResponse(call: Call, response: Response) {
                    if (response.isSuccessful) {
                        onResult(true)
                    } else {
                        trySendCandidate(candidateIndex + 1)
                    }
                }
            })
        }

        trySendCandidate(0)
    }
}
