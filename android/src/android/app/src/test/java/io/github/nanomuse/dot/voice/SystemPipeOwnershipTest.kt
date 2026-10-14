package io.github.nanomuse.dot.voice

import android.content.ContextWrapper
import android.os.ParcelFileDescriptor
import com.openminis.app.speech.SystemSpeechRecognitionEngine
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test
import com.openminis.app.speech.*
import java.util.Locale
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*

/** Controlled endpoints on the actual System raw-sink/retirement boundary; no native pipe claims. */
@OptIn(ExperimentalCoroutinesApi::class)
class SystemPipeOwnershipTest {
    private class Descriptor : ParcelFileDescriptor(null) {
        var closes = 0
        override fun close() { closes++ }
    }
    private class Pipe(val writeFailure: (() -> Unit)? = null, val closeFailure: Boolean = false, val onClose: () -> Unit = {}) {
        val read = Descriptor(); val write = Descriptor(); var streamCloses = 0
        val stream = object : OutputStream() {
            override fun write(b: Int) { writeFailure?.invoke() }
            override fun write(b: ByteArray, off: Int, len: Int) { writeFailure?.invoke() }
            override fun close() { streamCloses++; onClose(); if (closeFailure) error("close failed") }
        }
    }
    @Suppress("UNCHECKED_CAST")
    private fun install(engine: SystemSpeechRecognitionEngine, pipe: Pipe) {
        val pipes = engine.javaClass.getDeclaredField("audioPipes").also { it.isAccessible = true }
            .get(engine) as CapturePipes<ParcelFileDescriptor>
        pipes.install(CapturePipe(pipe.read, pipe.write, pipe.stream))
    }
    private fun write(engine: SystemSpeechRecognitionEngine) {
        engine.javaClass.getDeclaredMethod("writeAudioPipe", ByteArray::class.java, Int::class.javaPrimitiveType)
            .also { it.isAccessible = true }.invoke(engine, byteArrayOf(1), 1)
    }
    private fun close(engine: SystemSpeechRecognitionEngine) {
        engine.javaClass.getDeclaredMethod("closeAudioPipe").also { it.isAccessible = true }.invoke(engine)
    }
    @Test fun failedSinkStillClosesEveryOwnedEndpoint() {
        val engine = SystemSpeechRecognitionEngine(ContextWrapper(null)); val pipe = Pipe({ error("reader gone") })
        install(engine, pipe); write(engine); close(engine)
        assertEquals("failed sink's stream must remain owned", 1, pipe.streamCloses)
        assertEquals(1, pipe.write.closes); assertEquals(1, pipe.read.closes)
    }
    @Test fun streamCloseFailureStillAttemptsWriteAndReadDescriptors() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val engine = SystemSpeechRecognitionEngine(ContextWrapper(null)); val pipe = Pipe(closeFailure = true)
            install(engine, pipe); close(engine)
            assertEquals(1, pipe.streamCloses)
            assertEquals("write descriptor must have an independent close attempt", 1, pipe.write.closes)
            assertEquals(1, pipe.read.closes)
            val failure = runCatching { engine.cancelAndJoin() }.exceptionOrNull()
            assertEquals("close failed", failure?.message)
        } finally { Dispatchers.resetMain() }
    }
    @Test fun staleFailedWriterCannotRetireReplacementPipe() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val a = Pipe({ entered.countDown(); check(release.await(5, TimeUnit.SECONDS)); error("A reader gone") })
        val b = Pipe(); val engine = SystemSpeechRecognitionEngine(ContextWrapper(null)); install(engine, a)
        var workerFailure: Throwable? = null
        val worker = thread { try { write(engine) } catch (t: Throwable) { workerFailure = t } }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS)); close(engine); install(engine, b)
            release.countDown(); worker.join(5000); assertFalse(worker.isAlive); assertNull(workerFailure)
            close(engine)
            assertEquals("A completion must not lose B's stream", 1, b.streamCloses)
            assertEquals(1, a.write.closes); assertEquals(1, a.read.closes)
            assertEquals(1, b.write.closes); assertEquals(1, b.read.closes)
        } finally { release.countDown(); worker.join(5000) }
    }
    @Test fun retiredPipeCompletionKeepsLeaseUntilAllEndpointsClose() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val pipe = Pipe({ error("reader gone") }, onClose = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) })
        val engine = SystemSpeechRecognitionEngine(ContextWrapper(null)); install(engine, pipe)
        var resumed = 0
        val core = OwnedCapture(this, VoiceLease(), object : CaptureOutput {
            override suspend fun pause() {}
            override fun resume() { resumed++ }
        })
        val port = object : CaptureEngine {
            override fun start(locale: Locale, continuous: Boolean?, listener: SpeechRecognitionEngine.Listener) {}
            override fun stop() = engine.stop()
            override suspend fun cancelAndJoin() = engine.cancelAndJoin()
        }
        val listener = object : SpeechRecognitionEngine.Listener {
            override fun onPartial(text: String) {}
            override fun onFinal(text: String) {}
            override fun onError(error: RecognitionError, message: String?) {}
        }
        val ticket = core.start("main", port, Locale.US, null, listener)!!; runCurrent()
        var workerFailure: Throwable? = null
        val worker = thread { try { write(engine) } catch (t: Throwable) { workerFailure = t } }
        try {
            assertTrue("failed sink must begin owned retirement", entered.await(2, TimeUnit.SECONDS))
            core.cancel(ticket); runCurrent()
            assertEquals(0, resumed)
            assertNull(core.start("dot", port, Locale.US, false, listener))
            assertEquals(0, pipe.write.closes); assertEquals(0, pipe.read.closes)
            release.countDown(); worker.join(5000); assertFalse(worker.isAlive); assertNull(workerFailure)
            runCurrent()
            assertEquals(1, pipe.write.closes); assertEquals(1, pipe.read.closes); assertEquals(1, resumed)
            val next = core.start("dot", port, Locale.US, false, listener)!!
            core.cancel(next); runCurrent()
        } finally {
            release.countDown(); worker.join(5000); core.cancel(ticket); runCurrent(); Dispatchers.resetMain()
        }
    }

}
