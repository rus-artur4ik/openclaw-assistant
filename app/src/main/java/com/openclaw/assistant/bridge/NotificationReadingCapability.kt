package com.openclaw.assistant.bridge

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import com.openclaw.assistant.service.OpenClawNotificationListenerService
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Reads the current active
 * notifications via [OpenClawNotificationListenerService] (which Android
 * grants only after the user has explicitly enabled
 * "Notification access" for WakeClaw).
 *
 * `isAvailable` checks the system-level grant via
 * [NotificationManagerCompat.getEnabledListenerPackages]; if the package is
 * not in the list the capability stays out of the manifest entirely.
 */
object NotificationsActiveListCapability : BridgeCapability {
    override val name = "notifications.active.list"
    override val description = "List currently active system notifications"
    override val group = "notifications"
    override val riskLevel = RiskLevel.MEDIUM

    override fun isAvailable(context: Context): Boolean {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
        return enabled.contains(context.packageName)
    }

    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val snapshot = OpenClawNotificationListenerService.activeSnapshot()
        val arr = buildJsonArray {
            snapshot.forEach { sbn ->
                add(buildJsonObject {
                    put("packageName", sbn.packageName ?: "")
                    put("postTime", sbn.postTime)
                    val n = sbn.notification
                    val extras = n?.extras
                    put("title", extras?.getCharSequence("android.title")?.toString() ?: "")
                    put("text", extras?.getCharSequence("android.text")?.toString() ?: "")
                    put("subText", extras?.getCharSequence("android.subText")?.toString() ?: "")
                    put("key", sbn.key ?: "")
                })
            }
        }
        return buildJsonObject {
            put("notifications", arr)
            put("count", arr.size)
        }
    }
}
