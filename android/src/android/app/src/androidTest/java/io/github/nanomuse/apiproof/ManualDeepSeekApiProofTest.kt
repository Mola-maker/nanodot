package io.github.nanomuse.apiproof

import android.system.Os
import android.system.OsConstants
import android.system.ErrnoException
import org.junit.Assert.*
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Explicit root-owned manual case. Absent input skips before provider construction.
 * One streamMessage Flow, one collection, no retry/fallback/session/preferences/DAO.
 * This test is not evidence that a real API request or native execution succeeded.
 */
class ManualDeepSeekApiProofTest {
    @Test fun directProviderSingleReservedAttempt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var status = "invalid-input"
        var httpStatus = 0
        var exchanges = 0
        var deltas = 0
        var finishes = 0
        var finish: String? = null
        var usage: LLMUsage? = null
        var sawDone = false
        var closed = true
        val visible = StringBuilder()
        var inputPresent = false
        try {
            val credential = PrivateProofInput.consume(context)
            if (credential == null) status = "skipped"
            assumeTrue("Manual proof input absent; no provider was constructed", credential != null)
            inputPresent = true
            status = "setup-failed"
            val transport = ApiProofTransport("https://api.deepseek.com/chat/completions".toHttpUrl(), credential!!)
            closed = false
            try {
                val provider = transport.provider(credential, "https://api.deepseek.com")
                status = "failed"
                runBlocking {
                    withTimeout(45_000) {
                        // Deliberately no toList/resubscription/retry operator and exactly one collect.
                        transport.flow(provider).collect { chunk ->
                            when (chunk) {
                                is LLMStreamChunk.Text -> {
                                    check(visible.length + chunk.text.length <= 4096) { "Proof text limit" }
                                    if (chunk.text.isNotEmpty()) deltas++
                                    visible.append(chunk.text)
                                }
                                is LLMStreamChunk.Usage -> { check(usage == null); usage = chunk.usage }
                                is LLMStreamChunk.Finished -> { finishes++; finish = chunk.stopReason }
                                is LLMStreamChunk.Started -> Unit
                                is LLMStreamChunk.ThinkingDelta -> check(chunk.text.isEmpty())
                                is LLMStreamChunk.ReasoningContent -> check(chunk.content.isEmpty())
                                else -> error("Unexpected proof chunk")
                            }
                        }
                    }
                }
                check(transport.httpStatus == 200 && transport.exchangeCount == 1 && transport.sawDone)
                check(visible.isNotBlank() && finishes == 1 && finish in setOf("stop", "length"))
                check(usage != null && usage!!.outputTokens in 1..64 && usage!!.inputTokens >= 0 && usage!!.latestContextTokens >= 0)
                status = "passed"
            } finally {
                httpStatus = transport.httpStatus
                exchanges = transport.exchangeCount
                sawDone = transport.sawDone
                transport.close()
                closed = transport.isClosed
                if (status == "passed" && !closed) status = "cleanup-failed"
            }
        } catch (e: org.junit.AssumptionViolatedException) {
            throw e
        } catch (_: TimeoutCancellationException) {
            status = "timeout"
        } catch (_: Throwable) {
            if (status == "passed") status = "cleanup-failed"
            // No raw exception, stack, request, headers, response body, or credential output.
        } finally {
            if (inputPresent || status == "invalid-input") {
                val safeFinish = finish?.takeIf { it in setOf("stop", "length") }
                val result = JSONObject().apply {
                    put("endpoint", "https://api.deepseek.com/chat/completions")
                    put("model", "deepseek-flash")
                    put("status", JSONObject().put("outcome", status).put("http", httpStatus).put("done", sawDone).put("ownedclosed", closed))
                    put("configuredcap", JSONObject().put("max_tokens", 64).put("max_completion_tokens", 64).put("thinking", "disabled"))
                    put("exchangecount", exchanges)
                    put("deltacount", deltas)
                    put("finish", safeFinish ?: JSONObject.NULL)
                    put("usage", usage?.let { u -> JSONObject().put("freshinput", u.inputTokens).put("cachedinput", u.cacheReadInputTokens ?: 0).put("prompt", u.latestContextTokens).put("output", u.outputTokens).put("total", u.latestContextTokens.toLong() + u.outputTokens) } ?: JSONObject.NULL)
                    // Avoid saving arbitrary vendor response text. Only the requested public word is allowlisted.
                    put("text", if (visible.toString().trim() == "READY") "READY" else "[omitted]")
                }
                writeResult(context.cacheDir, result)
            }
        }
        if (status != "passed") throw AssertionError("Manual provider proof failed; inspect sanitized cache result")
    }

    private fun writeResult(cache: File, result: JSONObject) {
        val temporary = File.createTempFile("nanodot-api-proof-result-", ".tmp", cache)
        try {
            Os.chmod(temporary.path, 384)
            FileOutputStream(temporary).use { stream ->
                stream.write(result.toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            // Same-directory rename atomically replaces a stale result, without following its symlink.
            Os.rename(temporary.path, File(cache, PrivateProofInput.RESULT_NAME).path)
        } finally { temporary.delete() }
    }

    /** Public-only filesystem controls; root selects these BEFORE any real credential handoff. */
    private fun withPublicInput(block: (android.content.Context, File, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val input = File(context.cacheDir, PrivateProofInput.INPUT_NAME)
        val alias = File(context.cacheDir, "nanodot-api-proof.public-alias")
        fun absent(file: File): Boolean = try { Os.lstat(file.path); false } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT) true else throw IllegalStateException("Public fixture metadata failure")
        }
        // Never read/remove an existing real handoff or another run's artifact.
        assumeTrue("Public fixture requires absent owned input and alias", absent(input) && absent(alias))
        var created = false
        try {
            val fd = Os.open(input.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384)
            created = true
            FileOutputStream(fd).use { it.write("synthetic-proof-key".toByteArray(Charsets.US_ASCII)) }
            Os.chmod(input.path, 384)
            block(context, input, alias)
        } finally {
            // Only this fixture's two known public paths; the consumer never deletes unknown aliases.
            if (created) for (file in listOf(input, alias)) {
                try { Os.remove(file.path) } catch (e: ErrnoException) {
                    if (e.errno != OsConstants.ENOENT) throw IllegalStateException("Public fixture cleanup failure")
                }
            }
        }
    }

    @Test fun publicSyntheticUnaliasedInputIsRemoved() = withPublicInput { context, input, _ ->
        assertEquals("synthetic-proof-key", PrivateProofInput.consume(context))
        assertFalse(input.exists())
    }

    @Test fun publicSyntheticHardlinkRaceIsRejected() = withPublicInput { context, input, alias ->
        var linkCompleted = false
        var linkErrno: Int? = null
        val failure = runCatching { PrivateProofInput.consume(context) {
            try { Os.link(input.path, alias.path); linkCompleted = true }
            catch (error: ErrnoException) { linkErrno = error.errno; throw error }
        } }.exceptionOrNull()
        // A failed callback is not evidence that the post-unlink hardlink guard ran.
        assertTrue("Public Os.link did not complete (errno=$linkErrno); run capability diagnostic", linkCompleted)
        assertNotNull(failure)
        assertEquals("Proof input rejected", failure!!.message)
        assertNull(failure.cause)
        assertFalse(input.exists())
        assertEquals("synthetic-proof-key", alias.readText(Charsets.US_ASCII))
    }

    @Test fun publicSyntheticRenameReplacementRaceIsRejected() = withPublicInput { context, input, alias ->
        val failure = runCatching { PrivateProofInput.consume(context) {
            Os.rename(input.path, alias.path)
            val fd = Os.open(input.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384)
            FileOutputStream(fd).use { it.write("public-replacement".toByteArray(Charsets.US_ASCII)) }
        } }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("Proof input rejected", failure!!.message)
        assertFalse(input.exists())
        assertEquals("synthetic-proof-key", alias.readText(Charsets.US_ASCII))
    }

    /** Characterizes the actual native provider/reflection/JSON paths without a Flow or HTTP call. */
    @Test fun publicSyntheticRuntimeProviderAndStandardJsonPreflight() {
        val key = "synthetic-proof-key"
        val transport = ApiProofTransport("https://public.invalid/chat/completions".toHttpUrl(), key)
        try {
            val provider = transport.provider(key, "https://public.invalid")
            val ownedClient = ApiProofTransport::class.java.getDeclaredField("client").apply { isAccessible = true }.get(transport)
            val providerClient = provider.javaClass.getDeclaredField("client").apply { isAccessible = true }.get(provider)
            assertSame(ownedClient, providerClient) // provider() also executes its type and exact identity checks.
            publicRuntimeJsonPrecheck(transport,
                """data: {"choices":[],"public":{"url":"https://public.invalid/a/b","path":"a\/b"}}""")
            assertTrue(transport.tokenizerReached) // Returning successfully also proves native JSONObject/traversal ran.
            assertFalse(transport.sawDone)
            assertEquals(0, transport.httpStatus)
            assertEquals(0, transport.exchangeCount)
        } finally {
            transport.close()
            assertTrue(transport.isClosed)
            assertEquals(0, transport.exchangeCount)
        }
    }

    @Test fun publicSyntheticRuntimeDecodedEchoAndCommentPreflight() {
        val publicPayloads = listOf(
            """{"choices":[],"public":{"echo":"synthetic\u002dproof-key"}}""" to true,
            """{"choices":[],"public":{"synthetic\u002dproof-key":"public"}}""" to true,
            ("{\"ignored\":/*\"*/" + "[".repeat(33) + "0" + "]".repeat(33) + "/*\"*/}") to false,
        )
        for ((payload, reachesTokenizer) in publicPayloads) {
            assertFalse(payload.contains("synthetic-proof-key"))
            // Fresh owned instance per payload: the observation is monotonic and must start false.
            val transport = ApiProofTransport("https://public.invalid/chat/completions".toHttpUrl(), "synthetic-proof-key")
            try {
                assertFalse(transport.tokenizerReached)
                val failure = runCatching { publicRuntimeJsonPrecheck(transport, "data: $payload") }.exceptionOrNull()
                assertTrue(failure is IllegalStateException)
                assertNull(failure!!.cause)
                assertFalse(failure.message.orEmpty().contains("synthetic-proof-key"))
                assertEquals(reachesTokenizer, transport.tokenizerReached)
                assertFalse(transport.sawDone)
                assertEquals(0, transport.httpStatus)
                assertEquals(0, transport.exchangeCount)
            } finally {
                transport.close()
                assertTrue(transport.isClosed)
                assertEquals(0, transport.exchangeCount)
            }
        }
    }

    private fun publicRuntimeJsonPrecheck(transport: ApiProofTransport, line: String) {
        // Invoke the SAME test-only implementation; no copied scanner or native parser substitute.
        val method = ApiProofTransport::class.java.getDeclaredMethod("validateSseData", String::class.java)
        method.isAccessible = true
        try { method.invoke(transport, line) }
        catch (failure: java.lang.reflect.InvocationTargetException) { throw failure.targetException }
    }

    /** Diagnostic only: an explicit capability refusal is recorded, never called hardlink-guard success. */
    @Test fun publicSyntheticHardlinkCapabilityDiagnostic() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val cache = instrumentation.targetContext.cacheDir
        val source = File(cache, "nanodot-api-proof.public-link-source")
        val alias = File(cache, "nanodot-api-proof.public-link-alias")
        fun absent(file: File): Boolean = try { Os.lstat(file.path); false } catch (error: ErrnoException) {
            if (error.errno == OsConstants.ENOENT) true else throw IllegalStateException("Public diagnostic metadata failed")
        }
        check(absent(source) && absent(alias)) { "Public diagnostic requires absent paths" }
        val observed = JSONObject().put("errno", 0).put("linkCompleted", false)
        var sourceCreated = false
        var aliasCreated = false
        try {
            val fd = Os.open(source.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or
                OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384)
            sourceCreated = true
            FileOutputStream(fd).use { stream ->
                stream.write("public-link-diagnostic".toByteArray(Charsets.US_ASCII))
                val original = Os.fstat(fd)
                val pathStat = Os.lstat(source.path)
                val identity = original.st_dev == pathStat.st_dev && original.st_ino == pathStat.st_ino
                val single = original.st_nlink == 1L && pathStat.st_nlink == 1L
                observed.put("fdPathIdentity", identity).put("initialSingleLink", single)
                check(identity && single) { "Public diagnostic initial metadata differs" }
                var errno = 0
                try { Os.link(source.path, alias.path); aliasCreated = true }
                catch (error: ErrnoException) { errno = error.errno }
                observed.put("errno", errno).put("linkCompleted", aliasCreated)
                if (aliasCreated) {
                    val aliasStat = Os.lstat(alias.path)
                    val same = aliasStat.st_dev == original.st_dev && aliasStat.st_ino == original.st_ino
                    val two = aliasStat.st_nlink == 2L && Os.fstat(fd).st_nlink == 2L
                    observed.put("aliasSameInode", same).put("linkedCountTwo", two)
                    check(same && two) { "Public diagnostic linked metadata differs" }
                } else {
                    val absentAlias = absent(alias)
                    val stillSingle = Os.fstat(fd).st_nlink == 1L
                    observed.put("aliasAbsentAfterRefusal", absentAlias).put("singleAfterRefusal", stillSingle)
                    check(errno != 0 && absentAlias && stillSingle) { "Public diagnostic refusal is ambiguous" }
                }
                Os.remove(source.path)
                sourceCreated = false
                val remaining = Os.fstat(fd).st_nlink == if (aliasCreated) 1L else 0L
                observed.put("originalPathRemoved", absent(source)).put("fdLinksAfterOriginalRemoval", remaining)
                check(absent(source) && remaining) { "Public diagnostic removal metadata differs" }
                if (aliasCreated) { Os.remove(alias.path); aliasCreated = false }
                val zero = Os.fstat(fd).st_nlink == 0L
                observed.put("fdZeroLinksAfterCleanup", zero)
                check(zero) { "Public diagnostic cleanup left a link" }
            }
        } catch (_: Exception) {
            throw IllegalStateException("Public link diagnostic failed")
        } finally {
            // Only paths exclusively created by this public diagnostic; no fixed credential input is touched.
            try {
                if (aliasCreated) Os.remove(alias.path)
                if (sourceCreated) Os.remove(source.path)
            } catch (error: ErrnoException) {
                observed.put("cleanupErrno", error.errno)
                throw IllegalStateException("Public link diagnostic cleanup failed")
            } finally {
                instrumentation.sendStatus(2, android.os.Bundle().apply {
                    putString("public-link-diagnostic", observed.toString()) // PUBLIC errno/booleans only.
                })
            }
        }
    }
}
