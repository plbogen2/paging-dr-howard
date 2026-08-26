package com.example.pagingdrhoward

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pagingdrhoward.data.DefaultPagerRepository
import com.example.pagingdrhoward.network.FcmSender
import com.example.pagingdrhoward.service.EmergencyPagerService
import com.example.pagingdrhoward.util.DndHelper
import com.example.pagingdrhoward.viewmodel.MainUiState
import com.example.pagingdrhoward.viewmodel.MainViewModel
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize Architecture Layer (Repository + ViewModel)
        val prefs = getSharedPreferences(DefaultPagerRepository.PREF_NAME, MODE_PRIVATE)
        val repository = DefaultPagerRepository(prefs)
        viewModel = MainViewModel(repository)

        // Register DND-bypassing notification channel
        DndHelper.createEmergencyNotificationChannel(this)

        // Fetch FCM token if not present
        if (repository.getFcmToken() == null) {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful && task.result != null) {
                    viewModel.updateFcmToken(task.result)
                }
            }
        }

        setContent {
            MaterialTheme {
                MainPagerApp(
                    uiState = viewModel.uiState,
                    onSavePassphrase = { passphrase -> viewModel.saveFamilyPassphrase(passphrase) },
                    onGrantDnd = { DndHelper.openDndSettings(this) },
                    onTestAlarm = { triggerLocalTestPage() },
                    onCopyToken = { copyToClipboard(viewModel.uiState.fcmToken) },
                    onUpdateTargetToken = { viewModel.updateTargetToken(it) },
                    onUpdateSenderName = { viewModel.updateSenderName(it) },
                    onUpdateMessageText = { viewModel.updateMessageText(it) },
                    onUpdateServerKey = { viewModel.updateServerKey(it) },
                    onSendPage = { sendRemotePage() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.setDndGranted(DndHelper.hasDndAccess(this))
    }

    private fun triggerLocalTestPage() {
        val intent = Intent(this, EmergencyPagerService::class.java).apply {
            action = EmergencyPagerService.ACTION_START_ALARM
            putExtra("EXTRA_SENDER", "Self-Test")
            putExtra("EXTRA_MESSAGE", "This is a test of the emergency alarm sound!")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("FCM Device Token", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Device token copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    private fun sendRemotePage() {
        val (isValid, errorMsg) = viewModel.validatePageSubmission()
        if (!isValid) {
            Toast.makeText(this, errorMsg ?: "Invalid input", Toast.LENGTH_SHORT).show()
            return
        }

        val state = viewModel.uiState
        FcmSender.sendSecurePage(
            targetToken = state.targetTokenInput,
            senderName = state.senderNameInput,
            messageText = state.messageTextInput,
            familyPassphrase = state.familyPassphrase,
            serverKey = state.serverKeyInput
        ) { success, resultMsg ->
            runOnUiThread {
                Toast.makeText(this, resultMsg, Toast.LENGTH_LONG).show()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainPagerApp(
    uiState: MainUiState,
    onSavePassphrase: (String) -> Unit,
    onGrantDnd: () -> Unit,
    onTestAlarm: () -> Unit,
    onCopyToken: () -> Unit,
    onUpdateTargetToken: (String) -> Unit,
    onUpdateSenderName: (String) -> Unit,
    onUpdateMessageText: (String) -> Unit,
    onUpdateServerKey: (String) -> Unit,
    onSendPage: () -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paging Dr. Howard 🔐", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFD32F2F),
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.NotificationsActive, contentDescription = "Receive") },
                    label = { Text("My Device Setup") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Warning, contentDescription = "Send") },
                    label = { Text("Send Page") }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            if (selectedTab == 0) {
                RecipientSetupScreen(
                    uiState = uiState,
                    onSavePassphrase = onSavePassphrase,
                    onGrantDnd = onGrantDnd,
                    onTestAlarm = onTestAlarm,
                    onCopyToken = onCopyToken
                )
            } else {
                SendPageScreen(
                    uiState = uiState,
                    onUpdateTargetToken = onUpdateTargetToken,
                    onUpdateSenderName = onUpdateSenderName,
                    onUpdateMessageText = onUpdateMessageText,
                    onUpdateServerKey = onUpdateServerKey,
                    onSendPage = onSendPage
                )
            }
        }
    }
}

@Composable
fun RecipientSetupScreen(
    uiState: MainUiState,
    onSavePassphrase: (String) -> Unit,
    onGrantDnd: () -> Unit,
    onTestAlarm: () -> Unit,
    onCopyToken: () -> Unit
) {
    var keyInput by remember(uiState.familyPassphrase) { mutableStateOf(uiState.familyPassphrase) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Device Pager Setup", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            "Ensure Do Not Disturb access is enabled and configure your Family Security Key.",
            fontSize = 14.sp,
            color = Color.Gray,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        // DND Access Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (uiState.isDndAccessGranted) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (uiState.isDndAccessGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (uiState.isDndAccessGranted) Color(0xFF2E7D32) else Color(0xFFC62828),
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (uiState.isDndAccessGranted) "DND Override Enabled" else "DND Permission Needed",
                        fontWeight = FontWeight.Bold,
                        color = if (uiState.isDndAccessGranted) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                    Text(
                        text = if (uiState.isDndAccessGranted) "Phone will ring even during Do Not Disturb." else "Tap below to allow app to bypass DND mode.",
                        fontSize = 12.sp,
                        color = Color.DarkGray
                    )
                }
            }
        }

        if (!uiState.isDndAccessGranted) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onGrantDnd,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFC62828))
            ) {
                Text("Grant DND Access in Settings")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Security Key Configuration
        Text("Family Security Key 🔒", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("Both phones must share the same secret key to authenticate incoming emergency pages.", fontSize = 12.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = keyInput,
            onValueChange = { keyInput = it },
            label = { Text("Shared Secret Passphrase") },
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = { onSavePassphrase(keyInput) },
            modifier = Modifier.align(Alignment.End)
        ) {
            Text("Save Security Key")
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Device Token Card
        Text("Your Device Token", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("Share this token with family members so they can page this phone.", fontSize = 12.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = uiState.fcmToken,
                    fontSize = 12.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 4
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onCopyToken,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy Token")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Sound Test
        Text("Sound Test", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(
            onClick = onTestAlarm,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.NotificationsActive, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Test Emergency Alarm Sound")
        }
    }
}

@Composable
fun SendPageScreen(
    uiState: MainUiState,
    onUpdateTargetToken: (String) -> Unit,
    onUpdateSenderName: (String) -> Unit,
    onUpdateMessageText: (String) -> Unit,
    onUpdateServerKey: (String) -> Unit,
    onSendPage: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Send Secure Page", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Cryptographically signs & encrypts page payloads using your Family Security Key.", fontSize = 14.sp, color = Color.Gray)

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
            value = uiState.targetTokenInput,
            onValueChange = onUpdateTargetToken,
            label = { Text("Recipient FCM Token") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = uiState.senderNameInput,
            onValueChange = onUpdateSenderName,
            label = { Text("Your Name (Sender)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = uiState.messageTextInput,
            onValueChange = onUpdateMessageText,
            label = { Text("Alert Message") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = uiState.serverKeyInput,
            onValueChange = onUpdateServerKey,
            label = { Text("Firebase Server Key (Optional)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onSendPage,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
        ) {
            Text("SEND SECURE EMERGENCY PAGE", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
