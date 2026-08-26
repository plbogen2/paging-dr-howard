package com.example.pagingdrhoward.network

import android.util.Log
import com.example.pagingdrhoward.util.SecurityUtils
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException

object FcmSender {
    private const val TAG = "FcmSender"
    private val client = OkHttpClient()

    /**
     * Sends a high-priority, cryptographically signed & encrypted FCM page payload.
     */
    fun sendSecurePage(
        targetToken: String,
        senderName: String,
        messageText: String,
        familyPassphrase: String,
        serverKey: String,
        onResult: (Boolean, String) -> Unit
    ) {
        try {
            val timestamp = System.currentTimeMillis().toString()
            val rawPayload = "$senderName|$messageText|$timestamp"
            
            // 1. Encrypt message and generate HMAC signature using shared family passphrase
            val encryptedMessage = if (familyPassphrase.isNotBlank()) {
                SecurityUtils.encrypt(familyPassphrase, messageText)
            } else {
                messageText
            }

            val signature = if (familyPassphrase.isNotBlank()) {
                SecurityUtils.generateSignature(familyPassphrase, rawPayload)
            } else {
                ""
            }

            // 2. Build JSON payload
            val jsonBody = JSONObject().apply {
                put("to", targetToken)
                put("priority", "high")
                put("data", JSONObject().apply {
                    put("sender", senderName)
                    put("message", encryptedMessage)
                    put("timestamp", timestamp)
                    put("signature", signature)
                    put("encrypted", familyPassphrase.isNotBlank())
                    put("type", "EMERGENCY_PAGE")
                })
            }

            val requestBody = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("https://fcm.googleapis.com/fcm/send")
                .addHeader("Authorization", "key=$serverKey")
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "Failed to send FCM page", e)
                    onResult(false, e.localizedMessage ?: "Network error")
                }

                override fun onResponse(call: Call, response: Response) {
                    val responseStr = response.body?.string() ?: ""
                    val isSuccess = response.isSuccessful
                    Log.d(TAG, "FCM send response ($response.code): $responseStr")
                    onResult(isSuccess, if (isSuccess) "Secure Page Sent Successfully!" else "FCM Error ($response.code): $responseStr")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error building FCM request", e)
            onResult(false, e.localizedMessage ?: "Unknown error")
        }
    }
}
