package io.github.nanomuse.debug

import java.util.Collections

/** Header-only debug snapshot; leaves the caller's transport headers untouched. */
object RequestHeaderSanitizer {
    private val sensitiveNames = setOf(
        "authorization",
        "proxy-authorization",
        "x-api-key",
        "api-key",
        "apikey",
        "x-goog-api-key",
        "cookie",
        "set-cookie",
    )

    fun snapshot(headers: Map<String, String>): Map<String, String> {
        val snapshot = linkedMapOf<String, String>()
        for ((name, value) in headers) {
            snapshot[name] = if (sensitiveNames.any { it.equals(name, ignoreCase = true) }) {
                "[REDACTED]"
            } else value
        }
        return Collections.unmodifiableMap(snapshot)
    }
}
