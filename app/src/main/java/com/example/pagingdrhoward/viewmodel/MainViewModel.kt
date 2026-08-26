package com.example.pagingdrhoward.viewmodel

import androidx.lifecycle.ViewModel
import com.example.pagingdrhoward.data.FcmPayloadBuilder
import com.example.pagingdrhoward.data.PagerRepository

data class MainUiState(
    val fcmToken: String = "Fetching token...",
    val isDndAccessGranted: Boolean = false,
    val familyPassphrase: String = "",
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
        uiState = uiState.copy(
            fcmToken = savedToken,
            familyPassphrase = savedPassphrase
        )
    }

    fun setDndGranted(granted: Boolean) {
        uiState = uiState.copy(isDndAccessGranted = granted)
    }

    fun updateFcmToken(token: String) {
        repository.saveFcmToken(token)
        uiState = uiState.copy(fcmToken = token)
    }

    fun saveFamilyPassphrase(passphrase: String): Boolean {
        if (passphrase.isBlank()) {
            uiState = uiState.copy(errorMessage = "Security Key cannot be empty")
            return false
        }
        repository.saveFamilyPassphrase(passphrase)
        uiState = uiState.copy(
            familyPassphrase = passphrase,
            successMessage = "Security Key saved successfully!"
        )
        return true
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
