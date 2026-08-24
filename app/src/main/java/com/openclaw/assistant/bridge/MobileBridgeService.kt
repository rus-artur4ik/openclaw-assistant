package com.openclaw.assistant.bridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Foreground service hosting the Mobile Bridge HTTP server. The user must
 * explicitly opt-in from Settings; the bridge is off by default. While running,
 * a sticky notification advises the user that an external agent can call into
 * the device through the bridge.
 *
 * The service exposes ACTION_START / ACTION_STOP intents so the rest of the
 * app (Settings switches, onboarding) can drive it without coupling to
 * Service internals.
 */
class MobileBridgeService : Service() {

    private var server: MobileBridgeServer? = null
    private var watchdogJob: kotlinx.coroutines.Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                ensureChannel()
                startForeground(NOTIFICATION_ID, buildNotification())
                startServer()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun startServer() {
        if (server != null) return
        val cfg = MobileBridgeConfig.getInstance(this)
        if (!cfg.enabled.value) return
        val srv = MobileBridgeServer(this, cfg).also { it.start() }
        server = srv

        // Auto-disable watchdog — if cfg.autoDisableIdleMs > 0, stops the
        // service when no request has touched the server for that long.
        val idleMs = cfg.autoDisableIdleMs.value
        if (idleMs > 0L) {
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default + kotlinx.coroutines.SupervisorJob())
            watchdogJob = scope.launch {
                while (scope.isActive) {
                    kotlinx.coroutines.delay(15_000L)
                    val since = System.currentTimeMillis() - srv.lastActivityMs
                    if (since >= idleMs) {
                        stopServer()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        break
                    }
                }
            }
        }
    }

    private fun stopServer() {
        watchdogJob?.cancel(); watchdogJob = null
        server?.stop()
        server = null
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Mobile Bridge", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "WakeClaw Mobile Bridge is exposing local capabilities"
                    }
                )
            }
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("WakeClaw Bridge")
            .setContentText("Local bridge is running on port ${MobileBridgeConfig.getInstance(this).port.value}")
            .setSmallIcon(applicationInfo.icon)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "agent_voice_bridge"
        private const val NOTIFICATION_ID = 0xB47D6E

        const val ACTION_START = "com.openclaw.assistant.bridge.START"
        const val ACTION_STOP = "com.openclaw.assistant.bridge.STOP"

        fun start(context: Context) {
            val intent = Intent(context, MobileBridgeService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        }
        fun stop(context: Context) {
            context.startService(Intent(context, MobileBridgeService::class.java).setAction(ACTION_STOP))
        }
    }
}
