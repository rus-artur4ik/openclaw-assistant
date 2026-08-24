package com.openclaw.assistant.backend

import com.openclaw.assistant.node.NodeRuntime
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The OpenClaw scheduled-jobs RPC, the counterpart to the Hermes cron REST API
 * that [HermesConfigApiTest] covers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class OpenClawCronApiTest {

    private val runtime = mockk<NodeRuntime>()
    private val api = OpenClawCronApi(runtime)

    private fun job(
        id: String = "j1",
        enabled: Boolean = true,
        payloadKind: String = "agentTurn",
    ) = OpenClawCronJob(
        id = id,
        name = "Job",
        enabled = enabled,
        schedule = OpenClawCronSchedule(kind = "cron", expr = "0 9 * * *"),
        payload = OpenClawCronPayload(kind = payloadKind, message = "do a thing"),
    )

    @Test fun `listing asks for disabled jobs too`() = runBlocking {
        val params = slot<String>()
        coEvery { runtime.requestGateway("cron.list", capture(params), any()) } returns
            """{"jobs":[],"deliveryPreviews":{}}"""

        api.fetchJobs()

        assertTrue(JSONObject(params.captured).getBoolean("includeDisabled"))
    }

    @Test fun `a listing is parsed into jobs and delivery previews`() = runBlocking {
        coEvery { runtime.requestGateway("cron.list", any(), any()) } returns """
            {"jobs":[{"id":"a","name":"Morning","schedule":{"kind":"cron","expr":"0 9 * * *"},
                      "payload":{"kind":"agentTurn","message":"brief me"},
                      "delivery":{"mode":"push","channel":"telegram"}}],
             "deliveryPreviews":{"a":{"label":"Telegram","detail":"to me"}}}
        """.trimIndent()

        val result = api.fetchJobs()

        assertEquals(1, result.jobs.size)
        assertEquals("Morning", result.jobs.single().name)
        assertEquals("brief me", result.jobs.single().payload.message)
        assertEquals("Telegram", result.deliveryPreviews["a"]?.label)
    }

    @Test fun `unknown server fields do not break parsing`() = runBlocking {
        // The gateway adds fields over time; a strict parser would blank the
        // whole screen the first time one appeared.
        coEvery { runtime.requestGateway("cron.list", any(), any()) } returns """
            {"jobs":[{"id":"a","name":"N","brandNewField":true,
                      "schedule":{"kind":"cron","expr":"* * * * *","futureField":1},
                      "payload":{"kind":"agentTurn","message":"m"}}],
             "deliveryPreviews":{}, "somethingElse":42}
        """.trimIndent()

        assertEquals("a", api.fetchJobs().jobs.single().id)
    }

    @Test fun `an interval job parses without a cron expression`() = runBlocking {
        coEvery { runtime.requestGateway("cron.list", any(), any()) } returns """
            {"jobs":[{"id":"a","name":"N","schedule":{"kind":"every","everyMs":60000},
                      "payload":{"kind":"agentTurn","message":"m"}}]}
        """.trimIndent()

        val schedule = api.fetchJobs().jobs.single().schedule
        assertEquals("every", schedule.kind)
        assertEquals(60_000L, schedule.everyMs)
    }

    @Test fun `creating a job sends a cron schedule with the device timezone`() = runBlocking {
        val params = slot<String>()
        coEvery { runtime.requestGateway("cron.add", capture(params), any()) } returns
            """{"id":"new","name":"Morning","schedule":{"kind":"cron","expr":"0 9 * * *"},
                "payload":{"kind":"agentTurn","message":"brief me"}}"""

        val created = api.createJob("Morning", "0 9 * * *", "brief me")

        val sent = JSONObject(params.captured)
        assertEquals("Morning", sent.getString("name"))
        assertTrue(sent.getBoolean("enabled"))
        assertEquals("cron", sent.getJSONObject("schedule").getString("kind"))
        assertEquals("0 9 * * *", sent.getJSONObject("schedule").getString("expr"))
        assertTrue(sent.getJSONObject("schedule").getString("tz").isNotBlank())
        assertEquals("agentTurn", sent.getJSONObject("payload").getString("kind"))
        assertEquals("brief me", sent.getJSONObject("payload").getString("message"))
        assertEquals("new", created.id)
    }

    @Test fun `creating a job trims what the user typed`() = runBlocking {
        val params = slot<String>()
        coEvery { runtime.requestGateway("cron.add", capture(params), any()) } returns
            """{"id":"n","name":"N","schedule":{"kind":"cron"},"payload":{"kind":"agentTurn"}}"""

        api.createJob("  Morning  ", "  0 9 * * *  ", "  brief me  ")

        val sent = JSONObject(params.captured)
        assertEquals("Morning", sent.getString("name"))
        assertEquals("0 9 * * *", sent.getJSONObject("schedule").getString("expr"))
        assertEquals("brief me", sent.getJSONObject("payload").getString("message"))
    }

    @Test fun `an update sends only the fields that changed`() = runBlocking {
        val params = slot<String>()
        coEvery { runtime.requestGateway("cron.update", capture(params), any()) } returns
            """{"id":"j1","name":"Renamed","schedule":{"kind":"cron"},"payload":{"kind":"agentTurn"}}"""

        api.updateJob(job(), name = "Renamed")

        val patch = JSONObject(params.captured).getJSONObject("patch")
        assertEquals("Renamed", patch.getString("name"))
        assertFalse(patch.has("schedule"))
        assertFalse(patch.has("payload"))
        assertFalse(patch.has("enabled"))
    }

    @Test fun `an update keeps the job's own payload kind`() = runBlocking {
        val params = slot<String>()
        coEvery { runtime.requestGateway("cron.update", capture(params), any()) } returns
            """{"id":"j1","name":"N","schedule":{"kind":"cron"},"payload":{"kind":"systemEvent"}}"""

        api.updateJob(job(payloadKind = "systemEvent"), prompt = "new text")

        val payload = JSONObject(params.captured).getJSONObject("patch").getJSONObject("payload")
        assertEquals("systemEvent", payload.getString("kind"))
        assertEquals("new text", payload.getString("text"))
    }

    @Test fun `toggling a job sends only the enabled flag`() = runBlocking {
        val params = slot<String>()
        coEvery { runtime.requestGateway("cron.update", capture(params), any()) } returns
            """{"id":"j1","name":"N","enabled":false,"schedule":{"kind":"cron"},"payload":{"kind":"agentTurn"}}"""

        api.updateJob(job(), enabled = false)

        val patch = JSONObject(params.captured).getJSONObject("patch")
        assertFalse(patch.getBoolean("enabled"))
        assertEquals(1, patch.length())
    }

    @Test fun `disabling everything skips jobs that are already off`() = runBlocking {
        coEvery { runtime.requestGateway("cron.update", any(), any()) } returns
            """{"id":"x","name":"N","schedule":{"kind":"cron"},"payload":{"kind":"agentTurn"}}"""

        api.disableAll(listOf(job(id = "on1"), job(id = "off1", enabled = false), job(id = "on2")))

        coVerify(exactly = 2) { runtime.requestGateway("cron.update", any(), any()) }
    }

    @Test fun `deleting quotes the id so an odd one cannot break the payload`() = runBlocking {
        val params = slot<String>()
        coEvery { runtime.requestGateway("cron.remove", capture(params), any()) } returns "{}"

        api.deleteJob("""weird"id""")

        assertEquals("""weird"id""", JSONObject(params.captured).getString("id"))
    }
}
