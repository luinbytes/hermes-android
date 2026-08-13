package com.nousresearch.hermes.data

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.core.content.FileProvider
import com.nousresearch.hermes.network.DashboardAuthClient
import com.nousresearch.hermes.network.DashboardSessionCredential
import com.nousresearch.hermes.network.HermesRestClient
import com.nousresearch.hermes.domain.TimelineItem
import com.nousresearch.hermes.protocol.GatewayConnectionState
import com.nousresearch.hermes.protocol.GatewayEvent
import com.nousresearch.hermes.protocol.HermesGatewayClient
import com.nousresearch.hermes.protocol.StoredSession
import com.nousresearch.hermes.platform.newCameraCaptureUri
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
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
            gateway.blockNextReconnect(backendA.id, reconnectStarted, releaseReconnect)

            gateway.failConnection("network dropped")
            withTimeout(5_000L) { reconnectStarted.await() }
            registry.save(backendB)
            releaseReconnect.complete(Unit)
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
    fun `latest session open wins when an earlier resume finishes last`() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/api/status" -> MockResponse().setBody("""{"status":"ready","hermes_version":"0.18.2"}""")
                    "/api/profiles/sessions" -> MockResponse().setBody("""{"sessions":[]}""")
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
            val sessionFetches = AtomicInteger()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
                    "/api/status" -> MockResponse().setBody("""{"status":"ready","hermes_version":"0.18.2"}""")
                    "/api/profiles/sessions" -> if (sessionFetches.incrementAndGet() == 1) {
                        MockResponse().setBody("""{"sessions":[]}""")
                    } else {
                        MockResponse().setResponseCode(500).setBody("Refresh failed")
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

            val opening = launch { repository.openSession(StoredSession(sessionId = "session-1")) }
            withTimeout(5_000L) { openStarted.await() }
            repository.refreshSessions()

            assertTrue(repository.state.value.loading)
            assertFalse(repository.state.value.sessionListLoading)
            assertTrue(repository.state.value.sessionListError.orEmpty().isNotBlank())

            releaseOpen.complete(Unit)
            opening.join()
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
        withTimeout(5_000L) {
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

    private fun readyDashboardDispatcher() = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl?.encodedPath) {
            "/api/status" -> MockResponse().setBody("""{"status":"ready","hermes_version":"0.18.2"}""")
            "/api/profiles/sessions" -> MockResponse().setBody("""{"sessions":[]}""")
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
    val requests = mutableListOf<RecordedGatewayRequest>()
    val connectedBackendIds = mutableListOf<String>()

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
    ) {
        blockedReconnect = BlockedReconnect(backendId, started, release)
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
)

private data class RecordedGatewayRequest(
    val method: String,
    val params: JsonElement,
)
