package com.example.pagingdrhoward.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.example.pagingdrhoward.data.PageLevel
import com.example.pagingdrhoward.data.PairedContact
import com.example.pagingdrhoward.data.PairingPayload
import com.example.pagingdrhoward.data.PagerRepository
import com.example.pagingdrhoward.network.PushSender
import com.example.pagingdrhoward.util.AppUpdateManager
import com.example.pagingdrhoward.util.CryptoManager

data class MainUiState(
    val myTopicId: String = "",
    val myName: String = "Dad",
    val myPublicKeyBase64: String = "",
    val myPairingCode: String = "",
    val familyPassphrase: String = "",
    val relayServerUrl: String = "https://ntfy.tedomum.fr/",
    val pairedContacts: List<PairedContact> = emptyList(),
    val isDndAccessGranted: Boolean = false,
    val isListening: Boolean = true,
    val selectedContact: PairedContact? = null,
    val targetTopicInput: String = "",
    val messageTextInput: String = "URGENT: Please respond ASAP!",
    val updateInfo: AppUpdateManager.UpdateInfo? = null,
    val appVersion: String = "1.0.0",
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class MainViewModel(private val repository: PagerRepository) : ViewModel() {

    var uiState: MainUiState by mutableStateOf(MainUiState())
        private set

    init {
        loadSettings()
    }

    fun loadSettings() {
        val topicId = repository.getMyTopicId()
        val name = repository.getMyName()
        val pubKey = repository.getMyPublicKeyBase64()
        val passphrase = repository.getFamilyPassphrase()
        val serverUrl = repository.getRelayServerUrl()
        val contacts = repository.getPairedContacts()

        val pairingCode = PairingPayload.generatePairingCode(name, topicId, pubKey, passphrase, serverUrl)

        uiState = uiState.copy(
            myTopicId = topicId,
            myName = name,
            myPublicKeyBase64 = pubKey,
            myPairingCode = pairingCode,
            familyPassphrase = passphrase,
            relayServerUrl = serverUrl,
            pairedContacts = contacts
        )
    }

    fun setDndGranted(granted: Boolean) {
        uiState = uiState.copy(isDndAccessGranted = granted)
    }

    fun setAppVersion(version: String) {
        uiState = uiState.copy(appVersion = version)
    }

    fun setUpdateInfo(updateInfo: AppUpdateManager.UpdateInfo?) {
        uiState = uiState.copy(updateInfo = updateInfo)
    }

    fun updateMyName(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotBlank()) {
            val oldName = uiState.myName
            repository.saveMyName(trimmed)
            val pairingCode = PairingPayload.generatePairingCode(trimmed, uiState.myTopicId, uiState.myPublicKeyBase64, uiState.familyPassphrase, uiState.relayServerUrl)
            uiState = uiState.copy(
                myName = trimmed,
                myPairingCode = pairingCode
            )

            // Broadcast name change to all existing paired contacts
            if (oldName != trimmed) {
                val myPrivateKey = repository.getMyPrivateKey()
                uiState.pairedContacts.forEach { contact ->
                    val peerPublicKey = if (contact.publicKeyBase64.isNotBlank()) {
                        try { CryptoManager.publicKeyFromBase64(contact.publicKeyBase64) } catch (e: Exception) { null }
                    } else null

                    PushSender.sendNameUpdate(
                        targetTopicId = contact.topicId,
                        newName = trimmed,
                        myTopicId = uiState.myTopicId,
                        myPublicKeyBase64 = uiState.myPublicKeyBase64,
                        myPrivateKey = myPrivateKey,
                        peerPublicKey = peerPublicKey,
                        serverUrl = contact.relayServerUrl.ifBlank { uiState.relayServerUrl }
                    )
                }
            }
        }
    }

    fun saveFamilyPassphrase(passphrase: String): Boolean {
        if (passphrase.isBlank()) {
            uiState = uiState.copy(errorMessage = "Security Key cannot be empty")
            return false
        }
        repository.saveFamilyPassphrase(passphrase)
        val pairingCode = PairingPayload.generatePairingCode(uiState.myName, uiState.myTopicId, uiState.myPublicKeyBase64, passphrase, uiState.relayServerUrl)
        uiState = uiState.copy(
            familyPassphrase = passphrase,
            myPairingCode = pairingCode,
            successMessage = "Security Key saved successfully!"
        )
        return true
    }

    fun updateRelayServerUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isNotBlank()) {
            repository.saveRelayServerUrl(trimmed)
            loadSettings()
        }
    }

    fun importPairingCode(rawCode: String): Boolean {
        val contact = PairingPayload.parsePairingCode(rawCode)
        if (contact == null) {
            uiState = uiState.copy(errorMessage = "Invalid Pairing Code format")
            return false
        }

        repository.savePairedContact(contact)
        if (contact.passphrase.isNotBlank()) {
            repository.saveFamilyPassphrase(contact.passphrase)
        }

        // If the peer specified a custom relay server, adopt it or use it for contacting them
        val peerServer = contact.relayServerUrl.ifBlank { uiState.relayServerUrl }

        // Send silent mutual pairing handshake back to contact's topic
        val peerPublicKey = if (contact.publicKeyBase64.isNotBlank()) {
            try { CryptoManager.publicKeyFromBase64(contact.publicKeyBase64) } catch (e: Exception) { null }
        } else null

        PushSender.sendPairingHandshake(
            targetTopicId = contact.topicId,
            myName = uiState.myName,
            myTopicId = uiState.myTopicId,
            myPublicKeyBase64 = uiState.myPublicKeyBase64,
            myPrivateKey = repository.getMyPrivateKey(),
            peerPublicKey = peerPublicKey,
            serverUrl = peerServer
        )

        loadSettings()
        uiState = uiState.copy(
            targetTopicInput = contact.topicId,
            selectedContact = contact,
            successMessage = "Paired with ${contact.name}! You can now page each other."
        )
        return true
    }

    fun deleteContact(contactId: String) {
        repository.deletePairedContact(contactId)
        loadSettings()
    }

    fun selectContactForPage(contact: PairedContact) {
        uiState = uiState.copy(
            selectedContact = contact,
            targetTopicInput = contact.topicId
        )
    }

    fun updateTargetTopic(topic: String) {
        uiState = uiState.copy(targetTopicInput = topic)
    }

    fun updateMessageText(text: String) {
        uiState = uiState.copy(messageTextInput = text)
    }

    fun clearMessages() {
        uiState = uiState.copy(errorMessage = null, successMessage = null)
    }
}
