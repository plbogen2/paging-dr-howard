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
import androidx.compose.foundation.background
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
import com.example.pagingdrhoward.network.FcmSender
import com.example.pagingdrhoward.service.EmergencyPagerService
import com.example.pagingdrhoward.util.DndHelper
import com.google.firebase.messaging.FirebaseMessaging

class MainActivity : ComponentActivity() {

    private var fcmTokenState = mutableStateOf("Fetching token...")
    private var isDndAccessGranted = mutableStateOf(false)
    private var familyPassphraseState = mutableStateOf("")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Register DND-bypassing notification channel
        DndHelper.createEmergencyNotificationChannel(this)

        val prefs = getSharedPreferences("pager_prefs", MODE_PRIVATE)
        familyPassphraseState.value = prefs.getString("family_passphrase", "") ?: ""

        val savedToken = prefs.getString("fcm_token", null)
        if (savedToken != null) {
            fcmTokenState.value = savedToken
        } else {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val token = task.result
                    fcmTokenState.value = token
                    prefs.edit().putString("fcm_token", token).apply()
                } else {
                    fcmTokenState.value = "Failed to fetch token: ${task.exception?.message}"
                }
            }
        }

        setContent {
            MaterialTheme {
                MainPagerApp(
                    fcmToken = fcmTokenState.value,
                    isDndGranted = isDndAccessGranted.value,
                    passphrase = familyPassphraseState.value,
                    onSavePassphrase = { newPassphrase ->
                        prefs.edit().putString("family_passphrase", newPassphrase).apply()
                        familyPassphraseState.value = newPassphrase
                        Toast.makeText(this, "Family Security Key Saved!", Toast.LENGTH_SHORT).show()
                    },
                    onGrantDnd = { DndHelper.openDndSettings(this) },
                    onTestAlarm = { triggerLocalTestPage() },
                    onCopyToken = { copyToClipboard(fcmTokenState.value) },
                    onSendPage = { targetToken, senderName, message, passphrase, serverKey ->
                        sendRemotePage(targetToken, senderName, message, passphrase, serverKey)
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        isDndAccessGranted.value = DndHelper.hasDndAccess(this)
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

    private fun sendRemotePage(
        targetToken: String,
        senderName: String,
        message: String,
        passphrase: String,
        serverKey: String
    ) {
        if (targetToken.isBlank()) {
            Toast.makeText(this, "Please enter recipient device token", Toast.LENGTH_SHORT).show()
            return
        }
        FcmSender.sendSecurePage(targetToken, senderName, message, passphrase, serverKey) { success, resultMsg ->
            runOnUiThread {
                Toast.makeText(this, resultMsg, Toast.LENGTH_LONG).show()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainPagerApp(
    fcmToken: String,
    isDndGranted: Boolean,
    passphrase: String,
    onSavePassphrase: (String) -> Unit,
    onGrantDnd: () -> Unit,
    onTestAlarm: () -> Unit,
    onCopyToken: () -> Unit,
    onSendPage: (targetToken: String, senderName: String, message: String, passphrase: String, serverKey: String) -> Unit
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
                    fcmToken = fcmToken,
                    isDndGranted = isDndGranted,
                    passphrase = passphrase,
                    onSavePassphrase = onSavePassphrase,
                    onGrantDnd = onGrantDnd,
                    onTestAlarm = onTestAlarm,
                    onCopyToken = onCopyToken
                )
            } else {
                SendPageScreen(
                    savedPassphrase = passphrase,
                    onSendPage = onSendPage
                )
            }
        }
    }
}

@Composable
fun RecipientSetupScreen(
    fcmToken: String,
    isDndGranted: Boolean,
    passphrase: String,
    onSavePassphrase: (String) -> Unit,
    onGrantDnd: () -> Unit,
    onTestAlarm: () -> Unit,
    onCopyToken: () -> Unit
) {
    var keyInput by remember(passphrase) { mutableStateOf(passphrase) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "Device Pager Setup",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "Ensure Do Not Disturb access is enabled and configure your Family Security Key.",
            fontSize = 14.sp,
            color = Color.Gray,
            modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )

        // DND Access Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isDndGranted) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (isDndGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isDndGranted) Color(0xFF2E7D32) else Color(0xFFC62828),
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isDndGranted) "DND Override Enabled" else "DND Permission Needed",
                        fontWeight = FontWeight.Bold,
                        color = if (isDndGranted) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                    Text(
                        text = if (isDndGranted) "Phone will ring even during Do Not Disturb." else "Tap below to allow app to bypass DND mode.",
                        fontSize = 12.sp,
                        color = Color.DarkGray
                    )
                }
            }
        }

        if (!isDndGranted) {
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
                    text = fcmToken,
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
    savedPassphrase: String,
    onSendPage: (targetToken: String, senderName: String, message: String, passphrase: String, serverKey: String) -> Unit
) {
    var targetToken by remember { mutableStateOf("") }
    var senderName by remember { mutableStateOf("Dad") }
    var messageText by remember { mutableStateOf("URGENT: Please call me ASAP!") }
    var passphraseInput by remember(savedPassphrase) { mutableStateOf(savedPassphrase) }
    var serverKey by remember { mutableStateOf("") }

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
            value = targetToken,
            onValueChange = { targetToken = it },
            label = { Text("Recipient FCM Token") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = senderName,
            onValueChange = { senderName = it },
            label = { Text("Your Name (Sender)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = messageText,
            onValueChange = { messageText = it },
            label = { Text("Alert Message") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = passphraseInput,
            onValueChange = { passphraseInput = it },
            label = { Text("Family Security Key") },
            leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = serverKey,
            onValueChange = { serverKey = it },
            label = { Text("Firebase Server Key (Optional)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { onSendPage(targetToken, senderName, messageText, passphraseInput, serverKey) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
        ) {
            Text("SEND SECURE EMERGENCY PAGE", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}
