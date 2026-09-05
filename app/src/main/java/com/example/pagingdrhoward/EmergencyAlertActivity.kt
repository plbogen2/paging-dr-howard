package com.example.pagingdrhoward

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.pagingdrhoward.data.PageLevel
import com.example.pagingdrhoward.service.EmergencyPagerService

class EmergencyAlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        val senderName = intent.getStringExtra("EXTRA_SENDER") ?: "Family Member"
        val senderTopic = intent.getStringExtra("EXTRA_SENDER_TOPIC") ?: ""
        val messageText = intent.getStringExtra("EXTRA_MESSAGE") ?: "URGENT: Please respond immediately!"
        val levelCode = intent.getStringExtra("EXTRA_LEVEL")
        val pageLevel = PageLevel.fromCode(levelCode)

        setContent {
            EmergencyAlertScreen(
                pageLevel = pageLevel,
                senderName = senderName,
                messageText = messageText,
                onDismiss = { dismissPage(senderTopic) }
            )
        }
    }

    private fun dismissPage(senderTopic: String) {
        val stopServiceIntent = Intent(this, EmergencyPagerService::class.java).apply {
            action = EmergencyPagerService.ACTION_STOP_ALARM
        }
        startService(stopServiceIntent)

        // Send acknowledgment receipt back to sender's topic
        if (senderTopic.isNotBlank()) {
            val prefs = getSharedPreferences(com.example.pagingdrhoward.data.DefaultPagerRepository.PREF_NAME, MODE_PRIVATE)
            val repository = com.example.pagingdrhoward.data.DefaultPagerRepository(prefs)
            val contact = repository.getPairedContacts().find { it.topicId == senderTopic }
            val peerPublicKey = if (contact != null && contact.publicKeyBase64.isNotBlank()) {
                try { com.example.pagingdrhoward.util.CryptoManager.publicKeyFromBase64(contact.publicKeyBase64) } catch (e: Exception) { null }
            } else null

            com.example.pagingdrhoward.network.PushSender.sendAlertAck(
                targetTopicId = senderTopic,
                acknowledgerName = repository.getMyName(),
                myTopicId = repository.getMyTopicId(),
                myPublicKeyBase64 = repository.getMyPublicKeyBase64(),
                myPrivateKey = repository.getMyPrivateKey(),
                peerPublicKey = peerPublicKey,
                serverUrl = repository.getRelayServerUrl()
            )
        }

        finish()
    }

    companion object {
        fun createIntent(context: Context, sender: String?, senderTopic: String?, message: String?, level: PageLevel): Intent {
            return Intent(context, EmergencyAlertActivity::class.java).apply {
                putExtra("EXTRA_SENDER", sender)
                putExtra("EXTRA_SENDER_TOPIC", senderTopic)
                putExtra("EXTRA_MESSAGE", message)
                putExtra("EXTRA_LEVEL", level.code)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
    }
}

@Composable
fun EmergencyAlertScreen(pageLevel: PageLevel, senderName: String, messageText: String, onDismiss: () -> Unit) {
    val backgroundColor = Color(pageLevel.colorHex)

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = backgroundColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    modifier = Modifier
                        .size(100.dp)
                        .background(Color.White.copy(alpha = 0.2f), shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (pageLevel == PageLevel.HEY_LOOK) Icons.Default.Visibility else Icons.Default.NotificationsActive,
                        contentDescription = "Alert Level",
                        tint = Color.White,
                        modifier = Modifier.size(64.dp)
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = pageLevel.title.uppercase(),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "From: $senderName",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.9f)
                )

                Spacer(modifier = Modifier.height(24.dp))

                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "\"$messageText\"",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color.Black,
                        modifier = Modifier.padding(20.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
            ) {
                Text(
                    text = "ACKNOWLEDGE & DISMISS 🔕",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = backgroundColor
                )
            }
        }
    }
}
