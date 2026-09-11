package com.technomagick.pagingdrhoward

import android.content.ClipData
import android.content.ClipboardManager
import android.content.SharedPreferences
import com.technomagick.pagingdrhoward.util.LogHelper
import android.content.Context
import android.content.Intent

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.technomagick.pagingdrhoward.data.DefaultPagerRepository
import com.technomagick.pagingdrhoward.data.PageLevel
import com.technomagick.pagingdrhoward.data.PairedContact
import com.technomagick.pagingdrhoward.network.PushSender
import com.technomagick.pagingdrhoward.service.EmergencyPagerService
import com.technomagick.pagingdrhoward.service.PushListenerService
import com.technomagick.pagingdrhoward.util.AppUpdateManager
import com.technomagick.pagingdrhoward.util.CryptoManager
import com.technomagick.pagingdrhoward.util.DndHelper
import com.technomagick.pagingdrhoward.util.QrCodeGenerator
import com.technomagick.pagingdrhoward.viewmodel.MainUiState
import com.technomagick.pagingdrhoward.viewmodel.MainViewModel
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

// ─── Semantic safety colors — intentionally NOT from the dynamic palette ───────
private val SosRed    = Color(0xFFD32F2F)
private val HeyLookAmber = Color(0xFFF57C00)
private val SosRedContainer    = Color(0xFFFFEBEE)
private val HeyLookAmberContainer = Color(0xFFFFF3E0)

