package com.nousresearch.hermes.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.core.content.FileProvider
import com.nousresearch.hermes.network.DashboardAuthClient
import com.nousresearch.hermes.network.DashboardSessionCredential
import com.nousresearch.hermes.network.HermesRestClient
import com.nousresearch.hermes.domain.TimelineItem
import com.nousresearch.hermes.protocol.BotGroupSnapshot
import com.nousresearch.hermes.protocol.BotGroupMember
import com.nousresearch.hermes.protocol.GatewayConnectionState
import com.nousresearch.hermes.protocol.GatewayEvent
import com.nousresearch.hermes.protocol.HermesGatewayClient
import com.nousresearch.hermes.protocol.HermesRpcException
import com.nousresearch.hermes.protocol.StoredSession
import com.nousresearch.hermes.platform.newCameraCaptureUri
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class HermesRepositoryBillingTest {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    @Test
    fun `quick agent creation uses current profile contract and opens its canonical chat`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher(withProfiles = true)
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, backend.id)
            gateway.enqueue("profiles.create", json.parseToJsonElement("""{"ok":true,"name":"helper"}"""))
            gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"helper"}]}"""))
            gateway.enqueue("session.list", json.parseToJsonElement("""{"sessions":[]}"""))
            gateway.enqueue(
                "session.create",
                json.parseToJsonElement(
                    """{"session_id":"live-helper","stored_session_id":"helper-chat","messages":[],"info":{"stored_session_id":"helper-chat"}}""",
                ),
            )
            gateway.enqueue("model.options", json.parseToJsonElement("""{"providers":[]}"""))
            gateway.enqueue("session.title", json.parseToJsonElement("""{"ok":true}"""))
            gateway.enqueue("prompt.submit", json.parseToJsonElement("""{"status":"streaming"}"""))

            assertTrue(
                repository.createBotAgent(
                    BotAgentDraft(name = "helper", description = "Plans releases", soul = "Be careful."),
                ),
            )

            val create = gateway.requests.single { it.method == "profiles.create" }.params.toString()
            assertTrue(create.contains("\"description\":\"Plans releases\""))
            assertTrue(create.contains("\"mirror_credentials\":true"))
            assertTrue(create.contains("\"share_auth\":true"))
            assertEquals("helper", repository.state.value.activeStoredSession?.profile)
            assertEquals("Bot Chat", repository.state.value.activeStoredSession?.title)
        }
    }

    @Test
    fun `quick agent creation retries only the canonical chat after a partial failure`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher(withProfiles = true)
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, backend.id)
            gateway.enqueue("profiles.create", json.parseToJsonElement("""{"ok":true,"name":"helper"}"""))
            gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"helper"}]}"""))
            gateway.enqueueFailure("session.list", IOException("temporary lookup failure"))

            assertFalse(repository.createBotAgent(BotAgentDraft(name = "helper")))

            gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"helper"}]}"""))
            gateway.enqueue("session.list", json.parseToJsonElement("""{"sessions":[]}"""))
            gateway.enqueue(
                "session.create",
                json.parseToJsonElement(
                    """{"session_id":"live-helper","stored_session_id":"helper-chat","messages":[],"info":{"stored_session_id":"helper-chat"}}""",
                ),
            )
            gateway.enqueue("model.options", json.parseToJsonElement("""{"providers":[]}"""))
            gateway.enqueue("session.title", json.parseToJsonElement("""{"ok":true}"""))
            gateway.enqueue("prompt.submit", json.parseToJsonElement("""{"status":"streaming"}"""))

            assertTrue(repository.createBotAgent(BotAgentDraft(name = "helper")))
            assertEquals(1, gateway.requests.count { it.method == "profiles.create" })
        }
    }

    @Test
    fun `agent creation targets an isolated backend without switching the active conversation`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val current = backend(server)
            val remote = current.copy(id = "remote-${BACKEND_IDS.incrementAndGet()}", label = "Remote")
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            val scoped = RecordingGateway(json)
            gateway.forkedGateway = scoped
            registry.save(current)
            registry.save(remote)
            registry.select(current.id)
            credentials.put(current.id, SESSION_COOKIE)
            credentials.put(remote.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, current.id)
            scoped.enqueue("profiles.create", json.parseToJsonElement("""{"ok":true,"name":"helper"}"""))
            scoped.enqueue("session.list", json.parseToJsonElement("""{"sessions":[]}"""))
            scoped.enqueue(
                "session.create",
                json.parseToJsonElement(
                    """{"session_id":"remote-live","stored_session_id":"remote-chat","messages":[],"info":{"stored_session_id":"remote-chat"}}""",
                ),
            )
            scoped.enqueueFailure("session.title", HermesRpcException("Method not found", -32601))
            scoped.enqueue("prompt.submit", json.parseToJsonElement("""{"status":"streaming"}"""))

            assertTrue(repository.createBotAgent(BotAgentDraft(name = "helper"), backendId = remote.id))

            assertEquals(current.id, repository.state.value.backend?.id)
            assertEquals(listOf(remote.id), scoped.connectedBackendIds)
            assertTrue(scoped.requests.any { it.method == "profiles.create" })
            val intro = scoped.requests.single { it.method == "prompt.submit" }.params.toString()
            assertTrue(intro.contains("\"text\""))
            assertFalse(intro.contains("\"message\""))
            assertFalse(gateway.requests.any { it.method == "profiles.create" })
        }
    }

    @Test
    fun `group creation retries CAS with one durable room identity`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher(withProfiles = true)
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, backend.id)
            gateway.enqueue(
                "profiles.list",
                json.parseToJsonElement("""{"profiles":[{"name":"default","ui_meta_revisions":{"hermes-bots-groups":4}}]}"""),
            )
            gateway.enqueue("profiles.configure", json.parseToJsonElement("""{"applied":{"ui_meta":false}}"""))
            gateway.enqueue(
                "profiles.list",
                json.parseToJsonElement("""{"profiles":[{"name":"default","ui_meta_revisions":{"hermes-bots-groups":5}}]}"""),
            )
            gateway.enqueue(
                "profiles.configure",
                json.parseToJsonElement("""{"applied":{"ui_meta":true,"ui_meta_revisions":{"hermes-bots-groups":6}}}"""),
            )
            listOf("coder", "reviewer").forEach { member ->
                gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"$member"}]}"""))
                gateway.enqueue("profiles.configure", json.parseToJsonElement("""{"applied":{"ui_meta":true}}"""))
            }

            val room = repository.createBotGroup(
                "Launch",
                listOf(
                    com.nousresearch.hermes.protocol.BotGroupMember("coder", "coder-mac", backend.id),
                    com.nousresearch.hermes.protocol.BotGroupMember("reviewer", "reviewer-mac", backend.id),
                ),
            )

            assertTrue(room.roomId.isNotBlank())
            assertEquals(room.roomId, repository.state.value.botGroups.rooms.single().roomId)
            val writes = gateway.requests.filter { it.method == "profiles.configure" }.map { it.params.toString() }
            assertEquals(4, writes.size)
            assertTrue(writes[0].contains("\"hermes-bots-groups\":4"))
            assertTrue(writes[1].contains("\"hermes-bots-groups\":5"))
            val roomId = Regex("\"roomId\":\"([^\"]+)\"").find(writes[0])!!.groupValues[1]
            assertTrue(writes[1].contains("\"roomId\":\"$roomId\""))
            assertTrue(writes[2].contains("\"name\":\"coder\""))
            assertTrue(writes[2].contains("\"groups\":[\"Launch\"]"))
            assertTrue(writes[3].contains("\"name\":\"reviewer\""))
        }
    }

    @Test
    fun `group candidates qualify same named bots across reachable sources`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val current = backend(server)
            val remote = current.copy(id = "remote-${BACKEND_IDS.incrementAndGet()}", label = "Cloud Box")
            val remoteTwin = current.copy(id = "remote-twin-${BACKEND_IDS.incrementAndGet()}", label = "Cloud Box")
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            val scoped = RecordingGateway(json)
            gateway.forkedGateway = scoped
            registry.save(current)
            registry.save(remote)
            registry.save(remoteTwin)
            registry.select(current.id)
            credentials.put(current.id, SESSION_COOKIE)
            credentials.put(remote.id, SESSION_COOKIE)
            credentials.put(remoteTwin.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, current.id)
            gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"coder"}]}"""))
            scoped.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"coder"}]}"""))
            scoped.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"coder"}]}"""))

            val result = repository.botGroupCandidates()

            assertEquals(
                listOf(current, remote, remoteTwin).map { "coder-${it.id.take(8)}" }.sorted(),
                result.candidates.map { it.handle }.sorted(),
            )
            assertEquals(result.candidates.size, result.candidates.map { it.handle.lowercase() }.toSet().size)
            assertEquals(result.candidates, repository.state.value.botCandidates)
            assertEquals(current.id, repository.state.value.backend?.id)
            assertEquals(listOf(remote.id, remoteTwin.id), scoped.connectedBackendIds)
        }
    }

    @Test
    fun `bot roster retains only the failed sources last known rows as offline`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val current = backend(server).copy(id = "personal", label = "Personal")
            val remote = current.copy(id = "cloud", label = "Cloud")
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            val scoped = RecordingGateway(json)
            gateway.forkedGateway = scoped
            registry.save(current)
            registry.save(remote)
            registry.select(current.id)
            credentials.put(current.id, SESSION_COOKIE)
            credentials.put(remote.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, current.id)
            gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"coder"}]}"""))
            scoped.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"reviewer"}]}"""))
            val initial = repository.botGroupCandidates()
            gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"coder"}]}"""))
            scoped.enqueueFailure("profiles.list", IOException("cloud offline"))

            val refreshed = repository.botGroupCandidates()

            assertTrue("Cloud" in refreshed.unavailableSources)
            assertFalse(refreshed.candidates.single { it.backendId == current.id }.offline)
            assertTrue(refreshed.candidates.single { it.backendId == remote.id }.offline)
            assertEquals("reviewer", refreshed.candidates.single { it.backendId == remote.id }.profile.name)
            assertEquals(
                initial.candidates.single { it.backendId == remote.id }.handle,
                refreshed.candidates.single { it.backendId == remote.id }.handle,
            )
            val offlineGroup = runCatching {
                repository.createBotGroup(
                    "Unsafe",
                    listOf(
                        BotGroupMember("coder", "coder", current.id),
                        BotGroupMember("reviewer", "reviewer", remote.id),
                    ),
                )
            }.exceptionOrNull()
            assertTrue(offlineGroup is IllegalArgumentException)
            scoped.enqueue(
                "profiles.configure",
                json.parseToJsonElement("""{"applied":{"ui_meta":true}}"""),
            )
            gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"coder"}]}"""))
            scoped.enqueue(
                "profiles.list",
                json.parseToJsonElement(
                    """{"profiles":[{"name":"reviewer","ui_meta":{"hermes-bots":{"hidden":true}}}]}""",
                ),
            )

            repository.setBotHidden("reviewer", hidden = true, backendId = remote.id)

            assertEquals(current.id, repository.state.value.backend?.id)
            assertTrue(scoped.requests.any { it.method == "profiles.configure" })
            assertTrue(repository.state.value.botCandidates.single { it.backendId == remote.id }.profile.uiMeta.toString().contains("hidden"))
        }
    }

    @Test
    fun `forgotten source cannot republish an in flight bot roster result`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val current = backend(server).copy(id = "personal", label = "Personal")
            val remote = current.copy(id = "cloud", label = "Cloud")
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            val scoped = RecordingGateway(json)
            gateway.forkedGateway = scoped
            registry.save(current)
            registry.save(remote)
            registry.select(current.id)
            credentials.put(current.id, SESSION_COOKIE)
            credentials.put(remote.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, current.id)
            gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"coder"}]}"""))
            val remoteStarted = CompletableDeferred<Unit>()
            val remoteResult = CompletableDeferred<JsonElement>()
            scoped.enqueueBlock("profiles.list") {
                remoteStarted.complete(Unit)
                remoteResult.await()
            }

            val refresh = async { repository.botGroupCandidates() }
            remoteStarted.await()
            repository.forgetBackend(remote.id)
            remoteResult.complete(json.parseToJsonElement("""{"profiles":[{"name":"reviewer"}]}"""))
            val result = refresh.await()

            assertTrue(result.candidates.none { it.backendId == remote.id })
            assertTrue(repository.state.value.botCandidates.none { it.backendId == remote.id })
            assertNull(credentials.get(remote.id))
        }
    }

    @Test
    fun `disconnect and forget invalidates an in flight active bot roster`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server).copy(id = "active-forget", label = "Active")
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, backend.id)
            val requestStarted = CompletableDeferred<Unit>()
            val response = CompletableDeferred<JsonElement>()
            gateway.enqueueBlock("profiles.list") {
                requestStarted.complete(Unit)
                response.await()
            }

            val refresh = async { repository.botGroupCandidates() }
            requestStarted.await()
            repository.disconnectAndForget()
            response.complete(json.parseToJsonElement("""{"profiles":[{"name":"stale"}]}"""))
            val result = refresh.await()

            assertTrue(result.candidates.none { it.backendId == backend.id })
            assertTrue(repository.state.value.botCandidates.none { it.backendId == backend.id })
            assertNull(credentials.get(backend.id))
        }
    }

    @Test
    fun `remote bot chat uses only its source and preserves the active backend`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val current = backend(server).copy(id = "personal-chat", label = "Personal")
            val remote = current.copy(id = "cloud-chat", label = "Cloud")
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            val scoped = RecordingGateway(json)
            gateway.forkedGateway = scoped
            registry.save(current)
            registry.save(remote)
            registry.select(current.id)
            credentials.put(current.id, SESSION_COOKIE)
            credentials.put(remote.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, current.id)
            scoped.enqueue(
                "session.list",
                json.parseToJsonElement("""{"sessions":[{"id":"remote-bot","title":"Bot Chat"}]}"""),
            )
            scoped.enqueue(
                "session.resume",
                json.parseToJsonElement(
                    """{"session_id":"remote-live","session_key":"remote-bot","messages":[{"role":"assistant","content":"Earlier"}]}""",
                ),
            )
            scoped.enqueue("prompt.submit", json.parseToJsonElement("""{"status":"streaming"}"""))
            scoped.enqueue(
                "session.resume",
                checkNotNull(javaClass.getResource("/fixtures/bot-direct-chat-resume-64e5d89.json"))
                    .readText().let(json::parseToJsonElement),
            )

            val chat = repository.botDirectChat(remote.id, "reviewer", "Status?")

            assertEquals(listOf("Earlier", "Status?", "Green."), chat.messages.map { it.text })
            assertEquals(current.id, repository.state.value.backend?.id)
            assertTrue(gateway.requests.none { it.method in setOf("session.list", "session.resume", "prompt.submit") })
            assertEquals(remote.id, scoped.connectedBackendIds.single())
            val submit = scoped.requests.single { it.method == "prompt.submit" }.params.toString()
            assertTrue(submit.contains("\"text\":\"Status?\""))
            assertFalse(submit.contains("\"message\""))
        }
    }

    @Test
    fun `remote bot chat creation titles legacy sessions and busy loads return immediately`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val current = backend(server).copy(id = "personal-direct", label = "Personal")
            val remote = current.copy(id = "cloud-direct", label = "Cloud")
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            val scoped = RecordingGateway(json)
            gateway.forkedGateway = scoped
            registry.save(current)
            registry.save(remote)
            registry.select(current.id)
            credentials.put(current.id, SESSION_COOKIE)
            credentials.put(remote.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, current.id)
            scoped.enqueue("session.list", json.parseToJsonElement("""{"sessions":[]}"""))
            scoped.enqueue(
                "session.create",
                json.parseToJsonElement("""{"session_id":"new-live","stored_session_id":"new-bot","messages":[]}"""),
            )
            scoped.enqueueFailure("session.title", HermesRpcException("Method not found", -32601))
            scoped.enqueue("prompt.submit", json.parseToJsonElement("""{"status":"streaming"}"""))

            assertTrue(repository.botDirectChat(remote.id, "reviewer").messages.isEmpty())
            assertTrue(scoped.requests.single { it.method == "session.title" }.params.toString().contains("Bot Chat"))
            val intro = scoped.requests.single { it.method == "prompt.submit" }.params.toString()
            assertTrue(intro.contains("\"text\""))
            assertFalse(intro.contains("\"message\""))
            scoped.enqueue(
                "session.list",
                json.parseToJsonElement("""{"sessions":[{"id":"new-bot","title":"Bot Chat"}]}"""),
            )
            scoped.enqueue(
                "session.resume",
                json.parseToJsonElement(
                    """{"session_id":"new-live","session_key":"new-bot","running":true,"messages":[{"role":"assistant","content":"Working"}]}""",
                ),
            )

            val busy = withTimeout(1_000) { repository.botDirectChat(remote.id, "reviewer") }

            assertEquals(listOf("Working"), busy.messages.map { it.text })
            assertEquals(current.id, repository.state.value.backend?.id)
        }
    }

    @Test
    fun `group room metadata is synchronized to every reachable source`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher(withProfiles = true)
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val current = backend(server).copy(id = "personal", label = "Personal")
            val remote = current.copy(id = "cloud", label = "Cloud box")
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            val scoped = RecordingGateway(json)
            gateway.forkedGateway = scoped
            registry.save(current)
            registry.save(remote)
            registry.select(current.id)
            credentials.put(current.id, SESSION_COOKIE)
            credentials.put(remote.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, current.id)
            gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"default","ui_meta_revisions":{"hermes-bots-groups":2}}]}"""))
            gateway.enqueue("profiles.configure", json.parseToJsonElement("""{"applied":{"ui_meta":true,"ui_meta_revisions":{"hermes-bots-groups":3}}}"""))
            scoped.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"default","ui_meta_revisions":{"hermes-bots-groups":5}}]}"""))
            scoped.enqueue("profiles.configure", json.parseToJsonElement("""{"applied":{"ui_meta":true,"ui_meta_revisions":{"hermes-bots-groups":6}}}"""))
            gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"coder"}]}"""))
            gateway.enqueue("profiles.configure", json.parseToJsonElement("""{"applied":{"ui_meta":true}}"""))
            scoped.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"reviewer"}]}"""))
            scoped.enqueue("profiles.configure", json.parseToJsonElement("""{"applied":{"ui_meta":true}}"""))

            val room = repository.createBotGroup(
                "Launch",
                listOf(
                    com.nousresearch.hermes.protocol.BotGroupMember("coder", "coder-personal", current.id),
                    com.nousresearch.hermes.protocol.BotGroupMember("reviewer", "reviewer-cloud", remote.id),
                ),
            )

            val localMetadata = gateway.requests.first { request ->
                request.method == "profiles.configure" && request.params.toString().contains("hermes-bots-groups")
            }.params.toString()
            val remoteMetadata = scoped.requests.first { request ->
                request.method == "profiles.configure" && request.params.toString().contains("hermes-bots-groups")
            }.params.toString()
            assertTrue(localMetadata.contains(room.roomId))
            assertTrue(remoteMetadata.contains(room.roomId))
            assertEquals(current.id, repository.state.value.backend?.id)
        }
    }

    @Test
    fun `offline group source is replayed automatically after reconnect`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher(withProfiles = true)
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val current = backend(server).copy(id = "retry-personal", label = "Personal")
            val remote = current.copy(id = "retry-cloud", label = "Cloud")
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            val scoped = RecordingGateway(json)
            gateway.forkedGateway = scoped
            registry.backends.first().forEach { registry.remove(it.id) }
            registry.save(current)
            registry.save(remote)
            registry.select(current.id)
            credentials.put(current.id, SESSION_COOKIE)
            credentials.put(remote.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, current.id)
            val members = listOf(
                com.nousresearch.hermes.protocol.BotGroupMember("coder", "coder", current.id),
                com.nousresearch.hermes.protocol.BotGroupMember("reviewer", "reviewer", current.id),
            )
            gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"default"}]}"""))
            gateway.enqueue("profiles.configure", json.parseToJsonElement("""{"applied":{"ui_meta":true}}"""))
            scoped.enqueueFailure("profiles.list", IOException("offline"))
            members.forEach { member ->
                gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"${member.name}"}]}"""))
                gateway.enqueue("profiles.configure", json.parseToJsonElement("""{"applied":{"ui_meta":true}}"""))
            }

            val room = repository.createBotGroup("Launch", members)
            assertTrue(repository.state.value.botGroups.error.orEmpty().contains("sync will retry"))
            val snapshot = BotGroupSnapshot().upsert(room, System.currentTimeMillis()).asUiMeta(json)
            gateway.enqueue(
                "profiles.list",
                json.parseToJsonElement("""{"profiles":[{"name":"default","ui_meta":{"hermes-bots-groups":$snapshot}}]}"""),
            )
            gateway.enqueue("profiles.configure", json.parseToJsonElement("""{"applied":{"ui_meta":true}}"""))
            scoped.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"default"}]}"""))
            scoped.enqueue("profiles.configure", json.parseToJsonElement("""{"applied":{"ui_meta":true}}"""))

            runCatching {
                withTimeout(8_000) { repository.state.first { it.botGroups.error == null } }
            }.getOrElse {
                error(
                    "retry error=${repository.state.value.botGroups.error} " +
                        "local=${gateway.requests.groupingBy { request -> request.method }.eachCount()} " +
                        "remote=${scoped.requests.groupingBy { request -> request.method }.eachCount()}",
                )
            }

            assertTrue(scoped.requests.any {
                it.method == "profiles.configure" && it.params.toString().contains(room.roomId)
            })
        }
    }

    @Test
    fun `group driver stops at ten generated messages within three rounds`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher(withProfiles = true)
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.backends.first().forEach { registry.remove(it.id) }
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, backend.id)
            val members = (1..4).map { index ->
                com.nousresearch.hermes.protocol.BotGroupMember("bot$index", "bot$index", backend.id)
            }
            gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"default"}]}"""))
            gateway.enqueue("profiles.configure", json.parseToJsonElement("""{"applied":{"ui_meta":true}}"""))
            members.forEach { member ->
                gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"${member.name}"}]}"""))
                gateway.enqueue("profiles.configure", json.parseToJsonElement("""{"applied":{"ui_meta":true}}"""))
            }
            repeat(31) {
                gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"default"}]}"""))
                gateway.enqueue("profiles.configure", json.parseToJsonElement("""{"applied":{"ui_meta":true}}"""))
            }
            val room = repository.createBotGroup("Launch", members)
            repeat(10) { index ->
                gateway.enqueue("session.list", json.parseToJsonElement("""{"sessions":[]}"""))
                gateway.enqueue(
                    "session.create",
                    json.parseToJsonElement("""{"session_id":"live-$index","stored_session_id":"stored-$index"}"""),
                )
                gateway.enqueue("prompt.submit", json.parseToJsonElement("""{"status":"streaming"}"""))
                gateway.enqueue(
                    "session.resume",
                    json.parseToJsonElement(
                        """{"session_id":"live-$index","session_key":"stored-$index","messages":[{"role":"assistant","text":"reply $index"}]}""",
                    ),
                )
            }

            repository.sendBotGroupMessage(room.roomId, "Ship it")

            assertEquals(10, gateway.requests.count { it.method == "prompt.submit" })
            assertEquals(12, repository.state.value.botGroups.rooms.single().log.size)
            assertEquals(null, repository.state.value.botGroups.runningRoomId)
        }
    }

    @Test
    fun `group approval stays visible and routes to the member session`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher(withProfiles = true)
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, backend.id)
            val members = listOf(
                com.nousresearch.hermes.protocol.BotGroupMember("coder", "coder", backend.id),
                com.nousresearch.hermes.protocol.BotGroupMember("reviewer", "reviewer", backend.id),
            )
            gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"default"}]}"""))
            gateway.enqueue("profiles.configure", json.parseToJsonElement("""{"applied":{"ui_meta":true}}"""))
            members.forEach { member ->
                gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"${member.name}"}]}"""))
                gateway.enqueue("profiles.configure", json.parseToJsonElement("""{"applied":{"ui_meta":true}}"""))
            }
            repeat(5) {
                gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"default"}]}"""))
                gateway.enqueue("profiles.configure", json.parseToJsonElement("""{"applied":{"ui_meta":true}}"""))
            }
            val room = repository.createBotGroup("Launch", members)
            repeat(2) { index ->
                gateway.enqueue("session.list", json.parseToJsonElement("""{"sessions":[]}"""))
                gateway.enqueue("session.create", json.parseToJsonElement("""{"session_id":"live-$index","stored_session_id":"stored-$index"}"""))
                gateway.enqueue("prompt.submit", json.parseToJsonElement("""{"status":"streaming"}"""))
            }
            gateway.enqueue(
                "session.resume",
                json.parseToJsonElement(
                    """{"session_id":"live-0","pending_approval":{"request_id":"approve-1","command":"deploy","description":"Deploy now?","choices":["once","deny"]}}""",
                ),
            )
            gateway.enqueue(
                "session.resume",
                json.parseToJsonElement("""{"session_id":"live-0","messages":[{"role":"assistant","text":"(pass)"}]}"""),
            )
            gateway.enqueue(
                "session.resume",
                json.parseToJsonElement("""{"session_id":"live-1","messages":[{"role":"assistant","text":"pass"}]}"""),
            )
            gateway.enqueue("approval.respond", json.parseToJsonElement("""{"ok":true}"""))

            val sending = launch { repository.sendBotGroupMessage(room.roomId, "Can we deploy?") }
            val pending = withTimeout(5_000) {
                repository.state.first { it.botGroups.blockingRequests.isNotEmpty() }
            }.botGroups.blockingRequests.single()
            assertTrue(room.roomId in repository.state.value.botGroups.needsYouRoomIds)

            repository.answerBotGroupBlocking(pending.requestId, mapOf("choice" to listOf("once")))
            sending.join()

            val response = gateway.requests.single { it.method == "approval.respond" }.params.toString()
            assertTrue(response.contains("\"session_id\":\"live-0\""))
            assertTrue(response.contains("\"request_id\":\"approve-1\""))
            assertTrue(response.contains("\"choice\":\"once\""))
            assertTrue(repository.state.value.botGroups.blockingRequests.isEmpty())
        }
    }

    @Test
    fun `group attachment failure is reported in the durable room transcript`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher(withProfiles = true)
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, backend.id)
            val members = listOf(
                com.nousresearch.hermes.protocol.BotGroupMember("coder", "coder", backend.id),
                com.nousresearch.hermes.protocol.BotGroupMember("reviewer", "reviewer", backend.id),
            )
            gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"default"}]}"""))
            gateway.enqueue("profiles.configure", json.parseToJsonElement("""{"applied":{"ui_meta":true}}"""))
            members.forEach { member ->
                gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"${member.name}"}]}"""))
                gateway.enqueue("profiles.configure", json.parseToJsonElement("""{"applied":{"ui_meta":true}}"""))
            }
            repeat(7) {
                gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"default"}]}"""))
                gateway.enqueue("profiles.configure", json.parseToJsonElement("""{"applied":{"ui_meta":true}}"""))
            }
            val room = repository.createBotGroup("Launch", members)
            repeat(2) { index ->
                gateway.enqueue("session.list", json.parseToJsonElement("""{"sessions":[]}"""))
                gateway.enqueue("session.create", json.parseToJsonElement("""{"session_id":"live-$index","stored_session_id":"stored-$index"}"""))
                gateway.enqueue("prompt.submit", json.parseToJsonElement("""{"status":"streaming"}"""))
                gateway.enqueue("session.resume", json.parseToJsonElement("""{"session_id":"live-$index","messages":[{"role":"assistant","text":"pass"}]}"""))
            }
            gateway.enqueue("image.attach_bytes", json.parseToJsonElement("""{"attached":false,"path":""}"""))
            gateway.enqueue("image.attach_bytes", json.parseToJsonElement("""{"attached":true,"path":"/tmp/diagram.png"}"""))

            repository.sendBotGroupMessage(
                room.roomId,
                "Review this",
                attachments = listOf(com.nousresearch.hermes.protocol.BotGroupAttachment("diagram.png", "image/png", "AA==")),
            )

            assertTrue(repository.state.value.botGroups.rooms.single().log.any {
                it.from.kind == "system" && it.text == "coder could not receive diagram.png."
            })
        }
    }

    @Test
    fun `group recreation harvests a late reply without interrupting a still running member`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher(withProfiles = true)
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, backend.id)
            val members = listOf(
                com.nousresearch.hermes.protocol.BotGroupMember("coder", "coder", backend.id),
                com.nousresearch.hermes.protocol.BotGroupMember("reviewer", "reviewer", backend.id),
            )
            gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"default"}]}"""))
            gateway.enqueue("profiles.configure", json.parseToJsonElement("""{"applied":{"ui_meta":true}}"""))
            members.forEach { member ->
                gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"${member.name}"}]}"""))
                gateway.enqueue("profiles.configure", json.parseToJsonElement("""{"applied":{"ui_meta":true}}"""))
            }
            repeat(5) {
                gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[{"name":"default"}]}"""))
                gateway.enqueue("profiles.configure", json.parseToJsonElement("""{"applied":{"ui_meta":true}}"""))
            }
            var room = repository.createBotGroup("Launch", members)
            room = repository.updateBotGroup(
                room.copy(stranded = members.associate { member ->
                    botGroupMemberKey(member) to com.nousresearch.hermes.protocol.BotGroupStranded(0, "main")
                }),
            )
            gateway.enqueue(
                "session.list",
                json.parseToJsonElement("""{"sessions":[{"id":"coder-room","resolved_id":"coder-room"}]}"""),
            )
            gateway.enqueue(
                "session.list",
                json.parseToJsonElement("""{"sessions":[{"id":"reviewer-room","resolved_id":"reviewer-room"}]}"""),
            )
            gateway.enqueue("session.list", json.parseToJsonElement("""{"sessions":[]}"""))
            gateway.enqueue(
                "session.resume",
                json.parseToJsonElement("""{"session_id":"coder-live","session_key":"coder-room","messages":[{"id":"late-1","role":"assistant","text":"Late result"}]}"""),
            )
            gateway.enqueue(
                "session.resume",
                json.parseToJsonElement("""{"session_id":"reviewer-live","session_key":"reviewer-room","running":true}"""),
            )
            gateway.enqueue(
                "session.create",
                json.parseToJsonElement("""{"session_id":"coder-new","stored_session_id":"coder-new"}"""),
            )
            gateway.enqueue("prompt.submit", json.parseToJsonElement("""{"status":"streaming"}"""))
            gateway.enqueue(
                "session.resume",
                json.parseToJsonElement("""{"session_id":"coder-new","messages":[{"role":"assistant","text":"pass"}]}"""),
            )

            repository.sendBotGroupMessage(room.roomId, "Any updates?")

            val saved = repository.state.value.botGroups.rooms.single()
            assertEquals(1, saved.log.count { it.id == "late-1" && it.text == "Late result" })
            assertFalse(botGroupMemberKey(members[0]) in saved.stranded)
            assertTrue(botGroupMemberKey(members[1]) in saved.stranded)
            assertEquals(1, gateway.requests.count { it.method == "prompt.submit" })
        }
    }

    @Test
    fun `agent editor capabilities and every shared avatar source use gateway contracts`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher(withProfiles = true)
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, backend.id)
            gateway.enqueue(
                "profiles.describe",
                checkNotNull(javaClass.getResource("/fixtures/profile-description-261a4ef.json"))
                    .readText().let(json::parseToJsonElement),
            )

            assertEquals("hermes-4", repository.describeBotAgent("coder").model.default)

            gateway.enqueue(
                "profiles.configure",
                json.parseToJsonElement(
                    """{"ok":true,"applied":{"description":true,"soul":true,"model":true,"skills":true,"toolsets":true,"mcp_servers":true}}""",
                ),
            )
            gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[]}"""))
            repository.configureBotAgent(
                BotAgentDraft(
                    name = "coder",
                    description = "Builds apps",
                    soul = "Be precise.",
                    provider = "nous",
                    model = "hermes-4",
                    disabledSkills = setOf("legacy"),
                    enabledToolsets = setOf("web"),
                    enabledMcpServers = setOf("github"),
                ),
            )
            val configure = gateway.requests.single { it.method == "profiles.configure" }.params.toString()
            assertTrue(configure.contains("\"disabled_skills\":[\"legacy\"]"))
            assertTrue(configure.contains("\"enabled_toolsets\":[\"web\"]"))
            assertTrue(configure.contains("\"enabled_mcp_servers\":[\"github\"]"))

            gateway.enqueue("profiles.get_asset", json.parseToJsonElement("""{"found":true,"data":"data:image/png;base64,AA==","mime":"image/png","size":1}"""))
            assertTrue(repository.profileAvatar("coder").found)

            gateway.enqueue("profiles.set_asset", json.parseToJsonElement("""{"ok":true,"size":1}"""))
            gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[]}"""))
            repository.setProfileAvatar("coder", "data:image/png;base64,AA==")

            gateway.enqueue(
                "image.generate",
                json.parseToJsonElement("""{"available":true,"success":true,"image_data":"data:image/png;base64,AA=="}"""),
            )
            gateway.enqueue("profiles.set_asset", json.parseToJsonElement("""{"ok":true,"size":1}"""))
            gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[]}"""))
            assertEquals("data:image/png;base64,AA==", repository.generateProfileAvatar("coder", "friendly robot"))

            gateway.enqueue(
                "pet.gallery",
                json.parseToJsonElement("""{"enabled":true,"pets":[{"slug":"fox","displayName":"Fox","installed":true}]}"""),
            )
            assertEquals("fox", repository.profilePetGallery("coder").pets.single().slug)
            gateway.enqueue("pet.select", json.parseToJsonElement("""{"ok":true,"slug":"fox"}"""))
            gateway.enqueue(
                "pet.cells",
                json.parseToJsonElement("""{"enabled":true,"frames":[[[[255,0,0,255,0,0,255,255]]]]}"""),
            )
            gateway.enqueue("profiles.set_asset", json.parseToJsonElement("""{"ok":true,"size":80}"""))
            gateway.enqueue("profiles.list", json.parseToJsonElement("""{"profiles":[]}"""))
            assertTrue(repository.adoptProfilePet("coder", "fox").startsWith("data:image/png;base64,"))
        }
    }

    @Test
    fun `canonical bot chat opens the exact compression tip without creating`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, backend.id)
            gateway.enqueue(
                "session.list",
                checkNotNull(javaClass.getResource("/fixtures/canonical-session-list-261a4ef.json"))
                    .readText().let(json::parseToJsonElement),
            )
            gateway.enqueue(
                "session.resume",
                json.parseToJsonElement(
                    """{"session_id":"live-tip","session_key":"bot-chat-tip","messages":[],"info":{"stored_session_id":"bot-chat-tip"}}""",
                ),
            )
            gateway.enqueue("model.options", json.parseToJsonElement("""{"providers":[]}"""))

            assertTrue(repository.openCanonicalBotChat("coder"))

            assertEquals("bot-chat-tip", repository.state.value.activeStoredSession?.durableId)
            assertEquals("coder", repository.state.value.activeStoredSession?.profile)
            assertFalse(gateway.requests.any { it.method == "session.create" })
            val lookup = gateway.requests.single { it.method == "session.list" }.params.toString()
            assertTrue(lookup.contains("\"title\":\"Bot Chat\""))
            assertTrue(lookup.contains("\"include_hidden\":true"))
        }
    }

    @Test
    fun `known mentions are delivered to the other bots canonical chat without moving the active chat`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher(withProfiles = true)
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, backend.id)
            gateway.enqueue(
                "session.create",
                json.parseToJsonElement(
                    """{"session_id":"live-coder","stored_session_id":"coder-chat","messages":[],"info":{"stored_session_id":"coder-chat"}}""",
                ),
            )
            gateway.enqueue("model.options", json.parseToJsonElement("""{"providers":[]}"""))
            assertTrue(repository.newSession("coder", title = "Bot Chat", hidden = true))
            gateway.enqueue("prompt.submit", json.parseToJsonElement("""{"status":"streaming"}"""))
            gateway.enqueue(
                "profiles.list",
                json.parseToJsonElement("""{"profiles":[{"name":"coder"},{"name":"reviewer"}]}"""),
            )
            gateway.enqueue(
                "session.list",
                json.parseToJsonElement(
                    """{"sessions":[{"id":"wrong-chat","resolved_id":"wrong-tip","title":"Other"},{"id":"reviewer-chat","resolved_id":"reviewer-tip","title":"Bot Chat"}]}""",
                ),
            )
            gateway.enqueue(
                "session.resume",
                json.parseToJsonElement(
                    """{"session_id":"live-reviewer","session_key":"reviewer-tip","messages":[],"info":{"stored_session_id":"reviewer-tip"}}""",
                ),
            )
            gateway.enqueue("prompt.submit", json.parseToJsonElement("""{"status":"streaming"}"""))

            repository.send("Please ask @reviewer to check this; leave @unknown untouched.")
            withTimeout(5_000) {
                while (gateway.requests.count { it.method == "prompt.submit" } < 2) delay(10)
            }

            assertEquals("coder-chat", repository.state.value.activeStoredSession?.durableId)
            val handoff = gateway.requests.filter { it.method == "prompt.submit" }.last().params.toString()
            assertTrue(handoff.contains("live-reviewer"))
            assertTrue(handoff.contains("Message from 🤖 coder (@coder)"))
            assertTrue(handoff.contains("@unknown untouched"))
        }
    }

    @Test
    fun `bot routine lifecycle stays in its owner profile`() = runBlocking {
        MockWebServer().use { server ->
            val base = readyDashboardDispatcher()
            val cronPaths = mutableListOf<String>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.requestUrl?.encodedPath?.startsWith("/api/cron/jobs") != true) return base.dispatch(request)
                    cronPaths += request.path.orEmpty()
                    val owner = request.requestUrl?.queryParameter("profile") ?: "coder"
                    val jobId = if (owner == "reviewer") "review" else "daily"
                    val job = """{"id":"$jobId","enabled":true,"name":"[bot:$owner] Daily"}"""
                    val body = when {
                        request.requestUrl?.encodedPath?.endsWith("/runs") == true -> """{"runs":[],"limit":20}"""
                        request.method == "DELETE" -> """{"ok":true}"""
                        request.requestUrl?.encodedPath == "/api/cron/jobs" && request.method == "GET" ->
                            "[$job]"
                        else -> job
                    }
                    return MockResponse().setBody(body)
                }
            }
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(
                context,
                registry,
                credentials,
                BillingPendingChargeStore(context, json),
                RecordingGateway(json),
            )
            awaitReady(repository, backend.id)

            repository.refreshBotRoutines("reviewer")
            repository.refreshBotRoutines("coder")
            assertEquals(setOf("coder", "reviewer"), repository.state.value.cronJobs.mapNotNull { it.botRoutineOwner() }.toSet())
            repository.refreshCronRuns("daily")
            repository.setCronEnabled("daily", false)
            repository.triggerCron("daily")
            assertTrue(
                runCatching { repository.updateCron("daily", "Daily", "Plan", "0 9 * * *", "") }
                    .exceptionOrNull() is IllegalArgumentException,
            )
            repository.updateCron("daily", "[bot:coder] Daily", "Plan", "0 9 * * *", "")
            repository.deleteCron("daily")

            assertEquals(
                listOf(
                    "/api/cron/jobs?profile=reviewer",
                    "/api/cron/jobs?profile=coder",
                    "/api/cron/jobs/daily/runs?limit=20&profile=coder",
                    "/api/cron/jobs/daily/pause?profile=coder",
                    "/api/cron/jobs/daily/trigger?profile=coder",
                    "/api/cron/jobs/daily?profile=coder",
                    "/api/cron/jobs/daily?profile=coder",
                ),
                cronPaths,
            )
        }
    }

    @Test
    fun `mention creates and explicitly titles a missing canonical bot chat`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher(withProfiles = true)
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, backend.id)
            gateway.enqueue(
                "session.create",
                json.parseToJsonElement(
                    """{"session_id":"live-coder","stored_session_id":"coder-chat","messages":[],"info":{"stored_session_id":"coder-chat"}}""",
                ),
            )
            gateway.enqueue("model.options", json.parseToJsonElement("""{"providers":[]}"""))
            assertTrue(repository.newSession("coder", title = "Bot Chat", hidden = true))
            gateway.enqueue("prompt.submit", json.parseToJsonElement("""{"status":"streaming"}"""))
            gateway.enqueue(
                "profiles.list",
                json.parseToJsonElement("""{"profiles":[{"name":"coder"},{"name":"reviewer"}]}"""),
            )
            gateway.enqueue("session.list", json.parseToJsonElement("""{"sessions":[{"id":"other","title":"Other"}]}"""))
            gateway.enqueue(
                "session.create",
                json.parseToJsonElement(
                    """{"session_id":"live-reviewer","stored_session_id":"reviewer-chat","messages":[],"info":{"stored_session_id":"reviewer-chat"}}""",
                ),
            )
            gateway.enqueueFailure("session.title", HermesRpcException("Method not found", -32601))
            gateway.enqueue("prompt.submit", json.parseToJsonElement("""{"status":"streaming"}"""))

            repository.send("Please ask @reviewer.")
            withTimeout(5_000) {
                while (gateway.requests.count { it.method == "prompt.submit" } < 2) delay(10)
            }

            val create = gateway.requests.last { it.method == "session.create" }.params.toString()
            assertTrue(create.contains("\"profile\":\"reviewer\""))
            assertTrue(create.contains("\"title\":\"Bot Chat\""))
            assertTrue(create.contains("\"hidden\":true"))
            val title = gateway.requests.single { it.method == "session.title" }.params.toString()
            assertTrue(title.contains("live-reviewer"))
            assertTrue(title.contains("Bot Chat"))
            assertEquals("coder-chat", repository.state.value.activeStoredSession?.durableId)
        }
    }

    @Test
    fun `double tap creates and introduces one hidden canonical bot chat`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, backend.id)
            gateway.enqueue("session.list", json.parseToJsonElement("""{"sessions":[]}"""))
            gateway.enqueue(
                "session.create",
                json.parseToJsonElement(
                    """{"session_id":"live-new","stored_session_id":"bot-chat-new","messages":[],"info":{"stored_session_id":"bot-chat-new"}}""",
                ),
            )
            gateway.enqueue("model.options", json.parseToJsonElement("""{"providers":[]}"""))
            gateway.enqueueFailure("session.title", IllegalStateException("older gateway"))
            gateway.enqueue("prompt.submit", json.parseToJsonElement("""{"status":"streaming"}"""))

            val first = launch { assertTrue(repository.openCanonicalBotChat("coder")) }
            val second = launch { assertTrue(repository.openCanonicalBotChat("coder")) }
            first.join()
            second.join()

            assertEquals(1, gateway.requests.count { it.method == "session.create" })
            val create = gateway.requests.single { it.method == "session.create" }.params.toString()
            assertTrue(create.contains("\"profile\":\"coder\""))
            assertTrue(create.contains("\"title\":\"Bot Chat\""))
            assertTrue(create.contains("\"hidden\":true"))
            assertEquals(1, gateway.requests.count { it.method == "session.title" })
            assertEquals(1, gateway.requests.count { it.method == "prompt.submit" })
            assertTrue(repository.state.value.activeStoredSession?.title == "Bot Chat")
        }
    }

    @Test
    fun `canonical lookup failure never guesses by creating`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, backend.id)
            gateway.enqueueFailure("session.list", IOException("lookup disconnected"))

            assertFalse(repository.openCanonicalBotChat("coder"))

            assertFalse(gateway.requests.any { it.method == "session.create" })
            assertTrue(repository.state.value.error.orEmpty().contains("lookup disconnected"))
        }
    }

    @Test
    fun `canonical lookup failure resumes the registered canonical session`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher(withProfiles = true)
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, backend.id)
            gateway.enqueue(
                "profiles.list",
                json.parseToJsonElement(
                    """{"profiles":[{"name":"coder","canonical_session":{"id":"registered-root","resolved_id":"registered-tip","root_title":"Bot Chat","title":"Continued"}}]}""",
                ),
            )
            repository.refreshProfiles()
            gateway.enqueueFailure("session.list", IOException("lookup disconnected"))
            gateway.enqueue(
                "session.resume",
                json.parseToJsonElement(
                    """{"session_id":"live-registered","session_key":"registered-tip","messages":[],"info":{"stored_session_id":"registered-tip"}}""",
                ),
            )
            gateway.enqueue("model.options", json.parseToJsonElement("""{"providers":[]}"""))

            assertTrue(repository.openCanonicalBotChat("coder"))

            assertEquals("registered-tip", repository.state.value.activeStoredSession?.durableId)
            assertFalse(gateway.requests.any { it.method == "session.create" })
        }
    }

    @Test
    fun `older session listing still adopts an exact Bot Chat title`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, backend.id)
            gateway.enqueue(
                "session.list",
                json.parseToJsonElement("""{"sessions":[{"id":"legacy-chat","title":"Bot Chat","message_count":2}]}"""),
            )
            gateway.enqueue(
                "session.resume",
                json.parseToJsonElement(
                    """{"session_id":"live-legacy","session_key":"legacy-chat","messages":[],"info":{"stored_session_id":"legacy-chat"}}""",
                ),
            )
            gateway.enqueue("model.options", json.parseToJsonElement("""{"providers":[]}"""))

            assertTrue(repository.openCanonicalBotChat("coder"))

            assertEquals("legacy-chat", repository.state.value.activeStoredSession?.durableId)
            assertFalse(gateway.requests.any { it.method == "session.create" })
        }
    }

    @Test
    fun `new slash compacts an open canonical bot chat instead of forking it`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, backend.id)
            gateway.enqueue(
                "session.list",
                checkNotNull(javaClass.getResource("/fixtures/canonical-session-list-261a4ef.json"))
                    .readText().let(json::parseToJsonElement),
            )
            gateway.enqueue(
                "session.resume",
                json.parseToJsonElement(
                    """{"session_id":"live-tip","session_key":"bot-chat-tip","messages":[],"info":{"stored_session_id":"bot-chat-tip"}}""",
                ),
            )
            gateway.enqueue("model.options", json.parseToJsonElement("""{"providers":[]}"""))
            assertTrue(repository.openCanonicalBotChat("coder"))
            gateway.enqueue(
                "commands.catalog",
                json.parseToJsonElement("""{"pairs":[["new","New session"],["reset","Reset session"]]}"""),
            )
            gateway.enqueue("session.compress", json.parseToJsonElement("""{"status":"compressed","removed":4}"""))

            repository.executeSlash("/new")

            assertEquals(1, gateway.requests.count { it.method == "session.compress" })
            assertFalse(gateway.requests.any { it.method == "session.create" })
            assertEquals("bot-chat-tip", repository.state.value.activeStoredSession?.durableId)
        }
    }

    @Test
    fun `system shared text opens a draft and never submits it`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(
                context,
                registry,
                credentials,
                BillingPendingChargeStore(context, json),
                gateway,
            )
            awaitReady(repository, backend.id)
            gateway.enqueue(
                "session.create",
                json.parseToJsonElement(
                    """{"session_id":"live-share","stored_session_id":"stored-share","messages":[],"info":{"stored_session_id":"stored-share"}}""",
                ),
            )
            gateway.enqueue("model.options", json.parseToJsonElement("""{"providers":[]}"""))

            val imported = repository.ingestSharedContent("Review this before sending", emptyList())

            assertTrue(imported)
            assertEquals("Review this before sending", repository.state.value.draft)
            assertEquals("stored-share", repository.state.value.activeStoredSession?.durableId)
            assertFalse(gateway.requests.any { it.method == "prompt.submit" })
        }
    }

    @Test
    fun `shared attachment failures stay independent and successful camera files are released`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(
                context,
                registry,
                credentials,
                BillingPendingChargeStore(context, json),
                gateway,
            )
            awaitReady(repository, backend.id)
            gateway.enqueue(
                "session.create",
                json.parseToJsonElement(
                    """{"session_id":"live-files","stored_session_id":"stored-files","messages":[],"info":{"stored_session_id":"stored-files"}}""",
                ),
            )
            gateway.enqueue("model.options", json.parseToJsonElement("""{"providers":[]}"""))
            gateway.enqueue(
                "image.attach_bytes",
                json.parseToJsonElement(
                    """{"attached":true,"path":"/srv/.hermes/images/upload.png","count":1,"text":"[User attached image]","bytes":4,"width":2}""",
                ),
            )
            val rejectedFile = File(context.cacheDir, "camera/rejected.apk").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1, 2))
            }
            val rejectedUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                rejectedFile,
            )
            val cameraUri = newCameraCaptureUri(context).also { uri ->
                context.contentResolver.openOutputStream(uri)!!.use { it.write(byteArrayOf(1, 2, 3, 4)) }
            }

            assertTrue(repository.ingestSharedContent("", listOf(rejectedUri, cameraUri)))

            assertEquals(
                listOf(AttachmentPhase.ERROR, AttachmentPhase.READY),
                repository.state.value.pendingAttachments.map(PendingAttachment::phase),
            )
            assertTrue(repository.state.value.pendingAttachments.first().error.orEmpty().contains("not supported"))
            assertTrue(runCatching { context.contentResolver.openInputStream(cameraUri)!!.close() }.isFailure)
            assertFalse(gateway.requests.any { it.method == "prompt.submit" })
            repository.state.value.pendingAttachments.toList().forEach { repository.removePendingAttachment(it.id) }

            val uploadStarted = CompletableDeferred<Unit>()
            gateway.enqueueBlock("image.attach_bytes") {
                uploadStarted.complete(Unit)
                CompletableDeferred<JsonElement>().await()
            }
            val retryUri = newCameraCaptureUri(context).also { uri ->
                context.contentResolver.openOutputStream(uri)!!.use { it.write(byteArrayOf(1, 2, 3, 4)) }
            }
            val attaching = launch { repository.attach(retryUri) }
            uploadStarted.await()
            val attachmentId = repository.state.value.pendingAttachments.single().id

            repository.cancelPendingAttachment(attachmentId)
            attaching.join()

            assertEquals(AttachmentPhase.ERROR, repository.state.value.pendingAttachments.single().phase)
            assertEquals("Attachment cancelled", repository.state.value.pendingAttachments.single().error)
            gateway.enqueue(
                "image.attach_bytes",
                json.parseToJsonElement(
                    """{"attached":false,"path":"","count":0,"text":"","bytes":0,"width":0}""",
                ),
            )

            repository.retryPendingAttachment(attachmentId)

            assertEquals(AttachmentPhase.ERROR, repository.state.value.pendingAttachments.single().phase)
            assertTrue(repository.state.value.pendingAttachments.single().error.orEmpty().contains("did not attach"))
            gateway.enqueue(
                "image.attach_bytes",
                json.parseToJsonElement(
                    """{"attached":true,"path":"/srv/.hermes/images/retry.png","count":1,"text":"[User attached image]","bytes":4,"width":2}""",
                ),
            )

            repository.retryPendingAttachment(attachmentId)

            assertEquals(AttachmentPhase.READY, repository.state.value.pendingAttachments.single().phase)
            assertTrue(runCatching { context.contentResolver.openInputStream(retryUri)!!.close() }.isFailure)
            repository.removePendingAttachment(attachmentId)

            val staleUploadStarted = CompletableDeferred<Unit>()
            gateway.enqueueBlock("image.attach_bytes") {
                staleUploadStarted.complete(Unit)
                CompletableDeferred<JsonElement>().await()
            }
            val staleUri = newCameraCaptureUri(context).also { uri ->
                context.contentResolver.openOutputStream(uri)!!.use { it.write(byteArrayOf(1, 2, 3, 4)) }
            }
            val staleAttachment = launch { repository.attach(staleUri) }
            staleUploadStarted.await()
            gateway.enqueue(
                "session.create",
                json.parseToJsonElement(
                    """{"session_id":"live-new","stored_session_id":"stored-new","messages":[],"info":{"stored_session_id":"stored-new"}}""",
                ),
            )
            gateway.enqueue("model.options", json.parseToJsonElement("""{"providers":[]}"""))

            assertTrue(repository.newSession())
            staleAttachment.join()

            assertEquals("live-new", repository.state.value.runtimeSessionId)
            assertTrue(repository.state.value.pendingAttachments.isEmpty())
            assertTrue(runCatching { context.contentResolver.openInputStream(staleUri)!!.close() }.isFailure)
        }
    }

    @Test
    fun `sticky profile switch preserves pending attachment for the active session`() = runBlocking {
        MockWebServer().use { server ->
            var activeProfile = "default"
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/api/status" -> MockResponse().setBody("""{"status":"ready","hermes_version":"0.18.2"}""")
                    "/api/profiles/sessions" -> MockResponse().setBody("""{"sessions":[]}""")
                    "/api/profiles" -> MockResponse().setBody(
                        """{"profiles":[{"name":"default","is_default":true},{"name":"research"}]}""",
                    )
                    "/api/profiles/active" -> if (request.method == "POST") {
                        activeProfile = "research"
                        MockResponse().setBody("""{"ok":true}""")
                    } else {
                        MockResponse().setBody("""{"active":"$activeProfile","current":"default"}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(
                context,
                registry,
                credentials,
                BillingPendingChargeStore(context, json),
                gateway,
            )
            awaitReady(repository, backend.id)
            gateway.enqueue(
                "session.create",
                json.parseToJsonElement(
                    """{"session_id":"live-profile","stored_session_id":"stored-profile","messages":[],"info":{"stored_session_id":"stored-profile"}}""",
                ),
            )
            gateway.enqueue("model.options", json.parseToJsonElement("""{"providers":[]}"""))
            assertTrue(repository.newSession())

            val uploadStarted = CompletableDeferred<Unit>()
            gateway.enqueueBlock("image.attach_bytes") {
                uploadStarted.complete(Unit)
                CompletableDeferred<JsonElement>().await()
            }
            // Robolectric reuses FileProvider's authority cache across per-test application data roots.
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
                .get(null).let { (it as MutableMap<*, *>).clear() }
            val cameraUri = newCameraCaptureUri(context).also { uri ->
                context.contentResolver.openOutputStream(uri)!!.use { it.write(byteArrayOf(1, 2, 3, 4)) }
            }
            val attaching = launch { repository.attach(cameraUri) }
            uploadStarted.await()
            val pendingId = repository.state.value.pendingAttachments.single().id

            repository.setActiveProfile("research")
            assertEquals(1, repository.state.value.pendingAttachments.size)
            assertEquals("research", repository.state.value.activeProfile)

            repository.cancelPendingAttachment(pendingId)
            withTimeout(5_000L) { attaching.join() }
            repository.removePendingAttachment(pendingId)

            assertTrue(repository.state.value.pendingAttachments.isEmpty())
            assertTrue(runCatching { context.contentResolver.openInputStream(cameraUri)!!.close() }.isFailure)
        }
    }

    @Test
    fun `all failed shared attachments remain retryable and do not consume the share`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(
                context,
                registry,
                credentials,
                BillingPendingChargeStore(context, json),
                RecordingGateway(json),
            )
            awaitReady(repository, backend.id)
            // Keep the source provider deterministic across local and hosted Robolectric runs.
            FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
                .get(null).let { (it as MutableMap<*, *>).clear() }
            val rejectedFile = File(context.cacheDir, "shared/rejected.apk").apply {
                parentFile?.mkdirs()
                writeBytes(byteArrayOf(1, 2))
            }
            val rejectedUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                rejectedFile,
            )

            assertFalse(repository.ingestSharedContent("", listOf(rejectedUri)))
            assertFalse(repository.ingestSharedContent("", listOf(rejectedUri)))
            assertEquals(1, repository.state.value.pendingAttachments.size)
            assertEquals(AttachmentPhase.ERROR, repository.state.value.pendingAttachments.single().phase)
        }
    }

    @Test
    fun `failed new session preserves the active session and never closes it first`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(
                context,
                registry,
                credentials,
                BillingPendingChargeStore(context, json),
                gateway,
            )
            awaitReady(repository, backend.id)
            gateway.enqueue(
                "session.create",
                json.parseToJsonElement(
                    """{"session_id":"live-existing","stored_session_id":"stored-existing","messages":[],"info":{"stored_session_id":"stored-existing"}}""",
                ),
            )
            gateway.enqueue("model.options", json.parseToJsonElement("""{"providers":[]}"""))
            assertTrue(repository.newSession())
            gateway.enqueueFailure("session.create", IOException("create failed"))

            assertFalse(repository.newSession())

            assertEquals("live-existing", repository.state.value.runtimeSessionId)
            assertEquals("stored-existing", repository.state.value.activeStoredSession?.durableId)
            assertFalse(gateway.requests.any { it.method == "session.close" })
        }
    }

    @Test
    fun `cancelled new session clears its loading state`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(
                context,
                registry,
                credentials,
                BillingPendingChargeStore(context, json),
                gateway,
            )
            awaitReady(repository, backend.id)
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            gateway.enqueueBlock("session.create") {
                started.complete(Unit)
                release.await()
                error("cancelled request unexpectedly resumed")
            }

            val creation = launch { repository.newSession() }
            withTimeout(5_000L) { started.await() }
            creation.cancelAndJoin()

            assertFalse(repository.state.value.loading)
            assertEquals(null, repository.state.value.runtimeSessionId)
        }
    }

    @Test
    fun `ambiguous charge retries with the same key until settlement`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val pendingStore = BillingPendingChargeStore(context, json)
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, pendingStore, gateway)
            awaitReady(repository, backend.id)
            gateway.enqueue("billing.state", billingState())
            gateway.enqueue("subscription.state", json.parseToJsonElement("""{"ok":true,"logged_in":true}"""))
            repository.refreshBilling()
            gateway.enqueueFailure("billing.charge", IOException("connection dropped"))

            repository.chargeBillingCredits("20")

            assertTrue(repository.state.value.billingChargeUnconfirmed)
            val pending = checkNotNull(pendingStore.get(backend.id))
            gateway.enqueue("billing.charge", json.parseToJsonElement("""{"ok":true,"charge_id":"ch_1"}"""))
            gateway.enqueue("billing.charge_status", json.parseToJsonElement("""{"ok":true,"status":"settled","amount_usd":"20"}"""))
            gateway.enqueue("billing.state", billingState())
            gateway.enqueue("subscription.state", json.parseToJsonElement("""{"ok":true,"logged_in":true}"""))

            repository.chargeBillingCredits("20")

            val charges = gateway.requests.filter { it.method == "billing.charge" }
            assertEquals(2, charges.size)
            assertEquals(charges.first().params, charges.last().params)
            assertTrue(charges.first().params.toString().contains(pending.idempotencyKey))
            assertFalse(repository.state.value.billingChargeUnconfirmed)
            assertEquals(null, pendingStore.get(backend.id))
        }
    }

    @Test
    fun `pending charge restores before offline authentication and blocks forgetting backend`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val pendingStore = BillingPendingChargeStore(context, json)
            registry.save(backend)
            pendingStore.put(
                PendingBillingCharge(
                    backendId = backend.id,
                    amountUsd = "20",
                    idempotencyKey = "same-key",
                    settlementDeadlineEpochMillis = System.currentTimeMillis() + 60_000L,
                ),
            )
            val repository = repository(context, registry, credentials, pendingStore, RecordingGateway(json))

            withTimeout(5_000L) {
                repository.state.first {
                    it.reconnectRequiredBackendId == backend.id && it.billingChargeUnconfirmed
                }
            }
            repository.forgetBackend(backend.id)

            assertTrue(registry.backends.first().any { it.id == backend.id })
            assertNotNull(pendingStore.get(backend.id))
            assertTrue(repository.state.value.error.orEmpty().contains("unconfirmed charge", ignoreCase = true))
        }
    }

    @Test
    fun `cancelling an in-flight charge keeps the persisted review lock`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val pendingStore = BillingPendingChargeStore(context, json)
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, pendingStore, gateway)
            awaitReady(repository, backend.id)
            gateway.enqueue("billing.state", billingState())
            gateway.enqueue("subscription.state", json.parseToJsonElement("""{"ok":true,"logged_in":true}"""))
            repository.refreshBilling()
            val requestStarted = CompletableDeferred<Unit>()
            gateway.enqueueBlock("billing.charge") {
                requestStarted.complete(Unit)
                CompletableDeferred<JsonElement>().await()
            }

            val charge = launch { repository.chargeBillingCredits("20") }
            withTimeout(5_000L) { requestStarted.await() }
            charge.cancelAndJoin()

            assertTrue(repository.state.value.billingChargeUnconfirmed)
            assertNotNull(pendingStore.get(backend.id))
            assertTrue(repository.state.value.billingError.orEmpty().contains("unconfirmed", ignoreCase = true))
        }
    }

    @Test
    fun `backend switch wins over a reconnect already holding the gateway lock`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backendA = backend(server)
            val backendB = backendA.copy(id = "work", label = "Work")
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val pendingStore = BillingPendingChargeStore(context, json)
            val gateway = RecordingGateway(json)
            registry.save(backendA)
            credentials.put(backendA.id, SESSION_COOKIE)
            credentials.put(backendB.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, pendingStore, gateway)
            awaitReady(repository, backendA.id)
            val reconnectStarted = CompletableDeferred<Unit>()
            val releaseReconnect = CompletableDeferred<Unit>()
            val reconnectCompleted = CompletableDeferred<Unit>()
            gateway.blockNextReconnect(backendA.id, reconnectStarted, releaseReconnect, reconnectCompleted)

            gateway.failConnection("network dropped")
            withTimeout(5_000L) { reconnectStarted.await() }
            registry.save(backendB)
            releaseReconnect.complete(Unit)
            withTimeout(5_000L) { reconnectCompleted.await() }
            awaitReady(repository, backendB.id)

            assertEquals(backendB.id, gateway.connectedBackendIds.last())
            val lastB = gateway.connectedBackendIds.indexOfLast { it == backendB.id }
            assertFalse(gateway.connectedBackendIds.drop(lastB + 1).contains(backendA.id))
        }
    }

    @Test
    fun `backend switch releases silent session refreshes`() = runBlocking {
        MockWebServer().use { server ->
            val sessionFetches = AtomicInteger()
            val visibleRefreshStarted = CompletableDeferred<Unit>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/api/status" -> MockResponse().setBody("""{"status":"ready","hermes_version":"0.18.2"}""")
                    "/api/profiles/sessions" -> when (sessionFetches.incrementAndGet()) {
                        1 -> MockResponse().setBody("""{"sessions":[]}""")
                        2 -> {
                            visibleRefreshStarted.complete(Unit)
                            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        }
                        3 -> MockResponse().setBody("""{"sessions":[]}""")
                        else -> MockResponse().setBody(
                            """{"sessions":[{"session_id":"session-b","message_count":2}]}""",
                        )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backendA = backend(server)
            val backendB = backendA.copy(id = "work", label = "Work")
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backendA)
            credentials.put(backendA.id, SESSION_COOKIE)
            credentials.put(backendB.id, SESSION_COOKIE)
            val repository = repository(
                context,
                registry,
                credentials,
                BillingPendingChargeStore(context, json),
                gateway,
            )
            awaitReady(repository, backendA.id)

            val visibleA = launch { repository.refreshSessions() }
            withTimeout(5_000L) { visibleRefreshStarted.await() }
            registry.save(backendB)
            awaitReady(repository, backendB.id)

            repository.refreshSessions(showLoading = false)

            assertEquals(2, repository.state.value.sessions.single().messageCount)
            visibleA.cancelAndJoin()
        }
    }

    @Test
    fun `session deletion invalidates an in flight silent refresh`() = runBlocking {
        MockWebServer().use { server ->
            val sessionFetches = AtomicInteger()
            val refreshStarted = CompletableDeferred<Unit>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/api/status" -> MockResponse().setBody("""{"status":"ready","hermes_version":"0.18.2"}""")
                    "/api/profiles/sessions" -> when (sessionFetches.incrementAndGet()) {
                        1 -> MockResponse().setBody("""{"sessions":[{"session_id":"session-1"}]}""")
                        2 -> {
                            refreshStarted.complete(Unit)
                            MockResponse()
                                .setBodyDelay(1, TimeUnit.SECONDS)
                                .setBody("""{"sessions":[{"session_id":"session-1"}]}""")
                        }
                        else -> MockResponse().setBody("""{"sessions":[]}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(
                context,
                registry,
                credentials,
                BillingPendingChargeStore(context, json),
                gateway,
            )
            awaitReady(repository, backend.id)
            gateway.enqueue(
                "session.delete",
                json.parseToJsonElement("""{"deleted":"session-1"}"""),
            )
            val session = repository.state.value.sessions.single().copy(profile = "")
            val draftContext = DraftContext(backend.id, "", session.durableId)
            DraftStore(context).put(draftContext, "unfinished")

            val refresh = launch { repository.refreshSessions(showLoading = false) }
            withTimeout(5_000L) { refreshStarted.await() }
            repository.deleteSession(session)
            refresh.join()
            withTimeout(5_000L) {
                while (sessionFetches.get() < 3) delay(10)
            }

            assertTrue(repository.state.value.sessions.isEmpty())
            assertEquals("", DraftStore(context).get(draftContext))
        }
    }

    @Test
    fun `delayed delete failure cannot overwrite a new backend`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backendA = backend(server)
            val backendB = backendA.copy(id = "work", label = "Work")
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backendA)
            credentials.put(backendA.id, SESSION_COOKIE)
            credentials.put(backendB.id, SESSION_COOKIE)
            val repository = repository(
                context,
                registry,
                credentials,
                BillingPendingChargeStore(context, json),
                gateway,
            )
            awaitReady(repository, backendA.id)
            val deleteStarted = CompletableDeferred<Unit>()
            val releaseDelete = CompletableDeferred<Unit>()
            gateway.enqueueBlock("session.delete") {
                deleteStarted.complete(Unit)
                releaseDelete.await()
                error("Delete failed")
            }

            val deleting = launch {
                repository.deleteSession(StoredSession(sessionId = "session-1", profile = "research"))
            }
            withTimeout(5_000L) { deleteStarted.await() }
            registry.save(backendB)
            awaitReady(repository, backendB.id)
            releaseDelete.complete(Unit)
            deleting.join()

            assertEquals(backendB.id, repository.state.value.backend?.id)
            assertFalse(repository.state.value.error.orEmpty().contains("Delete failed"))
        }
    }

    @Test
    fun `concurrent silent session refreshes coalesce without losing an older success`() = runBlocking {
        MockWebServer().use { server ->
            val sessionFetches = AtomicInteger()
            val firstRefreshStarted = CompletableDeferred<Unit>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/api/status" -> MockResponse().setBody("""{"status":"ready","hermes_version":"0.18.2"}""")
                    "/api/profiles/sessions" -> when (sessionFetches.incrementAndGet()) {
                        1 -> MockResponse().setBody("""{"sessions":[]}""")
                        2 -> {
                            firstRefreshStarted.complete(Unit)
                            MockResponse()
                                .setBodyDelay(1, TimeUnit.SECONDS)
                                .setBody("""{"sessions":[{"session_id":"session-1","message_count":2}]}""")
                        }
                        else -> MockResponse().setResponseCode(500)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(
                context,
                registry,
                credentials,
                BillingPendingChargeStore(context, json),
                RecordingGateway(json),
            )
            awaitReady(repository, backend.id)

            val first = launch { repository.refreshSessions(showLoading = false) }
            withTimeout(5_000L) { firstRefreshStarted.await() }
            repository.refreshSessions(showLoading = false)
            first.join()
            withTimeout(5_000L) {
                while (sessionFetches.get() < 3) delay(10)
            }

            assertEquals(2, repository.state.value.sessions.single().messageCount)
        }
    }

    @Test
    fun `latest session open wins when an earlier resume finishes last`() = runBlocking {
        MockWebServer().use { server ->
            val sessionFetches = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/api/status" -> MockResponse().setBody("""{"status":"ready","hermes_version":"0.18.2"}""")
                    "/api/profiles/sessions" -> MockResponse().also { sessionFetches.incrementAndGet() }
                        .setBody("""{"sessions":[]}""")
                    "/api/sessions/session-a/messages" -> MockResponse().setBody(
                        """{"session_id":"session-a","messages":[{"role":"user","text":"A"}]}""",
                    )
                    "/api/sessions/session-b/messages" -> MockResponse().setBody(
                        """{"session_id":"session-b","messages":[{"role":"user","text":"B"}]}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, backend.id)
            val firstStarted = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            gateway.enqueueBlock("session.resume") {
                firstStarted.complete(Unit)
                releaseFirst.await()
                json.parseToJsonElement(
                    """{"session_id":"live-a","session_key":"session-a","messages":[{"role":"user","text":"A"}]}""",
                )
            }
            gateway.enqueue(
                "session.resume",
                json.parseToJsonElement(
                    """{"session_id":"live-b","session_key":"session-b","messages":[{"role":"user","text":"B"}]}""",
                ),
            )
            repeat(2) { gateway.enqueue("model.options", json.parseToJsonElement("""{"providers":[]}""")) }

            val first = launch { repository.openSession(StoredSession(sessionId = "session-a")) }
            withTimeout(5_000L) { firstStarted.await() }
            val second = launch { repository.openSession(StoredSession(sessionId = "session-b")) }
            withTimeout(5_000L) { repository.state.first { it.runtimeSessionId == "live-b" } }
            releaseFirst.complete(Unit)
            first.join()
            second.join()

            assertEquals("session-b", repository.state.value.activeStoredSession?.durableId)
            assertEquals("live-b", repository.state.value.runtimeSessionId)
            assertEquals("session-b", registry.sessionTarget(backend.id)?.sessionId)

            gateway.emit(GatewayEvent("message.complete", "live-a", buildJsonObject { put("text", "A done") }))
            withTimeout(5_000L) {
                while (sessionFetches.get() < 2) delay(10)
            }
            assertEquals("live-b", repository.state.value.runtimeSessionId)
            gateway.emit(
                GatewayEvent("session.info", "live-b", buildJsonObject { put("running", false) }),
            )
            withTimeout(5_000L) {
                while (sessionFetches.get() < 3) delay(10)
            }
        }
    }

    @Test
    fun `archived session reopens only after the server advertises it`() = runBlocking {
        MockWebServer().use { server ->
            val advertiseSession = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/api/status" -> MockResponse().setBody("""{"status":"ready","hermes_version":"0.18.2"}""")
                    "/api/profiles/sessions" -> MockResponse().setBody(
                        if (advertiseSession.get() == 0) {
                            """{"sessions":[]}"""
                        } else {
                            """{"sessions":[{"session_id":"session-archived","profile":"default","title":"Returned"}]}"""
                        },
                    )
                    "/api/sessions/session-archived" -> MockResponse().setBody("{}")
                    "/api/sessions/session-archived/messages" -> MockResponse().setBody(
                        """{"session_id":"session-archived","messages":[]}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            val session = StoredSession(sessionId = "session-archived")
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, backend.id)
            gateway.enqueue(
                "session.resume",
                json.parseToJsonElement(
                    """{"session_id":"live-archived","session_key":"session-archived","messages":[]}""",
                ),
            )
            gateway.enqueue("model.options", json.parseToJsonElement("""{"providers":[]}"""))

            repository.archiveSession(backend.id, session)
            repository.openSession(session)

            assertFalse(gateway.requests.any { it.method == "session.resume" })
            assertEquals(null, repository.state.value.activeStoredSession)

            advertiseSession.incrementAndGet()
            repository.refreshSessions()
            repository.openSession(session)

            assertTrue(gateway.requests.any { it.method == "session.resume" })
            assertEquals("session-archived", repository.state.value.activeStoredSession?.durableId)
        }
    }

    @Test
    fun `session preflight failure leaves restoration in explicit authentication recovery`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, backend.id)
            credentials.remove(backend.id)

            repository.openSession(StoredSession(sessionId = "stored-session"))

            assertEquals(SessionRestorationStatus.AUTHENTICATION_REQUIRED, repository.state.value.restoration.status)
            assertTrue(repository.state.value.error.orEmpty().contains("Reconnect", ignoreCase = true))
        }
    }

    @Test
    fun `branch becomes immediately durable and restorable from authoritative response`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, backend.id)
            gateway.enqueue(
                "session.create",
                json.parseToJsonElement(
                    """{"session_id":"live-parent","stored_session_id":"stored-parent","messages":[],"info":{"stored_session_id":"stored-parent"}}""",
                ),
            )
            gateway.enqueue("model.options", json.parseToJsonElement("""{"providers":[]}"""))
            repository.newSession()
            gateway.enqueue(
                "session.branch",
                json.parseToJsonElement(
                    """{"session_id":"live-branch","stored_session_id":"stored-branch","title":"Branch","parent":"stored-parent","messages":[{"role":"user","text":"Parent message"}],"info":{"stored_session_id":"stored-branch"}}""",
                ),
            )
            gateway.enqueue("model.options", json.parseToJsonElement("""{"providers":[]}"""))

            repository.branchActive()

            assertEquals(SessionRestorationStatus.READY, repository.state.value.restoration.status)
            assertEquals("stored-branch", repository.state.value.activeStoredSession?.durableId)
            assertEquals("stored-branch", registry.sessionTarget(backend.id)?.sessionId)
        }
    }

    @Test
    fun `fresh repository reauthenticates and restores the persisted durable target`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/api/status" -> MockResponse().setBody("""{"status":"ready","hermes_version":"0.18.2"}""")
                    "/api/profiles/sessions" -> MockResponse().setBody(
                        """{"sessions":[{"session_id":"stored-session","profile":"research","title":"Restored"}]}""",
                    )
                    "/api/sessions/stored-session/messages" -> MockResponse().setBody(
                        """{"session_id":"stored-session","messages":[{"role":"user","text":"Persisted question"}]}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            registry.saveSessionTarget(SessionTarget(backend.id, "research", "stored-session"))
            credentials.put(backend.id, SESSION_COOKIE)
            gateway.enqueue(
                "session.resume",
                json.parseToJsonElement(
                    """{"session_id":"live-restored","session_key":"stored-session","messages":[{"role":"user","text":"Persisted question"}],"info":{"stored_session_id":"stored-session"}}""",
                ),
            )
            gateway.enqueue("model.options", json.parseToJsonElement("""{"providers":[]}"""))

            val restored = repository(
                context,
                registry,
                credentials,
                BillingPendingChargeStore(context, json),
                gateway,
            )

            withTimeout(5_000L) {
                restored.state.first {
                    it.restoration.status == SessionRestorationStatus.READY &&
                        it.runtimeSessionId == "live-restored"
                }
            }
            assertEquals("stored-session", restored.state.value.activeStoredSession?.durableId)
            assertEquals("research", restored.state.value.activeStoredSession?.profile)
        }
    }

    @Test
    fun `completion received during history refresh survives the older resume snapshot and refreshes metadata`() = runBlocking {
        MockWebServer().use { server ->
            val sessionFetches = AtomicInteger()
            val metadataRefreshStarted = CompletableDeferred<Unit>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/api/status" -> MockResponse().setBody("""{"status":"ready","hermes_version":"0.18.2"}""")
                    "/api/profiles/sessions" -> if (sessionFetches.incrementAndGet() == 1) {
                        MockResponse().setBody("""{"sessions":[]}""")
                    } else {
                        metadataRefreshStarted.complete(Unit)
                        MockResponse()
                            .setBodyDelay(1, TimeUnit.SECONDS)
                            .setBody(
                                """{"sessions":[{"session_id":"session-1","message_count":2,"last_active":1786579200}]}""",
                            )
                    }
                    "/api/sessions/session-1/messages" -> MockResponse()
                        .setBodyDelay(1, TimeUnit.SECONDS)
                        .setBody(
                            """{"session_id":"session-1","messages":[{"role":"user","text":"Question"},{"role":"assistant","text":"Complete answer"}]}""",
                        )
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(context, registry, credentials, BillingPendingChargeStore(context, json), gateway)
            awaitReady(repository, backend.id)
            gateway.enqueue(
                "session.resume",
                json.parseToJsonElement(
                    """{"session_id":"live-1","session_key":"session-1","messages":[],"running":true,"inflight":{"user":"Question","assistant":"Partial","streaming":true},"info":{"running":true}}""",
                ),
            )
            gateway.enqueue("model.options", json.parseToJsonElement("""{"providers":[]}"""))

            val opening = launch { repository.openSession(StoredSession(sessionId = "session-1")) }
            withTimeout(5_000L) {
                repository.state.first { state ->
                    state.runtimeSessionId == "live-1" && state.timeline.items.any {
                        it is TimelineItem.Message && it.text == "Partial" && it.streaming
                    }
                }
            }
            gateway.emit(
                GatewayEvent(
                    "message.complete",
                    "live-1",
                    buildJsonObject { put("text", "Complete answer"); put("status", "complete") },
                ),
            )
            withTimeout(5_000L) {
                repository.state.first { state ->
                    !state.runtimeInfo.running && state.timeline.items.any {
                        it is TimelineItem.Message && it.text == "Complete answer" && !it.streaming
                    }
                }
            }
            withTimeout(5_000L) { metadataRefreshStarted.await() }
            assertFalse(repository.state.value.loading)
            withTimeout(5_000L) {
                repository.state.first { state ->
                    !state.runtimeInfo.running && state.timeline.items.any {
                        it is TimelineItem.Message && it.text == "Complete answer" && !it.streaming
                    } && state.sessions.singleOrNull()?.messageCount == 2
                }
            }
            opening.join()

            val assistant = repository.state.value.timeline.items.filterIsInstance<TimelineItem.Message>().last()
            assertEquals("Complete answer", assistant.text)
            assertFalse(assistant.streaming)
            assertFalse(repository.state.value.runtimeInfo.running)
        }
    }

    @Test
    fun `silent session refresh cannot strand a visible refresh spinner`() = runBlocking {
        MockWebServer().use { server ->
            val sessionFetches = AtomicInteger()
            val visibleRefreshStarted = CompletableDeferred<Unit>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/api/status" -> MockResponse().setBody("""{"status":"ready","hermes_version":"0.18.2"}""")
                    "/api/profiles/sessions" -> when (sessionFetches.incrementAndGet()) {
                        1 -> MockResponse().setBody("""{"sessions":[]}""")
                        2 -> {
                            visibleRefreshStarted.complete(Unit)
                            MockResponse()
                                .setBodyDelay(1, TimeUnit.SECONDS)
                                .setBody("""{"sessions":[{"session_id":"session-1","message_count":1}]}""")
                        }
                        else -> MockResponse()
                            .setBodyDelay(1, TimeUnit.SECONDS)
                            .setBody("""{"sessions":[{"session_id":"session-1","message_count":2}]}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(
                context,
                registry,
                credentials,
                BillingPendingChargeStore(context, json),
                RecordingGateway(json),
            )
            awaitReady(repository, backend.id)

            val visible = launch { repository.refreshSessions() }
            withTimeout(5_000L) { visibleRefreshStarted.await() }
            assertTrue(repository.state.value.sessionListLoading)
            assertFalse(repository.state.value.loading)
            val silent = launch { repository.refreshSessions(showLoading = false) }
            visible.join()
            silent.join()
            withTimeout(5_000L) {
                repository.state.first { it.sessions.singleOrNull()?.messageCount == 2 }
            }

            assertFalse(repository.state.value.sessionListLoading)
            assertEquals(2, repository.state.value.sessions.single().messageCount)
        }
    }

    @Test
    fun `visible session refresh supersedes an in flight silent refresh`() = runBlocking {
        MockWebServer().use { server ->
            val sessionFetches = AtomicInteger()
            val silentRefreshStarted = CompletableDeferred<Unit>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/api/status" -> MockResponse().setBody("""{"status":"ready","hermes_version":"0.18.2"}""")
                    "/api/profiles/sessions" -> when (sessionFetches.incrementAndGet()) {
                        1 -> MockResponse().setBody("""{"sessions":[]}""")
                        2 -> {
                            silentRefreshStarted.complete(Unit)
                            MockResponse()
                                .setBodyDelay(1, TimeUnit.SECONDS)
                                .setBody("""{"sessions":[{"session_id":"session-1","message_count":1}]}""")
                        }
                        else -> MockResponse()
                            .setBodyDelay(1, TimeUnit.SECONDS)
                            .setBody("""{"sessions":[{"session_id":"session-1","message_count":2}]}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(
                context,
                registry,
                credentials,
                BillingPendingChargeStore(context, json),
                RecordingGateway(json),
            )
            awaitReady(repository, backend.id)

            val silent = launch { repository.refreshSessions(showLoading = false) }
            withTimeout(5_000L) { silentRefreshStarted.await() }
            val visible = launch { repository.refreshSessions() }
            withTimeout(5_000L) {
                while (sessionFetches.get() < 3) delay(10)
            }
            assertTrue(repository.state.value.sessionListLoading)
            assertFalse(repository.state.value.loading)
            visible.join()
            silent.join()

            assertFalse(repository.state.value.sessionListLoading)
            assertEquals(2, repository.state.value.sessions.single().messageCount)
        }
    }

    @Test
    fun `cancelled visible session refresh releases silent refreshes`() = runBlocking {
        MockWebServer().use { server ->
            val sessionFetches = AtomicInteger()
            val visibleRefreshStarted = CompletableDeferred<Unit>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/api/status" -> MockResponse().setBody("""{"status":"ready","hermes_version":"0.18.2"}""")
                    "/api/profiles/sessions" -> when (sessionFetches.incrementAndGet()) {
                        1 -> MockResponse().setBody("""{"sessions":[]}""")
                        2 -> {
                            visibleRefreshStarted.complete(Unit)
                            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        }
                        else -> MockResponse().setBody(
                            """{"sessions":[{"session_id":"session-1","message_count":2}]}""",
                        )
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(
                context,
                registry,
                credentials,
                BillingPendingChargeStore(context, json),
                RecordingGateway(json),
            )
            awaitReady(repository, backend.id)

            val visible = launch { repository.refreshSessions() }
            withTimeout(5_000L) { visibleRefreshStarted.await() }
            assertTrue(repository.state.value.sessionListLoading)
            assertFalse(repository.state.value.loading)
            visible.cancelAndJoin()
            assertFalse(repository.state.value.sessionListLoading)
            assertEquals(null, repository.state.value.error)

            repository.refreshSessions()

            assertEquals(2, repository.state.value.sessions.single().messageCount)
        }
    }

    @Test
    fun `cancelled visible refresh does not clear a newer session open spinner`() = runBlocking {
        MockWebServer().use { server ->
            val sessionFetches = AtomicInteger()
            val visibleRefreshStarted = CompletableDeferred<Unit>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/api/status" -> MockResponse().setBody("""{"status":"ready","hermes_version":"0.18.2"}""")
                    "/api/profiles/sessions" -> when (sessionFetches.incrementAndGet()) {
                        1 -> MockResponse().setBody("""{"sessions":[]}""")
                        else -> {
                            visibleRefreshStarted.complete(Unit)
                            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        }
                    }
                    "/api/sessions/session-1/messages" -> MockResponse().setBody(
                        """{"session_id":"session-1","messages":[]}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(
                context,
                registry,
                credentials,
                BillingPendingChargeStore(context, json),
                gateway,
            )
            awaitReady(repository, backend.id)
            val openStarted = CompletableDeferred<Unit>()
            val releaseOpen = CompletableDeferred<Unit>()
            gateway.enqueueBlock("session.resume") {
                openStarted.complete(Unit)
                releaseOpen.await()
                json.parseToJsonElement(
                    """{"session_id":"live-1","session_key":"session-1","messages":[]}""",
                )
            }
            gateway.enqueue("model.options", json.parseToJsonElement("""{"providers":[]}"""))

            val visible = launch { repository.refreshSessions() }
            withTimeout(5_000L) { visibleRefreshStarted.await() }
            val opening = launch { repository.openSession(StoredSession(sessionId = "session-1")) }
            withTimeout(5_000L) { openStarted.await() }
            visible.cancelAndJoin()

            assertTrue(repository.state.value.loading)
            assertFalse(repository.state.value.sessionListLoading)
            assertEquals(null, repository.state.value.error)

            releaseOpen.complete(Unit)
            opening.join()
        }
    }

    @Test
    fun `cancelled visible refresh clears loading after a session list mutation`() = runBlocking {
        MockWebServer().use { server ->
            val sessionFetches = AtomicInteger()
            val visibleRefreshStarted = CompletableDeferred<Unit>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.requestUrl?.encodedPath == "/api/status" ->
                        MockResponse().setBody("""{"status":"ready","hermes_version":"0.18.2"}""")
                    request.requestUrl?.encodedPath == "/api/profiles/sessions" ->
                        if (sessionFetches.incrementAndGet() == 1) {
                            MockResponse().setBody("""{"sessions":[]}""")
                        } else {
                            visibleRefreshStarted.complete(Unit)
                            MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)
                        }
                    request.requestUrl?.encodedPath == "/api/sessions/session-1" && request.method == "PATCH" ->
                        MockResponse().setBody("{}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(
                context,
                registry,
                credentials,
                BillingPendingChargeStore(context, json),
                RecordingGateway(json),
            )
            awaitReady(repository, backend.id)

            val visible = launch { repository.refreshSessions() }
            withTimeout(5_000L) { visibleRefreshStarted.await() }
            repository.pinSession(backend.id, StoredSession(sessionId = "session-1", pinned = false))
            visible.cancelAndJoin()

            assertFalse(repository.state.value.sessionListLoading)
            assertEquals(null, repository.state.value.error)
        }
    }

    @Test
    fun `visible refresh invalidated by a session mutation retries`() = runBlocking {
        MockWebServer().use { server ->
            val sessionFetches = AtomicInteger()
            val refreshStarted = CompletableDeferred<Unit>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.requestUrl?.encodedPath == "/api/status" ->
                        MockResponse().setBody("""{"status":"ready","hermes_version":"0.18.2"}""")
                    request.requestUrl?.encodedPath == "/api/profiles/sessions" ->
                        when (sessionFetches.incrementAndGet()) {
                            1 -> MockResponse().setBody("""{"sessions":[]}""")
                            2 -> {
                                refreshStarted.complete(Unit)
                                MockResponse().setHeadersDelay(1, TimeUnit.SECONDS).setResponseCode(500)
                            }
                            else -> MockResponse().setBody(
                                """{"sessions":[{"session_id":"session-1","pinned":true}]}""",
                            )
                        }
                    request.requestUrl?.encodedPath == "/api/sessions/session-1" && request.method == "PATCH" ->
                        MockResponse().setBody("{}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(
                context,
                registry,
                credentials,
                BillingPendingChargeStore(context, json),
                RecordingGateway(json),
            )
            awaitReady(repository, backend.id)

            val visible = launch { repository.refreshSessions() }
            withTimeout(5_000L) { refreshStarted.await() }
            repository.pinSession(backend.id, StoredSession(sessionId = "session-1", pinned = false))
            visible.join()
            withTimeout(5_000L) {
                while (sessionFetches.get() < 3) delay(10)
            }

            assertFalse(repository.state.value.sessionListLoading)
        }
    }

    @Test
    fun `silent session refresh handles missing active credentials`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = readyDashboardDispatcher()
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(
                context,
                registry,
                credentials,
                BillingPendingChargeStore(context, json),
                RecordingGateway(json),
            )
            awaitReady(repository, backend.id)
            credentials.remove(backend.id)

            repository.refreshSessions(showLoading = false)
            withTimeout(5_000L) { repository.state.first { it.backend == null } }

            assertFalse(repository.state.value.sessionListLoading)
        }
    }

    @Test
    fun `failed visible session refresh preserves chat loading`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/api/status" -> MockResponse().setBody("""{"status":"ready","hermes_version":"0.18.2"}""")
                    "/api/profiles/sessions" -> MockResponse().setBody("""{"sessions":[]}""")
                    "/api/sessions/session-1/messages" -> MockResponse().setBody(
                        """{"session_id":"session-1","messages":[]}""",
                    )
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(
                context,
                registry,
                credentials,
                BillingPendingChargeStore(context, json),
                gateway,
            )
            awaitReady(repository, backend.id)
            val openStarted = CompletableDeferred<Unit>()
            val releaseOpen = CompletableDeferred<Unit>()
            gateway.enqueueBlock("session.resume") {
                openStarted.complete(Unit)
                releaseOpen.await()
                json.parseToJsonElement(
                    """{"session_id":"live-1","session_key":"session-1","messages":[]}""",
                )
            }
            gateway.enqueue("model.options", json.parseToJsonElement("""{"providers":[]}"""))

            val opening = launch { repository.openSession(StoredSession(sessionId = "session-1")) }
            withTimeout(5_000L) { openStarted.await() }
            repository.refreshSessions()

            assertTrue(repository.state.value.loading)
            assertFalse(repository.state.value.sessionListLoading)
            assertTrue(repository.state.value.sessionListError.orEmpty().isNotBlank())

            releaseOpen.complete(Unit)
            opening.join()
            repository.refreshSessions(showLoading = false)
            assertEquals(null, repository.state.value.sessionListError)
        }
    }

    @Test
    fun `silent authentication failure survives a concurrent session mutation`() = runBlocking {
        MockWebServer().use { server ->
            val sessionFetches = AtomicInteger()
            val refreshStarted = CompletableDeferred<Unit>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.requestUrl?.encodedPath == "/api/status" ->
                        MockResponse().setBody("""{"status":"ready","hermes_version":"0.18.2"}""")
                    request.requestUrl?.encodedPath == "/api/profiles/sessions" && sessionFetches.incrementAndGet() == 1 -> {
                        MockResponse().setBody("""{"sessions":[]}""")
                    }
                    request.requestUrl?.encodedPath == "/api/profiles/sessions" -> {
                        refreshStarted.complete(Unit)
                        MockResponse().setHeadersDelay(1, TimeUnit.SECONDS).setResponseCode(401)
                    }
                    request.requestUrl?.encodedPath == "/api/sessions/session-1" && request.method == "PATCH" ->
                        MockResponse().setBody("{}")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(
                context,
                registry,
                credentials,
                BillingPendingChargeStore(context, json),
                RecordingGateway(json),
            )
            awaitReady(repository, backend.id)

            val refresh = launch { repository.refreshSessions(showLoading = false) }
            withTimeout(5_000L) { refreshStarted.await() }
            repository.pinSession(backend.id, StoredSession(sessionId = "session-1", pinned = false))
            refresh.join()
            withTimeout(5_000L) { repository.state.first { it.backend == null } }

            assertFalse(repository.state.value.sessionListLoading)
        }
    }

    @Test
    fun `chat failure does not change an in flight session refresh`() = runBlocking {
        MockWebServer().use { server ->
            val sessionFetches = AtomicInteger()
            val visibleRefreshStarted = CompletableDeferred<Unit>()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/api/status" -> MockResponse().setBody("""{"status":"ready","hermes_version":"0.18.2"}""")
                    "/api/profiles/sessions" -> if (sessionFetches.incrementAndGet() == 1) {
                        MockResponse().setBody("""{"sessions":[]}""")
                    } else {
                        visibleRefreshStarted.complete(Unit)
                        MockResponse()
                            .setBodyDelay(1, TimeUnit.SECONDS)
                            .setBody("""{"sessions":[{"session_id":"session-1","message_count":2}]}""")
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
            server.start()
            val context = RuntimeEnvironment.getApplication()
            val backend = backend(server)
            val registry = BackendRegistry(context, json)
            val credentials = InMemoryCredentialStore()
            val gateway = RecordingGateway(json)
            registry.save(backend)
            credentials.put(backend.id, SESSION_COOKIE)
            val repository = repository(
                context,
                registry,
                credentials,
                BillingPendingChargeStore(context, json),
                gateway,
            )
            awaitReady(repository, backend.id)
            gateway.enqueueFailure("session.resume", IllegalStateException("Chat failed"))

            val refresh = launch { repository.refreshSessions() }
            withTimeout(5_000L) { visibleRefreshStarted.await() }
            repository.openSession(StoredSession(sessionId = "session-1"))

            assertTrue(repository.state.value.sessionListLoading)
            assertEquals("Chat failed", repository.state.value.error)

            refresh.join()

            assertFalse(repository.state.value.sessionListLoading)
            assertEquals("Chat failed", repository.state.value.error)
            assertEquals(2, repository.state.value.sessions.single().messageCount)
        }
    }

    private fun repository(
        context: Context,
        registry: BackendRegistry,
        credentials: SessionCredentialStore,
        pendingStore: BillingPendingChargeStore,
        gateway: HermesGatewayClient,
    ): HermesRepository {
        val client = OkHttpClient()
        val rest = HermesRestClient(client, json)
        return HermesRepository(
            backendRegistry = registry,
            tokenStore = credentials,
            restClient = rest,
            gateway = gateway,
            dashboardConnector = DashboardBackendConnector(
                DashboardAuthClient(client, json),
                rest,
                gateway,
                credentials,
                registry,
            ),
            json = json,
            attachmentReader = AttachmentReader(context),
            draftStore = DraftStore(context),
            composerQueueStore = ComposerQueueStore(context, json),
            privacyPreferences = PrivacyPreferences(
                PreferenceDataStoreFactory.create { context.filesDir.resolve("privacy-test.preferences_pb") },
            ),
            billingPendingChargeStore = pendingStore,
        )
    }

    private suspend fun awaitReady(repository: HermesRepository, backendId: String) {
        withTimeout(10_000L) {
            repository.state.first {
                it.backend?.id == backendId && !it.loading && !it.backendTransitionInProgress
            }
        }
    }

    private fun backend(server: MockWebServer) = BackendConfig(
        id = "personal-${BACKEND_IDS.incrementAndGet()}",
        label = "Personal",
        baseUrl = server.url("/").toString().replace("localhost", "127.0.0.1").trimEnd('/'),
        authMode = AuthMode.DASHBOARD_SESSION,
        allowInsecurePrivateNetwork = true,
    )

    private fun billingState(): JsonElement = checkNotNull(
        javaClass.getResource("/fixtures/billing-state-5988fe6.json"),
    ).readText().let(json::parseToJsonElement)

    private fun readyDashboardDispatcher(withProfiles: Boolean = false) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
            "/api/status" -> MockResponse().setBody("""{"status":"ready","hermes_version":"0.18.2"}""")
            "/api/profiles/sessions" -> MockResponse().setBody("""{"sessions":[]}""")
            "/api/profiles" -> if (withProfiles) MockResponse().setBody("""{"profiles":[]}""") else MockResponse().setResponseCode(404)
            "/api/profiles/active" -> if (withProfiles) MockResponse().setBody("""{"active":"default","current":"default"}""") else MockResponse().setResponseCode(404)
            else -> MockResponse().setResponseCode(404)
        }
    }

    private companion object {
        val BACKEND_IDS = AtomicInteger()
        val SESSION_COOKIE = DashboardSessionCredential("hermes_session_at", "session-value")
    }
}

private class InMemoryCredentialStore : SessionCredentialStore {
    private val cookies = mutableMapOf<String, DashboardSessionCredential>()

    override fun put(backendId: String, cookie: DashboardSessionCredential) {
        cookies[backendId] = cookie
    }

    override fun get(backendId: String): DashboardSessionCredential? = cookies[backendId]

    override fun remove(backendId: String) {
        cookies.remove(backendId)
    }
}

private class RecordingGateway(
    private val json: Json,
) : HermesGatewayClient {
    private val mutableConnectionState = MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Idle)
    private val mutableEvents = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 8)
    private val responses = mutableMapOf<String, ArrayDeque<suspend () -> JsonElement>>()
    private var blockedReconnect: BlockedReconnect? = null
    val requests = CopyOnWriteArrayList<RecordedGatewayRequest>()
    val connectedBackendIds = mutableListOf<String>()
    var forkedGateway: RecordingGateway? = null

    override fun fork(): HermesGatewayClient = forkedGateway ?: this

    override val connectionState: StateFlow<GatewayConnectionState> = mutableConnectionState
    override val events: SharedFlow<GatewayEvent> = mutableEvents

    fun enqueue(method: String, response: JsonElement) {
        responses.getOrPut(method, ::ArrayDeque).addLast { response }
    }

    fun enqueueFailure(method: String, error: Throwable) {
        responses.getOrPut(method, ::ArrayDeque).addLast { throw error }
    }

    fun enqueueBlock(method: String, response: suspend () -> JsonElement) {
        responses.getOrPut(method, ::ArrayDeque).addLast(response)
    }

    fun blockNextReconnect(
        backendId: String,
        started: CompletableDeferred<Unit>,
        release: CompletableDeferred<Unit>,
        completed: CompletableDeferred<Unit>,
    ) {
        blockedReconnect = BlockedReconnect(backendId, started, release, completed)
    }

    fun failConnection(reason: String) {
        mutableConnectionState.value = GatewayConnectionState.Failed(reason)
    }

    fun emit(event: GatewayEvent) {
        check(mutableEvents.tryEmit(event))
    }

    override suspend fun connect(config: BackendConfig, token: String) {
        connect(config)
    }

    override suspend fun connect(config: BackendConfig, cookie: DashboardSessionCredential) {
        connect(config)
    }

    private suspend fun connect(config: BackendConfig) {
        val blocked = blockedReconnect?.takeIf {
            it.backendId == config.id && connectedBackendIds.contains(config.id)
        }
        if (blocked != null) {
            blockedReconnect = null
            blocked.started.complete(Unit)
            blocked.release.await()
        }
        connectedBackendIds += config.id
        mutableConnectionState.value = GatewayConnectionState.Open
        blocked?.completed?.complete(Unit)
    }

    override suspend fun disconnect() {
        mutableConnectionState.value = GatewayConnectionState.Closed("test disconnect")
    }

    override suspend fun request(method: String, params: JsonElement): JsonElement {
        requests += RecordedGatewayRequest(method, params)
        return responses[method]?.removeFirstOrNull()?.invoke()
            ?: error("No fake response for $method: ${json.encodeToString(JsonElement.serializer(), params)}")
    }
}

private data class BlockedReconnect(
    val backendId: String,
    val started: CompletableDeferred<Unit>,
    val release: CompletableDeferred<Unit>,
    val completed: CompletableDeferred<Unit>,
)

private data class RecordedGatewayRequest(
    val method: String,
    val params: JsonElement,
)
