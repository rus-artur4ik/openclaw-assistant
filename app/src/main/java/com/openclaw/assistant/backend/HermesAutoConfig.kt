package com.openclaw.assistant.backend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * What happened when the app tried to reach a Hermes server.
 *
 * The distinction the user actually needs is "I could not find anything there"
 * versus "I found it, and it wants a key" versus "I found it and your key is
 * wrong". A single boolean cannot say any of that, and "connection failed" sends
 * people to check their network when the real problem is a typo in the key.
 */
sealed class HermesSetupProbe {

    /** Reachable and authenticated. */
    data class Ready(val baseUrl: String, val capabilities: HermesCapabilities) : HermesSetupProbe()

    /** A Hermes server answered, but no key was supplied and it requires one. */
    data class NeedsKey(val baseUrl: String) : HermesSetupProbe()

    /** A Hermes server answered and refused the key that was supplied. */
    data class KeyRejected(val baseUrl: String, val detail: String) : HermesSetupProbe()

    /** Something answered, but it does not serve the Hermes API. */
    data class NotHermes(val baseUrl: String, val detail: String) : HermesSetupProbe()

    /** Nothing answered on any of the addresses tried. */
    data class Unreachable(val tried: List<String>, val detail: String) : HermesSetupProbe()

    val baseUrlOrNull: String?
        get() = when (this) {
            is Ready -> baseUrl
            is NeedsKey -> baseUrl
            is KeyRejected -> baseUrl
            is NotHermes -> baseUrl
            is Unreachable -> null
        }
}

/** Model and provider the server suggests, so the user does not have to know either. */
data class HermesSuggestedModel(
    val model: String?,
    val provider: String?,
    val options: List<HermesModelOption> = emptyList(),
    val providers: List<String> = emptyList(),
)

/**
 * Works out how to talk to a Hermes server from as little as a typed IP address.
 *
 * Every guess is checked against the server rather than assumed: which scheme
 * and port answer, whether the key is accepted, which transports the build
 * supports, and which models it can actually route to.
 */