// ─── Material You Theme ────────────────────────────────────────────────────────
@Composable
fun PagingDrHowardTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val darkTheme = isSystemInDarkTheme()

    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context)
            else dynamicLightColorScheme(context)
        }
        darkTheme -> darkColorScheme(
            primary = Color(0xFFEF5350),
            onPrimary = Color.White,
            primaryContainer = Color(0xFF8B0000),
            secondary = Color(0xFFFFB74D),
        )
        else -> lightColorScheme(
            primary = Color(0xFFD32F2F),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFFFCDD2),
            secondary = Color(0xFFF57C00),
        )
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel
    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == DefaultPagerRepository.KEY_PAIRED_CONTACTS ||
            key == DefaultPagerRepository.KEY_LAST_ACK_UPDATE ||
            (key != null && key.startsWith(DefaultPagerRepository.KEY_PREFIX_LAST_ACK))) {
            runOnUiThread {
                viewModel.loadSettings()
            }
        }
    }

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            val code = result.contents.trim()
            if (viewModel.importPairingCode(code)) {
                Toast.makeText(this, "Device successfully paired!", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Invalid QR code. Make sure to scan the correct pairing screen.", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = MainViewModel(application)
        viewModel.loadSettings()

        val prefs = getSharedPreferences(DefaultPagerRepository.PREF_NAME, Context.MODE_PRIVATE)
        prefs.registerOnSharedPreferenceChangeListener(prefListener)

        startPushListenerService()

        setContent {
            PagingDrHowardTheme {
                MainPagerApp(
                    uiState = viewModel.uiState,
                    onUpdateMyName = { name -> viewModel.updateMyName(name) },
                    onUpdateRelayServerUrl = { url ->
                        viewModel.updateRelayServerUrl(url)
                    },
                    onImportPairingCode = { code -> viewModel.importPairingCode(code) },
                    onDeleteContact = { id -> viewModel.deleteContact(id) },
                    onSelectContact = { contact -> viewModel.selectContactForPage(contact) },
                    onGrantDnd = { DndHelper.openDndSettings(this) },
                    onTestAlarm = { triggerLocalTestPage() },
                    onTestHeyLook = { triggerLocalTestPage(PageLevel.HEY_LOOK) },
                    onToggleNaviSound = { enabled -> viewModel.setNaviSoundEnabled(enabled) },
                    onCopyText = { label, text -> copyToClipboard(label, text) },
                    onPasteFromClipboard = { getClipboardText() },
                    onShareText = { title, text -> shareText(title, text) },
                    onScanQrCode = { launchQrScanner() },
                    onSendPage = { contact, level, customMessage -> sendRemotePage(contact, level, customMessage) },
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
            startPushListenerService()
            viewModel.loadSettings()
            viewModel.setDndGranted(DndHelper.hasDndAccess(this))
        } catch (e: Throwable) {
            viewModel.setDndGranted(false)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        val prefs = getSharedPreferences(DefaultPagerRepository.PREF_NAME, Context.MODE_PRIVATE)
        prefs.unregisterOnSharedPreferenceChangeListener(prefListener)
    }

    private fun startPushListenerService() {
        try {
            val intent = Intent(this, PushListenerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            LogHelper.e("MainActivity", "Failed to start push listener service", e)
        }
    }

    private fun triggerLocalTestPage(level: PageLevel = PageLevel.SOS) {
        val prefs = getSharedPreferences(DefaultPagerRepository.PREF_NAME, Context.MODE_PRIVATE)
        val repository = DefaultPagerRepository(prefs)
        val intent = Intent(this, EmergencyPagerService::class.java).apply {
            action = EmergencyPagerService.ACTION_START_ALARM
            putExtra("EXTRA_SENDER", "Test Page")
            putExtra("EXTRA_MESSAGE", if (level == PageLevel.HEY_LOOK) "Hey Look! 👀 Test" else "URGENT: This is a test alarm!")
            putExtra("EXTRA_LEVEL", level.code)
            putExtra("EXTRA_TIMESTAMP", System.currentTimeMillis())
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun launchQrScanner() {
        val options = ScanOptions().apply {
            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            setPrompt("Scan the family member's pairing QR code")
            setCameraId(0)
            setBeepEnabled(true)
            setBarcodeImageEnabled(false)
        }
        qrScanLauncher.launch(options)
    }

    private fun sendRemotePage(contact: PairedContact, level: PageLevel, customMessage: String? = null) {
        val prefs = getSharedPreferences(DefaultPagerRepository.PREF_NAME, Context.MODE_PRIVATE)
        val repository = DefaultPagerRepository(prefs)

        val senderName = repository.getMyName()
        val myTopicId = repository.getMyTopicId()
        val myPublicKeyBase64 = repository.getMyPublicKeyBase64()
        val myPrivateKey = repository.getMyPrivateKey()
        val serverUrl = repository.getRelayServerUrl()

        val peerPublicKey = if (contact.publicKeyBase64.isNotBlank()) {
            try { CryptoManager.publicKeyFromBase64(contact.publicKeyBase64) } catch (e: Exception) { null }
        } else null

        val message = customMessage?.trim()?.takeIf { it.isNotBlank() }
            ?: if (level == PageLevel.SOS) "URGENT: Please respond immediately!" else "Hey look! 👀"

        viewModel.startCooldown(contact.topicId)

        Thread {
            try {
                PushSender.sendAlert(
                    targetTopicId = contact.topicId,
                    senderName = senderName,
                    myTopicId = myTopicId,
                    myPublicKeyBase64 = myPublicKeyBase64,
                    myPrivateKey = myPrivateKey,
                    peerPublicKey = peerPublicKey,
                    level = level,
                    message = message,
                    serverUrl = serverUrl
                )
                LogHelper.i("MainActivity", "Page sent to ${contact.name} (${level.code})")
            } catch (e: Exception) {
                LogHelper.e("MainActivity", "Failed to send page to ${contact.name}", e)
            }
        }.start()
    }

    private fun copyToClipboard(label: String, text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(label, text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "$label copied!", Toast.LENGTH_SHORT).show()
    }

    private fun getClipboardText(): String? {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    }

    private fun shareText(title: String, text: String) {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(intent, "Share via"))
    }
}

// ─── Shell ────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainPagerApp(
    uiState: MainUiState,
    onUpdateMyName: (String) -> Unit,
    onUpdateRelayServerUrl: (String) -> Unit,
    onImportPairingCode: (String) -> Boolean,
    onDeleteContact: (String) -> Unit,
    onSelectContact: (PairedContact) -> Unit,
    onGrantDnd: () -> Unit,
    onTestAlarm: () -> Unit,
    onTestHeyLook: () -> Unit,
    onToggleNaviSound: (Boolean) -> Unit,
    onCopyText: (String, String) -> Unit,
    onPasteFromClipboard: () -> String?,
    onShareText: (String, String) -> Unit,
    onScanQrCode: () -> Unit,
    onSendPage: (PairedContact, PageLevel, String?) -> Unit,
    onInstallUpdate: (AppUpdateManager.UpdateInfo) -> Unit
) {
    var selectedTab by remember { mutableStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Paging Dr. Howard 📟",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.People, contentDescription = null) },
                    label = { Text("Contacts") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text("My Setup") }
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(modifier = Modifier.padding(paddingValues)) {

            // Update banner
            uiState.updateInfo?.let { update ->
                if (update.hasUpdate) {
                    ElevatedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.elevatedCardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.SystemUpdate,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Update ${update.latestVersionName} available",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    "Tap to install seamlessly",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                            FilledTonalButton(
                                onClick = { onInstallUpdate(update) }
                            ) { Text("Update") }
                        }
                    }
                }
            }

            Box(modifier = Modifier.fillMaxSize()) {
                if (selectedTab == 0) {
                    FamilyContactsScreen(
                        uiState = uiState,
                        onDeleteContact = onDeleteContact,
                        onPageContact = onSendPage,
                        onGoToSetupTab = { selectedTab = 1 }
                    )
                } else {
                    RecipientSetupScreen(
                        uiState = uiState,
                        onUpdateMyName = onUpdateMyName,
                        onUpdateRelayServerUrl = onUpdateRelayServerUrl,
                        onImportPairingCode = onImportPairingCode,
                        onPasteFromClipboard = onPasteFromClipboard,
                        onScanQrCode = onScanQrCode,
                        onGrantDnd = onGrantDnd,
                        onTestAlarm = onTestAlarm,
                        onTestHeyLook = onTestHeyLook,
                        onToggleNaviSound = onToggleNaviSound,
                        onCopyText = onCopyText,
                        onShareText = onShareText
                    )
                }
            }
        }
    }
}

// ─── Contacts Tab ─────────────────────────────────────────────────────────────

@Composable
fun FamilyContactsScreen(
    uiState: MainUiState,
    onDeleteContact: (String) -> Unit,
    onPageContact: (PairedContact, PageLevel, String?) -> Unit,
    onGoToSetupTab: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        Text(
            "Contacts",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "Page your family instantly with Hey Look or SOS.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(20.dp))

        if (uiState.pairedContacts.isEmpty()) {
            // Empty state
            Spacer(modifier = Modifier.height(48.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(
                            MaterialTheme.colorScheme.secondaryContainer,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.GroupAdd,
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    "No contacts yet",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Pair with a family member's phone in the\nMy Setup tab to get started.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(24.dp))
                Button(
                    onClick = onGoToSetupTab,
                    shape = RoundedCornerShape(50)
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Go to My Setup", fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            uiState.pairedContacts.forEach { contact ->
                ContactCard(
                    contact = contact,
                    cooldown = uiState.cooldowns[contact.topicId] ?: 0,
                    isRecentlyAcked = run {
                        val lastAck = uiState.lastAckedContacts[contact.topicId] ?: 0L
                        (System.currentTimeMillis() - lastAck) < 600_000L
                    },
                    onDelete = { onDeleteContact(contact.id) },
                    onPage = { level, msg -> onPageContact(contact, level, msg) }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun ContactCard(
    contact: PairedContact,
    cooldown: Int,
    isRecentlyAcked: Boolean,
    onDelete: () -> Unit,
    onPage: (PageLevel, String?) -> Unit
) {
    val isCoolingDown = cooldown > 0
    var customMessage by remember(contact.topicId) { mutableStateOf("") }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {

            // Header row: avatar + name + delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Avatar circle with initials
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            MaterialTheme.colorScheme.primaryContainer,
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = contact.name.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = contact.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (isRecentlyAcked) {
                        Text(
                            "✔ Page acknowledged",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF2E7D32),
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Remove contact",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Optional message input
            OutlinedTextField(
                value = customMessage,
                onValueChange = { customMessage = it },
                placeholder = {
                    Text(
                        "Optional message…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                    focusedBorderColor = MaterialTheme.colorScheme.primary
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Action buttons — pill shaped, semantic colors
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Hey Look — amber
                Button(
                    onClick = { onPage(PageLevel.HEY_LOOK, customMessage) },
                    enabled = !isCoolingDown,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = HeyLookAmber,
                        contentColor = Color.White,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (isCoolingDown) "${cooldown}s" else "Hey Look!",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // SOS — red
                Button(
                    onClick = { onPage(PageLevel.SOS, customMessage) },
                    enabled = !isCoolingDown,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SosRed,
                        contentColor = Color.White,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                        disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (isCoolingDown) "${cooldown}s" else "SOS",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

// ─── Setup Tab ────────────────────────────────────────────────────────────────

@Composable
fun RecipientSetupScreen(
    uiState: MainUiState,
    onUpdateMyName: (String) -> Unit,
    onUpdateRelayServerUrl: (String) -> Unit,
    onImportPairingCode: (String) -> Boolean,
    onPasteFromClipboard: () -> String?,
    onScanQrCode: () -> Unit,
    onGrantDnd: () -> Unit,
    onTestAlarm: () -> Unit,
    onTestHeyLook: () -> Unit,
    onToggleNaviSound: (Boolean) -> Unit,
    onCopyText: (String, String) -> Unit,
    onShareText: (String, String) -> Unit
) {
    val context = LocalContext.current
    var nameInput by remember(uiState.myName) { mutableStateOf(uiState.myName) }
    var serverInput by remember(uiState.relayServerUrl) { mutableStateOf(uiState.relayServerUrl) }
    var pairingCodeInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(20.dp))

        Text(
            "My Setup",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            "Configure this device and pair with family.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ── Status chips row ──────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Push active
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier.wrapContentWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Circle,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(8.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "Push Active",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            // DND status chip
            val dndOk = uiState.isDndAccessGranted
            Surface(
                shape = RoundedCornerShape(50),
                color = if (dndOk) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier.wrapContentWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        if (dndOk) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (dndOk) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (dndOk) "DND Enabled" else "DND Needed",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (dndOk) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }

        if (!uiState.isDndAccessGranted) {
            Spacer(modifier = Modifier.height(12.dp))
            Button(
                onClick = onGrantDnd,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Grant DND Access", fontWeight = FontWeight.SemiBold)
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Display name ──────────────────────────────────────────────────────
        SetupSection(title = "Your Name") {
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it; onUpdateMyName(it) },
                label = { Text("Display name") },
                placeholder = { Text("e.g. Dad, Mom, Daughter…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                leadingIcon = {
                    Icon(Icons.Default.Person, contentDescription = null)
                }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Pair a device ─────────────────────────────────────────────────────
        SetupSection(title = "Pair a Device") {
            Button(
                onClick = onScanQrCode,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scan QR Code", fontWeight = FontWeight.SemiBold)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    " or ",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = pairingCodeInput,
                onValueChange = { pairingCodeInput = it },
                label = { Text("Paste pairing code") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    IconButton(onClick = {
                        val clip = onPasteFromClipboard()
                        if (!clip.isNullOrBlank()) {
                            pairingCodeInput = clip.trim()
                            Toast.makeText(context, "Pasted!", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "Clipboard is empty.", Toast.LENGTH_SHORT).show()
                        }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste")
                    }
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            FilledTonalButton(
                onClick = {
                    val trimmed = pairingCodeInput.trim()
                    when {
                        trimmed.isBlank() -> Toast.makeText(context, "Enter a pairing code first.", Toast.LENGTH_LONG).show()
                        onImportPairingCode(trimmed) -> {
                            pairingCodeInput = ""
                            Toast.makeText(context, "Device paired!", Toast.LENGTH_SHORT).show()
                        }
                        else -> Toast.makeText(context, "Invalid code — copy the full code from the other device.", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50)
            ) {
                Icon(Icons.Default.PersonAdd, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add via Text Code")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── QR code ───────────────────────────────────────────────────────────
        SetupSection(title = "Your Pairing QR Code") {
            Text(
                "Show this to another phone to pair with you.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))

            val qrBitmap = remember(uiState.myPairingCode) {
                if (uiState.myPairingCode.isNotBlank())
                    QrCodeGenerator.generateQrBitmap(uiState.myPairingCode, 512)?.asImageBitmap()
                else null
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                qrBitmap?.let { bitmap ->
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .background(Color.White, RoundedCornerShape(16.dp))
                            .padding(12.dp)
                    ) {
                        Image(
                            bitmap = bitmap,
                            contentDescription = "My Pairing QR Code",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } ?: Text(
                    "Generating…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { onShareText("Paging Dr. Howard Pairing Code", uiState.myPairingCode) },
                        shape = RoundedCornerShape(50)
                    ) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share")
                    }
                    Button(
                        onClick = { onCopyText("Pairing Code", uiState.myPairingCode) },
                        shape = RoundedCornerShape(50)
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Copy")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Navi sound ────────────────────────────────────────────────────────
        SetupSection(title = "Notification Sound") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "🧚 Navi \"Hey Look!\" sound",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        "Play Navi's voice clip on Hey Look pages. Off = system sound.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = uiState.isNaviSoundEnabled,
                    onCheckedChange = onToggleNaviSound
                )
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onTestHeyLook,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(50)
                ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Test Hey Look!", style = MaterialTheme.typography.labelMedium)
                }
                OutlinedButton(
                    onClick = onTestAlarm,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = SosRed),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SosRed)
                ) {
                    Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Test SOS", style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Relay server ──────────────────────────────────────────────────────
        SetupSection(title = "Relay Server") {
            OutlinedTextField(
                value = serverInput,
                onValueChange = { serverInput = it; onUpdateRelayServerUrl(it) },
                label = { Text("Server URL") },
                placeholder = { Text("https://your-relay-server.com/") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                leadingIcon = { Icon(Icons.Default.Cloud, contentDescription = null) }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── About ─────────────────────────────────────────────────────────────
        SetupSection(title = "About") {
            InfoRow("Version", uiState.appVersion)
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            InfoRow(
                label = "Device ID",
                value = uiState.myTopicId.take(16) + "…",
                trailingAction = {
                    IconButton(
                        onClick = { onCopyText("Device Topic ID", uiState.myTopicId) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = "Copy",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            )
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )
            InfoRow("Protocol", "SSE + ECDSA P-256")

            Spacer(modifier = Modifier.height(12.dp))

            val monitorUrl = "${uiState.relayServerUrl.trimEnd('/')}/${uiState.myTopicId}"
            val ctx = LocalContext.current
            OutlinedButton(
                onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(monitorUrl)))
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(50)
            ) {
                Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Open Push Web Monitor", style = MaterialTheme.typography.labelMedium)
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}

// ─── Reusable components ──────────────────────────────────────────────────────

@Composable
fun SetupSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(bottom = 10.dp)
    )
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    trailingAction: @Composable (() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            trailingAction?.invoke()
        }
    }
}
