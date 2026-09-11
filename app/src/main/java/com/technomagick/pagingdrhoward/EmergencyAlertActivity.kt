package com.technomagick.pagingdrhoward

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
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
import com.technomagick.pagingdrhoward.data.PageLevel
import com.technomagick.pagingdrhoward.service.EmergencyPagerService

class EmergencyAlertActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activeActivity = this

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

        renderAlert(intent)
    }

    override fun onNewIntent(newIntent: Intent?) {
        super.onNewIntent(newIntent)
        newIntent?.let {
            setIntent(it)
            renderAlert(it)
        }
    }

    private fun renderAlert(currentIntent: Intent) {
        val senderName = currentIntent.getStringExtra("EXTRA_SENDER") ?: "Family Member"
        val senderTopic = currentIntent.getStringExtra("EXTRA_SENDER_TOPIC") ?: ""
        val messageText = currentIntent.getStringExtra("EXTRA_MESSAGE") ?: "URGENT: Please respond immediately!"
        val levelCode = currentIntent.getStringExtra("EXTRA_LEVEL")
        val timestamp = currentIntent.getLongExtra("EXTRA_TIMESTAMP", 0L)
        val messageKey = currentIntent.getStringExtra("EXTRA_MESSAGE_KEY") ?: ""
        val pageLevel = PageLevel.fromCode(levelCode)

        setContent {
            PagingDrHowardTheme {
                EmergencyAlertScreen(
                    pageLevel = pageLevel,
                    senderName = senderName,
                    messageText = messageText,
                    onDismiss = { dismissPage(senderTopic, timestamp, messageKey) }
                )
            }
        }
    }

    private fun dismissPage(senderTopic: String, alertTimestamp: Long, messageKey: String = "") {
        com.technomagick.pagingdrhoward.receiver.AlertActionReceiver.performDismiss(this, senderTopic, alertTimestamp, messageKey)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (activeActivity === this) {
            activeActivity = null
        }
    }

    companion object {
        const val EXTRA_MESSAGE_KEY = "EXTRA_MESSAGE_KEY"

        @Volatile
        private var activeActivity: EmergencyAlertActivity? = null

        fun dismissCurrentAlert(context: Context) {
            // Stop alarm audio and background emergency pager service
            com.technomagick.pagingdrhoward.util.AudioPlayer.stopEmergencyAlarm(context)
            val stopServiceIntent = Intent(context, EmergencyPagerService::class.java).apply {
                action = EmergencyPagerService.ACTION_STOP_ALARM
            }
            context.startService(stopServiceIntent)

            // Dismiss the full screen UI if showing
            activeActivity?.let { activity ->
                activity.runOnUiThread {
                    activity.finish()
                }
            }
        }

        fun createIntent(
            context: Context,
            sender: String?,
            senderTopic: String?,
            message: String?,
            level: PageLevel,
            timestamp: Long = 0L,
            messageKey: String? = null
        ): Intent {
            return Intent(context, EmergencyAlertActivity::class.java).apply {
                putExtra("EXTRA_SENDER", sender)
                putExtra("EXTRA_SENDER_TOPIC", senderTopic)
                putExtra("EXTRA_MESSAGE", message)
                putExtra("EXTRA_LEVEL", level.code)
                putExtra("EXTRA_TIMESTAMP", timestamp)
                putExtra(EXTRA_MESSAGE_KEY, messageKey)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }
    }
}

@Composable
fun EmergencyAlertScreen(pageLevel: PageLevel, senderName: String, messageText: String, onDismiss: () -> Unit) {
    val backgroundColor = Color(pageLevel.colorHex)
    val isHeyLook = pageLevel == PageLevel.HEY_LOOK

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = backgroundColor
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // ── Top: icon + level title ──────────────────────────────────────
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // Outer glow ring + icon circle
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .background(Color.White.copy(alpha = 0.15f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .background(Color.White.copy(alpha = 0.25f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isHeyLook) Icons.Default.Visibility
                                          else Icons.Default.NotificationsActive,
                            contentDescription = "Alert level",
                            tint = Color.White,
                            modifier = Modifier.size(52.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                Text(
                    text = if (isHeyLook) "HEY LOOK! 👀" else "SOS EMERGENCY 🚨",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White,
                    letterSpacing = 1.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Sender pill
                Surface(
                    shape = RoundedCornerShape(50),
                    color = Color.White.copy(alpha = 0.2f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Sender avatar
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .background(Color.White.copy(alpha = 0.4f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                senderName.take(1).uppercase(),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "From $senderName",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Message card
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.95f)),
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "\"$messageText\"",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Normal,
                        color = Color.Black,
                        modifier = Modifier.padding(24.dp),
                        textAlign = TextAlign.Center
                    )
                }
            }

            // ── Bottom: dismiss button ───────────────────────────────────────
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp)
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    tint = backgroundColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    "Acknowledge & Dismiss",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = backgroundColor
                )
            }
        }
    }
}
