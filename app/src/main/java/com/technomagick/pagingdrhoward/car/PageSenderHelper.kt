package com.technomagick.pagingdrhoward.car

import android.content.Context
import com.technomagick.pagingdrhoward.data.DefaultPagerRepository
import com.technomagick.pagingdrhoward.data.PageLevel
import com.technomagick.pagingdrhoward.data.PairedContact
import com.technomagick.pagingdrhoward.network.PushSender
import com.technomagick.pagingdrhoward.util.CryptoManager
import com.technomagick.pagingdrhoward.util.LogHelper

object PageSenderHelper {
    private const val TAG = "PageSenderHelper"

    fun sendRemotePage(
        context: Context,
        contact: PairedContact,
        level: PageLevel,
        customMessage: String? = null,
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        val prefs = context.getSharedPreferences(DefaultPagerRepository.PREF_NAME, Context.MODE_PRIVATE)
        val repository = DefaultPagerRepository(prefs)

        val senderName = repository.getMyName()
        val myTopicId = repository.getMyTopicId()
        val myPublicKeyBase64 = repository.getMyPublicKeyBase64()
        val myPrivateKey = repository.getMyPrivateKey()
        val serverUrl = repository.getRelayServerUrl()

        val peerPublicKey = if (contact.publicKeyBase64.isNotBlank()) {
            try {
                CryptoManager.publicKeyFromBase64(contact.publicKeyBase64)
            } catch (e: Exception) {
                null
            }
        } else null

        val message = customMessage?.trim()?.takeIf { it.isNotBlank() }
            ?: if (level == PageLevel.SOS) "URGENT: Please respond immediately!" else "Hey look! 👀"

        Thread {
            try {
                PushSender.sendPage(
                    targetTopicId = contact.topicId,
                    senderName = senderName,
                    senderTopicId = myTopicId,
                    senderPublicKeyBase64 = myPublicKeyBase64,
                    senderPrivateKey = myPrivateKey,
                    recipientPublicKey = peerPublicKey,
                    pageLevel = level,
                    messageText = message,
                    serverUrl = serverUrl
                ) { success, msg ->
                    LogHelper.i(TAG, "Page dispatched to ${contact.name} (${level.code}): success=$success msg=$msg")
                    onComplete?.invoke(success, msg)
                }
            } catch (e: Exception) {
                LogHelper.e(TAG, "Failed to send page to ${contact.name}", e)
                onComplete?.invoke(false, e.localizedMessage ?: "Failed to dispatch page")
            }
        }.start()
    }
}
