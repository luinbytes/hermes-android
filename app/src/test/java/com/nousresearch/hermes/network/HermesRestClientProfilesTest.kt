package com.nousresearch.hermes.network

import com.nousresearch.hermes.data.AuthMode
import com.nousresearch.hermes.data.BackendConfig
import com.nousresearch.hermes.protocol.ProfileCreatePayload
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesRestClientProfilesTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `profile lifecycle uses audited REST routes and preserves scope semantics`() = runTest {
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
            server.enqueue(MockResponse().setBody("""{"profiles":[{"name":"default","display_name":"Hermes","description":"General assistant","is_default":true,"skill_count":4},{"name":"coder","display_name":"Code Fox","description":"Builds Android apps","description_auto":false,"provider":"nous","model":"hermes-4","skill_count":9,"gateway_running":true}]}"""))
            server.enqueue(MockResponse().setBody("""{"active":"coder","current":"default"}"""))
            server.enqueue(MockResponse().setBody("""{"ok":true,"name":"research","path":"/profiles/research"}"""))
            server.enqueue(MockResponse().setBody("""{"ok":true,"name":"engineering","path":"/profiles/engineering"}"""))
            server.enqueue(MockResponse().setBody("""{"ok":true,"active":"engineering"}"""))
            server.enqueue(MockResponse().setBody("""{"ok":true,"path":"/profiles/engineering"}"""))
            server.enqueue(MockResponse().setBody("""{"content":"# Researcher\n\nBe precise.","exists":true}"""))
            server.enqueue(MockResponse().setBody("""{"command":"hermes --profile engineering"}"""))
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            server.enqueue(MockResponse().setBody("""{"ok":true,"provider":"nous","model":"hermes-4"}"""))

            val profiles = client.profiles(config, "secret")
            val active = client.activeProfile(config, "secret")
            client.createProfile(
                config,
                "secret",
                ProfileCreatePayload(name = "research", cloneFrom = "coder", cloneAll = true),
            )
            client.renameProfile(config, "secret", "research", "engineering")
            client.setActiveProfile(config, "secret", "engineering")
            client.deleteProfile(config, "secret", "engineering")
            val soul = client.profileSoul(config, "secret", "engineering")
            val setup = client.profileSetupCommand(config, "secret", "engineering")
            client.updateProfileSoul(config, "secret", "engineering", "# Researcher\n\nBe precise.")
            client.updateProfileModel(config, "secret", "engineering", "nous", "hermes-4")

            assertTrue(profiles.profiles.first().isDefault)
            assertEquals("Hermes", profiles.profiles.first().displayName)
            assertEquals("Builds Android apps", profiles.profiles.last().description)
            assertTrue(profiles.profiles.last().gatewayRunning)
            assertEquals("hermes-4", profiles.profiles.last().model)
            assertEquals("coder", active.active)
            assertEquals("default", active.current)
            assertEquals("# Researcher\n\nBe precise.", soul.content)
            assertTrue(soul.exists)
            assertEquals("hermes --profile engineering", setup.command)

            val requests = List(10) { server.takeRequest() }
            assertEquals("/api/profiles", requests[0].path)
            assertEquals("/api/profiles/active", requests[1].path)
            assertEquals("POST", requests[2].method)
            assertTrue(requests[2].body.readUtf8().contains("\"clone_from\":\"coder\""))
            assertEquals("PATCH", requests[3].method)
            assertEquals("/api/profiles/research", requests[3].path)
            assertTrue(requests[3].body.readUtf8().contains("\"new_name\":\"engineering\""))
            assertEquals("POST", requests[4].method)
            assertEquals("/api/profiles/active", requests[4].path)
            assertEquals("DELETE", requests[5].method)
            assertEquals("/api/profiles/engineering", requests[5].path)
            assertEquals("/api/profiles/engineering/soul", requests[6].path)
            assertEquals("/api/profiles/engineering/setup-command", requests[7].path)
            assertEquals("PUT", requests[8].method)
            assertEquals("/api/profiles/engineering/soul", requests[8].path)
            assertTrue(requests[8].body.readUtf8().contains("Be precise."))
            assertEquals("PUT", requests[9].method)
            assertEquals("/api/profiles/engineering/model", requests[9].path)
            assertEquals("{\"provider\":\"nous\",\"model\":\"hermes-4\"}", requests[9].body.readUtf8())
            assertTrue(requests.all { it.getHeader("Authorization") == "Bearer secret" })
        }
    }

    @Test
    fun `profile mutations reject missing acknowledgements and oversized soul content`() = runTest {
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
            server.enqueue(MockResponse().setBody("""{"ok":false}"""))

            assertTrue(runCatching {
                client.updateProfileSoul(config, "secret", "coder", "content")
            }.isFailure)
            assertTrue(runCatching {
                client.updateProfileSoul(config, "secret", "coder", "x".repeat(131_073))
            }.isFailure)
            assertEquals(1, server.requestCount)
        }
    }
}
