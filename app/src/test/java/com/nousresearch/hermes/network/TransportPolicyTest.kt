package com.nousresearch.hermes.network

import com.nousresearch.hermes.data.AuthMode
import com.nousresearch.hermes.data.BackendConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportPolicyTest {
    @Test
    fun `https is accepted without private-network override`() {
        assertTrue(TransportPolicy.validate(config("https://hermes.example.com", false)).isSuccess)
    }

    @Test
    fun `private-network selection does not downgrade public https`() {
        assertEquals(
            "https://hermes.example.com",
            TransportPolicy.validate(config("https://hermes.example.com", true)).getOrThrow().toString(),
        )
    }

    @Test
    fun `cleartext public host is rejected even with override`() {
        assertTrue(TransportPolicy.validate(config("http://hermes.example.com", true)).isFailure)
    }

    @Test
    fun `cleartext private literal requires explicit override`() {
        assertTrue(TransportPolicy.validate(config("http://192.168.1.10:8080", false)).isFailure)
        assertTrue(TransportPolicy.validate(config("http://192.168.1.10:8080", true)).isSuccess)
    }

    @Test
    fun `private-network HTTP selection resolves private https input as cleartext`() {
        assertEquals(
            "http://192.168.0.112:9120",
            TransportPolicy.validate(config("https://192.168.0.112:9120", true)).getOrThrow().toString(),
        )
    }

    @Test
    fun `tailscale cgnat range is treated as private`() {
        assertTrue(TransportPolicy.validate(config("http://100.79.4.2:8080", true)).isSuccess)
    }

    private fun config(url: String, allowHttp: Boolean) = BackendConfig(
        id = "test",
        label = "Test",
        baseUrl = url,
        authMode = AuthMode.TOKEN,
        allowInsecurePrivateNetwork = allowHttp,
    )
}
