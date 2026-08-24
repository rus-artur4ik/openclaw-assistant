package com.openclaw.assistant.ui

import com.openclaw.assistant.backend.AgentBackendConfig
import com.openclaw.assistant.backend.BackendManager
import com.openclaw.assistant.backend.BackendRepository
import com.openclaw.assistant.backend.BackendType
import com.openclaw.assistant.backend.InMemorySharedPreferences
import com.openclaw.assistant.ui.backend.ChatBackendTarget
import org.junit.rules.ExternalResource

/**
 * Installs a plain in-memory [BackendRepository] for UI tests.
 *
 * The production singleton is backed by EncryptedSharedPreferences and a
 * keystore, neither of which exists on the JVM, and both branches of the app
 * read the same singleton — so tests that render a Hermes screen and tests that
 * render a gateway screen would otherwise see each other's state.
 */
class BackendTestEnv : ExternalResource() {

    lateinit var repo: BackendRepository
        private set

    override fun before() {
        repo = BackendRepository(InMemorySharedPreferences())
        BackendRepository.setInstanceForTests(repo)
        BackendManager.setInstanceForTests(BackendManager(repo))
        ChatBackendTarget.set(null)
    }

    override fun after() {
        BackendRepository.setInstanceForTests(null)
        BackendManager.setInstanceForTests(null)
        ChatBackendTarget.set(null)
    }

    fun hermes(
        id: String = "hermes-1",
        name: String = "My Hermes",
        primary: Boolean = false,
        model: String? = "default",
    ) = add(
        AgentBackendConfig(
            id = id,
            displayName = name,
            type = BackendType.HERMES_API_SERVER,
            isPrimary = primary,
            baseUrl = "http://hermes.test:8642",
            apiKeyOrToken = "k",
            modelName = model,
        ),
    )

    fun gateway(
        id: String = "gateway-1",
        name: String = "My OpenClaw",
        primary: Boolean = false,
        model: String? = "openclaw",
    ) = add(
        AgentBackendConfig(
            id = id,
            displayName = name,
            type = BackendType.OPENCLAW_GATEWAY,
            isPrimary = primary,
            host = "gateway.test",
            port = 8443,
            useTls = true,
            modelName = model,
        ),
    )

    fun openClawHttp(
        id: String = "http-1",
        name: String = "My OpenClaw API",
        primary: Boolean = false,
    ) = add(
        AgentBackendConfig(
            id = id,
            displayName = name,
            type = BackendType.OPENCLAW_HTTP,
            isPrimary = primary,
            baseUrl = "http://api.test",
            modelName = "openclaw",
        ),
    )

    private fun add(config: AgentBackendConfig): AgentBackendConfig {
        repo.upsert(config)
        if (config.isPrimary) repo.setPrimary(config.id)
        return repo.backends.value.first { it.id == config.id }
    }
}
