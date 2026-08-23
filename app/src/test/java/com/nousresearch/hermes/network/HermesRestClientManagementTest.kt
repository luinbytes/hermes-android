package com.nousresearch.hermes.network

import com.nousresearch.hermes.data.AuthMode
import com.nousresearch.hermes.data.BackendConfig
import com.nousresearch.hermes.protocol.CronJobCreatePayload
import com.nousresearch.hermes.protocol.CronJobUpdates
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesRestClientManagementTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `skills and cron actions use audited REST routes and typed responses`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val config = BackendConfig(
                id = "fake",
                label = "Fake Hermes",
                baseUrl = server.url("/").toString().trimEnd('/'),
                authMode = AuthMode.TOKEN,
                allowInsecurePrivateNetwork = true,
            )
            val client = HermesRestClient(OkHttpClient(), json)
            server.enqueue(MockResponse().setBody("""[{"name":"browser","description":"Web research","category":null,"enabled":true,"usage":12,"provenance":"bundled"}]"""))
            server.enqueue(MockResponse().setBody("""{"ok":true,"name":"browser","enabled":false}"""))
            server.enqueue(MockResponse().setBody("""[{"id":"daily","enabled":true,"name":"Daily brief","schedule_display":"0 8 * * *","next_run_at":"2026-07-18T08:00:00Z"}]"""))
            server.enqueue(MockResponse().setBody("""{"runs":[{"session_id":"cron_daily_1","title":"Daily brief run","profile":"default","source":"cron","message_count":2}],"limit":20}"""))
            server.enqueue(MockResponse().setBody("""{"id":"daily","enabled":false,"name":"Daily brief"}"""))
            server.enqueue(MockResponse().setBody("""{"id":"daily","enabled":false,"name":"Daily brief","state":"queued"}"""))
            server.enqueue(
                MockResponse().setBody(
                    checkNotNull(javaClass.getResource("/fixtures/bot-routine-64e5d89.json")).readText(),
                ),
            )
            server.enqueue(MockResponse().setBody("""{"id":"weekly","enabled":true,"name":"Friday review"}"""))
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))

            val skills = client.skills(config, "secret")
            val toggled = client.toggleSkill(config, "secret", "browser", false)
            val jobs = client.cronJobs(config, "secret", "reviewer bot")
            val runs = client.cronRuns(config, "secret", "daily", profile = "reviewer bot")
            val paused = client.setCronEnabled(config, "secret", "daily", false, "reviewer bot")
            val triggered = client.triggerCron(config, "secret", "daily", "reviewer bot")
            val created = client.createCron(
                config,
                "secret",
                CronJobCreatePayload(name = "Weekly review", prompt = "Review the week", schedule = "0 17 * * 5"),
                profile = "reviewer bot",
            )
            val updated = client.updateCron(
                config,
                "secret",
                "weekly",
                CronJobUpdates(name = "Friday review", schedule = "0 16 * * 5"),
                "reviewer bot",
            )
            client.deleteCron(config, "secret", "weekly", "reviewer bot")

            assertEquals(12, skills.single().usage)
            assertEquals(null, skills.single().category)
            assertFalse(toggled.enabled)
            assertEquals("0 8 * * *", jobs.single().scheduleDisplay)
            assertEquals("cron_daily_1", runs.runs.single().durableId)
            assertFalse(paused.enabled)
            assertEquals("queued", triggered.state)
            assertEquals("weekly", created.id)
            assertEquals("0 17 * * 5", created.schedule?.expr)
            assertEquals("Friday review", updated.name)

            val requests = List(9) { server.takeRequest() }
            assertEquals("/api/skills", requests[0].path)
            assertEquals("PUT", requests[1].method)
            assertEquals("/api/skills/toggle", requests[1].path)
            assertEquals("/api/cron/jobs?profile=reviewer%20bot", requests[2].path)
            assertEquals("/api/cron/jobs/daily/runs?limit=20&profile=reviewer%20bot", requests[3].path)
            assertEquals("/api/cron/jobs/daily/pause?profile=reviewer%20bot", requests[4].path)
            assertEquals("/api/cron/jobs/daily/trigger?profile=reviewer%20bot", requests[5].path)
            assertEquals("POST", requests[6].method)
            assertEquals("/api/cron/jobs?profile=reviewer%20bot", requests[6].path)
            assertTrue(requests[6].body.readUtf8().contains("Review the week"))
            assertEquals("PUT", requests[7].method)
            assertTrue(requests[7].body.readUtf8().contains("\"updates\""))
            assertEquals("DELETE", requests[8].method)
            assertEquals("/api/cron/jobs/weekly?profile=reviewer%20bot", requests[8].path)
            assertTrue(requests.all { it.getHeader("Authorization") == "Bearer secret" })
        }
    }

    @Test
    fun `session search uses the profile scoped full text endpoint`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val config = BackendConfig(
                id = "fake",
                label = "Fake Hermes",
                baseUrl = server.url("/").toString().trimEnd('/'),
                authMode = AuthMode.TOKEN,
                allowInsecurePrivateNetwork = true,
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"results":[{"session_id":"stored-1","snippet":"Android release notes","role":"assistant","source":"tui","model":"hermes-4","session_started":42.0}]}""",
                ),
            )

            val result = HermesRestClient(OkHttpClient(), json).searchSessions(
                config,
                "secret",
                "Android release",
                "research profile",
            )

            assertEquals("stored-1", result.results.single().sessionId)
            assertEquals("Android release notes", result.results.single().snippet)
            assertEquals(
                "/api/sessions/search?q=Android%20release&limit=30&profile=research%20profile",
                server.takeRequest().path,
            )
        }
    }
}
