package com.openclaw.assistant.backend

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Persists the user's list of agent backends and tracks the primary one. Backed
 * by [EncryptedSharedPreferences] so API keys and bridge tokens are never written
 * to disk in plain text.
 *
 * Invariants enforced on every write:
 *  - if any backend exists, exactly one is marked [AgentBackendConfig.isPrimary].
 *  - the primary is always [AgentBackendConfig.enabled].
 */
class BackendRepository internal constructor(
    private val prefs: SharedPreferences,
    private val json: Json = DEFAULT_JSON,
) {
    private val _backends = MutableStateFlow(load())
    val backends: StateFlow<List<AgentBackendConfig>> = _backends.asStateFlow()

    val primary: AgentBackendConfig? get() = _backends.value.firstOrNull { it.isPrimary && it.enabled }

    fun upsert(config: AgentBackendConfig) {
        val current = _backends.value.toMutableList()
        val idx = current.indexOfFirst { it.id == config.id }
        val updated = config.copy(updatedAt = System.currentTimeMillis())
        if (idx >= 0) current[idx] = updated else current.add(updated)
        save(reconcile(current))
    }

    fun delete(id: String) {
        save(reconcile(_backends.value.filterNot { it.id == id }.toMutableList()))
    }

    fun setPrimary(id: String) {
        val list = _backends.value.map { it.copy(isPrimary = it.id == id, enabled = if (it.id == id) true else it.enabled) }
        save(reconcile(list.toMutableList()))
    }

    fun setEnabled(id: String, enabled: Boolean) {
        val list = _backends.value.map { if (it.id == id) it.copy(enabled = enabled) else it }
        save(reconcile(list.toMutableList()))
    }

    internal fun reconcile(list: MutableList<AgentBackendConfig>): List<AgentBackendConfig> {
        if (list.isEmpty()) return list
        val primaryIdx = list.indexOfFirst { it.isPrimary && it.enabled }
        return if (primaryIdx >= 0) {
            list.mapIndexed { i, c -> if (i == primaryIdx) c else c.copy(isPrimary = false) }
        } else {
            // Promote first enabled (or first overall) to primary.
            val promote = list.indexOfFirst { it.enabled }.takeIf { it >= 0 } ?: 0
            list.mapIndexed { i, c -> if (i == promote) c.copy(isPrimary = true, enabled = true) else c.copy(isPrimary = false) }
        }
    }

    private fun load(): List<AgentBackendConfig> {
        val raw = prefs.getString(KEY_LIST, null) ?: return emptyList()
        return try {
            json.decodeFromString(ListSerializer(AgentBackendConfig.serializer()), raw)
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun save(list: List<AgentBackendConfig>) {
        val encoded = json.encodeToString(ListSerializer(AgentBackendConfig.serializer()), list)
        prefs.edit { putString(KEY_LIST, encoded) }
        _backends.value = list
    }

    companion object {
        private const val KEY_LIST = "backends.v1"
        private const val PREFS_NAME = "openclaw.backends.secure"
        internal val DEFAULT_JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        @Volatile private var instance: BackendRepository? = null
        fun getInstance(context: Context): BackendRepository = instance ?: synchronized(this) {
            instance ?: BackendRepository(createPrefs(context)).also { instance = it }
        }

        /**
         * Test seam. The real instance is backed by [EncryptedSharedPreferences],
         * which needs a keystore; UI tests install a plain in-memory one so both
         * backend branches can be rendered without a device.
         */
        @androidx.annotation.VisibleForTesting
        internal fun setInstanceForTests(repo: BackendRepository?) = synchronized(this) { instance = repo }

        private fun createPrefs(context: Context): SharedPreferences {
            val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                context,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
