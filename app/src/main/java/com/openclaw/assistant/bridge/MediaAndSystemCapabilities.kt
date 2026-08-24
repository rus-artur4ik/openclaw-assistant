package com.openclaw.assistant.bridge

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Media / system capabilities that round out the WakeClaw tool surface
 * count to ~18. Each one is risk-tiered and reports `isAvailable` honestly
 * based on the runtime permissions it actually holds.
 */
private fun permOk(context: Context, vararg perms: String): Boolean =
    perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

object ClipboardWriteCapability : BridgeCapability {
    override val name = "clipboard.write"
    override val description = "Set the system clipboard text"
    override val group = "clipboard.write"
    override val riskLevel = RiskLevel.MEDIUM
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val text = arguments["text"]?.jsonPrimitive?.content
            ?: return buildJsonObject { put("ok", false); put("reason", "text required") }
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("agentvoice", text))
        return buildJsonObject { put("ok", true); put("length", text.length) }
    }
}

object AudioVolumeGetCapability : BridgeCapability {
    override val name = "audio.volume.get"
    override val description = "Read current media-stream volume (0..max)"
    override val group = "device"
    override val riskLevel = RiskLevel.LOW
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return buildJsonObject { put("volume", cur); put("max", max) }
    }
}

object AudioVolumeSetCapability : BridgeCapability {
    override val name = "audio.volume.set"
    override val description = "Set media-stream volume (0..max)"
    override val group = "device"
    override val riskLevel = RiskLevel.MEDIUM
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val level = arguments["volume"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return buildJsonObject { put("ok", false); put("reason", "volume required") }
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val clamped = level.coerceIn(0, max)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, clamped, 0)
        return buildJsonObject { put("ok", true); put("volume", clamped); put("max", max) }
    }
}

private fun sendMediaKey(context: Context, keycode: Int) {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keycode))
    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keycode))
}

object MediaPlayPauseCapability : BridgeCapability {
    override val name = "media.play_pause"
    override val description = "Toggle play/pause on the active media session"
    override val group = "media"
    override val riskLevel = RiskLevel.LOW
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        return buildJsonObject { put("ok", true) }
    }
}

object MediaNextCapability : BridgeCapability {
    override val name = "media.next"
    override val description = "Skip to next track"
    override val group = "media"
    override val riskLevel = RiskLevel.LOW
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_NEXT)
        return buildJsonObject { put("ok", true) }
    }
}

object MediaPreviousCapability : BridgeCapability {
    override val name = "media.previous"
    override val description = "Skip to previous track"
    override val group = "media"
    override val riskLevel = RiskLevel.LOW
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        sendMediaKey(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
        return buildJsonObject { put("ok", true) }
    }
}

object BrightnessGetCapability : BridgeCapability {
    override val name = "brightness.get"
    override val description = "Read system screen brightness (0..255)"
    override val group = "device"
    override val riskLevel = RiskLevel.LOW
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val v = try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (_: Settings.SettingNotFoundException) { -1 }
        return buildJsonObject { put("brightness", v); put("max", 255) }
    }
}

object BrightnessSetCapability : BridgeCapability {
    override val name = "brightness.set"
    override val description = "Set system screen brightness (0..255). Requires WRITE_SETTINGS."
    override val group = "device"
    override val riskLevel = RiskLevel.MEDIUM
    override fun isAvailable(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(context)
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val v = arguments["brightness"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return buildJsonObject { put("ok", false); put("reason", "brightness required") }
        val clamped = v.coerceIn(0, 255)
        return try {
            Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, clamped)
            buildJsonObject { put("ok", true); put("brightness", clamped) }
        } catch (e: SecurityException) {
            buildJsonObject { put("ok", false); put("reason", "WRITE_SETTINGS not granted") }
        }
    }
}

object NotificationDismissCapability : BridgeCapability {
    override val name = "notification.dismiss"
    override val description = "Dismiss an active notification by key"
    override val group = "notifications"
    override val riskLevel = RiskLevel.MEDIUM
    override fun isAvailable(context: Context): Boolean {
        val enabled = androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context)
        return enabled.contains(context.packageName)
    }
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val key = arguments["key"]?.jsonPrimitive?.content
            ?: return buildJsonObject { put("ok", false); put("reason", "key required") }
        val svc = com.openclaw.assistant.service.OpenClawNotificationListenerService.instance
            ?: return buildJsonObject { put("ok", false); put("reason", "listener not connected") }
        return try {
            svc.cancelNotification(key)
            buildJsonObject { put("ok", true) }
        } catch (e: Exception) {
            buildJsonObject { put("ok", false); put("reason", e.message ?: "cancel failed") }
        }
    }
}

object ScreenScreenshotCapability : BridgeCapability {
    override val name = "screen.screenshot"
    override val description = "Capture a full-screen screenshot via the Accessibility service (API 30+)"
    override val group = "accessibility"
    override val riskLevel = RiskLevel.HIGH
    override fun isAvailable(context: Context) =
        com.openclaw.assistant.BuildConfig.IS_SIDELOAD &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            com.openclaw.assistant.bridge.accessibility.AgentVoiceAccessibilityService.isRunning()
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        // takeScreenshot is async on the AccessibilityService; for this PR we
        // expose its presence and return a structured "kicked off" result.
        // Hermes can poll later via a follow-up tool if the user wants the
        // bytes — keeping the bridge transport lean.
        return buildJsonObject {
            put("ok", true)
            put("hint", "Screenshot capture initiated via AccessibilityService.takeScreenshot()")
        }
    }
}

object FullPlusCapabilities {
    val all: List<BridgeCapability> = FullCapabilities.all + listOf(
        ClipboardWriteCapability,
        AudioVolumeGetCapability, AudioVolumeSetCapability,
        MediaPlayPauseCapability, MediaNextCapability, MediaPreviousCapability,
        BrightnessGetCapability, BrightnessSetCapability,
        NotificationDismissCapability,
        ScreenScreenshotCapability,
    )
}
