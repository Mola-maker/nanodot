package io.github.nanomuse.apiproof

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ForegroundOwnedClientTest {
    private fun gate(): ForegroundProofContract {
        val run="01234567-89ab-cdef-0123-456789abcdef"
        return ForegroundProofContract(JSONObject("""{"schema":1,"run":"$run","owner":"$run","serial":"emulator-5584","source":"${"a".repeat(40)}","main_apk_sha256":"${"b".repeat(64)}","test_apk_sha256":"${"c".repeat(64)}","certificate_sha256":"${"d".repeat(64)}","reservations":[{"slot":"Chat","case":"foreground-chat-$run","number":5,"cap":64},{"slot":"CareInitial","case":"foreground-care-initial-$run","number":6,"cap":1024},{"slot":"CareNext","case":"foreground-care-next-$run","number":7,"cap":1024}]}"""))
    }
    private fun request(url: HttpUrl, body: String="public") = Request.Builder().url(url).post(body.toRequestBody("application/json".toMediaType())).build()
    @Test fun automaticFollowupsAndIndependentCallsNeverTransmitTwice() {
        listOf(302,401,503).forEach { code -> MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Retry-After","0").setHeader("Location","/redirect").setBody("remote body"))
            val endpoint=server.url("/chat/completions")
            ForegroundOwnedClient(endpoint,ProofSlot.Chat,gate(),{ check(it=="public") },{ it }).use { owned ->
                owned.client.newCall(request(endpoint)).execute().use { assertEquals(code,it.code); assertFalse(it.body!!.string().contains("remote body")) }
                try { owned.client.newCall(request(endpoint)).execute().close(); fail("second exchange admitted") } catch (_: IOException) {}
                assertEquals(1,server.requestCount); assertEquals(1,owned.exchanges)
            }
        } }
    }
    @Test fun wrongBodyOrOutOfOrderSlotIsRejectedBeforeHeaders() {
        MockWebServer().use { server ->
            val endpoint=server.url("/chat/completions")
            listOf(ProofSlot.Chat,ProofSlot.CareInitial).forEach { slot ->
                ForegroundOwnedClient(endpoint,slot,gate(),{ check(it=="public") },{ it }).use { owned ->
                    try { owned.client.newCall(request(endpoint,if(slot==ProofSlot.Chat) "private" else "public")).execute().close(); fail("unsafe request admitted") } catch (_: IOException) {}
                    assertEquals(0,owned.exchanges)
                }
            }
            assertEquals(0,server.requestCount)
        }
    }
    @Test fun closeCancelsOnlyOwnedResourcesAndDeniesFutureCalls() {
        MockWebServer().use { server ->
            val endpoint=server.url("/chat/completions")
            val owned=ForegroundOwnedClient(endpoint,ProofSlot.Chat,gate(),{}, { it }); owned.close(); assertTrue(owned.isClosed)
            try { owned.client.newCall(request(endpoint)).execute().close(); fail("closed client admitted") } catch (_: Exception) {}
            assertEquals(0,server.requestCount)
            server.enqueue(MockResponse().setBody("independent"))
            OkHttpClient().newCall(Request.Builder().url(endpoint).build()).execute().use { assertEquals("independent",it.body!!.string()) }
        }
    }
}
