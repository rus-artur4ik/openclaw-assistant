package com.openclaw.assistant.bridge

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Tiny HTTP/1.1 server that exposes Mobile Bridge endpoints to localhost (and
 * optionally the LAN). Intentionally dependency-free — no NanoHTTPD or Ktor —
 * because Bridge is opt-in and the surface area is small (4 endpoints).
 *
 * Threading: socket accept runs on [Dispatchers.IO]; each request is handled on
 * its own IO coroutine. The handler itself is synchronous; capability execution
 * is suspending and is launched in the same scope so cancellation propagates.
 *
 * Security notes:
 *  - All endpoints except `/health` require `Authorization: Bearer <token>`.
 *  - The token is never written to logs.
 *  - When [MobileBridgeConfig.bindMode] is LOCAL_ONLY the socket binds to
 *    127.0.0.1 so off-device callers cannot reach it. LAN bind is opt-in.
 */
open class MobileBridgeServer(
    private val context: Context,
    private val config: MobileBridgeConfig,
    private val registry: BridgeRegistry = BridgeRegistry(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    internal val rateLimiter: RateLimiter = RateLimiter(),
) {
    private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null
    @Volatile internal var lastActivityMs: Long = System.currentTimeMillis()

    fun start() {
        if (serverSocket != null) return
        val bindAddress = when (config.bindMode.value) {
            BridgeBindMode.LOCAL_ONLY -> InetAddress.getByName("127.0.0.1")
            BridgeBindMode.LAN -> InetAddress.getByName("0.0.0.0")
        }
        val socket = ServerSocket()
        socket.reuseAddress = true
        socket.bind(InetSocketAddress(bindAddress, config.port.value))
        serverSocket = socket
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        s.launch { acceptLoop(socket) }
    }

    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        scope?.cancel()
        scope = null
    }

    private suspend fun acceptLoop(socket: ServerSocket) {
        val s = scope ?: return
        while (s.isActive && !socket.isClosed) {
            val client = try { socket.accept() } catch (_: IOException) { return }
            s.launch { handleClient(client) }
        }
    }

    internal suspend fun handleClient(client: Socket) {
        client.use { sock ->
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val request = readRequest(reader) ?: run {
                writeResponse(sock.getOutputStream(), 400, errorJson("bad_request", "Malformed request"))
                return
            }
            val response = dispatch(request)
            writeResponse(sock.getOutputStream(), response.status, response.body)
        }
    }

    internal data class HttpRequest(val method: String, val path: String, val headers: Map<String, String>, val body: String)
    internal data class HttpResponse(val status: Int, val body: String)

    internal fun readRequest(reader: BufferedReader): HttpRequest? {
        val requestLine = reader.readLine() ?: return null
        val parts = requestLine.split(' ')
        if (parts.size < 2) return null
        val method = parts[0]
        val path = parts[1]
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        val body = if (contentLength > 0) {
            val buf = CharArray(contentLength)
            var read = 0
            while (read < contentLength) {
                val n = reader.read(buf, read, contentLength - read)
                if (n < 0) break
                read += n
            }
            String(buf, 0, read)
        } else ""
        return HttpRequest(method, path, headers, body)
    }

    internal suspend fun dispatch(req: HttpRequest): HttpResponse {
        // /health is unauthenticated by design.
        if (req.method == "GET" && req.path == "/health") {
            return HttpResponse(200, buildJsonObject {
                put("status", "ok"); put("app", "WakeClaw"); put("bridge", true)
            }.toString())
        }
        // /pair is the only OTHER unauthenticated endpoint — by definition,
        // pairing happens before the caller has the token. It's gated by the
        // one-shot 6-character code shown in the BridgePairingActivity UI.
        if (req.method == "POST" && req.path == "/pair") {
            return pairResponse(req.body)
        }
        val rateKey = req.headers["authorization"]?.takeIf { it.isNotBlank() } ?: "anonymous"
        if (!rateLimiter.tryAcquire(rateKey)) {
            return HttpResponse(429, errorJson("rate_limited", "Too many requests"))
        }
        if (!authorize(req)) {
            return HttpResponse(401, errorJson("unauthorized", "Missing or invalid bearer token"))
        }
        lastActivityMs = System.currentTimeMillis()
        return when {
            req.method == "GET" && req.path == "/manifest" -> manifestResponse()
            req.method == "POST" && req.path == "/execute" -> executeResponse(req.body)
            req.method == "POST" && req.path.startsWith("/cancel/") -> cancelResponse(req.path.removePrefix("/cancel/"))
            req.method == "POST" && req.path == "/revoke" -> revokeResponse(req.body)
            req.method == "GET" && req.path == "/grants" -> grantsResponse()
            else -> HttpResponse(404, errorJson("not_found", "No such endpoint"))
        }
    }

    private fun pairResponse(body: String): HttpResponse {
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return HttpResponse(400, errorJson("bad_request", "Invalid JSON"))
        val code = parsed["code"]?.jsonPrimitive?.content
            ?: return HttpResponse(400, errorJson("bad_request", "code required"))
        val token = com.openclaw.assistant.bridge.pairing.BridgePairing.redeem(code, config)
            ?: return HttpResponse(403, errorJson("invalid_code", "Code not found or expired"))
        return HttpResponse(200, buildJsonObject {
            put("token", token)
            put("hint", "Send this token as 'Authorization: Bearer …' from now on")
        }.toString())
    }

    private fun revokeResponse(body: String): HttpResponse {
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return HttpResponse(400, errorJson("bad_request", "Invalid JSON"))
        val cap = parsed["capability"]?.jsonPrimitive?.content
        val all = parsed["all"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() == true
        when {
            all -> { com.openclaw.assistant.bridge.grants.BridgeGrants.revokeAll() }
            cap != null -> { com.openclaw.assistant.bridge.grants.BridgeGrants.revoke(cap) }
            else -> return HttpResponse(400, errorJson("bad_request", "capability or all required"))
        }
        return HttpResponse(200, buildJsonObject { put("revoked", true) }.toString())
    }

    private fun grantsResponse(): HttpResponse {
        val arr = buildJsonArray {
            com.openclaw.assistant.bridge.grants.BridgeGrants.snapshot().forEach { g ->
                add(buildJsonObject {
                    put("capability", g.capability)
                    put("expiresAtMs", g.expiresAtMs)
                })
            }
        }
        return HttpResponse(200, buildJsonObject { put("grants", arr) }.toString())
    }

    private fun authorize(req: HttpRequest): Boolean {
        val expected = config.tokenOrNull() ?: return false
        val header = req.headers["authorization"] ?: return false
        if (!header.startsWith("Bearer ", ignoreCase = true)) return false
        val token = header.substring("Bearer ".length).trim()
        return constantTimeEquals(token, expected)
    }

    private fun manifestResponse(): HttpResponse {
        val allowed = config.allowedCapabilityGroups.value
        val capsArr = buildJsonArray {
            registry.visible(context, allowed).forEach { add(it.manifestEntry(context)) }
        }
        val obj = buildJsonObject {
            put("deviceId", runCatching { android.os.Build.FINGERPRINT?.hashCode()?.toString() }.getOrNull() ?: "unknown")
            put("deviceName", runCatching { android.os.Build.MODEL }.getOrNull() ?: "unknown")
            put("appVersion", runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "unknown")
            put("capabilities", capsArr)
        }
        return HttpResponse(200, obj.toString())
    }

    private suspend fun executeResponse(body: String): HttpResponse {
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return HttpResponse(400, errorJson("bad_request", "Invalid JSON"))
        val requestId = parsed["requestId"]?.jsonPrimitive?.content ?: ""
        val capabilityName = parsed["capability"]?.jsonPrimitive?.content
            ?: return HttpResponse(400, errorEnvelope(requestId, "bad_request", "Missing capability"))
        val arguments = parsed["arguments"] as? JsonObject ?: buildJsonObject {}

        val allowed = config.allowedCapabilityGroups.value
        val cap = registry.byName(capabilityName)
        if (cap == null || !cap.isAvailable(context) || cap.group !in allowed) {
            BridgeActivityLog.record(context, capabilityName, cap?.riskLevel, "blocked", "unsupported or disabled")
            return HttpResponse(200, errorEnvelope(requestId, "unsupported_capability", "Capability is not supported"))
        }

        if (requiresApproval(cap)) {
            val approved = approvalGate(requestId, capabilityName, arguments)
            if (!approved) {
                BridgeActivityLog.record(context, capabilityName, cap.riskLevel, "denied", "User denied or approval timed out")
                return HttpResponse(200, errorEnvelope(requestId, "approval_denied", "User denied or approval timed out"))
            }
        }

        return try {
            val result = cap.execute(context, arguments)
            BridgeActivityLog.record(context, capabilityName, cap.riskLevel, "completed")
            HttpResponse(200, buildJsonObject {
                put("requestId", requestId)
                put("status", "completed")
                put("result", result)
                put("error", kotlinx.serialization.json.JsonNull)
            }.toString())
        } catch (e: Exception) {
            BridgeActivityLog.record(context, capabilityName, cap.riskLevel, "failed", e.message ?: e.javaClass.simpleName)
            HttpResponse(200, errorEnvelope(requestId, "execution_failed", e.message ?: "Unknown error"))
        }
    }

    private fun cancelResponse(requestId: String): HttpResponse =
        HttpResponse(200, buildJsonObject {
            put("requestId", requestId); put("status", "cancelled"); put("result", kotlinx.serialization.json.JsonNull)
        }.toString())

    /**
     * Open seam for tests: overridable approval gate. The production server
     * launches the notification approval Activity and suspends on
     * [BridgeApprovalRegistry]; tests substitute a deterministic gate.
     */
    internal open suspend fun approvalGate(requestId: String, capability: String, arguments: kotlinx.serialization.json.JsonObject): Boolean {
        // Honour outstanding grants with user-chosen TTLs. A destructive verb
        // forces a fresh prompt regardless of the grant.
        val destructive = com.openclaw.assistant.bridge.grants.DestructiveVerbs.isDestructive(capability)
        if (!destructive && com.openclaw.assistant.bridge.grants.BridgeGrants.isGranted(capability)) return true
        runCatching { BridgeApprovalNotifier.notify(context, requestId, capability) }
        return BridgeApprovalRegistry.await(requestId, capability, arguments)
    }

    private fun requiresApproval(cap: BridgeCapability): Boolean = when (config.approvalMode.value) {
        BridgeApprovalMode.ALWAYS_CONFIRM -> true
        BridgeApprovalMode.CONFIRM_MEDIUM_HIGH -> cap.riskLevel != RiskLevel.LOW
        BridgeApprovalMode.TRUSTED -> false
    }

    private fun errorJson(code: String, message: String) = buildJsonObject {
        put("error", buildJsonObject { put("code", code); put("message", message) })
    }.toString()

    private fun errorEnvelope(requestId: String, code: String, message: String) = buildJsonObject {
        put("requestId", requestId); put("status", "failed"); put("result", kotlinx.serialization.json.JsonNull)
        put("error", buildJsonObject { put("code", code); put("message", message) })
    }.toString()

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    private fun writeResponse(out: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (status) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; 429 -> "Too Many Requests"; else -> "Status" }
        val headers = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(headers.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }
}
