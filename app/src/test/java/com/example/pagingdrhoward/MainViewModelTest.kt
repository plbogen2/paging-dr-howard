package com.example.pagingdrhoward

import com.example.pagingdrhoward.data.PairedContact
import com.example.pagingdrhoward.data.PagerRepository
import com.example.pagingdrhoward.data.PairingPayload
import com.example.pagingdrhoward.viewmodel.MainViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FakePagerRepository : PagerRepository {
    private var token: String? = null
    private var passphrase: String = ""
    private val contacts = mutableListOf<PairedContact>()

    override fun getFcmToken(): String? = token
    override fun saveFcmToken(token: String) { this.token = token }
    override fun getFamilyPassphrase(): String = passphrase
    override fun saveFamilyPassphrase(passphrase: String) { this.passphrase = passphrase }
    override fun getPairedContacts(): List<PairedContact> = contacts.toList()
    override fun savePairedContact(contact: PairedContact) {
        val index = contacts.indexOfFirst { it.id == contact.id || it.fcmToken == contact.fcmToken }
        if (index != -1) contacts[index] = contact else contacts.add(contact)
    }
    override fun deletePairedContact(contactId: String) {
        contacts.removeAll { it.id == contactId }
    }
}

class MainViewModelTest {

    private lateinit var repository: FakePagerRepository
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        repository = FakePagerRepository()
        viewModel = MainViewModel(repository)
    }

    @Test
    fun `test initial state loads default values`() {
        assertEquals("Fetching token...", viewModel.uiState.fcmToken)
        assertEquals("", viewModel.uiState.familyPassphrase)
        assertFalse(viewModel.uiState.isDndAccessGranted)
        assertTrue(viewModel.uiState.pairedContacts.isEmpty())
    }

    @Test
    fun `test saveFamilyPassphrase saves to repository and updates state`() {
        val success = viewModel.saveFamilyPassphrase("MySecret123")

        assertTrue(success)
        assertEquals("MySecret123", viewModel.uiState.familyPassphrase)
        assertEquals("MySecret123", repository.getFamilyPassphrase())
        assertNotNull(viewModel.uiState.successMessage)
    }

    @Test
    fun `test saveFamilyPassphrase fails on blank input`() {
        val success = viewModel.saveFamilyPassphrase("   ")

        assertFalse(success)
        assertNotNull(viewModel.uiState.errorMessage)
    }

    @Test
    fun `test updateFcmToken updates repository and state`() {
        viewModel.updateFcmToken("token_xyz_999")

        assertEquals("token_xyz_999", viewModel.uiState.fcmToken)
        assertEquals("token_xyz_999", repository.getFcmToken())
    }

    @Test
    fun `test importPairingCode successfully adds contact to repository`() {
        val pairingCode = PairingPayload.generatePairingCode("Daughter", "fcm_daughter_token", "pub_key_123", "SecretPass123")
        val success = viewModel.importPairingCode(pairingCode)

        assertTrue(success)
        assertEquals(1, viewModel.uiState.pairedContacts.size)
        assertEquals("Daughter", viewModel.uiState.pairedContacts[0].name)
        assertEquals("fcm_daughter_token", viewModel.uiState.targetTokenInput)
    }

    @Test
    fun `test deleteContact removes contact from repository and state`() {
        val contact = PairedContact("c1", "Mom", "fcm_mom_token")
        repository.savePairedContact(contact)
        viewModel.loadSettings()

        assertEquals(1, viewModel.uiState.pairedContacts.size)

        viewModel.deleteContact("c1")
        assertTrue(viewModel.uiState.pairedContacts.isEmpty())
    }

    @Test
    fun `test validatePageSubmission validation rules`() {
        viewModel.updateTargetToken("target_token_123")
        val (isValidInitial, _) = viewModel.validatePageSubmission()
        assertTrue(isValidInitial)

        viewModel.updateTargetToken("")
        val (isValidBlankToken, errorToken) = viewModel.validatePageSubmission()
        assertFalse(isValidBlankToken)
        assertEquals("Recipient token is required", errorToken)

        viewModel.updateTargetToken("target_token_123")
        viewModel.updateSenderName("")
        val (isValidBlankSender, errorSender) = viewModel.validatePageSubmission()
        assertFalse(isValidBlankSender)
        assertEquals("Sender name is required", errorSender)
    }

    @Test
    fun `test setTokenError updates state and displays error`() {
        viewModel.setTokenError("Google Play Services not installed")

        assertEquals("Google Play Services not installed", viewModel.uiState.fcmToken)
        assertEquals("Google Play Services not installed", viewModel.uiState.errorMessage)
    }
}
