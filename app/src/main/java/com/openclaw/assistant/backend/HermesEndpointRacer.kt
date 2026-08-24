package com.openclaw.assistant.backend

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Races a list of candidate Hermes API Server URLs (LAN, Tailscale, public)
 * in parallel and returns the first one to respond successfully to
 * `GET /v1/models` (with `/health` fallback).
 *
 * Races LAN, VPN, and public URLs so the same configured backend works at
 * home, on a train, or behind a VPN without manual reconfiguration.
 *
 * The racer never falls back to a non-2xx endpoint — auth failure on the
 * fastest endpoint is still preferred over silently using a slow stale one.
 */
class HermesEndpointRacer(
    private val httpClient: OkHttpClient = defaultClient(),
    private val perEndpointTimeoutMs: Long = 4_000L,
) {
    data class Outcome(val url: String, val ok: Boolean, val latencyMs: Long, val httpStatus: Int? = null, val errorMessage: String? = null)

    /** Returns the winning endpoint, or null if none responded within the budget. */
    suspend fun race(candidates: List<String>, token: String?): Outcome? = coroutineScope {
        if (candidates.isEmpty()) return@coroutineScope null
        val winner = CompletableDeferred<Outcome>()
        val results = mutableListOf<Outcome>()
        val resultsLock = Any()
        // Cancelling a coroutine does not interrupt a blocking socket read, and
        // coroutineScope waits for every child — so the losing probes have to be
        // aborted at the OkHttp level or a black-holed candidate stalls the race
        // for its full timeout.
        val inFlight = java.util.Collections.synchronizedList(mutableListOf<Call>())
        val jobs = candidates.distinct().map { candidate ->
            launch(Dispatchers.IO) {
                val outcome = probe(candidate, token, inFlight)
                synchronized(resultsLock) { results += outcome }
                if (outcome.ok && !winner.isCompleted) winner.complete(outcome)
            }
        }
        val first = withTimeoutOrNull(perEndpointTimeoutMs + 500L) { winner.await() }
        synchronized(inFlight) { inFlight.toList() }.forEach { runCatching { it.cancel() } }
        jobs.forEach { it.cancel() }
        first ?: synchronized(resultsLock) {
            results.minByOrNull { it.latencyMs }
        }
    }

    private suspend fun probe(
        candidate: String,
        token: String?,
        inFlight: MutableList<Call>,
    ): Outcome = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()

        fun call(url: String): Call {
            val req = Request.Builder().url(url).apply {
                token?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
            }.get().build()
            return httpClient.newCall(req).also { inFlight.add(it) }
        }

        try {
            call(HermesUrl.modelsUrl(candidate)).execute().use { resp ->
                val elapsed = System.currentTimeMillis() - started
                if (resp.isSuccessful) return@withContext Outcome(candidate, true, elapsed, resp.code)
                if (resp.code == 404) {
                    call(HermesUrl.healthUrl(candidate)).execute().use { h ->
                        return@withContext Outcome(candidate, h.isSuccessful, System.currentTimeMillis() - started, h.code)
                    }
                }
                Outcome(candidate, false, elapsed, resp.code, "HTTP ${resp.code}")
            }
        } catch (e: Throwable) {
            Outcome(candidate, false, System.currentTimeMillis() - started, null, e.message ?: e.javaClass.simpleName)
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(3, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Process-wide cache of "which endpoint is currently winning for backend X".
 * Refreshed on every successful race; consumed by [HermesApiServerClient] and
 * the admin APIs so requests do not have to re-probe.
 *
 * Entries expire. A phone that connects on the home LAN and then leaves would
 * otherwise keep dialling an unreachable `192.168.x.x` for the life of the
 * process, even with a working VPN route configured.
 */
object HermesEndpointSelection {
    /** Long enough to cover a conversation, short enough to notice a network change. */
    private const val TTL_MS = 5 * 60 * 1000L

    private data class Entry(val url: String, val chosenAtMs: Long)

    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()
    private val _selected = MutableStateFlow<Map<String, String>>(emptyMap())
    val selected: StateFlow<Map<String, String>> = _selected.asStateFlow()

    fun remember(backendId: String, winner: String) {
        entries[backendId] = Entry(winner, System.currentTimeMillis())
        publish()
    }

    fun forBackend(backendId: String): String? {
        val entry = entries[backendId] ?: return null
        if (System.currentTimeMillis() - entry.chosenAtMs > TTL_MS) {
            entries.remove(backendId)
            publish()
            return null
        }
        return entry.url
    }

    /** Drops a cached winner that just failed, so the next call re-races. */
    fun invalidate(backendId: String) {
        if (entries.remove(backendId) != null) publish()
    }

    fun clear() {
        entries.clear()
        publish()
    }

    private fun publish() {
        _selected.value = entries.mapValues { it.value.url }
    }
}
