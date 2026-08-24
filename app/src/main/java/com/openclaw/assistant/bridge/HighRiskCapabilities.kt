package com.openclaw.assistant.bridge

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * High-risk capabilities. Every one of them:
 *  - declares `riskLevel = HIGH` so the default approval mode always prompts
 *    the user via [BridgeApprovalActivity]
 *  - reports `isAvailable = false` when the required permission is not held,
 *    so the manifest never advertises an action the device cannot perform
 *  - returns structured `{launched/sent/added: false, reason: ...}` payloads
 *    instead of throwing when the underlying call fails
 *
 * No high-risk action is ever auto-retried.
 */
private fun permissionOk(context: Context, vararg perms: String): Boolean =
    perms.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }

object SmsSendCapability : BridgeCapability {
    override val name = "sms.send"
    override val description = "Send an SMS to a phone number"
    override val group = "sms"
    override val riskLevel = RiskLevel.HIGH
    override val requiresPermissions = listOf(Manifest.permission.SEND_SMS)
    override fun isAvailable(context: Context): Boolean {
        // Google Play restricts SMS to default-SMS-app handlers. Sideload only.
        if (!com.openclaw.assistant.BuildConfig.IS_SIDELOAD) return false
        return permissionOk(context, Manifest.permission.SEND_SMS)
    }
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val number = arguments["number"]?.jsonPrimitive?.content
        val body = arguments["text"]?.jsonPrimitive?.content
        if (number.isNullOrBlank() || body.isNullOrBlank())
            return buildJsonObject { put("sent", false); put("reason", "number and text are required") }
        return try {
            val sm = context.getSystemService(Context.TELEPHONY_SERVICE) // ensure service is up
            android.telephony.SmsManager.getDefault().sendTextMessage(number, null, body, null, null)
            buildJsonObject { put("sent", true) }
        } catch (e: SecurityException) {
            buildJsonObject { put("sent", false); put("reason", "permission denied") }
        } catch (e: Exception) {
            buildJsonObject { put("sent", false); put("reason", e.message ?: "send failed") }
        }
    }
}

object ContactsCreateCapability : BridgeCapability {
    override val name = "contacts.create"
    override val description = "Create a new contact (display name + optional phone)"
    override val group = "contacts"
    override val riskLevel = RiskLevel.HIGH
    override val requiresPermissions = listOf(Manifest.permission.WRITE_CONTACTS)
    override fun isAvailable(context: Context) = permissionOk(context, Manifest.permission.WRITE_CONTACTS)
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val displayName = arguments["displayName"]?.jsonPrimitive?.content
            ?: return buildJsonObject { put("created", false); put("reason", "displayName required") }
        val phone = arguments["phone"]?.jsonPrimitive?.content
        return try {
            val cr = context.contentResolver
            val rawUri = cr.insert(
                ContactsContract.RawContacts.CONTENT_URI,
                ContentValues().apply {
                    putNull(ContactsContract.RawContacts.ACCOUNT_TYPE)
                    putNull(ContactsContract.RawContacts.ACCOUNT_NAME)
                },
            )
            val rawId = rawUri?.lastPathSegment?.toLongOrNull()
                ?: return buildJsonObject { put("created", false); put("reason", "could not insert raw contact") }
            cr.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
            })
            if (!phone.isNullOrBlank()) {
                cr.insert(ContactsContract.Data.CONTENT_URI, ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawId)
                    put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    put(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                })
            }
            buildJsonObject { put("created", true); put("rawContactId", rawId.toString()) }
        } catch (e: SecurityException) {
            buildJsonObject { put("created", false); put("reason", "permission denied") }
        } catch (e: Exception) {
            buildJsonObject { put("created", false); put("reason", e.message ?: "create failed") }
        }
    }
}

object ContactsDeleteCapability : BridgeCapability {
    override val name = "contacts.delete"
    override val description = "Delete a contact by rawContactId"
    override val group = "contacts"
    override val riskLevel = RiskLevel.HIGH
    override val requiresPermissions = listOf(Manifest.permission.WRITE_CONTACTS)
    override fun isAvailable(context: Context) = permissionOk(context, Manifest.permission.WRITE_CONTACTS)
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val id = arguments["rawContactId"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: return buildJsonObject { put("deleted", false); put("reason", "rawContactId required") }
        return try {
            val uri = ContactsContract.RawContacts.CONTENT_URI.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true").build()
            val rows = context.contentResolver.delete(uri, "${ContactsContract.RawContacts._ID}=?", arrayOf(id.toString()))
            buildJsonObject { put("deleted", rows > 0); put("rows", rows) }
        } catch (e: Exception) {
            buildJsonObject { put("deleted", false); put("reason", e.message ?: "delete failed") }
        }
    }
}

