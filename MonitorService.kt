package com.example.babymonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Foreground service that keeps listening to the microphone even while the
 * screen is off, computes an approximate decibel (SPL) level from raw PCM
 * samples, and places a normal phone call when the noise stays above the
 * configured threshold for the configured duration.
 */
class MonitorService : Service() {

    companion object {
        const val CHANNEL_ID = "baby_monitor_channel"
        const val NOTIFICATION_ID = 1

        const val EXTRA_PHONE = "extra_phone"
        const val EXTRA_THRESHOLD = "extra_threshold"
        const val EXTRA_DURATION = "extra_duration"
        const val ACTION_STOP = "com.example.babymonitor.ACTION_STOP"

        private const val SAMPLE_RATE = 44100

        // Minimum time between two consecutive automatic calls, so the app
        // doesn't dial repeatedly while the baby keeps crying.
        private const val CALL_COOLDOWN_MS = 60_000L
    }

    private var job: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default)

    private var phoneNumber: String = ""
    private var thresholdDb: Double = 70.0
    private var requiredDurationMs: Long = 5_000L
    private var lastCallTime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopMonitoring()
            return START_NOT_STICKY
        }

        phoneNumber = intent?.getStringExtra(EXTRA_PHONE) ?: phoneNumber
        thresholdDb = intent?.getDoubleExtra(EXTRA_THRESHOLD, thresholdDb) ?: thresholdDb
        requiredDurationMs = intent?.getLongExtra(EXTRA_DURATION, requiredDurationMs) ?: requiredDurationMs

        val notification = buildNotification("מאזין לרעשים ברקע...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        MonitorState.isRunning.value = true
        MonitorState.statusText.value = "מאזין בבטחה ברקע..."

        startListening()
        return START_STICKY
    }

    private fun startListening() {
        job?.cancel()
        job = serviceScope.launch {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBufferSize <= 0) {
                MonitorState.statusText.value = "שגיאה באתחול המיקרופון"
                return@launch
            }

            val bufferSize = minBufferSize * 2
            val audioRecord = try {
                AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            } catch (e: SecurityException) {
                MonitorState.statusText.value = "אין הרשאת מיקרופון"
                return@launch
            }

            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                MonitorState.statusText.value = "שגיאה באתחול המיקרופון"
                audioRecord.release()
                return@launch
            }

            val buffer = ShortArray(bufferSize)
            audioRecord.startRecording()

            var loudSince = -1L

            try {
                while (true) {
                    val readCount = audioRecord.read(buffer, 0, buffer.size)
                    if (readCount > 0) {
                        val db = calculateDecibels(buffer, readCount)
                        MonitorState.currentDb.value = db

                        val now = System.currentTimeMillis()
                        if (db >= thresholdDb) {
                            if (loudSince < 0) loudSince = now
                            val elapsedSec = (now - loudSince) / 1000
                            val requiredSec = requiredDurationMs / 1000
                            MonitorState.statusText.value = "מזהה רעש! ($elapsedSec/$requiredSec שנ')"

                            if (now - loudSince >= requiredDurationMs) {
                                triggerCall()
                                loudSince = -1L
                            }
                        } else {
                            loudSince = -1L
                            MonitorState.statusText.value = "מאזין בבטחה ברקע..."
                        }
                    }
                }
            } finally {
                audioRecord.stop()
                audioRecord.release()
            }
        }
    }

    /** Approximates sound pressure level in dB from a block of 16-bit PCM samples. */
    private fun calculateDecibels(buffer: ShortArray, readCount: Int): Double {
        var sum = 0.0
        for (i in 0 until readCount) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        val rms = sqrt(sum / readCount)
        if (rms < 1.0) return 0.0
        return 20 * log10(rms)
    }

    private fun triggerCall() {
        val now = System.currentTimeMillis()
        if (now - lastCallTime < CALL_COOLDOWN_MS) return
        if (phoneNumber.isBlank()) return
        lastCallTime = now

        try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(callIntent)
            updateNotification("בוצעה שיחה אוטומטית ל-$phoneNumber")
        } catch (e: SecurityException) {
            MonitorState.statusText.value = "אין הרשאת חיוג (CALL_PHONE)"
        }
    }

    private fun stopMonitoring() {
        job?.cancel()
        MonitorState.isRunning.value = false
        MonitorState.statusText.value = "ממתין להפעלה"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, MonitorService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("שמרטף חכם")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openPendingIntent)
            .addAction(0, "עצור", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "ניטור שמרטף",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "התראה קבועה בזמן שהניטור פעיל ברקע"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job?.cancel()
        MonitorState.isRunning.value = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
