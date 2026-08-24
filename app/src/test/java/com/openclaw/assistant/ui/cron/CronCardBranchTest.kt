package com.openclaw.assistant.ui.cron

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.openclaw.assistant.backend.HermesCronJob
import com.openclaw.assistant.backend.HermesCronRepeat
import com.openclaw.assistant.backend.HermesCronSchedule
import com.openclaw.assistant.backend.OpenClawCronDelivery
import com.openclaw.assistant.backend.OpenClawCronJob
import com.openclaw.assistant.backend.OpenClawCronPayload
import com.openclaw.assistant.backend.OpenClawCronSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scheduled jobs render from two unrelated server shapes.
 *
 * Hermes describes a schedule with any of `expr`, `minutes`, `run_at` or a
 * server-rendered display string; OpenClaw uses `kind` plus one of its own
 * fields. Assuming one shape crashed the whole screen on the other, so both are
 * rendered here for every schedule kind they can produce.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, qualifiers = "w1080dp-h2400dp")
class CronCardBranchTest {

    @get:Rule val compose = createComposeRule()

    private fun hermesJob(
        schedule: HermesCronSchedule = HermesCronSchedule(kind = "cron", expr = "0 9 * * *"),
        scheduleDisplay: String? = null,
        enabled: Boolean = true,
        state: String? = null,
        nextRun: String? = null,
        repeat: HermesCronRepeat? = null,
    ) = HermesCronJob(
        id = "j1",
        name = "Morning briefing",
        schedule = schedule,
        scheduleDisplay = scheduleDisplay,
        prompt = "Summarise my inbox",
        deliver = "local",
        enabled = enabled,
        state = state,
        nextRun = nextRun,
        repeat = repeat,
    )

    private fun showHermes(
        job: HermesCronJob,
        onToggle: (Boolean) -> Unit = {},
        onRunNow: () -> Unit = {},
    ) = compose.setContent {
        CronJobCard(job = job, onToggle = onToggle, onEdit = {}, onDelete = {}, onRunNow = onRunNow)
    }

    // ---- hermes schedules ---------------------------------------------------

    @Test fun `a hermes cron expression is shown`() {
        showHermes(hermesJob())
        compose.onNodeWithText("Morning briefing").assertIsDisplayed()
        compose.onNodeWithText("0 9 * * *").assertIsDisplayed()
        compose.onNodeWithText("Prompt: Summarise my inbox").assertIsDisplayed()
    }

    @Test fun `a hermes interval schedule renders without an expression`() {
        // An interval job carries no `expr`; reading it as one used to crash the
        // whole screen, taking the cron jobs of both products with it.
        showHermes(hermesJob(schedule = HermesCronSchedule(kind = "interval", minutes = 30)))
        compose.onNodeWithText("every 30m").assertIsDisplayed()
    }

    @Test fun `a hermes one-shot schedule shows its run time`() {
        showHermes(hermesJob(schedule = HermesCronSchedule(kind = "once", runAt = "2026-09-01T09:00:00Z")))
        compose.onNodeWithText("2026-09-01T09:00:00Z").assertIsDisplayed()
    }

    @Test fun `a server-rendered display string wins over the raw fields`() {
        showHermes(
            hermesJob(
                schedule = HermesCronSchedule(kind = "cron", expr = "0 9 * * *"),
                scheduleDisplay = "Every weekday at 09:00",
            ),
        )
        compose.onNodeWithText("Every weekday at 09:00").assertIsDisplayed()
        compose.onNodeWithText("0 9 * * *").assertDoesNotExist()
    }

    @Test fun `a schedule with nothing usable still renders its kind`() {
        showHermes(hermesJob(schedule = HermesCronSchedule(kind = "custom")))
        compose.onNodeWithText("custom").assertIsDisplayed()
    }

    @Test fun `a completely empty schedule renders a placeholder`() {
        showHermes(hermesJob(schedule = HermesCronSchedule()))
        compose.onNodeWithText("?").assertIsDisplayed()
    }

    // ---- hermes pause state -------------------------------------------------

    @Test fun `a server-paused job is badged and its switch is off`() {
        showHermes(hermesJob(state = "paused"))
        compose.onNodeWithText("Paused").assertIsDisplayed()
        compose.onNode(isToggleable()).assertIsOff()
    }

    @Test fun `a disabled job counts as paused`() {
        showHermes(hermesJob(enabled = false))
        compose.onNodeWithText("Paused").assertIsDisplayed()
    }

    @Test fun `a running job is not badged and its switch is on`() {
        showHermes(hermesJob(nextRun = "2026-08-25T09:00:00Z"))
        compose.onNodeWithText("Paused").assertDoesNotExist()
        compose.onNode(isToggleable()).assertIsOn()
        compose.onNodeWithText("Next run: 2026-08-25T09:00:00Z").assertIsDisplayed()
    }