object CalendarCreateCapability : BridgeCapability {
    override val name = "calendar.create"
    override val description = "Create a calendar event (title, startMs, endMs)"
    override val group = "calendar"
    override val riskLevel = RiskLevel.HIGH
    override val requiresPermissions = listOf(Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR)
    override fun isAvailable(context: Context) = permissionOk(context,
        Manifest.permission.WRITE_CALENDAR, Manifest.permission.READ_CALENDAR)
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val title = arguments["title"]?.jsonPrimitive?.content
        val startMs = arguments["startMs"]?.jsonPrimitive?.content?.toLongOrNull()
        val endMs = arguments["endMs"]?.jsonPrimitive?.content?.toLongOrNull()
        if (title.isNullOrBlank() || startMs == null || endMs == null)
            return buildJsonObject { put("created", false); put("reason", "title/startMs/endMs required") }
        return try {
            val calId = firstWritableCalendarId(context)
                ?: return buildJsonObject { put("created", false); put("reason", "no writable calendar") }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, startMs)
                put(CalendarContract.Events.DTEND, endMs)
                put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            })
            val eid = uri?.lastPathSegment
            if (eid == null) buildJsonObject { put("created", false); put("reason", "insert failed") }
            else buildJsonObject { put("created", true); put("eventId", eid) }
        } catch (e: Exception) {
            buildJsonObject { put("created", false); put("reason", e.message ?: "insert failed") }
        }
    }
    private fun firstWritableCalendarId(context: Context): Long? {
        val proj = arrayOf(CalendarContract.Calendars._ID)
        context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI, proj,
            "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?",
            arrayOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), null,
        )?.use { c -> if (c.moveToFirst()) return c.getLong(0) }
        return null
    }
}

object CalendarDeleteCapability : BridgeCapability {
    override val name = "calendar.delete"
    override val description = "Delete a calendar event by eventId"
    override val group = "calendar"
    override val riskLevel = RiskLevel.HIGH
    override val requiresPermissions = listOf(Manifest.permission.WRITE_CALENDAR)
    override fun isAvailable(context: Context) = permissionOk(context, Manifest.permission.WRITE_CALENDAR)
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject {
        val id = arguments["eventId"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: return buildJsonObject { put("deleted", false); put("reason", "eventId required") }
        return try {
            val rows = context.contentResolver.delete(CalendarContract.Events.CONTENT_URI,
                "${CalendarContract.Events._ID}=?", arrayOf(id.toString()))
            buildJsonObject { put("deleted", rows > 0); put("rows", rows) }
        } catch (e: Exception) {
            buildJsonObject { put("deleted", false); put("reason", e.message ?: "delete failed") }
        }
    }
}

/** Stub camera capability — returns "unsupported on this device" by default;
 *  capturing a real photo requires a UI prompt and CameraX session, which the
 *  app provides separately. We advertise the action so Hermes knows the name
 *  exists but never auto-execute. */
object CameraCapturePhotoCapability : BridgeCapability {
    override val name = "camera.capture_photo"
    override val description = "Capture a photo via the device camera"
    override val group = "camera"
    override val riskLevel = RiskLevel.HIGH
    override val requiresPermissions = listOf(Manifest.permission.CAMERA)
    override fun isAvailable(context: Context) = false
    override suspend fun execute(context: Context, arguments: JsonObject): JsonObject = buildJsonObject {
        put("captured", false)
        put("reason", "interactive capture must be triggered from the WakeClaw UI")
    }
}

/** All capabilities including high-risk. Used by [BridgeRegistry]. */
object FullCapabilities {
    val all: List<BridgeCapability> = AllCapabilities.all + listOf(
        SmsSendCapability,
        ContactsCreateCapability,
        ContactsDeleteCapability,
        CalendarCreateCapability,
        CalendarDeleteCapability,
        CameraCapturePhotoCapability,
        NotificationsActiveListCapability,
    ) + A11yCapabilities.all
}
