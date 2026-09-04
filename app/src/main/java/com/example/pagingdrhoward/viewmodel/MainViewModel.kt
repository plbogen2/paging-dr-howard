package com.example.pagingdrhoward.viewmodel

import androidx.lifecycle.ViewModel
import com.example.pagingdrhoward.data.PairedContact
import com.example.pagingdrhoward.data.PairingPayload
import com.example.pagingdrhoward.data.PagerRepository

data class MainUiState(
    val fcmToken: String = "Fetching token...",
    val isDndAccessGranted: Boolean = false,
    val familyPassphrase: String = "",
    val pairedContacts: List<PairedContact> = emptyList(),
    val myName: String = "Dad",
    val myPairingCode: String = "",
    val targetTokenInput: String = "",
    val senderNameInput: String = "Dad",
    val messageTextInput: String = "URGENT: Please call me ASAP!",
    val serverKeyInput: String = "",
    val errorMessage: String? = null,
    val successMessage: String? = null
)

class MainViewModel(private val repository: PagerRepository) : ViewModel() {

    var uiState: MainUiState = MainUiState()
        private set

    init {
        loadSettings()
    }

    fun loadSettings() {
        val savedToken = repository.getFcmToken() ?: "Fetching token..."
        val savedPassphrase = repository.getFamilyPassphrase()
        val contacts = repository.getPairedContacts()
        
        val pairingCode = if (savedToken != "Fetching token...") {
            PairingPayload.generatePairingCode(uiState.myName, savedToken, savedPassphrase)
        } else {
            ""
        }

        uiState = uiState.copy(
            fcmToken = savedToken,
            familyPassphrase = savedPassphrase,
            pairedContacts = contacts,
            myPairingCode = pairingCode
        )
    }

    fun setDndGranted(granted: Boolean) {
        uiState = uiState.copy(isDndAccessGranted = granted)
    }

    fun updateFcmToken(token: String) {
        repository.saveFcmToken(token)
        val pairingCode = PairingPayload.generatePairingCode(uiState.myName, token, uiState.familyPassphrase)
        uiState = uiState.copy(
            fcmToken = token,
            myPairingCode = pairingCode
        )
    }

    fun setTokenError(error: String) {
        uiState = uiState.copy(
            fcmToken = error,
            errorMessage = error
        )
    }

    fun updateMyName(name: String) {
        val pairingCode = PairingPayload.generatePairingCode(name, uiState.fcmToken, uiState.familyPassphrase)
        uiState = uiState.copy(
            myName = name,
            senderNameInput = name,
            myPairingCode = pairingCode
        )
    }

    fun saveFamilyPassphrase(passphrase: String): Boolean {
        if (passphrase.isBlank()) {
            uiState = uiState.copy(errorMessage = "Security Key cannot be empty")
            return false
        }
        repository.saveFamilyPassphrase(passphrase)
        val pairingCode = PairingPayload.generatePairingCode(uiState.myName, uiState.fcmToken, passphrase)
        uiState = uiState.copy(
            familyPassphrase = passphrase,
            myPairingCode = pairingCode,
            successMessage = "Security Key saved successfully!"
        )
        return true
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

        loadSettings()
        uiState = uiState.copy(
            targetTokenInput = contact.fcmToken,
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
            targetTokenInput = contact.fcmToken
        )
    }

    fun updateTargetToken(token: String) {
        uiState = uiState.copy(targetTokenInput = token)
    }

    fun updateSenderName(name: String) {
        uiState = uiState.copy(senderNameInput = name)
    }

    fun updateMessageText(text: String) {
        uiState = uiState.copy(messageTextInput = text)
    }

    fun updateServerKey(key: String) {
        uiState = uiState.copy(serverKeyInput = key)
    }

    fun validatePageSubmission(): Pair<Boolean, String?> {
        if (uiState.targetTokenInput.isBlank()) {
            return Pair(false, "Recipient token is required")
        }
        if (uiState.senderNameInput.isBlank()) {
            return Pair(false, "Sender name is required")
        }
        if (uiState.messageTextInput.isBlank()) {
            return Pair(false, "Message text is required")
        }
        return Pair(true, null)
    }

    fun clearMessages() {
        uiState = uiState.copy(errorMessage = null, successMessage = null)
    }
}
