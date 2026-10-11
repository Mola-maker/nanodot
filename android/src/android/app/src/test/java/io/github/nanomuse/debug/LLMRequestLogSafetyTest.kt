package io.github.nanomuse.debug

import com.openminis.app.BuildConfig
import com.openminis.app.debug.LLMRequestLog
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LLMRequestLogSafetyTest {
    @Before
    fun resetLog() {
        assertTrue("These retention tests require the actual debug BuildConfig", BuildConfig.DEBUG)
        LLMRequestLog.clear()
    }

    @After
    fun clearLog() = LLMRequestLog.clear()

    // Removing ingestion masking must expose these synthetic values in retained entries and JSON.
    @Test
    fun `sensitive headers are masked before retention and raw JSON export`() {
        val sensitive = linkedMapOf(
            "Authorization" to "TEST_ONLY_AUTHORIZATION",
            "aUtHoRiZaTiOn" to "TEST_ONLY_MIXED_AUTHORIZATION",
            "pRoXy-AuThOrIzAtIoN" to "TEST_ONLY_PROXY_AUTHORIZATION",
            "X-API-Key" to "TEST_ONLY_X_API_KEY",
            "aPi-KeY" to "TEST_ONLY_API_KEY",
            "ApIkEy" to "TEST_ONLY_APIKEY",
            "x-GoOg-ApI-kEy" to "TEST_ONLY_GOOGLE_API_KEY",
            "cOoKiE" to "TEST_ONLY_COOKIE",
            "sEt-CoOkIe" to "TEST_ONLY_SET_COOKIE",
        )
        val headers = sensitive + mapOf("Content-Type" to "application/json", "X-Request-Id" to "public-id")
        LLMRequestLog.add(entry(headers))

        val retained = LLMRequestLog.getAll().single().requestHeaders
        val last = LLMRequestLog.getLast(1).single().requestHeaders
        val full = LLMRequestLog.toJSON()
        val tail = LLMRequestLog.toJSON(1)
        for (name in sensitive.keys) {
            assertEquals("Retained header $name", "[REDACTED]", retained[name])
            assertEquals("Tail header $name", "[REDACTED]", last[name])
            assertEquals("Exported header $name", "[REDACTED]", full.getJSONArray("requests")
                .getJSONObject(0).getJSONObject("requestHeaders").getString(name))
        }
        for (fixture in sensitive.values) {
            assertFalse("Full export retained a synthetic secret", full.toString().contains(fixture))
            assertFalse("Tail export retained a synthetic secret", tail.toString().contains(fixture))
        }
        assertEquals(headers.keys, retained.keys)
        assertEquals("application/json", retained["Content-Type"])
        assertEquals("public-id", retained["X-Request-Id"])
        assertEquals("public-id", full.getJSONArray("requests").getJSONObject(0)
            .getJSONObject("requestHeaders").getString("X-Request-Id"))
        assertEquals("TEST_ONLY_AUTHORIZATION", headers["Authorization"])
    }

    // Aliasing the caller map in either body branch must change the retained/exported snapshot.
    @Test
    fun `caller mutation cannot change retained headers for short or truncated bodies`() {
        for (body in listOf("{}", "x".repeat(64 * 1024 + 7))) {
            val headers = linkedMapOf("Authorization" to "TEST_ONLY_BEFORE_MUTATION", "Accept" to "application/json")
            LLMRequestLog.clear()
            LLMRequestLog.add(entry(headers, body))
            headers["Authorization"] = "TEST_ONLY_AFTER_MUTATION"
            headers["Accept"] = "changed"
            headers["X-API-Key"] = "TEST_ONLY_LATE_KEY"

            val retained = LLMRequestLog.getLast(1).single()
            assertEquals("[REDACTED]", retained.requestHeaders["Authorization"])
            assertEquals("application/json", retained.requestHeaders["Accept"])
            assertFalse(retained.requestHeaders.containsKey("X-API-Key"))
            val exported = LLMRequestLog.toJSON().toString()
            for (fixture in listOf("TEST_ONLY_BEFORE_MUTATION", "TEST_ONLY_AFTER_MUTATION", "TEST_ONLY_LATE_KEY")) {
                assertFalse("Caller mutation reintroduced a synthetic secret", exported.contains(fixture))
            }
            if (body.length > 64 * 1024) {
                assertEquals("x".repeat(64 * 1024) + "\n…[truncated 7 chars]", retained.requestBody)
            } else {
                assertEquals("{}", retained.requestBody)
            }
        }
    }

    // Header sanitization must keep the existing bounded ring and JSON envelope useful.
    @Test
    fun `masked entries preserve ring order and diagnostic envelope`() {
        repeat(21) { index ->
            LLMRequestLog.add(entry(mapOf("Authorization" to "TEST_ONLY_RING_$index"), provider = "fixture-$index"))
        }
        assertEquals(20, LLMRequestLog.getAll().size)
        assertEquals("fixture-1", LLMRequestLog.getAll().first().provider)
        assertEquals(listOf("fixture-19", "fixture-20"), LLMRequestLog.getLast(2).map { it.provider })
        val export = LLMRequestLog.toJSON(2)
        assertEquals(2, export.getInt("count"))
        val newest = export.getJSONArray("requests").getJSONObject(1)
        assertEquals("fixture-20", newest.getString("provider"))
        assertEquals("https://example.invalid/chat/completions", newest.getString("requestURL"))
        assertEquals("POST", newest.getString("requestMethod"))
        assertEquals(200, newest.getInt("responseStatusCode"))
        assertEquals(12L, newest.getLong("durationMs"))
        assertEquals("public response", newest.getString("responseBody"))
        assertEquals(0, newest.getJSONObject("requestBody").length())
        assertFalse(export.toString().contains("TEST_ONLY_RING_"))
    }

    private fun entry(
        headers: Map<String, String>,
        body: String = "{}",
        provider: String = "fixture",
    ) = LLMRequestLog.Entry(
        provider = provider,
        requestURL = "https://example.invalid/chat/completions",
        requestHeaders = headers,
        requestBody = body,
        durationMs = 12,
        responseStatusCode = 200,
        responseBody = "public response",
    )
}
