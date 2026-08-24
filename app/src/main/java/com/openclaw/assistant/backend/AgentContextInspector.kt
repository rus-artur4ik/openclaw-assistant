package com.openclaw.assistant.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

data class AgentContextInspection(
    val contextName: String?,
    val contextDetail: String?,
    val summary: String,
)

/**
 * Read-only inspector for what an agent backend actually is: which model it
 * advertises, which skills and toolsets it has loaded, and whether it reports
 * itself healthy.
 *
 * Everything is probed in parallel and every endpoint is optional, so a server
 * that predates one of them still yields a partial answer instead of nothing.
 */
class AgentContextInspector(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    suspend fun inspect(config: AgentBackendConfig): AgentContextInspection = withContext(Dispatchers.IO) {
        val baseUrl = HermesEndpointSelection.forBackend(config.id)
            ?: config.baseUrl?.trim()?.takeIf { it.isNotBlank() }
            ?: return@withContext AgentContextInspection(null, null, "No base URL configured")

        val headers = config.apiKeyOrToken
            ?.takeIf { it.isNotBlank() }
            ?.let { mapOf("Authorization" to "Bearer $it") }
            .orEmpty()

        // Serial probes against a black-holed address used to cost the caller
        // one timeout each; run them together instead.
        val capabilitiesTask: JsonObject?
        val skills: JsonObject?
        val toolsets: JsonObject?
        val health: JsonObject?
        coroutineScope {
            val caps = async { getJson(HermesUrl.capabilitiesUrl(baseUrl), headers) }
            val sk = async { getJson(HermesUrl.skillsUrl(baseUrl), headers) }
            val ts = async { getJson(HermesUrl.toolsetsUrl(baseUrl), headers) }
            val hp = async { getJson(HermesUrl.detailedHealthUrl(baseUrl), headers) }
            capabilitiesTask = caps.await()
            skills = sk.await()
            toolsets = ts.await()
            health = hp.await()
        }
        val capabilities = capabilitiesTask

        val model = capabilities?.get("model")?.jsonPrimitive?.contentOrNull
            ?: config.modelName
        val platform = capabilities?.get("platform")?.jsonPrimitive?.contentOrNull
        val skillNames = listItems(skills).mapNotNull { it.stringOrNull("name") }
        val enabledToolsets = listItems(toolsets)
            .filter { (it["enabled"] as? JsonPrimitive)?.booleanOrNull == true }
            .mapNotNull { it.stringOrNull("name") }
        val healthStatus = health?.get("status")?.jsonPrimitive?.contentOrNull

        val detailParts = listOfNotNull(
            model?.takeIf { it.isNotBlank() }?.let { "model: $it" },
            config.providerName?.takeIf { it.isNotBlank() }?.let { "provider: $it" },
            skillNames.takeIf { it.isNotEmpty() }?.let { "skills: ${it.size}" },
            enabledToolsets.takeIf { it.isNotEmpty() }?.let { "toolsets: ${it.joinToString(", ")}" },
            healthStatus?.let { "health: $it" },
        )

        val nothingAnswered = capabilities == null && skills == null && toolsets == null && health == null
        AgentContextInspection(
            contextName = (platform ?: config.agentContextName)?.takeIf { it.isNotBlank() },
            contextDetail = detailParts.joinToString(" · ").ifBlank { null },
            summary = if (nothingAnswered) {
                "This server did not answer any of the optional discovery endpoints " +
                    "(/v1/capabilities, /v1/skills, /v1/toolsets, /health/detailed). " +
                    "Manual context fields can still be used."
            } else {
                buildString {
                    appendLine(detailParts.joinToString("\n"))
                    if (skillNames.isNotEmpty()) {
                        append("\nSkills: ")
                        append(skillNames.take(12).joinToString(", "))
                        if (skillNames.size > 12) append(", +${skillNames.size - 12} more")
                    }
                }.trim().ifBlank { "The server responded, but reported no displayable fields." }
            },
        )
    }

    /** Accepts both `{"object":"list","data":[…]}` and a bare top-level array. */
    private fun listItems(obj: JsonObject?): List<JsonObject> =
        (obj?.get("data") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun getJson(url: String, headers: Map<String, String>): JsonObject? = runCatching {
        val builder = Request.Builder().url(url).get()
        headers.forEach { (k, v) -> builder.header(k, v) }
        httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val body = response.body?.string()?.takeIf { it.isNotBlank() } ?: return@runCatching null
            val element = json.parseToJsonElement(body)
            // `/v1/toolsets` answers with a bare array; wrap it so callers see
            // one shape.
            when (element) {
                is JsonArray -> JsonObject(mapOf("data" to element))
                is JsonObject -> element
                else -> null
            }
        }
    }.getOrNull()
}
