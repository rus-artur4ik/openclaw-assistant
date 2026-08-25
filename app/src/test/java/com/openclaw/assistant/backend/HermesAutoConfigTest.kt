package com.openclaw.assistant.backend

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Finding a Hermes server from a typed address, and saying something useful
 * when it cannot be found.
 *
 * "Connection failed" sends people to check their Wi-Fi when the real problem
 * is a mistyped key, so each outcome is asserted separately.
 */
class HermesAutoConfigTest {

    private lateinit var server: MockWebServer

    // Short timeouts: the https:// guess against a plain-HTTP MockWebServer can
    // only ever end in a timeout, and the suite should not pay for it.
    private val autoConfig = HermesAutoConfig(
        httpClient = okhttp3.OkHttpClient.Builder()
            .connectTimeout(400, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(800, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build(),
    )

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    /** The typed address form: host:port, exactly what a user would paste. */
    private fun address() = "${server.hostName}:${server.port}"

    private fun serve(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = handler(request)
        }
    }

    private val capabilitiesBody =
        """{"model":"hermes-agent","features":{"chat_completions":true,"run_submission":true,
            "run_events_sse":true,"run_stop":true,"approval_events":true,
            "session_chat_streaming":true}}"""

    @Test fun `an authenticated server is reported ready with its capabilities`() = runBlocking {
        serve { request ->
            when {
                request.path?.contains("/v1/models") == true ->
                    MockResponse().setBody("""{"data":[{"id":"hermes-agent"}]}""")
                request.path?.contains("/v1/capabilities") == true ->
                    MockResponse().setBody(capabilitiesBody)
                else -> MockResponse().setResponseCode(404)
            }
        }

        val probe = autoConfig.probe(address(), "key")

        val ready = probe as HermesSetupProbe.Ready
        assertTrue(ready.baseUrl.startsWith("http://"))
        assertTrue(ready.capabilities.detected)
        assertEquals(HermesTransport.SESSION_CHAT, ready.capabilities.preferredTransport())
    }

    @Test fun `a server that wants a key says so instead of failing`() = runBlocking {
        serve { MockResponse().setResponseCode(401) }

        val probe = autoConfig.probe(address(), null)

        assertTrue("was $probe", probe is HermesSetupProbe.NeedsKey)
    }

    @Test fun `a rejected key is distinguished from a missing one`() = runBlocking {
        serve { MockResponse().setResponseCode(401) }

        val probe = autoConfig.probe(address(), "wrong-key")

        assertTrue("was $probe", probe is HermesSetupProbe.KeyRejected)
    }

    @Test fun `a forbidden response is treated the same as unauthorised`() = runBlocking {
        serve { MockResponse().setResponseCode(403) }
        assertTrue(autoConfig.probe(address(), "k") is HermesSetupProbe.KeyRejected)
    }

    @Test fun `a web server that is not hermes is called out`() = runBlocking {
        // Answers, but neither /v1/models nor /health exist.
        serve { MockResponse().setResponseCode(404).setBody("<html>nginx</html>") }

        val probe = autoConfig.probe(address(), null)

        assertTrue("was $probe", probe is HermesSetupProbe.NotHermes)
    }

    @Test fun `a hermes behind a proxy that only serves health is accepted`() = runBlocking {
        serve { request ->
            if (request.path == "/health") MockResponse().setBody("""{"status":"ok"}""")
            else MockResponse().setResponseCode(404)
        }

        assertTrue(autoConfig.probe(address(), null) is HermesSetupProbe.Ready)
    }

    @Test fun `nothing listening reports every address it tried`() = runBlocking {
        val port = server.port
        server.shutdown()

        val probe = autoConfig.probe("127.0.0.1:$port", null)

        val unreachable = probe as HermesSetupProbe.Unreachable
        assertTrue(unreachable.tried.any { it.contains("127.0.0.1:$port") })
    }

    @Test fun `a blank address is refused before any request`() = runBlocking {
        val probe = autoConfig.probe("  ", null)
        assertTrue(probe is HermesSetupProbe.Unreachable)
        assertEquals(0, server.requestCount)
    }

    @Test fun `the working scheme is discovered without the user choosing`() = runBlocking {
        // MockWebServer is plain HTTP; the https guesses must fail quietly and
        // the http one must win.
        serve { request ->
            when {
                request.path?.contains("/v1/models") == true -> MockResponse().setBody("""{"data":[]}""")
                request.path?.contains("/v1/capabilities") == true -> MockResponse().setBody(capabilitiesBody)
                else -> MockResponse().setResponseCode(404)
            }
        }

        val ready = autoConfig.probe(address(), "k") as HermesSetupProbe.Ready

        assertTrue(ready.baseUrl.startsWith("http://"))
    }

    @Test fun `the bearer key reaches the endpoint that answered`() = runBlocking {
        val seen = mutableListOf<String?>()
        serve { request ->
            seen += request.getHeader("Authorization")
            when {
                request.path?.contains("/v1/models") == true -> MockResponse().setBody("""{"data":[]}""")
                else -> MockResponse().setBody(capabilitiesBody)
            }
        }

        autoConfig.probe(address(), "secret")

        assertTrue("headers seen: $seen", seen.any { it == "Bearer secret" })
    }

    @Test fun `discovery is anonymous so the key never rides a guess`() = runBlocking {
        // The guess list contains a plaintext http:// candidate. Probing them
        // all with the key attached would put it on the wire even when the
        // server was reachable over TLS, so the first request must carry none.
        val seen = mutableListOf<String?>()
        serve { request ->
            seen += request.getHeader("Authorization")
            when {
                request.path?.contains("/v1/models") == true -> MockResponse().setBody("""{"data":[]}""")
                else -> MockResponse().setBody(capabilitiesBody)
            }
        }

        autoConfig.probe(address(), "secret")

        assertEquals("the discovery request must be anonymous", null, seen.first())
    }

    @Test fun `a server needing no key is never sent one`() = runBlocking {
        val seen = mutableListOf<String?>()
        serve { request ->
            seen += request.getHeader("Authorization")
            when {
                request.path?.contains("/v1/models") == true -> MockResponse().setBody("""{"data":[]}""")
                else -> MockResponse().setBody(capabilitiesBody)
            }
        }

        autoConfig.probe(address(), null)

        assertTrue("headers seen: $seen", seen.all { it == null })
    }

    @Test fun `a reachable candidate does not wait for the others to time out`() = runBlocking {
        // Deliberately generous timeouts: if the probe awaited every candidate,
        // the https:// guess against this plain-HTTP server would hold the whole
        // thing for five seconds while the http:// one already answered.
        val patientConfig = HermesAutoConfig(
            httpClient = okhttp3.OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .build(),
        )
        serve { request ->
            when {
                request.path?.contains("/v1/models") == true -> MockResponse().setBody("""{"data":[]}""")
                else -> MockResponse().setBody(capabilitiesBody)
            }
        }

        val startedAt = System.currentTimeMillis()
        val probe = patientConfig.probe(address(), "k")
        val elapsed = System.currentTimeMillis() - startedAt

        assertTrue("was $probe", probe is HermesSetupProbe.Ready)
        assertTrue("took ${elapsed}ms; the losing candidate was not cancelled", elapsed < 2_000)
    }

    // ---- model suggestion ---------------------------------------------------

    @Test fun `the server's configured model and provider are suggested`() = runBlocking {
        serve { request ->
            if (request.path?.startsWith("/api/model/options") == true) {
                // Shape served by Hermes: the active model at the top level, and
                // models grouped under the provider that owns them.
                MockResponse().setBody(
                    """{"model":"kimi-k2","provider":"moonshot",
                        "providers":[
                          {"name":"Moonshot","slug":"moonshot","models":["kimi-k2"]},
                          {"name":"OpenAI","slug":"openai","models":["gpt-5"]}
                        ]}""",
                )
            } else {
                MockResponse().setResponseCode(404)
            }
        }

        val suggested = autoConfig.suggestModel(
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = "k",
            capabilities = HermesCapabilities(advertisedModel = "hermes-agent", detected = true),
        )

        assertEquals("kimi-k2", suggested.model)
        assertEquals("moonshot", suggested.provider)
        assertEquals(2, suggested.options.size)
    }

    @Test fun `a provider is inferred from the catalogue when the config omits it`() = runBlocking {
        serve { request ->
            if (request.path?.startsWith("/api/model/options") == true) {
                MockResponse().setBody(
                    """{"model":"gpt-5","providers":[{"name":"OpenAI","slug":"openai","models":["gpt-5"]}]}""",
                )
            } else {
                MockResponse().setResponseCode(404)
            }
        }

        val suggested = autoConfig.suggestModel(
            server.url("/").toString().trimEnd('/'),
            "k",
            HermesCapabilities(detected = true),
        )

        assertEquals("openai", suggested.provider)
    }

    @Test fun `a server with no catalogue still yields a usable suggestion`() = runBlocking {
        serve { MockResponse().setResponseCode(404) }

        val suggested = autoConfig.suggestModel(
            server.url("/").toString().trimEnd('/'),
            "k",
            HermesCapabilities(advertisedModel = "hermes-agent", detected = true),
        )

        assertTrue(suggested.options.isEmpty())
    }

    // ---- the config it would save ------------------------------------------

    @Test fun `the saved config carries what the probe learned`() {
        val config = autoConfig.buildConfig(
            baseUrl = "http://192.168.1.50:8642",
            token = "k",
            displayName = "Home Hermes",
            model = "kimi-k2",
            provider = "moonshot",
            memoryScopeKey = "artur",
            isPrimary = true,
        )

        assertEquals(BackendType.HERMES_API_SERVER, config.type)
        assertEquals("http://192.168.1.50:8642", config.baseUrl)
        assertEquals("kimi-k2", config.modelName)
        assertEquals("moonshot", config.providerName)
        assertEquals("artur", config.memoryScopeKey)
        assertTrue(config.isPrimary)
        // Detected, not chosen: the probe already asked the server.
        assertEquals(HermesTransportPreference.AUTO, config.transport)
    }

    @Test fun `an unset model is saved as the server-default sentinel`() {
        val config = autoConfig.buildConfig(
            baseUrl = "http://h:8642", token = "", displayName = "H",
            model = "", provider = "", memoryScopeKey = "", isPrimary = false,
        )
        assertEquals("default", config.modelName)
        assertEquals(null, config.providerName)
        assertEquals(null, config.memoryScopeKey)
        assertEquals(null, config.apiKeyOrToken)
    }
}
