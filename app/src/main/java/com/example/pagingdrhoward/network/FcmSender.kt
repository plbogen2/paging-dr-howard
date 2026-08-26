package com.example.pagingdrhoward.network

import android.util.Log
import com.example.pagingdrhoward.data.FcmPayloadBuilder
import com.example.pagingdrhoward.data.PageLevel
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

object FcmSender {
    private const val TAG = "FcmSender"
    private val client = OkHttpClient()

    /**
     * Sends a high-priority, cryptographically signed & encrypted FCM page payload with multi-level paging.
     */
    fun sendSecurePage(
        targetToken: String,
        senderName: String,
        messageText: String,
        familyPassphrase: String,
        pageLevel: PageLevel = PageLevel.SOS,
        serverKey: String,
        onResult: (Boolean, String) -> Unit
    ) {
        try {
            val payload = FcmPayloadBuilder.PagePayload(
                targetToken = targetToken,
                senderName = senderName,
                messageText = messageText,
                familyPassphrase = familyPassphrase,
                level = pageLevel
            )

            val jsonBodyStr = FcmPayloadBuilder.buildJsonPayload(payload)
            val requestBody = jsonBodyStr.toRequestBody("application/json; charset=utf-8".toMediaType())
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
                    onResult(isSuccess, if (isSuccess) "${pageLevel.title} Page Sent!" else "FCM Error ($response.code): $responseStr")
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Error building FCM request", e)
            onResult(false, e.localizedMessage ?: "Unknown error")
        }
    }
}
