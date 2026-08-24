package com.openclaw.assistant.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Finds Hermes servers on the phone's own network.
 *
 * Hermes is a plain uvicorn process and does not advertise itself over mDNS the
 * way the OpenClaw gateway does, so there is nothing to browse for — the only
 * way to find it is to ask every address on the local subnet whether it answers
 * on the Hermes port. That is fine for a /24 and deliberately not attempted for
 * anything larger.
 */
class HermesLanScanner(
    private val httpClient: OkHttpClient = defaultClient(),
    private val concurrency: Int = 32,
) {

    data class Found(
        val baseUrl: String,
        val host: String,
        /** Model the server advertises, when it answered without needing a key. */
        val advertisedModel: String? = null,
        /** True when the server answered but refused an anonymous request. */
        val requiresKey: Boolean = false,
    )

    data class Progress(
        val scanned: Int,
        val total: Int,
        val found: List<Found>,
        val done: Boolean = false,
    )

    /**
     * Probes each host in parallel and emits progress as results arrive.
     *
     * Hosts are passed in rather than derived here so the caller decides how
     * much of the network to touch — and so this is testable without a network.
     */
    fun scan(
        hosts: List<String>,
        token: String? = null,
        port: Int = HermesAddressCandidates.DEFAULT_PORT,
    ): Flow<Progress> = flow {
        if (hosts.isEmpty()) {
            emit(Progress(0, 0, emptyList(), done = true))
            return@flow
        }
        val found = mutableListOf<Found>()
        var scanned = 0
        val results = Channel<Found?>(Channel.UNLIMITED)

        coroutineScope {
            val gate = Semaphore(concurrency)
            launch(Dispatchers.IO) {
                hosts.forEach { host ->
                    launch { gate.withPermit { results.send(probe(host, port, token)) } }
                }
            }
            repeat(hosts.size) {
                val hit = results.receive()
                scanned++
                if (hit != null) found += hit
                emit(Progress(scanned, hosts.size, found.toList()))
            }
            results.close()
        }
        emit(Progress(hosts.size, hosts.size, found.toList(), done = true))
    }

    private fun probe(host: String, port: Int, token: String?): Found? {
        val baseUrl = "http://$host:$port"
        return try {
            val request = Request.Builder().url(HermesUrl.capabilitiesUrl(baseUrl)).get().apply {
                token?.takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") }
            }.build()
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val body = response.body?.string().orEmpty()
                        Found(baseUrl, host, advertisedModel = advertisedModel(body))
                    }
                    // It answered and demanded credentials, which is itself proof
                    // that something is listening — worth offering to the user.
                    response.code == 401 || response.code == 403 ->
                        Found(baseUrl, host, requiresKey = true)
                    else -> null
                }
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun advertisedModel(body: String): String? = runCatching {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return@runCatching null
        (root["model"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    companion object {
        /** Largest subnet worth walking address by address. */
        const val MAX_HOSTS = 254

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(600, TimeUnit.MILLISECONDS)
            .readTimeout(1200, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(false)
            .build()

        /**
         * Usable host addresses on the subnet [ipv4] belongs to, excluding the
         * network and broadcast addresses and the device's own address.
         *
         * A prefix shorter than /24 is narrowed to the /24 around [ipv4]:
         * walking a /16 would be 65k requests, which is a port scan, not a
         * convenience feature.
         */
        fun hostsFor(ipv4: String, prefixLength: Int, cap: Int = MAX_HOSTS): List<String> {
            val self = parseIpv4(ipv4) ?: return emptyList()
            val effectivePrefix = prefixLength.coerceIn(24, 32)
            if (effectivePrefix >= 31) return emptyList()
            val mask = (-1 shl (32 - effectivePrefix))
            val network = self and mask
            val broadcast = network or mask.inv()
            return ((network + 1) until broadcast)
                .asSequence()
                .filter { it != self }
                .take(cap)
                .map { formatIpv4(it) }
                .toList()
        }

        private fun parseIpv4(raw: String): Int? {
            val parts = raw.trim().split('.')
            if (parts.size != 4) return null
            var value = 0
            for (part in parts) {
                val octet = part.toIntOrNull() ?: return null
                if (octet !in 0..255) return null
                value = (value shl 8) or octet
            }
            return value
        }

        private fun formatIpv4(value: Int): String =
            "${(value ushr 24) and 0xFF}.${(value ushr 16) and 0xFF}.${(value ushr 8) and 0xFF}.${value and 0xFF}"
    }
}
