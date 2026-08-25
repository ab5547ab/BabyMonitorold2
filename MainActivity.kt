package com.example.babymonitor

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.babymonitor.ui.theme.BabyMonitorTheme

class MainActivity : ComponentActivity() {

    private lateinit var prefs: SharedPreferences

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* results handled implicitly - Start button re-checks before use */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("baby_monitor_prefs", MODE_PRIVATE)

        requestNeededPermissions()

        setContent {
            BabyMonitorTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BabyMonitorScreen(
                        initialPhone = prefs.getString("phone", "") ?: "",
                        onStart = { phone, threshold, durationSec ->
                            prefs.edit().putString("phone", phone).apply()
                            startMonitorService(phone, threshold, durationSec)
                        },
                        onStop = { stopMonitorService() }
                    )
                }
            }
        }
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CALL_PHONE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestPermissionsLauncher.launch(permissions.toTypedArray())
    }

    private fun startMonitorService(phone: String, thresholdDb: Double, durationSec: Int) {
        val intent = Intent(this, MonitorService::class.java).apply {
            putExtra(MonitorService.EXTRA_PHONE, phone)
            putExtra(MonitorService.EXTRA_THRESHOLD, thresholdDb)
            putExtra(MonitorService.EXTRA_DURATION, durationSec * 1000L)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun stopMonitorService() {
        val intent = Intent(this, MonitorService::class.java).apply {
            action = MonitorService.ACTION_STOP
        }
        startService(intent)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BabyMonitorScreen(
    initialPhone: String,
    onStart: (phone: String, thresholdDb: Double, durationSec: Int) -> Unit,
    onStop: () -> Unit
) {
    var phoneNumber by remember { mutableStateOf(initialPhone) }
    var threshold by remember { mutableFloatStateOf(70f) }
    var durationSec by remember { mutableFloatStateOf(5f) }

    val isRunning by MonitorState.isRunning.collectAsState()
    val statusText by MonitorState.statusText.collectAsState()

    val accent by accentColor(isRunning)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Filled.GraphicEq else Icons.Filled.ChildCare,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(64.dp)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "שמרטף חכם",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(8.dp))

        AnimatedContent(targetState = statusText, label = "status") { text ->
            Text(
                text = text,
                fontSize = 16.sp,
                color = accent,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        OutlinedTextField(
            value = phoneNumber,
            onValueChange = { phoneNumber = it },
            label = { Text("מספר טלפון להתראה") },
            leadingIcon = { Icon(Icons.Filled.Phone, contentDescription = null) },
            singleLine = true,
            enabled = !isRunning,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(24.dp))

        Text(text = "רגישות (סף רעש): ${threshold.toInt()} dB", modifier = Modifier.fillMaxWidth())
        Slider(
            value = threshold,
            onValueChange = { threshold = it },
            valueRange = 50f..100f,
            enabled = !isRunning
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(text = "משך רעש נדרש: ${durationSec.toInt()} שניות", modifier = Modifier.fillMaxWidth())
        Slider(
            value = durationSec,
            onValueChange = { durationSec = it },
            valueRange = 2f..15f,
            enabled = !isRunning
        )

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = {
                if (isRunning) {
                    onStop()
                } else if (phoneNumber.isNotBlank()) {
                    onStart(phoneNumber, threshold.toDouble(), durationSec.toInt())
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = accent)
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(text = if (isRunning) "עצור ניטור" else "התחל ניטור", fontSize = 18.sp)
        }
    }
}

@Composable
private fun accentColor(isRunning: Boolean): State<Color> {
    val target = if (isRunning) Color(0xFF2E7D32) else Color(0xFFC62828)
    return animateColorAsState(targetValue = target, label = "accent")
}
