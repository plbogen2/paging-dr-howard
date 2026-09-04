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
import androidx.compose.material.icons.filled.*
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
import com.example.pagingdrhoward.data.PageLevel
import com.example.pagingdrhoward.data.PairedContact
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

        val prefs = getSharedPreferences(DefaultPagerRepository.PREF_NAME, MODE_PRIVATE)
        val repository = DefaultPagerRepository(prefs)
        viewModel = MainViewModel(repository)

        try {
            DndHelper.createEmergencyNotificationChannel(this)
        } catch (e: Throwable) {
            // Ignore channel creation errors on customized OS
        }

        try {
            if (repository.getFcmToken() == null) {
                FirebaseMessaging.getInstance().token
                    .addOnCompleteListener { task ->
                        try {
                            if (task.isSuccessful && task.result != null) {
                                viewModel.updateFcmToken(task.result)
                            } else {
                                val err = task.exception?.localizedMessage ?: "FCM unavailable (requires Google Play Services)"
                                viewModel.setTokenError(err)
                            }
                        } catch (e: Throwable) {
                            viewModel.setTokenError("Google Play Services / FCM not available on this device.")
                        }
                    }
                    .addOnFailureListener { e ->
                        viewModel.setTokenError("FCM Error: ${e.localizedMessage ?: "Google Play Services not installed"}")
                    }
            }
        } catch (e: Throwable) {
            viewModel.setTokenError("Push notifications require Google Play Services (not present on standard Fire OS).")
        }

        setContent {
            MaterialTheme {
                MainPagerApp(
                    uiState = viewModel.uiState,
                    onSavePassphrase = { passphrase -> viewModel.saveFamilyPassphrase(passphrase) },
                    onUpdateMyName = { name -> viewModel.updateMyName(name) },
                    onImportPairingCode = { code -> viewModel.importPairingCode(code) },
                    onDeleteContact = { id -> viewModel.deleteContact(id) },
                    onSelectContact = { contact -> viewModel.selectContactForPage(contact) },
                    onGrantDnd = { DndHelper.openDndSettings(this) },
                    onTestAlarm = { triggerLocalTestPage() },
                    onCopyText = { label, text -> copyToClipboard(label, text) },
                    onSendPage = { targetToken, level -> sendRemotePage(targetToken, level) }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            viewModel.setDndGranted(DndHelper.hasDndAccess(this))
        } catch (e: Throwable) {
            viewModel.setDndGranted(false)
        }
    }

    private fun triggerLocalTestPage() {
        val intent = Intent(this, EmergencyPagerService::class.java).apply {
            action = EmergencyPagerService.ACTION_START_ALARM
            putExtra("EXTRA_SENDER", "Self-Test")
            putExtra("EXTRA_MESSAGE", "This is a test of the emergency alarm sound!")
            putExtra("EXTRA_LEVEL", PageLevel.SOS.code)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "$label copied to clipboard!", Toast.LENGTH_SHORT).show()
    }

    private fun sendRemotePage(targetToken: String, pageLevel: PageLevel) {
        if (targetToken.isBlank()) {
            Toast.makeText(this, "Please select or enter recipient token", Toast.LENGTH_SHORT).show()
            return
        }

        val state = viewModel.uiState
        FcmSender.sendSecurePage(
            targetToken = targetToken,
            senderName = state.senderNameInput,
            messageText = if (pageLevel == PageLevel.HEY_LOOK) "Hey look! Check your phone when free." else state.messageTextInput,
            familyPassphrase = state.familyPassphrase,
            pageLevel = pageLevel,
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
    onUpdateMyName: (String) -> Unit,
    onImportPairingCode: (String) -> Boolean,
    onDeleteContact: (String) -> Unit,
    onSelectContact: (PairedContact) -> Unit,
    onGrantDnd: () -> Unit,
    onTestAlarm: () -> Unit,
    onCopyText: (String, String) -> Unit,
    onSendPage: (String, PageLevel) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paging Dr. Howard 📟", fontWeight = FontWeight.Bold) },
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
                    icon = { Icon(Icons.Default.People, contentDescription = "Family Contacts") },
                    label = { Text("Family Contacts") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Setup") },
                    label = { Text("My Device Setup") }
                )
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            if (selectedTab == 0) {
                FamilyContactsScreen(
                    uiState = uiState,
                    onImportPairingCode = onImportPairingCode,
                    onDeleteContact = onDeleteContact,
                    onPageContact = { contact, level ->
                        onSelectContact(contact)
                        onSendPage(contact.fcmToken, level)
                    }
                )
            } else {
                RecipientSetupScreen(
                    uiState = uiState,
                    onSavePassphrase = onSavePassphrase,
                    onUpdateMyName = onUpdateMyName,
                    onGrantDnd = onGrantDnd,
                    onTestAlarm = onTestAlarm,
                    onCopyText = onCopyText
                )
            }
        }
    }
}

