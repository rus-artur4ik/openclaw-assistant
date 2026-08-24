package com.openclaw.assistant.bridge.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Rect
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.openclaw.assistant.bridge.WakeLockManager
import java.security.MessageDigest

/**
 * Accessibility Bridge — the agent reads the visible screen and acts on it:
 * tap, type, swipe, screenshots, clipboard, media, notifications.
 *
 * The user must explicitly enable WakeClaw in Android Settings →
 * Accessibility before any of the screen.* capabilities can run. The service
 * is intentionally a thin "remote control" — it has no auto-behaviour, no
 * background scraping, no event-stream broadcasting. It only acts when a
 * capability invocation reaches it.
 *
 * This service is exposed only on sideload builds (`BuildConfig.IS_SIDELOAD`)
 * because Google Play restricts accessibility services that are not assistive
 * to disabled users; the Play track keeps the manifest entry but the
 * AccessibilityCapabilities advertise `isAvailable = false` to keep the bridge
 * honest.
 */
class AgentVoiceAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { /* no-op: pull-driven service */ }

    fun performTap(x: Float, y: Float): Boolean {
        return WakeLockManager.withWakeLock {
            val path = Path().apply { moveTo(x, y); lineTo(x + 1f, y + 1f) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, 50L)
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }
    }

    fun performSwipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        return WakeLockManager.withWakeLock {
            val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(50L))
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }
    }

    fun performLongPress(x: Float, y: Float, durationMs: Long): Boolean {
        return WakeLockManager.withWakeLock {
            val path = Path().apply { moveTo(x, y); lineTo(x + 1f, y + 1f) }
            val stroke = GestureDescription.StrokeDescription(path, 0L, durationMs.coerceAtLeast(350L))
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        }
    }

    fun readScreenSnapshot(): ScreenSnapshot {
        val roots = snapshotRoots()
        return try {
            ScreenSnapshot.fromRoots(roots)
        } finally {
            roots.forEach { runCatching { it.recycle() } }
        }
    }

    fun findNodes(text: String?, className: String?, clickable: Boolean?, limit: Int): List<ScreenNodeSnapshot> {
        val needle = text?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val klass = className?.trim()?.takeIf { it.isNotEmpty() }
        return readScreenSnapshot().nodes.filter { node ->
            val hay = listOfNotNull(node.text, node.contentDescription).joinToString(" ").lowercase()
            (needle == null || needle in hay) &&
                (klass == null || node.className == klass) &&
                (clickable == null || node.clickable == clickable)
        }.take(limit.coerceIn(1, 50))
    }

    fun describeNode(nodeId: String): ScreenNodeSnapshot? =
        readScreenSnapshot().nodes.firstOrNull { it.nodeId == nodeId }

    fun screenHash(): ScreenHash {
        val snapshot = readScreenSnapshot()
        return ScreenHash(
            hash = snapshot.computeHash(),
            nodeCount = snapshot.nodes.size,
            truncated = snapshot.truncated,
        )
    }

    private fun snapshotRoots(): List<AccessibilityNodeInfo> {
        val multi = runCatching { windows.mapNotNull { it.root } }.getOrDefault(emptyList())
        return if (multi.isNotEmpty()) multi else listOfNotNull(rootInActiveWindow)
    }

    companion object {
        @Volatile private var instance: AgentVoiceAccessibilityService? = null
        fun get(): AgentVoiceAccessibilityService? = instance
        fun isRunning(): Boolean = instance != null
    }
}

data class ScreenSnapshot(
    val packageName: String?,
    val rootBounds: BoundsSnapshot,
    val nodes: List<ScreenNodeSnapshot>,
    val truncated: Boolean = false,
) {
    fun computeHash(): String {
        val joined = nodes.joinToString("\u001e") { node ->
            listOf(
                node.className.orEmpty(),
                node.text.orEmpty(),
                node.contentDescription.orEmpty(),
                node.viewId.orEmpty(),
                "${node.bounds.left},${node.bounds.top},${node.bounds.right},${node.bounds.bottom}",
            ).joinToString("|")
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(joined.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val MAX_NODES = 512
        fun fromRoots(roots: List<AccessibilityNodeInfo>): ScreenSnapshot {
            val nodes = mutableListOf<ScreenNodeSnapshot>()
            var truncated = false
            var packageName: String? = null
            val union = Rect()
            var unionSet = false
            roots.forEachIndexed { windowIndex, root ->
                if (nodes.size >= MAX_NODES) {
                    truncated = true
                    return@forEachIndexed
                }
                if (packageName == null) packageName = root.packageName?.toString()
                val rect = Rect()
                root.getBoundsInScreen(rect)
                if (!unionSet) {
                    union.set(rect)
                    unionSet = true
                } else {
                    union.union(rect)
                }
                walk(root, windowIndex, nodes)
                if (nodes.size >= MAX_NODES) truncated = true
            }
            return ScreenSnapshot(
                packageName = packageName,
                rootBounds = union.toBoundsSnapshot(),
                nodes = nodes.take(MAX_NODES),
                truncated = truncated,
            )
        }

        private fun walk(node: AccessibilityNodeInfo?, windowIndex: Int, out: MutableList<ScreenNodeSnapshot>) {
            if (node == null || out.size >= MAX_NODES) return
            val rect = Rect()
            node.getBoundsInScreen(rect)
            val text = node.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.take(500)
            val desc = node.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.take(500)
            val interesting = (text != null || desc != null || node.isClickable || node.isLongClickable || node.isScrollable || node.isEditable) &&
                rect.width() > 0 && rect.height() > 0
            if (interesting) {
                out += ScreenNodeSnapshot(
                    nodeId = "w$windowIndex:${out.size}",
                    text = text,
                    contentDescription = desc,
                    className = node.className?.toString(),
                    viewId = node.viewIdResourceName,
                    bounds = rect.toBoundsSnapshot(),
                    clickable = node.isClickable,
                    longClickable = node.isLongClickable,
                    scrollable = node.isScrollable,
                    editable = node.isEditable,
                    enabled = node.isEnabled,
                )
            }
            for (i in 0 until node.childCount) {
                if (out.size >= MAX_NODES) return
                val child = node.getChild(i) ?: continue
                try {
                    walk(child, windowIndex, out)
                } finally {
                    runCatching { child.recycle() }
                }
            }
        }
    }
}

data class ScreenNodeSnapshot(
    val nodeId: String,
    val text: String?,
    val contentDescription: String?,
    val className: String?,
    val viewId: String?,
    val bounds: BoundsSnapshot,
    val clickable: Boolean,
    val longClickable: Boolean,
    val scrollable: Boolean,
    val editable: Boolean,
    val enabled: Boolean,
)

data class BoundsSnapshot(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

data class ScreenHash(val hash: String, val nodeCount: Int, val truncated: Boolean)

private fun Rect.toBoundsSnapshot(): BoundsSnapshot = BoundsSnapshot(left, top, right, bottom)
