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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pagingdrhoward.data.DefaultPagerRepository
import com.example.pagingdrhoward.data.PageLevel
import com.example.pagingdrhoward.data.PairedContact
import com.example.pagingdrhoward.network.PushSender
import com.example.pagingdrhoward.service.EmergencyPagerService
import com.example.pagingdrhoward.service.PushListenerService
import com.example.pagingdrhoward.util.AppUpdateManager
import com.example.pagingdrhoward.util.CryptoManager
import com.example.pagingdrhoward.util.DndHelper
import com.example.pagingdrhoward.viewmodel.MainUiState
import com.example.pagingdrhoward.viewmodel.MainViewModel

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences(DefaultPagerRepository.PREF_NAME, MODE_PRIVATE)
        val repository = DefaultPagerRepository(prefs)
        viewModel = MainViewModel(repository)

        // Initialize DND channel & start background push listener service
        DndHelper.createEmergencyNotificationChannel(this)
        startPushListenerService()

        // Check for app updates from GitHub releases
        val currentBuildNumber = try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            1001
        }

        AppUpdateManager.checkForUpdate(currentBuildNumber) { updateInfo ->
            if (updateInfo != null && updateInfo.hasUpdate) {
                runOnUiThread {
                    viewModel.setUpdateInfo(updateInfo)
                }
            }
        }

        setContent {
            MaterialTheme {
                MainPagerApp(
                    uiState = viewModel.uiState,
                    onUpdateMyName = { name -> viewModel.updateMyName(name) },
                    onImportPairingCode = { code -> viewModel.importPairingCode(code) },
                    onDeleteContact = { id -> viewModel.deleteContact(id) },
                    onSelectContact = { contact -> viewModel.selectContactForPage(contact) },
                    onGrantDnd = { DndHelper.openDndSettings(this) },
                    onTestAlarm = { triggerLocalTestPage() },
                    onCopyText = { label, text -> copyToClipboard(label, text) },
                    onPasteFromClipboard = { getClipboardText() },
                    onShareText = { title, text -> shareText(title, text) },
                    onSendPage = { contact, level -> sendRemotePage(contact, level) },
                    onInstallUpdate = { info ->
                        AppUpdateManager.downloadAndInstallUpdate(this, info.apkDownloadUrl, info.latestVersionName)
                        Toast.makeText(this, "Downloading update ${info.latestVersionName}...", Toast.LENGTH_SHORT).show()
                    }
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

    private fun startPushListenerService() {
        val serviceIntent = Intent(this, PushListenerService::class.java).apply {
            action = PushListenerService.ACTION_START_LISTENING
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            // Ignored
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

    private fun getClipboardText(): String? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            return clip.getItemAt(0).text?.toString()
        }
        return null
    }

    private fun shareText(title: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, title))
    }

    private fun sendRemotePage(contact: PairedContact, pageLevel: PageLevel) {
        val state = viewModel.uiState
        val peerPublicKey = if (contact.publicKeyBase64.isNotBlank()) {
            try { CryptoManager.publicKeyFromBase64(contact.publicKeyBase64) } catch (e: Exception) { null }
        } else null

        val prefs = getSharedPreferences(DefaultPagerRepository.PREF_NAME, MODE_PRIVATE)
        val repository = DefaultPagerRepository(prefs)

        PushSender.sendPage(
            targetTopicId = contact.topicId,
            senderName = state.myName,
            senderTopicId = state.myTopicId,
            senderPublicKeyBase64 = state.myPublicKeyBase64,
            senderPrivateKey = repository.getMyPrivateKey(),
            recipientPublicKey = peerPublicKey,
            pageLevel = pageLevel,
            messageText = if (pageLevel == PageLevel.HEY_LOOK) "Hey look! Check your phone when free." else "EMERGENCY: Urgent assistance needed!",
            onResult = { isSuccess, resultMsg ->
                runOnUiThread {
                    Toast.makeText(this, resultMsg, Toast.LENGTH_LONG).show()
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainPagerApp(
    uiState: MainUiState,
    onUpdateMyName: (String) -> Unit,
    onImportPairingCode: (String) -> Boolean,
    onDeleteContact: (String) -> Unit,
    onSelectContact: (PairedContact) -> Unit,
    onGrantDnd: () -> Unit,
    onTestAlarm: () -> Unit,
    onCopyText: (String, String) -> Unit,
    onPasteFromClipboard: () -> String?,
    onShareText: (String, String) -> Unit,
    onSendPage: (PairedContact, PageLevel) -> Unit,
    onInstallUpdate: (AppUpdateManager.UpdateInfo) -> Unit
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
        Column(modifier = Modifier.padding(paddingValues)) {
            // In-App Auto-Update Banner
            uiState.updateInfo?.let { update ->
                if (update.hasUpdate) {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFFE3F2FD))
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = Color(0xFF1976D2))
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("New Update Available: ${update.latestVersionName}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text("Tap to install latest build seamlessly.", fontSize = 11.sp, color = Color.DarkGray)
                            }
                            Button(
                                onClick = { onInstallUpdate(update) },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                            ) {
                                Text("Update", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (selectedTab == 0) {
                    FamilyContactsScreen(
                        uiState = uiState,
                        onImportPairingCode = onImportPairingCode,
                        onDeleteContact = onDeleteContact,
                        onPageContact = onSendPage,
                        onPasteFromClipboard = onPasteFromClipboard,
                        onGoToSetupTab = { selectedTab = 1 }
                    )
                } else {
                    RecipientSetupScreen(
                        uiState = uiState,
                        onUpdateMyName = onUpdateMyName,
                        onGrantDnd = onGrantDnd,
                        onTestAlarm = onTestAlarm,
                        onCopyText = onCopyText,
                        onShareText = onShareText
                    )
                }
            }
        }
    }
}

@Composable
fun FamilyContactsScreen(
    uiState: MainUiState,
    onImportPairingCode: (String) -> Boolean,
    onDeleteContact: (String) -> Unit,
    onPageContact: (PairedContact, PageLevel) -> Unit,
    onPasteFromClipboard: () -> String?,
    onGoToSetupTab: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pairingCodeInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Family Address Book", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Tap (1) Hey look! or (2) SOS to page your family members instantly.", fontSize = 14.sp, color = Color.Gray)

        Spacer(modifier = Modifier.height(16.dp))

        // Pair New Family Member Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("➕ Pair New Family Phone", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Paste your family member's Pairing Code from their device.", fontSize = 12.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = pairingCodeInput,
                    onValueChange = { pairingCodeInput = it },
                    label = { Text("Paste Family Pairing Code") },
                    trailingIcon = {
                        IconButton(onClick = {
                            val clip = onPasteFromClipboard()
                            if (!clip.isNullOrBlank()) {
                                pairingCodeInput = clip.trim()
                                Toast.makeText(context, "Pasted from clipboard!", Toast.LENGTH_SHORT).show()
                            } else {
                                Toast.makeText(context, "Clipboard is empty.", Toast.LENGTH_SHORT).show()
                            }
                        }) {
                            Icon(Icons.Default.ContentPaste, contentDescription = "Paste from clipboard")
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        val trimmed = pairingCodeInput.trim()
                        if (trimmed.isBlank()) {
                            Toast.makeText(context, "Please paste or enter a pairing code first! (From the other phone's Setup tab)", Toast.LENGTH_LONG).show()
                        } else if (onImportPairingCode(trimmed)) {
                            pairingCodeInput = ""
                            Toast.makeText(context, "Device successfully paired!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Invalid pairing code. Make sure to copy the full code from the other device.", Toast.LENGTH_LONG).show()
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("📱 How to Pair with Another Phone:", fontWeight = FontWeight.Bold, color = Color(0xFFF57F17))
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("1. Install Paging Dr. Howard on the other family member's phone.", fontSize = 13.sp)
                    Text("2. On their phone, tap 'My Device Setup' tab and tap 'Copy Pairing Code'.", fontSize = 13.sp)
                    Text("3. Paste that code into the box above and tap 'Add & Pair Phone'.", fontSize = 13.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedButton(
                        onClick = onGoToSetupTab,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.QrCode, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("View My Own Pairing Code")
                    }
                }
            }
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
    onUpdateMyName: (String) -> Unit,
    onGrantDnd: () -> Unit,
    onTestAlarm: () -> Unit,
    onCopyText: (String, String) -> Unit,
    onShareText: (String, String) -> Unit
) {
    var nameInput by remember(uiState.myName) { mutableStateOf(uiState.myName) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text("Device & Pairing Setup", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text("Configure your name, DND access, and share your pairing code with family.", fontSize = 14.sp, color = Color.Gray)

        Spacer(modifier = Modifier.height(16.dp))

        // Push Service Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32), modifier = Modifier.size(28.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Push Listener: Active 🟢", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                    Text("Ready for instant emergency pages (Zero-Server / Zero-Firebase).", fontSize = 12.sp, color = Color.DarkGray)
                }
            }
        }

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
                    maxLines = 4
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(
                        onClick = { onShareText("Paging Dr. Howard Pairing Code", uiState.myPairingCode) }
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { onCopyText("Pairing Code", uiState.myPairingCode) }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy Code")
                    }
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
