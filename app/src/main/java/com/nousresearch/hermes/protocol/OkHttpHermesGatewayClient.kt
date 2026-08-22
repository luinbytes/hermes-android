package com.nousresearch.hermes.protocol

import com.nousresearch.hermes.data.BackendConfig
import com.nousresearch.hermes.data.AuthMode
import com.nousresearch.hermes.network.DashboardAuthClient
import com.nousresearch.hermes.network.DashboardSessionCredential
import com.nousresearch.hermes.network.TransportPolicy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

@Singleton
class OkHttpHermesGatewayClient @Inject constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val authClient: DashboardAuthClient,
) : HermesGatewayClient {
    private val requestIds = AtomicLong(0)
    private val connectionIds = AtomicLong(0)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val mutableConnectionState = MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Idle)
    private val mutableEvents = MutableSharedFlow<GatewayEvent>(
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    private var socket: WebSocket? = null

    override val connectionState = mutableConnectionState.asStateFlow()
    override val events = mutableEvents.asSharedFlow()

    override fun fork(): HermesGatewayClient = OkHttpHermesGatewayClient(client, json, authClient)

    override suspend fun connect(config: BackendConfig, token: String) {
        require(config.authMode != AuthMode.DASHBOARD_SESSION) { "Dashboard sessions require a single-use WebSocket ticket" }
        connect(gatewayUrl(config, "token", token))
    }

    override suspend fun connect(config: BackendConfig, cookie: DashboardSessionCredential) {
        require(config.authMode == AuthMode.DASHBOARD_SESSION) { "Dashboard session credentials require dashboard authentication" }
        connect(gatewayUrl(config, "ticket", authClient.mintWebSocketTicket(config, cookie)))
    }

    private suspend fun connect(url: HttpUrl) {
        val connectionId = connectionIds.incrementAndGet()
        val previous = socket
        socket = null
        previous?.close(1000, "connection replaced")
        failPending(HermesRpcException("Hermes gateway connection replaced"))
        mutableConnectionState.value = GatewayConnectionState.Connecting(attempt = 1)
        val opened = CompletableDeferred<Unit>()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Hermes-Android/0.1")
            .build()
        val nextSocket = client.newWebSocket(request, listener(opened, connectionId))
        if (connectionIds.get() != connectionId) {
            nextSocket.cancel()
            throw HermesRpcException("Hermes gateway connection was superseded")
        }
        socket = nextSocket
        try {
            withTimeout(CONNECT_TIMEOUT_MILLIS) { opened.await() }
        } catch (error: Throwable) {
            if (socket === nextSocket) socket = null
            nextSocket.cancel()
            throw error
        }
    }

    override suspend fun disconnect() {
        connectionIds.incrementAndGet()
        val previous = socket
        socket = null
        previous?.close(1000, "client disconnect")
        failPending(HermesRpcException("Hermes gateway disconnected"))
        mutableConnectionState.value = GatewayConnectionState.Closed("client disconnect")
    }

    override suspend fun request(method: String, params: JsonElement): JsonElement {
        val activeSocket = socket ?: throw HermesRpcException("Hermes gateway is not connected")
        val id = requestIds.incrementAndGet()
        val deferred = CompletableDeferred<JsonElement>()
        pending[id] = deferred
        val frame = JsonRpcRequest(id = id, method = method, params = params)
        val accepted = activeSocket.send(json.encodeToString(JsonRpcRequest.serializer(), frame))
        if (!accepted) {
            pending.remove(id)
            throw HermesRpcException("Hermes gateway rejected the request")
        }
        return try {
            withTimeout(REQUEST_TIMEOUT_MILLIS) { deferred.await() }
        } finally {
            pending.remove(id)
        }
    }

    private fun listener(
        opened: CompletableDeferred<Unit>,
        connectionId: Long,
    ) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (connectionIds.get() != connectionId) return
            mutableConnectionState.value = GatewayConnectionState.Open
            opened.complete(Unit)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (connectionIds.get() != connectionId) return
            val frame = runCatching { json.decodeFromString(JsonRpcFrame.serializer(), text) }
                .getOrElse { return }
            frame.params?.takeIf { frame.method == "event" }?.let {
                if (!mutableEvents.tryEmit(it)) {
                    webSocket.close(1013, "client event buffer exhausted")
                    mutableConnectionState.value = GatewayConnectionState.Failed(
                        "Hermes sent events faster than Android could safely process them; reconnecting to resynchronise.",
                    )
                }
                return
            }
            val id = frame.id ?: return
            val call = pending.remove(id) ?: return
            frame.error?.let {
                call.completeExceptionally(HermesRpcException(it.message, it.code))
            } ?: call.complete(frame.result ?: kotlinx.serialization.json.JsonNull)
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(code, reason)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (connectionIds.get() != connectionId) return
            socket = null
            failPending(HermesRpcException("Hermes gateway closed: $reason"))
            mutableConnectionState.value = GatewayConnectionState.Closed(reason)
            if (!opened.isCompleted) opened.completeExceptionally(HermesRpcException(reason))
        }

        override fun onFailure(webSocket: WebSocket, throwable: Throwable, response: Response?) {
            if (connectionIds.get() != connectionId) return
            socket = null
            failPending(throwable)
            mutableConnectionState.value = GatewayConnectionState.Failed(
                throwable.message ?: "Hermes gateway connection failed",
            )
            if (!opened.isCompleted) opened.completeExceptionally(throwable)
        }
    }

    private fun gatewayUrl(config: BackendConfig, credentialName: String, credential: String): HttpUrl {
        val uri = TransportPolicy.validate(config).getOrThrow()
        val base = uri.toString().trimEnd('/').toHttpUrl()
        return base.newBuilder()
            .addPathSegments("api/ws")
            .addQueryParameter(credentialName, credential)
            .build()
    }

    private fun failPending(error: Throwable) {
        pending.values.forEach { it.completeExceptionally(error) }
        pending.clear()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 15_000L
        const val REQUEST_TIMEOUT_MILLIS = 60_000L
    }
}
