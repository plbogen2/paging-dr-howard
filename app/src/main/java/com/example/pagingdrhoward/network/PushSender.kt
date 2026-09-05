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
    const val NTFY_BASE_URL = "https://ntfy.sh/"
    private val client = OkHttpClient()

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
     * Sends an encrypted, high-priority emergency page to target contact's private topic on ntfy.sh.
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
        onResult: (Boolean, String) -> Unit
    ) {
        val topic = targetTopicId.trim().removePrefix(NTFY_BASE_URL).removePrefix("/")
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
        val url = "$NTFY_BASE_URL$topic"

        val request = Request.Builder()
            .url(url)
            .addHeader("Priority", if (pageLevel == PageLevel.SOS) "5" else "4")
            .addHeader("Title", "${pageLevel.title} from $senderName")
            .addHeader("Tags", if (pageLevel == PageLevel.SOS) "rotating_light,sos" else "eyes,bell")
            .addHeader("Content-Type", "application/json")
            .post(jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e(TAG, "Failed to send page: ${e.localizedMessage}")
                onResult(false, "Network error: ${e.localizedMessage}")
            }

            override fun onResponse(call: Call, response: Response) {
                val isSuccess = response.isSuccessful
                Log.d(TAG, "Page sent response code: ${response.code}")
                onResult(isSuccess, if (isSuccess) "${pageLevel.title} sent successfully!" else "Error (${response.code}) sending page")
            }
        })
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
        onResult: (Boolean) -> Unit = {}
    ) {
        val topic = targetTopicId.trim().removePrefix(NTFY_BASE_URL).removePrefix("/")
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
        val request = Request.Builder()
            .url("$NTFY_BASE_URL$topic")
            .addHeader("Priority", "3")
            .addHeader("Title", "Pairing Handshake")
            .addHeader("Content-Type", "application/json")
            .post(jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onResult(false)
            }
            override fun onResponse(call: Call, response: Response) {
                onResult(response.isSuccessful)
            }
        })
    }
}
