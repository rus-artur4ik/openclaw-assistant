package com.openclaw.assistant.backend

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

data class HermesModelOption(
    val id: String,
    val description: String? = null,
    /**
     * Provider slug this model belongs to. Sending it alongside the model id is
     * what makes a model choice stick on Hermes' OpenAI-compatible endpoints,
     * which otherwise ignore a bare model name.
     */
    val provider: String? = null,
)

/**
 * A job's schedule as Hermes stores it. The shape depends on `kind`:
 * cron jobs carry [expr], intervals carry [minutes], one-shots carry [runAt].
 * [display] is the only field present on all three.
 */
@Serializable
data class HermesCronSchedule(
    val kind: String? = null,
    val expr: String? = null,
    val minutes: Int? = null,
    @kotlinx.serialization.SerialName("run_at") val runAt: String? = null,
    val display: String? = null,
) {
    /** Human-readable form, falling back through the kind-specific fields. */
    fun describe(): String = display
        ?: expr
        ?: minutes?.let { "every ${it}m" }
        ?: runAt
        ?: kind
        ?: "?"
}

@Serializable
data class HermesCronRepeat(
    val times: Int? = null,
    val completed: Int? = null,
)

@Serializable
data class HermesCronJob(
    val id: String,
    val name: String = "",
    val schedule: HermesCronSchedule = HermesCronSchedule(),
    @kotlinx.serialization.SerialName("schedule_display") val scheduleDisplay: String? = null,
    val prompt: String = "",
    val deliver: String = "local",
    val skills: List<String> = emptyList(),
    val repeat: HermesCronRepeat? = null,
    val enabled: Boolean = true,
    /** Server-side lifecycle state; "paused" when the job is suspended. */
    val state: String? = null,
    @kotlinx.serialization.SerialName("last_run_at") val lastRun: String? = null,
    @kotlinx.serialization.SerialName("next_run_at") val nextRun: String? = null,
) {
    val paused: Boolean get() = state == "paused" || !enabled
    fun scheduleLabel(): String = scheduleDisplay?.takeIf { it.isNotBlank() } ?: schedule.describe()
}

data class HermesConfigState(
    val model: String?,
    val provider: String?,
)

data class HermesModelCatalog(
    val config: HermesConfigState?,
    val models: List<HermesModelOption>,
    val providers: List<String>,
)

/**
 * Read/write access to the Hermes admin surface: the provider/model inventory
 * (`GET /api/model/options`) and the cron jobs API (`/api/jobs/…`).
 *
 * Every call goes to whichever of the backend's configured endpoints the racer
 * most recently reached, so these screens keep working when only the VPN or
 * public route is up.
 */