@Composable
fun FamilyContactsScreen(
    uiState: MainUiState,
    onImportPairingCode: (String) -> Boolean,
    onDeleteContact: (String) -> Unit,
    onPageContact: (PairedContact, PageLevel) -> Unit
) {
    var pairingCodeInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Family Contacts Address Book", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Select a page level: (1) Hey look! or (2) SOS Emergency", fontSize = 14.sp, color = Color.Gray)

        Spacer(modifier = Modifier.height(16.dp))

        // Pair New Family Member Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("➕ Pair New Family Phone", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Paste your family member's Pairing Code to add them bidirectionally.", fontSize = 12.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = pairingCodeInput,
                    onValueChange = { pairingCodeInput = it },
                    label = { Text("Paste Family Pairing Code") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (onImportPairingCode(pairingCodeInput)) {
                            pairingCodeInput = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add & Pair Phone")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text("Paired Family Members (${uiState.pairedContacts.size})", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.pairedContacts.isEmpty()) {
            Text("No family contacts added yet. Add a phone above or share your pairing code in setup!", color = Color.Gray, fontSize = 14.sp)
        } else {
            uiState.pairedContacts.forEach { contact ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(contact.name, fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color(0xFFE65100))
                            IconButton(onClick = { onDeleteContact(contact.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray)
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Level 1: Hey look!
                            Button(
                                onClick = { onPageContact(contact, PageLevel.HEY_LOOK) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(PageLevel.HEY_LOOK.colorHex)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Hey Look!", fontSize = 13.sp)
                            }

                            // Level 2: SOS
                            Button(
                                onClick = { onPageContact(contact, PageLevel.SOS) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(PageLevel.SOS.colorHex)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("SOS", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RecipientSetupScreen(
    uiState: MainUiState,
    onSavePassphrase: (String) -> Unit,
    onUpdateMyName: (String) -> Unit,
    onGrantDnd: () -> Unit,
    onTestAlarm: () -> Unit,
    onCopyText: (String, String) -> Unit
) {
    var nameInput by remember(uiState.myName) { mutableStateOf(uiState.myName) }
    var keyInput by remember(uiState.familyPassphrase) { mutableStateOf(uiState.familyPassphrase) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Device & Pairing Setup", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Configure your name, DND access, and share your pairing code with family.", fontSize = 14.sp, color = Color.Gray)

        Spacer(modifier = Modifier.height(16.dp))

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

        OutlinedTextField(
            value = nameInput,
            onValueChange = {
                nameInput = it
                onUpdateMyName(it)
            },
            label = { Text("Your Display Name (e.g. Dad or Daughter)") },
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text("Family Security Key 🔒", fontWeight = FontWeight.Bold, fontSize = 16.sp)
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

        Text("Share Your Pairing Code 📲", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        Text("Send this code to your family member so they can add your phone bidirectionally.", fontSize = 12.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(8.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = uiState.myPairingCode.ifEmpty { "Generating pairing code..." },
                    fontSize = 11.sp,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 3
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onCopyText("Pairing Code", uiState.myPairingCode) },
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Copy Pairing Code")
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

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
