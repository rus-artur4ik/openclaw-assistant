package com.openclaw.assistant.backend

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Tests for the Hermes admin surface, against the endpoints the gateway
 * actually serves. The previous implementation called `/api/config` and
 * `/api/available-models`, neither of which exists in hermes-agent.
 */
class HermesConfigApiTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        HermesEndpointSelection.clear()
    }

    @After fun tearDown() {
        server.shutdown()
        HermesEndpointSelection.clear()
    }

    private fun config() = AgentBackendConfig(
        id = "hermes-config-api-test",
        displayName = "Hermes",
        type = BackendType.HERMES_API_SERVER,
        baseUrl = server.url("/").toString().trimEnd('/'),
        apiKeyOrToken = "test-token",
    )

    /** Paths are asserted as a set because the two catalog reads are order-sensitive only by implementation detail. */
    private fun takePaths(count: Int): List<RecordedRequest> = (0 until count).map { server.takeRequest() }

    // ------------------------------------------------------------------
    // Model catalog
    // ------------------------------------------------------------------

    @Test fun `catalog reads the provider inventory from the api server with the bearer key`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {
                  "model": "anthropic/claude-sonnet-4.6",
                  "provider": "openrouter",
                  "providers": [
                    {
                      "name": "OpenRouter",
                      "slug": "openrouter",
                      "models": [
                        {"id": "anthropic/claude-sonnet-4.6", "label": "Claude Sonnet"},
                        {"id": "openrouter/auto"}
                      ]
                    },
                    {"name": "OpenAI Codex", "slug": "codex", "models": ["gpt-5.5"]}
                  ]
                }
                """.trimIndent(),
            ),
        )
        server.enqueue(MockResponse().setResponseCode(404))

        val catalog = HermesConfigApi().fetchCatalog(config())

        assertEquals(
            listOf("anthropic/claude-sonnet-4.6", "openrouter/auto", "gpt-5.5"),
            catalog.models.map { it.id },
        )
        // The provider slug travels with the model — without it Hermes ignores
        // the model on its OpenAI-compatible endpoints.
        assertEquals("openrouter", catalog.models.first().provider)
        assertEquals("codex", catalog.models.last().provider)
        assertEquals("anthropic/claude-sonnet-4.6", catalog.config?.model)
        assertEquals(listOf("OpenRouter", "OpenAI Codex"), catalog.providers)

        val inventory = server.takeRequest()
        assertEquals("/api/model/options", inventory.path)
        assertEquals("Bearer test-token", inventory.getHeader("Authorization"))
        assertEquals("/v1/models", server.takeRequest().path)
    }

    @Test fun `catalog falls back to the v1 models alias`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"object":"list","data":[{"id":"hermes-agent","object":"model","owned_by":"hermes"}]}""",
            ),
        )

        val catalog = HermesConfigApi().fetchCatalog(config())

        assertEquals(listOf("hermes-agent"), catalog.models.map { it.id })
        assertEquals("hermes", catalog.models.single().description)
        assertNull(catalog.config)
    }

    @Test fun `refresh asks the server to bust its provider cache`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"providers":[]}"""))
        server.enqueue(MockResponse().setResponseCode(404))

        HermesConfigApi().fetchCatalog(config(), refresh = true)

        assertEquals("/api/model/options?refresh=1", server.takeRequest().path)
    }

    @Test fun `catalog uses the endpoint the racer reached, not the stored one`() = runBlocking {
        val reachable = server.url("/").toString().trimEnd('/')
        val stored = config().copy(baseUrl = "http://192.168.0.9:8642", secondaryUrls = listOf(reachable))
        HermesEndpointSelection.remember(stored.id, reachable)

        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"providers":[]}"""))
        server.enqueue(MockResponse().setResponseCode(404))

        HermesConfigApi().fetchCatalog(stored)

        // Reaching the dead LAN address would have thrown instead.
        assertEquals("/api/model/options", server.takeRequest().path)
    }

    // ------------------------------------------------------------------
    // Cron jobs
    // ------------------------------------------------------------------

    @Test fun `an interval job no longer breaks the whole job list`() = runBlocking {
        // `expr` exists only on cron-kind schedules. Requiring it made one
        // "every 30m" job throw and blank the entire screen.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """
                {"jobs": [
                  {"id":"aaaaaaaaaaaa","name":"interval","prompt":"p",
                   "schedule":{"kind":"interval","minutes":30,"display":"every 30m"},
                   "schedule_display":"every 30m","enabled":true,"skills":[]},
                  {"id":"bbbbbbbbbbbb","name":"cron","prompt":"p",
                   "schedule":{"kind":"cron","expr":"0 9 * * *","display":"0 9 * * *"},
                   "schedule_display":"0 9 * * *","enabled":true,"skills":[]},
                  {"id":"cccccccccccc","name":"once","prompt":"p",
                   "schedule":{"kind":"once","run_at":"2026-02-03T14:00:00+00:00","display":"2026-02-03 14:00"},
                   "schedule_display":"2026-02-03 14:00","enabled":false,"state":"paused","skills":[]}
                ]}
                """.trimIndent(),
            ),
        )

        val jobs = HermesConfigApi().fetchJobs(config())

        assertEquals(3, jobs.size)
        assertEquals("every 30m", jobs[0].scheduleLabel())
        assertEquals(30, jobs[0].schedule.minutes)
        assertEquals("0 9 * * *", jobs[1].scheduleLabel())
        assertEquals("2026-02-03 14:00", jobs[2].scheduleLabel())
        assertTrue("a paused job must report itself paused", jobs[2].paused)
    }

    @Test fun `the job list asks for paused jobs, which the server hides by default`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"jobs":[]}"""))
        HermesConfigApi().fetchJobs(config())
        assertEquals("/api/jobs?include_disabled=true", server.takeRequest().path)
    }

    @Test fun `a single malformed job does not blank the list`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"jobs":[{"nope":true},{"id":"aaaaaaaaaaaa","name":"ok","prompt":"p","schedule":{"kind":"cron","expr":"* * * * *"}}]}""",
            ),
        )
        val jobs = HermesConfigApi().fetchJobs(config())
        assertEquals(listOf("ok"), jobs.map { it.name })
    }

    @Test fun `create sends the schedule as the string the server parses`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"job":{"id":"aaaaaaaaaaaa","name":"n","prompt":"p","schedule":{"kind":"interval","minutes":30}}}""",
            ),
        )
        HermesConfigApi().createJob(config(), name = "n", schedule = "every 30m", prompt = "p")
        val request = server.takeRequest()
        assertEquals("/api/jobs", request.path)
        assertTrue(request.body.readUtf8().contains("\"schedule\":\"every 30m\""))
    }

    @Test fun `pause resume and run-now hit their own endpoints`() = runBlocking {
        val jobBody = """{"job":{"id":"aaaaaaaaaaaa","name":"n","prompt":"p","schedule":{"kind":"cron","expr":"* * * * *"}}}"""
        repeat(3) { server.enqueue(MockResponse().setResponseCode(200).setBody(jobBody)) }

        val api = HermesConfigApi()
        api.pauseJob(config(), "aaaaaaaaaaaa")
        api.resumeJob(config(), "aaaaaaaaaaaa")
        api.runJobNow(config(), "aaaaaaaaaaaa")

        assertEquals(
            listOf(
                "/api/jobs/aaaaaaaaaaaa/pause",
                "/api/jobs/aaaaaaaaaaaa/resume",
                "/api/jobs/aaaaaaaaaaaa/run",
            ),
            takePaths(3).map { it.path },
        )
    }

    @Test fun `a rejected key is reported as a key problem, not raw JSON`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Invalid API key"}}"""))
        val failure = runCatching { HermesConfigApi().fetchJobs(config()) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("rejected the API key"))
    }

    @Test fun `a build without the cron module explains itself`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(501).setBody("""{"error":"Cron module not available"}"""))
        val failure = runCatching { HermesConfigApi().fetchJobs(config()) }.exceptionOrNull()
        assertEquals("Cron module not available", failure?.message)
    }

    // ------------------------------------------------------------------
    // Session model lock
    // ------------------------------------------------------------------

    @Test fun `locking a session model posts to the session, since global config is read-only`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        HermesConfigApi().lockSessionModel(config(), "s1", "gpt-5.5", provider = "codex")
        val request = server.takeRequest()
        assertEquals("/api/sessions/s1/model", request.path)
        val body = request.body.readUtf8()
        assertTrue(body.contains("\"model\":\"gpt-5.5\""))
        assertTrue(body.contains("\"provider\":\"codex\""))
    }
}