class HermesConfigApi(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
) {
    // ------------------------------------------------------------------
    // Model inventory
    // ------------------------------------------------------------------

    /**
     * Provider/model inventory for the model picker.
     *
     * The API server serves the same inventory the dashboard and TUI pickers
     * use, under the ordinary bearer key. A separately paired Hermes dashboard
     * is consulted only as a fallback, for setups whose API server predates the
     * endpoint.
     */
    suspend fun fetchCatalog(
        config: AgentBackendConfig,
        refresh: Boolean = false,
    ): HermesModelCatalog = withContext(Dispatchers.IO) {
        val baseUrl = effectiveBaseUrl(config)
        val options = getJson(config, HermesUrl.modelOptionsUrl(baseUrl, refresh))
            ?: fetchDashboardModelOptions(config)
        val v1Models = getJson(config, HermesUrl.modelsUrl(baseUrl))
        val current = options?.let(::parseConfig)

        val models = (parseProviderModels(options) + parseModels(v1Models))
            .ifEmpty {
                current?.model
                    ?.takeIf { it.isNotBlank() }
                    ?.let { listOf(HermesModelOption(it, "current")) }
                    .orEmpty()
            }
            .distinctBy { it.id }

        HermesModelCatalog(
            config = current,
            models = models,
            providers = parseProviders(options),
        )
    }

    /**
     * Pins a model for one Hermes session.
     *
     * Hermes has no writable global config over the API server — it advertises
     * `admin_config_rw: false` — so a model choice is expressed either per
     * request or as a session-scoped lock, which is what this sets.
     */
    suspend fun lockSessionModel(
        config: AgentBackendConfig,
        sessionId: String,
        model: String,
        provider: String? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", model.trim())
            provider?.takeIf { it.isNotBlank() }?.let { put("provider", it.trim()) }
        }.toString().toRequestBody(JSON_MEDIA)
        val request = authed(
            config,
            Request.Builder()
                .url(HermesUrl.sessionModelUrl(effectiveBaseUrl(config), sessionId))
                .post(body),
        ).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException(describeFailure(response))
            true
        }
    }

    // ------------------------------------------------------------------
    // Cron jobs
    // ------------------------------------------------------------------

    /** Lists jobs, including paused ones — which the server hides by default. */
    suspend fun fetchJobs(config: AgentBackendConfig): List<HermesCronJob> = withContext(Dispatchers.IO) {
        val url = "${HermesUrl.jobsUrl(effectiveBaseUrl(config))}?include_disabled=true"
        val request = authed(config, Request.Builder().url(url).get()).build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(describeFailure(response, text))
            val array = runCatching { json.parseToJsonElement(text).jsonObject["jobs"]?.jsonArray }.getOrNull()
                ?: return@withContext emptyList()
            // One malformed job must not blank the whole screen.
            array.mapNotNull { element ->
                runCatching { json.decodeFromJsonElement(HermesCronJob.serializer(), element) }.getOrNull()
            }
        }
    }

    suspend fun createJob(
        config: AgentBackendConfig,
        name: String,
        schedule: String,
        prompt: String,
        deliver: String = "local",
        repeat: Int? = null,
    ): HermesCronJob = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("name", name.trim())
            // Create takes the schedule as the plain string the user typed;
            // only responses carry the parsed object form.
            put("schedule", schedule.trim())
            put("prompt", prompt.trim())
            put("deliver", deliver.trim())
            repeat?.let { put("repeat", it) }
        }.toString().toRequestBody(JSON_MEDIA)
        postForJob(config, HermesUrl.jobsUrl(effectiveBaseUrl(config)), body)
    }

    suspend fun updateJob(
        config: AgentBackendConfig,
        jobId: String,
        name: String? = null,
        schedule: String? = null,
        prompt: String? = null,
        enabled: Boolean? = null,
        repeat: Int? = null,
    ): HermesCronJob = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            name?.let { put("name", it.trim()) }
            schedule?.let { put("schedule", it.trim()) }
            prompt?.let { put("prompt", it.trim()) }
            enabled?.let { put("enabled", it) }
            repeat?.let { put("repeat", it) }
        }.toString().toRequestBody(JSON_MEDIA)
        val request = authed(
            config,
            Request.Builder().url(HermesUrl.jobUrl(effectiveBaseUrl(config), jobId)).patch(body),
        ).build()
        executeForJob(request)
    }

    suspend fun deleteJob(config: AgentBackendConfig, jobId: String): Boolean = withContext(Dispatchers.IO) {
        val request = authed(
            config,
            Request.Builder().url(HermesUrl.jobUrl(effectiveBaseUrl(config), jobId)).delete(),
        ).build()
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(describeFailure(response, text))
            true
        }
    }

    suspend fun pauseJob(config: AgentBackendConfig, jobId: String): HermesCronJob = withContext(Dispatchers.IO) {
        postForJob(config, HermesUrl.jobPauseUrl(effectiveBaseUrl(config), jobId), EMPTY_BODY)
    }

    suspend fun resumeJob(config: AgentBackendConfig, jobId: String): HermesCronJob = withContext(Dispatchers.IO) {
        postForJob(config, HermesUrl.jobResumeUrl(effectiveBaseUrl(config), jobId), EMPTY_BODY)
    }

    /** Runs a job immediately, outside its schedule. */
    suspend fun runJobNow(config: AgentBackendConfig, jobId: String): HermesCronJob = withContext(Dispatchers.IO) {
        postForJob(config, HermesUrl.jobRunUrl(effectiveBaseUrl(config), jobId), EMPTY_BODY)
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun postForJob(config: AgentBackendConfig, url: String, body: okhttp3.RequestBody): HermesCronJob {
        val request = authed(config, Request.Builder().url(url).post(body)).build()
        return executeForJob(request)
    }

    private fun executeForJob(request: Request): HermesCronJob =
        httpClient.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException(describeFailure(response, text))
            val jobJson = json.parseToJsonElement(text).jsonObject["job"]
                ?: throw IllegalStateException("Hermes did not return the job")
            json.decodeFromJsonElement(HermesCronJob.serializer(), jobJson)
        }

    /** Prefers the endpoint the racer most recently reached over the stored one. */
    private fun effectiveBaseUrl(config: AgentBackendConfig): String =
        HermesEndpointSelection.forBackend(config.id)
            ?: config.baseUrl?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("Hermes backend has no base URL")

    private fun getJson(config: AgentBackendConfig, url: String): JsonObject? = runCatching {
        httpClient.newCall(authed(config, Request.Builder().url(url).get()).build()).execute().use { response ->
            if (!response.isSuccessful) return@runCatching null
            val text = response.body?.string()?.takeIf { it.isNotBlank() } ?: return@runCatching null
            json.parseToJsonElement(text).jsonObject
        }
    }.getOrNull()

    /** Same inventory endpoint on a separately paired Hermes dashboard, which uses session-token auth. */
    private fun fetchDashboardModelOptions(config: AgentBackendConfig): JsonObject? {
        val terminalUrl = config.terminalUrl?.takeIf { it.isNotBlank() } ?: return null
        val sessionToken = config.terminalSessionToken?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            val request = Request.Builder()
                .url(HermesUrl.dashboardModelOptionsUrl(terminalUrl))
                .header("X-Hermes-Session-Token", sessionToken)
                .get()
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val text = response.body?.string()?.takeIf { it.isNotBlank() } ?: return@runCatching null
                json.parseToJsonElement(text).jsonObject
            }
        }.getOrNull()
    }

    private fun authed(config: AgentBackendConfig, builder: Request.Builder): Request.Builder {
        config.apiKeyOrToken?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", "Bearer $it") }
        return builder
    }

    private fun parseConfig(obj: JsonObject): HermesConfigState = HermesConfigState(
        model = obj["model"]?.jsonPrimitive?.contentOrNull,
        provider = obj["provider"]?.jsonPrimitive?.contentOrNull,
    )

    /** `/v1/models` and other OpenAI-shaped lists. */
    private fun parseModels(obj: JsonObject?): List<HermesModelOption> {
        val array = (obj?.get("data") as? JsonArray)
            ?: (obj?.get("models") as? JsonArray)
            ?: (obj?.get("items") as? JsonArray)
            ?: return emptyList()
        return array.mapNotNull { item -> modelOption(item, fallbackDescription = null, provider = null) }
    }

    /** `/api/model/options`, which groups models under provider rows. */
    private fun parseProviderModels(obj: JsonObject?): List<HermesModelOption> {
        val providers = obj?.get("providers") as? JsonArray ?: return emptyList()
        return providers.flatMap { providerItem ->
            val provider = providerItem as? JsonObject ?: return@flatMap emptyList()
            val label = providerName(provider)
            val slug = provider["slug"]?.jsonPrimitive?.contentOrNull ?: label
            val models = provider["models"] as? JsonArray ?: return@flatMap emptyList()
            models.mapNotNull { modelOption(it, fallbackDescription = label, provider = slug) }
        }
    }

    private fun modelOption(
        item: kotlinx.serialization.json.JsonElement,
        fallbackDescription: String?,
        provider: String?,
    ): HermesModelOption? = when (item) {
        is JsonPrimitive -> item.contentOrNull
            ?.takeIf { it.isNotBlank() }
            ?.let { HermesModelOption(it, fallbackDescription, provider) }
        is JsonObject -> {
            val id = item["id"]?.jsonPrimitive?.contentOrNull
                ?: item["model"]?.jsonPrimitive?.contentOrNull
                ?: item["name"]?.jsonPrimitive?.contentOrNull
            id?.takeIf { it.isNotBlank() }?.let {
                HermesModelOption(
                    id = it,
                    description = item["label"]?.jsonPrimitive?.contentOrNull
                        ?: item["description"]?.jsonPrimitive?.contentOrNull
                        ?: item["owned_by"]?.jsonPrimitive?.contentOrNull
                        ?: fallbackDescription,
                    provider = item["provider"]?.jsonPrimitive?.contentOrNull ?: provider,
                )
            }
        }
        else -> null
    }

    private fun parseProviders(obj: JsonObject?): List<String> {
        val array = obj?.get("providers") as? JsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            when (item) {
                is JsonPrimitive -> item.contentOrNull?.takeIf(String::isNotBlank)
                is JsonObject -> providerName(item)?.takeIf(String::isNotBlank)
                else -> null
            }
        }.distinct()
    }

    private fun providerName(obj: JsonObject): String? =
        obj["name"]?.jsonPrimitive?.contentOrNull
            ?: obj["provider"]?.jsonPrimitive?.contentOrNull
            ?: obj["slug"]?.jsonPrimitive?.contentOrNull
            ?: obj["id"]?.jsonPrimitive?.contentOrNull

    /**
     * Hermes uses two error envelopes — `{"error": {"message": …}}` from the
     * OpenAI-compatible handlers and a bare `{"error": "…"}` from the jobs API.
     */
    private fun describeFailure(response: Response, bodyText: String? = null): String {
        val text = bodyText ?: runCatching { response.peekBody(4096).string() }.getOrNull().orEmpty()
        val detail = runCatching {
            val obj = json.parseToJsonElement(text).jsonObject
            (obj["error"] as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                ?: (obj["error"] as? JsonPrimitive)?.contentOrNull
                ?: obj["message"]?.jsonPrimitive?.contentOrNull
        }.getOrNull().orEmpty().trim().take(300)

        return when (response.code) {
            401, 403 -> "Hermes rejected the API key (HTTP ${response.code})"
            404 -> "Not found on this Hermes server (HTTP 404)"
            501 -> detail.ifBlank { "This Hermes build has the cron module disabled" }
            else -> listOf("HTTP ${response.code}", detail.ifBlank { response.message })
                .filter { it.isNotBlank() }
                .joinToString(": ")
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val EMPTY_BODY = "{}".toRequestBody(JSON_MEDIA)
    }
}
