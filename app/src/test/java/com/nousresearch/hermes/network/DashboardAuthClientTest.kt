package com.nousresearch.hermes.network

import com.nousresearch.hermes.data.AuthMode
import com.nousresearch.hermes.data.BackendConfig
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardAuthClientTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `password login posts expected body and returns complete session cookie bundle`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(passwordProviderResponse("company-password"))
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Set-Cookie", "__Host-hermes_session_at=session-value; Path=/; Secure; HttpOnly; SameSite=Lax")
                    .addHeader("Set-Cookie", "__Host-hermes_session_rt=refresh-value; Path=/; Secure; HttpOnly; SameSite=Lax")
                    .addHeader("Set-Cookie", "__Host-hermes_session_provider=password-provider; Path=/; Secure; HttpOnly; SameSite=Lax")
                    .setBody("""{"ok":true,"next":"/"}"""),
            )
            server.start()
            val client = DashboardAuthClient(OkHttpClient(), json)

            val cookie = client.login(config(server), "dashboard-user", "password-value")

            assertEquals(
                "__Host-hermes_session_at=session-value; __Host-hermes_session_rt=refresh-value; " +
                    "__Host-hermes_session_provider=password-provider",
                cookie.headerValue,
            )
            assertEquals("/api/auth/providers", server.takeRequest().path)
            val request = server.takeRequest()
            assertEquals("/auth/password-login", request.path)
            assertEquals("POST", request.method)
            assertEquals("application/json; charset=utf-8", request.getHeader("Content-Type"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"provider\":\"company-password\""))
            assertTrue(body.contains("\"username\":\"dashboard-user\""))
            assertTrue(body.contains("\"password\":\"password-value\""))
            assertFalse(cookie.headerValue.contains("password-value"))
            assertFalse(cookie.toString().contains("session-value"))
            assertFalse(cookie.toString().contains("refresh-value"))
        }
    }

    @Test
    fun `session cookie bundle merges rotated cookies and ignores unrelated cookies`() {
        val cookie = checkNotNull(
            DashboardSessionCredential.fromSetCookieHeaders(
                listOf(
                    "hermes_session_at=access-1; Path=/; HttpOnly",
                    "hermes_session_rt=refresh-1; Path=/; HttpOnly",
                    "hermes_session_provider=testpw; Path=/; HttpOnly",
                    "analytics=not-a-session; Path=/",
                ),
            ),
        )

        assertTrue(
            cookie.mergeSetCookieHeaders(
                listOf(
                    "hermes_session_at=access-2; Path=/; HttpOnly",
                    "hermes_session_rt=refresh-2; Path=/; HttpOnly",
                    "analytics=still-not-a-session; Path=/",
                ),
            ),
        )

        assertEquals(
            "hermes_session_at=access-2; hermes_session_rt=refresh-2; hermes_session_provider=testpw",
            cookie.headerValue,
        )
    }

    @Test
    fun `missing or malformed access cookie rejects login`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(passwordProviderResponse())
            server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
            server.enqueue(passwordProviderResponse())
            server.enqueue(MockResponse().setResponseCode(200).addHeader("Set-Cookie", "hermes_session_at=; Path=/; HttpOnly").setBody("""{"ok":true}"""))
            server.start()
            val client = DashboardAuthClient(OkHttpClient(), json)

            val missing = runCatching { client.login(config(server), "user", "password") }.exceptionOrNull()
            val malformed = runCatching { client.login(config(server), "user", "password") }.exceptionOrNull()

            assertTrue(missing is DashboardAuthenticationException)
            assertTrue(malformed is DashboardAuthenticationException)
        }
    }

    @Test
    fun `rejected credentials return a generic authentication failure`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(passwordProviderResponse())
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"Invalid credentials"}"""))
            server.start()
            val client = DashboardAuthClient(OkHttpClient(), json)

            val failure = runCatching { client.login(config(server), "alice@example.test", "wrong") }.exceptionOrNull()

            assertTrue(failure is DashboardAuthenticationException)
            assertFalse(failure?.message.orEmpty().contains("alice@example.test"))
        }
    }

    @Test
    fun `login rejects ambiguous or absent password providers before submitting credentials`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"providers":[{"name":"oauth","display_name":"OAuth","supports_password":false}]}""",
                ),
            )
            server.enqueue(
                MockResponse().setBody(
                    """{"providers":[{"name":"one","display_name":"One","supports_password":true},{"name":"two","display_name":"Two","supports_password":true}]}""",
                ),
            )
            server.start()
            val client = DashboardAuthClient(OkHttpClient(), json)

            val absent = runCatching { client.login(config(server), "user", "password") }.exceptionOrNull()
            val ambiguous = runCatching { client.login(config(server), "user", "password") }.exceptionOrNull()

            assertTrue(absent is DashboardAuthenticationException)
            assertTrue(ambiguous is DashboardAuthenticationException)
            assertEquals(2, server.requestCount)
        }
    }

    @Test
    fun `discovery exposes only bounded password providers with display names`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"providers":[{"name":"basic","display_name":"Password","supports_password":true},{"name":"oauth","display_name":"OAuth","supports_password":false}]}""",
                ),
            )
            server.start()
            val providers = DashboardAuthClient(OkHttpClient(), json).discoverPasswordProviders(config(server))

            assertEquals(1, providers.size)
            assertEquals("basic", providers.single().name)
            assertEquals("Password", providers.single().displayName)
            assertTrue(providers.single().supportsPassword)
        }
    }

    @Test
    fun `private-network HTTP selection reaches a cleartext dashboard from https input`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(passwordProviderResponse("basic"))
            server.start()
            val config = config(server).copy(
                baseUrl = server.url("/").newBuilder().scheme("https").build().toString().trimEnd('/'),
            )

            val providers = DashboardAuthClient(OkHttpClient(), json).discoverPasswordProviders(config)

            assertEquals("basic", providers.single().name)
            assertEquals("/api/auth/providers", server.takeRequest().path)
        }
    }

    @Test
    fun `explicit provider is validated against fresh discovery and submitted exactly`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(
                MockResponse().setBody(
                    """{"providers":[{"name":"one","display_name":"One","supports_password":true},{"name":"two","display_name":"Two","supports_password":true}]}""",
                ),
            )
            server.enqueue(
                MockResponse()
                    .setResponseCode(200)
                    .addHeader("Set-Cookie", "hermes_session_at=access; Path=/; HttpOnly")
                    .setBody("{}"),
            )
            server.start()
            DashboardAuthClient(OkHttpClient(), json).login(config(server), "user", "password", "two")

            server.takeRequest()
            val loginRequest = server.takeRequest()
            assertTrue(loginRequest.body.readUtf8().contains("\"provider\":\"two\""))
        }
    }

    @Test
    fun `stale non-password malicious and duplicate selections fail closed before login`() = runTest {
        val cases = listOf(
            "stale" to """{"providers":[{"name":"actual","display_name":"Actual","supports_password":true}]}""",
            "oauth" to """{"providers":[{"name":"oauth","display_name":"OAuth","supports_password":false}]}""",
            "bad\u0000name" to """{"providers":[{"name":"actual","display_name":"Actual","supports_password":true}]}""",
            "duplicate" to """{"providers":[{"name":"same","display_name":"A","supports_password":true},{"name":"same","display_name":"B","supports_password":true}]}""",
        )
        cases.forEach { (selected, providers) ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setBody(providers))
                server.start()
                val failure = runCatching {
                    DashboardAuthClient(OkHttpClient(), json).login(config(server), "user", "password", selected)
                }.exceptionOrNull()

                assertTrue(failure is DashboardAuthenticationException)
                assertEquals(1, server.requestCount)
            }
        }
    }

    @Test
    fun `credential requests never follow redirects`() = runTest {
        MockWebServer().use { target ->
            MockWebServer().use { dashboard ->
                target.start()
                dashboard.enqueue(passwordProviderResponse("basic"))
                dashboard.enqueue(
                    MockResponse()
                        .setResponseCode(307)
                        .addHeader("Location", target.url("/capture")),
                )
                dashboard.enqueue(
                    MockResponse()
                        .setResponseCode(307)
                        .addHeader("Location", target.url("/capture-ticket")),
                )
                dashboard.start()
                val client = DashboardAuthClient(OkHttpClient(), json)

                val loginFailure = runCatching {
                    client.login(config(dashboard), "private-user", "private-password")
                }.exceptionOrNull()
                val ticketFailure = runCatching {
                    client.mintWebSocketTicket(
                        config(dashboard),
                        DashboardSessionCredential("hermes_session_at", "private-session"),
                    )
                }.exceptionOrNull()

                assertTrue(loginFailure is DashboardAuthenticationException)
                assertTrue(ticketFailure is DashboardAuthenticationException)
                assertEquals(0, target.requestCount)
                assertEquals(3, dashboard.requestCount)
            }
        }
    }

    private fun passwordProviderResponse(name: String? = null) = MockResponse().setBody(
        name?.let { """{"providers":[{"name":"$it","display_name":"Password","supports_password":true}]}""" }
            ?: checkNotNull(javaClass.getResource("/fixtures/dashboard-auth-providers-f15a38ee.json")).readText(),
    )

    private fun config(server: MockWebServer) = BackendConfig(
        id = "fake",
        label = "Fake Dashboard",
        baseUrl = server.url("/").toString().trimEnd('/'),
        authMode = AuthMode.DASHBOARD_SESSION,
        allowInsecurePrivateNetwork = true,
    )
}
