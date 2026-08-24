package com.openclaw.assistant.backend

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

/**
 * Façade over [BackendRepository] used by features that need to decide
 * *where* a request goes. Wake-word, Voice Overlay, Wear OS, and the
 * Mobile Bridge all read [primaryClient]; Chat may override per-message.
 */
class BackendManager internal constructor(private val repo: BackendRepository) {

    val backends: StateFlow<List<AgentBackendConfig>> = repo.backends

    /** Highest-priority backend for non-Android-runtime clients. Android voice paths use [PrimaryBackendDispatcher]. */
    fun primaryClient(): AgentClient? = repo.primary?.let { AgentClientFactory.create(it) }

    /** Looks up an explicit backend (used by the Chat backend selector). */
    fun clientForId(id: String): AgentClient? =
        backends.value.firstOrNull { it.id == id && it.enabled }?.let { AgentClientFactory.create(it) }

    /** Backends that are usable as chat targets (enabled + has a real client). */
    fun chatTargets(): List<AgentBackendConfig> = backends.value.filter { it.enabled }

    companion object {
        @Volatile private var instance: BackendManager? = null
        fun getInstance(context: Context): BackendManager = instance ?: synchronized(this) {
            instance ?: BackendManager(BackendRepository.getInstance(context)).also { instance = it }
        }

        /** Test seam; see [BackendRepository.Companion.setInstanceForTests]. */
        @androidx.annotation.VisibleForTesting
        internal fun setInstanceForTests(manager: BackendManager?) = synchronized(this) { instance = manager }
    }
}
