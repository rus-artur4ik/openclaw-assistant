package com.openclaw.assistant.backend

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Finding Hermes on the local network.
 *
 * Hermes publishes no mDNS record, so this walks the subnet. The host list is
 * the part that decides whether that is a convenience or a port scan, so it is
 * pinned carefully.
 */
class HermesLanScannerTest {

    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun scanner() = HermesLanScanner(
        httpClient = OkHttpClient.Builder()
            .connectTimeout(300, TimeUnit.MILLISECONDS)
            .readTimeout(500, TimeUnit.MILLISECONDS)
            .build(),
        concurrency = 8,
    )

    // ---- host enumeration ---------------------------------------------------

    @Test fun `a slash 24 yields every usable address except the device's own`() {
        val hosts = HermesLanScanner.hostsFor("192.168.1.50", 24)
        assertEquals(253, hosts.size)
        assertTrue(hosts.contains("192.168.1.1"))
        assertTrue(hosts.contains("192.168.1.254"))
        assertFalse("network address must be skipped", hosts.contains("192.168.1.0"))
        assertFalse("broadcast address must be skipped", hosts.contains("192.168.1.255"))
        assertFalse("no point asking ourselves", hosts.contains("192.168.1.50"))
    }

    @Test fun `a wider prefix is narrowed to the surrounding slash 24`() {
        // A /16 is 65k requests. That is a port scan, not a feature.
        val hosts = HermesLanScanner.hostsFor("10.0.5.7", 16)
        assertTrue(hosts.all { it.startsWith("10.0.5.") })
        assertTrue(hosts.size <= HermesLanScanner.MAX_HOSTS)
    }

    @Test fun `a narrow prefix is respected rather than widened`() {
        val hosts = HermesLanScanner.hostsFor("192.168.1.9", 30)
        assertEquals(listOf("192.168.1.10"), hosts.filter { it != "192.168.1.9" }.take(1))
        assertTrue(hosts.size <= 2)
    }

    @Test fun `a point-to-point link has nothing to scan`() {
        assertTrue(HermesLanScanner.hostsFor("10.1.2.3", 31).isEmpty())
        assertTrue(HermesLanScanner.hostsFor("10.1.2.3", 32).isEmpty())
    }

    @Test fun `the cap is honoured`() {
        assertEquals(10, HermesLanScanner.hostsFor("192.168.1.50", 24, cap = 10).size)
    }

    @Test fun `a malformed address yields no hosts`() {
        assertTrue(HermesLanScanner.hostsFor("not-an-ip", 24).isEmpty())
        assertTrue(HermesLanScanner.hostsFor("192.168.1", 24).isEmpty())
        assertTrue(HermesLanScanner.hostsFor("192.168.1.999", 24).isEmpty())
    }

    // ---- scanning -----------------------------------------------------------

    private fun serve(handler: (RecordedRequest) -> MockResponse) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = handler(request)
        }
    }

    @Test fun `a responding host is reported with the model it advertises`() = runBlocking {
        serve { MockResponse().setBody("""{"model":"hermes-agent","features":{}}""") }

        val progress = scanner()
            .scan(listOf(server.hostName), port = server.port)
            .toList()

        val found = progress.last().found.single()
        assertEquals("hermes-agent", found.advertisedModel)
        assertEquals("http://${server.hostName}:${server.port}", found.baseUrl)
        assertFalse(found.requiresKey)
    }

    @Test fun `a host that demands a key still counts as found`() = runBlocking {
        // It answered, so something is listening — worth offering to the user
        // rather than reporting an empty network.
        serve { MockResponse().setResponseCode(401) }

        val found = scanner().scan(listOf(server.hostName), port = server.port).toList().last().found

        assertEquals(1, found.size)
        assertTrue(found.single().requiresKey)
    }

    @Test fun `a host serving something else is not reported`() = runBlocking {
        serve { MockResponse().setResponseCode(404).setBody("<html>nginx</html>") }

        assertTrue(scanner().scan(listOf(server.hostName), port = server.port).toList().last().found.isEmpty())
    }

    @Test fun `dead hosts do not stop the scan`() = runBlocking {
        serve { MockResponse().setBody("""{"model":"hermes-agent"}""") }
        val hosts = listOf("192.0.2.1", server.hostName, "192.0.2.2")

        val last = scanner().scan(hosts, port = server.port).toList().last()

        assertTrue(last.done)
        assertEquals(3, last.scanned)
        assertEquals(1, last.found.size)
    }

    @Test fun `progress is reported as the scan runs`() = runBlocking {
        serve { MockResponse().setResponseCode(404) }
        val hosts = List(5) { server.hostName }

        val progress = scanner().scan(hosts, port = server.port).toList()

        assertEquals(hosts.size, progress.last().total)
        assertTrue("expected incremental updates, got ${progress.size}", progress.size >= hosts.size)
        assertTrue(progress.last().done)
    }

    @Test fun `an empty host list finishes immediately`() = runBlocking {
        val progress = scanner().scan(emptyList()).toList()
        assertEquals(1, progress.size)
        assertTrue(progress.single().done)
        assertEquals(0, progress.single().total)
    }

    @Test fun `the api key is offered to each host`() = runBlocking {
        val seen = mutableListOf<String?>()
        serve { request ->
            seen += request.getHeader("Authorization")
            MockResponse().setBody("""{"model":"hermes-agent"}""")
        }

        scanner().scan(listOf(server.hostName), token = "secret", port = server.port).toList()

        assertEquals(listOf("Bearer secret"), seen)
    }

    @Test fun `it asks the capabilities endpoint`() = runBlocking {
        serve { MockResponse().setBody("""{"model":"hermes-agent"}""") }

        scanner().scan(listOf(server.hostName), port = server.port).toList()

        assertEquals("/v1/capabilities", server.takeRequest().path)
    }
}
