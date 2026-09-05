package com.example.pagingdrhoward

import com.example.pagingdrhoward.data.PairedContact
import com.example.pagingdrhoward.data.PagerRepository
import com.example.pagingdrhoward.data.PairingPayload
import com.example.pagingdrhoward.util.AppUpdateManager
import com.example.pagingdrhoward.util.CryptoManager
import com.example.pagingdrhoward.viewmodel.MainViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.security.PrivateKey
import java.security.PublicKey

class FakePagerRepository : PagerRepository {
    private var topicId: String = "pdh_test_device_123"
    private var myName: String = "Dad"
    private var passphrase: String = ""
    private val keyPair = CryptoManager.generateKeyPair()
    private val contacts = mutableListOf<PairedContact>()

    override fun getMyTopicId(): String = topicId
    override fun getMyName(): String = myName
    override fun saveMyName(name: String) { this.myName = name }
    override fun getMyPublicKeyBase64(): String = CryptoManager.publicKeyToBase64(keyPair.public)
    override fun getMyPrivateKey(): PrivateKey? = keyPair.private
    override fun getMyPublicKey(): PublicKey? = keyPair.public
    override fun getFamilyPassphrase(): String = passphrase
    override fun saveFamilyPassphrase(passphrase: String) { this.passphrase = passphrase }
    override fun getPairedContacts(): List<PairedContact> = contacts.toList()
    override fun savePairedContact(contact: PairedContact) {
        val index = contacts.indexOfFirst { it.id == contact.id || it.topicId == contact.topicId }
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
        assertEquals("pdh_test_device_123", viewModel.uiState.myTopicId)
        assertEquals("Dad", viewModel.uiState.myName)
        assertTrue(viewModel.uiState.myPublicKeyBase64.isNotBlank())
        assertTrue(viewModel.uiState.myPairingCode.startsWith("PAGING_PAIR:"))
        assertTrue(viewModel.uiState.pairedContacts.isEmpty())
        assertFalse(viewModel.uiState.isDndAccessGranted)
    }

    @Test
    fun `test updateMyName updates repository and pairing code`() {
        viewModel.updateMyName("Mom")

        assertEquals("Mom", viewModel.uiState.myName)
        assertEquals("Mom", repository.getMyName())
        assertTrue(viewModel.uiState.myPairingCode.contains("Mom"))
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
    fun `test importPairingCode successfully adds contact to repository`() {
        val peerKeyPair = CryptoManager.generateKeyPair()
        val peerPubBase64 = CryptoManager.publicKeyToBase64(peerKeyPair.public)
        val pairingCode = PairingPayload.generatePairingCode("Daughter", "pdh_daughter_456", peerPubBase64, "SecretPass123")

        val success = viewModel.importPairingCode(pairingCode)

        assertTrue(success)
        assertEquals(1, viewModel.uiState.pairedContacts.size)
        assertEquals("Daughter", viewModel.uiState.pairedContacts[0].name)
        assertEquals("pdh_daughter_456", viewModel.uiState.targetTopicInput)
    }

    @Test
    fun `test deleteContact removes contact from repository and state`() {
        val contact = PairedContact("c1", "Mom", "pdh_mom_789")
        repository.savePairedContact(contact)
        viewModel.loadSettings()

        assertEquals(1, viewModel.uiState.pairedContacts.size)

        viewModel.deleteContact("c1")
        assertTrue(viewModel.uiState.pairedContacts.isEmpty())
    }

    @Test
    fun `test setUpdateInfo updates ui state with update details`() {
        val update = AppUpdateManager.UpdateInfo(
            latestVersionName = "v1.0.0.1025",
            latestBuildNumber = 1025,
            apkDownloadUrl = "https://example.com/app.apk",
            releaseNotes = "New features",
            hasUpdate = true
        )
        viewModel.setUpdateInfo(update)

        assertNotNull(viewModel.uiState.updateInfo)
        assertTrue(viewModel.uiState.updateInfo!!.hasUpdate)
        assertEquals("v1.0.0.1025", viewModel.uiState.updateInfo!!.latestVersionName)
    }
}