    @Test fun `a paused job hides its stale next-run time`() {
        showHermes(hermesJob(state = "paused", nextRun = "2026-08-25T09:00:00Z"))
        compose.onNodeWithText("Next run: 2026-08-25T09:00:00Z").assertDoesNotExist()
    }

    @Test fun `toggling reports the new state`() {
        val toggles = mutableListOf<Boolean>()
        showHermes(hermesJob(state = "paused"), onToggle = { toggles += it })
        compose.onNode(isToggleable()).performClick()
        assertEquals(listOf(true), toggles)
    }

    @Test fun `run now is offered for hermes jobs`() {
        var ran = false
        showHermes(hermesJob(), onRunNow = { ran = true })
        compose.onNodeWithContentDescription("Run now").performClick()
        assertTrue(ran)
    }

    @Test fun `repeat progress is shown when the server reports it`() {
        showHermes(hermesJob(repeat = HermesCronRepeat(times = 5, completed = 2)))
        compose.onNodeWithText("Repeat: 2 / 5").assertIsDisplayed()
    }

    @Test fun `delivery is rendered with a human label`() {
        showHermes(hermesJob())
        compose.onNodeWithText("Delivery: Local only").assertIsDisplayed()
    }

    // ---- openclaw jobs ------------------------------------------------------

    private fun showOpenClaw(job: OpenClawCronJob, deliveryPreview: String = "") = compose.setContent {
        OpenClawCronJobCard(
            job = job,
            deliveryPreview = deliveryPreview,
            onToggle = {},
            onEdit = {},
            onDelete = {},
        )
    }

    private fun openClawJob(
        schedule: OpenClawCronSchedule = OpenClawCronSchedule(kind = "cron", expr = "*/5 * * * *", tz = "Asia/Bangkok"),
        payload: OpenClawCronPayload = OpenClawCronPayload(kind = "message", message = "Check the build"),
        delivery: OpenClawCronDelivery? = OpenClawCronDelivery(mode = "push", channel = "telegram", to = "me"),
        enabled: Boolean = true,
    ) = OpenClawCronJob(
        id = "o1",
        name = "Build watch",
        enabled = enabled,
        schedule = schedule,
        payload = payload,
        delivery = delivery,
    )

    @Test fun `an openclaw cron expression is shown with its timezone`() {
        showOpenClaw(openClawJob())
        compose.onNodeWithText("Build watch").assertIsDisplayed()
        compose.onNodeWithText("*/5 * * * *  Asia/Bangkok").assertIsDisplayed()
        compose.onNodeWithText("Prompt: Check the build").assertIsDisplayed()
    }

    @Test fun `an openclaw interval schedule renders in seconds`() {
        showOpenClaw(openClawJob(schedule = OpenClawCronSchedule(kind = "every", everyMs = 90_000)))
        compose.onNodeWithText("90s").assertIsDisplayed()
    }

    @Test fun `an openclaw one-shot schedule shows its time`() {
        showOpenClaw(openClawJob(schedule = OpenClawCronSchedule(kind = "at", at = "2026-09-01T09:00:00Z")))
        compose.onNodeWithText("2026-09-01T09:00:00Z").assertIsDisplayed()
    }

    @Test fun `an openclaw job falls back to the payload text`() {
        showOpenClaw(openClawJob(payload = OpenClawCronPayload(kind = "text", message = null, text = "raw text")))
        compose.onNodeWithText("Prompt: raw text").assertIsDisplayed()
    }

    @Test fun `openclaw delivery is assembled from its parts`() {
        showOpenClaw(openClawJob())
        compose.onNodeWithText("Delivery: push telegram me").assertIsDisplayed()
    }

    @Test fun `an explicit delivery preview wins`() {
        showOpenClaw(openClawJob(), deliveryPreview = "Telegram · Artur")
        compose.onNodeWithText("Delivery: Telegram · Artur").assertIsDisplayed()
    }

    @Test fun `an openclaw job with no delivery omits the line`() {
        showOpenClaw(openClawJob(delivery = null))
        compose.onNodeWithText("Delivery:", substring = true).assertDoesNotExist()
    }

    @Test fun `openclaw jobs offer no run-now control`() {
        // The gateway cron API has no run-now endpoint; offering the button
        // would be a control that cannot work.
        showOpenClaw(openClawJob())
        compose.onNodeWithContentDescription("Run now").assertDoesNotExist()
    }

    @Test fun `a disabled openclaw job shows an off switch`() {
        showOpenClaw(openClawJob(enabled = false))
        compose.onNode(isToggleable()).assertIsOff()
    }

    @Test fun `editing is only offered for openclaw cron schedules`() {
        showOpenClaw(openClawJob(schedule = OpenClawCronSchedule(kind = "every", everyMs = 1_000)))
        compose.onNodeWithContentDescription("Edit Job").assertIsNotEnabled()
    }
}
