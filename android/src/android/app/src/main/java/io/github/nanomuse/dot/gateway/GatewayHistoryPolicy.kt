package io.github.nanomuse.dot.gateway

import java.net.URI

/** Output reasoning and reasoning-history input are distinct endpoint capabilities. */
object GatewayHistoryPolicy {
    fun forbidsReasoningHistory(baseURL: String): Boolean = runCatching {
        val uri = URI(baseURL)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("molamaker.si", ignoreCase = true) &&
            (uri.port == -1 || uri.port == 443) && uri.rawUserInfo == null
    }.getOrDefault(false)
}
