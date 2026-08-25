package com.example.babymonitor

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Simple in-process singleton used to publish live status from [MonitorService]
 * to the Compose UI in [MainActivity]. Since the service and the activity run
 * in the same process, a shared StateFlow is enough and avoids the overhead
 * of a Messenger/Binder or broadcast-based IPC mechanism.
 */
object MonitorState {
    val isRunning = MutableStateFlow(false)
    val statusText = MutableStateFlow("ממתין להפעלה")
    val currentDb = MutableStateFlow(0.0)
}
