package com.example.pagingdrhoward

import com.example.pagingdrhoward.data.PagerRepository
import com.example.pagingdrhoward.viewmodel.MainViewModel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class FakePagerRepository : PagerRepository {
    private var token: String? = null
    private var passphrase: String = ""

    override fun getFcmToken(): String? = token
    override fun saveFcmToken(token: String) { this.token = token }
    override fun getFamilyPassphrase(): String = passphrase
    override fun saveFamilyPassphrase(passphrase: String) { this.passphrase = passphrase }
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
    fun `test validatePageSubmission validation rules`() {
        // 1. Initial default valid inputs
        viewModel.updateTargetToken("target_token_123")
        val (isValidInitial, _) = viewModel.validatePageSubmission()
        assertTrue(isValidInitial)

        // 2. Blank target token fails validation
        viewModel.updateTargetToken("")
        val (isValidBlankToken, errorToken) = viewModel.validatePageSubmission()
        assertFalse(isValidBlankToken)
        assertEquals("Recipient token is required", errorToken)

        // 3. Blank sender name fails validation
        viewModel.updateTargetToken("target_token_123")
        viewModel.updateSenderName("")
        val (isValidBlankSender, errorSender) = viewModel.validatePageSubmission()
        assertFalse(isValidBlankSender)
        assertEquals("Sender name is required", errorSender)
    }
}
