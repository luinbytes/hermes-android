package com.nousresearch.hermes.network

import com.nousresearch.hermes.data.BackendConfig
import java.net.URI

object TransportPolicy {
    fun validate(config: BackendConfig): Result<URI> = runCatching {
        val requested = URI(config.baseUrl.trim().trimEnd('/'))
        require(requested.scheme == "https" || requested.scheme == "http") {
            "Hermes URL must use HTTPS or HTTP"
        }
        require(!requested.host.isNullOrBlank()) { "Hermes URL must include a host" }
        require(requested.userInfo == null) { "Credentials must not be embedded in the URL" }
        require(requested.fragment == null) { "Hermes URL must not include a fragment" }

        val uri = if (
            requested.scheme == "https" &&
            config.allowInsecurePrivateNetwork &&
            isPrivateLiteral(requested.host)
        ) {
            URI("http:${requested.rawSchemeSpecificPart}")
        } else {
            requested
        }

        if (uri.scheme == "http") {
            require(config.allowInsecurePrivateNetwork) {
                "Cleartext HTTP is disabled. Use HTTPS, or explicitly allow a private-network connection."
            }
            require(isPrivateLiteral(uri.host)) {
                "Cleartext HTTP is allowed only for literal loopback, RFC1918, or Tailscale addresses"
            }
        }
        uri
    }

    private fun isPrivateLiteral(host: String): Boolean {
        val normalized = host.removePrefix("[").removeSuffix("]").lowercase()
        if (normalized == "localhost" || normalized == "::1") return true
        if (normalized.startsWith("fd") || normalized.startsWith("fc")) return true
        val octets = normalized.split('.').mapNotNull(String::toIntOrNull)
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            octets[0] == 127 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 100 && octets[1] in 64..127)
    }
}
