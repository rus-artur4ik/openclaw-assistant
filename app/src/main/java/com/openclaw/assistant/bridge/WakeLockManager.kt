package com.openclaw.assistant.bridge

import android.content.Context
import android.os.PowerManager
import android.util.Log

object WakeLockManager {
    private const val TAG = "BridgeWakeLock"
    private const val LOCK_TAG = "WakeClaw::BridgeAction"
    private const val TIMEOUT_MS = 10_000L

    private val lock = Any()
    private var count = 0
    @Volatile private var wakeLock: PowerManager.WakeLock? = null

    fun initialize(context: Context) {
        synchronized(lock) {
            if (wakeLock != null) return
            val pm = context.applicationContext.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG).apply {
                setReferenceCounted(false)
            }
        }
    }

    fun <T> withWakeLock(block: () -> T): T {
        val acquired = acquire()
        return try {
            block()
        } finally {
            if (acquired) release()
        }
    }

    fun acquire(): Boolean {
        val wl = wakeLock ?: return false
        synchronized(lock) {
            if (count == 0) {
                try {
                    wl.acquire(TIMEOUT_MS)
                } catch (t: Throwable) {
                    Log.w(TAG, "acquire failed: ${t.message}")
                    return false
                }
            }
            count += 1
            return true
        }
    }

    fun release() {
        val wl = wakeLock ?: return
        synchronized(lock) {
            if (count <= 0) {
                count = 0
                return
            }
            count -= 1
            if (count == 0) {
                runCatching { if (wl.isHeld) wl.release() }
            }
        }
    }
}