class HermesAutoConfig(
    private val httpClient: OkHttpClient = defaultClient(),
    private val capabilitiesProbe: HermesCapabilitiesProbe = HermesCapabilitiesProbe(),
    private val configApi: HermesConfigApi = HermesConfigApi(),
) {

    /**
     * Works out which address actually hosts Hermes, then authenticates.
     *
     * Two phases on purpose. Discovery runs **without** the key: the guesses
     * include a plaintext `http://` variant, and probing them all with the key
     * attached would put it on the wire in cleartext even when the server was
     * reachable over TLS. A 401 identifies a Hermes as well as a 200 does, so
     * the key is only ever sent to the single endpoint that answered.
     */
    suspend fun probe(rawAddress: String, token: String?): HermesSetupProbe {
        val candidates = HermesAddressCandidates.expand(rawAddress)
        if (candidates.isEmpty()) {
            return HermesSetupProbe.Unreachable(emptyList(), "Enter an address such as 192.168.1.50")
        }

        val discovered = discover(candidates)
        val endpoint = discovered.baseUrlOrNull
            // Nothing worth authenticating against.
            ?: return HermesSetupProbe.Unreachable(candidates, unreachableDetail(discovered))
        if (discovered is HermesSetupProbe.NotHermes) return discovered

        if (token.isNullOrBlank()) {
            return withCapabilities(discovered, token = null)
        }
        return withCapabilities(classifyOnce(endpoint, token), token = token)
    }

    /** Phase one: which of the guesses answers at all, asked anonymously. */
    private suspend fun discover(candidates: List<String>): HermesSetupProbe {
        val results = java.util.Collections.synchronizedList(mutableListOf<HermesSetupProbe>())
        val inFlight = java.util.Collections.synchronizedList(mutableListOf<Call>())
        val settled = CompletableDeferred<HermesSetupProbe?>()

        return coroutineScope {
            val jobs = candidates.map { candidate ->
                launch(Dispatchers.IO) {
                    val outcome = classify(candidate, token = null, inFlight = inFlight)
                    results += outcome
                    // Either answer pins the endpoint, so stop probing the rest.
                    if (outcome is HermesSetupProbe.Ready || outcome is HermesSetupProbe.NeedsKey) {
                        settled.complete(outcome)
                    }
                }
            }
            launch { jobs.joinAll(); settled.complete(null) }

            val winner = withTimeoutOrNull(PROBE_BUDGET_MS) { settled.await() }
            // Cancelling a coroutine does not interrupt a blocking socket read,
            // so the losing probes have to be aborted at the OkHttp level.
            synchronized(inFlight) { inFlight.toList() }.forEach { runCatching { it.cancel() } }
            jobs.forEach { it.cancel() }
            winner
                ?: synchronized(results) { results.toList() }.minByOrNull { rank(it) }
                ?: HermesSetupProbe.Unreachable(candidates, "No response")
        }
    }

    /** Phase two: one authenticated request, to the endpoint discovery settled on. */
    private suspend fun classifyOnce(endpoint: String, token: String?): HermesSetupProbe =
        classify(endpoint, token, java.util.Collections.synchronizedList(mutableListOf()))

    private suspend fun withCapabilities(probe: HermesSetupProbe, token: String?): HermesSetupProbe =
        if (probe is HermesSetupProbe.Ready) {
            probe.copy(capabilities = capabilitiesProbe.fetch(probe.baseUrl, token))
        } else {
            probe
        }

    private fun unreachableDetail(probe: HermesSetupProbe): String = when (probe) {
        is HermesSetupProbe.Unreachable -> probe.detail
        else -> "No response"
    }

    /**
     * Asks the server which models it can route to, and picks a sensible default.
     *
     * The provider matters: Hermes ignores a bare model name on its
     * OpenAI-compatible endpoints unless the operator opted in, so a model is
     * only suggested when it arrives with the provider that owns it.
     */
    suspend fun suggestModel(
        baseUrl: String,
        token: String?,
        capabilities: HermesCapabilities,
    ): HermesSuggestedModel {
        val probeConfig = AgentBackendConfig(
            id = "hermes-setup-probe",
            displayName = "Hermes",
            type = BackendType.HERMES_API_SERVER,
            baseUrl = baseUrl,
            apiKeyOrToken = token,
        )
        val catalog = runCatching { configApi.fetchCatalog(probeConfig) }.getOrNull()
            ?: return HermesSuggestedModel(model = null, provider = null)

        // The server's own configured model is the best default there is.
        val configured = catalog.config?.model?.takeIf { it.isNotBlank() }
        val chosen = configured ?: capabilities.advertisedModel
        val provider = catalog.config?.provider?.takeIf { it.isNotBlank() }
            ?: catalog.models.firstOrNull { it.id == chosen }?.provider
        return HermesSuggestedModel(
            model = chosen,
            provider = provider,
            options = catalog.models,
            providers = catalog.providers,
        )
    }

    /** Config the wizard would save, so the review step and the save agree exactly. */
    fun buildConfig(
        baseUrl: String,
        token: String?,
        displayName: String,
        model: String?,
        provider: String?,
        memoryScopeKey: String?,
        isPrimary: Boolean,
        existing: AgentBackendConfig? = null,
    ): AgentBackendConfig = (existing ?: AgentBackendConfig(
        displayName = displayName,
        type = BackendType.HERMES_API_SERVER,
    )).copy(
        displayName = displayName.ifBlank { "Hermes Agent" },
        type = BackendType.HERMES_API_SERVER,
        baseUrl = baseUrl,
        apiKeyOrToken = token?.takeIf { it.isNotBlank() },
        modelName = model?.takeIf { it.isNotBlank() } ?: "default",
        providerName = provider?.takeIf { it.isNotBlank() },
        memoryScopeKey = memoryScopeKey?.takeIf { it.isNotBlank() },
        // Detected rather than chosen: the probe already asked the server what
        // it supports, so there is nothing for the user to decide here.
        transport = HermesTransportPreference.AUTO,
        useStreaming = true,
        isPrimary = isPrimary,
    )

    private suspend fun classify(
        candidate: String,
        token: String?,
        inFlight: MutableList<Call>,
    ): HermesSetupProbe =
        withContext(Dispatchers.IO) {
            try {
                get(HermesUrl.modelsUrl(candidate), token, inFlight).use { response ->
                    when {
                        response.isSuccessful ->
                            HermesSetupProbe.Ready(candidate, HermesCapabilities.LEGACY)
                        response.code == 401 || response.code == 403 ->
                            if (token.isNullOrBlank()) {
                                HermesSetupProbe.NeedsKey(candidate)
                            } else {
                                HermesSetupProbe.KeyRejected(candidate, "HTTP ${response.code}")
                            }
                        response.code == 404 -> notHermesOrUnreachable(candidate, token, "HTTP 404", inFlight)
                        else -> notHermesOrUnreachable(candidate, token, "HTTP ${response.code}", inFlight)
                    }
                }
            } catch (e: Throwable) {
                HermesSetupProbe.Unreachable(listOf(candidate), e.message ?: e.javaClass.simpleName)
            }
        }

    /**
     * A 404 on `/v1/models` is ambiguous — a wrong path on a real Hermes, or an
     * unrelated web server. The root health endpoint settles it.
     */
    private fun notHermesOrUnreachable(
        candidate: String,
        token: String?,
        detail: String,
        inFlight: MutableList<Call>,
    ): HermesSetupProbe = try {
        get(HermesUrl.rootHealthUrl(candidate), token, inFlight).use { health ->
            if (health.isSuccessful) {
                HermesSetupProbe.Ready(candidate, HermesCapabilities.LEGACY)
            } else {
                HermesSetupProbe.NotHermes(candidate, detail)
            }
        }
    } catch (_: Throwable) {
        HermesSetupProbe.NotHermes(candidate, detail)
    }

    private fun get(url: String, token: String?, inFlight: MutableList<Call>) = httpClient.newCall(
        Request.Builder().url(url).get().apply {
            token?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
        }.build(),
    ).also { inFlight.add(it) }.execute()

    private fun rank(outcome: HermesSetupProbe): Int = when (outcome) {
        is HermesSetupProbe.Ready -> 0
        is HermesSetupProbe.NeedsKey -> 1
        is HermesSetupProbe.KeyRejected -> 2
        is HermesSetupProbe.NotHermes -> 3
        is HermesSetupProbe.Unreachable -> 4
    }

    companion object {
        /** Whole-probe ceiling, so a black-holed address cannot hang the wizard. */
        const val PROBE_BUDGET_MS = 8_000L

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(6, TimeUnit.SECONDS)
            .build()
    }
}
