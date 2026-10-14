package com.openminis.app.sandbox

import android.content.Context
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * On-device tests for ExecutionCoordinator.
 * Tests session mount switching, command serialization, and lifecycle.
 *
 * nanoMuse: run this class only on a provisioned isolated arm64 target, passing
 * -Pandroid.testInstrumentationRunnerArguments.executionCoordinatorSandbox=true.
 * Missing prerequisites produce JUnit skips, not native acceptance.
 */
@RunWith(AndroidJUnit4::class)
class ExecutionCoordinatorInstrumentedTest {

    // nanoMuse: each test owns only UUID-named sessions/files; ordinary shared data survives.
    private lateinit var context: Context
    private val owner = "coordinator-test-${UUID.randomUUID()}"
    private val sessions = linkedMapOf<String, String>()
    private val ownedSharedFiles = mutableListOf<File>()
    private var originalMounts: Map<String, String> = emptyMap()
    private var originalEnvironment: Map<String, String> = emptyMap()

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        originalMounts = PRootKernel.bindMounts.toMap()
        originalEnvironment = PRootKernel.customEnvironment.toMap()
        ExecutionCoordinator.init(context)
    }

    @After
    fun tearDown() {
        // nanoMuse: stop every owned shell before deleting its files; restore even on failure.
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (error: Throwable) {
                failure?.addSuppressed(error) ?: run { failure = error }
            }
        }
        val stopped = mutableSetOf<String>()
        sessions.values.forEach { id -> attempt {
            ExecutionCoordinator.sessionDidTerminate(id)
            stopped.add(id)
        } }
        if (stopped.size == sessions.size) ownedSharedFiles.forEach { file -> attempt {
            assertTrue("Unable to remove owned file $file", !file.exists() || file.delete())
        } }
        stopped.forEach { id -> attempt {
            val directory = File(context.filesDir, "minis-sessions/$id")
            assertTrue("Unable to remove owned session $id", !directory.exists() || directory.deleteRecursively())
        } }
        attempt {
            PRootKernel.clearBindMounts()
            originalMounts.forEach { (linux, host) -> PRootKernel.addBindMount(linux, host) }
        }
        attempt {
            PRootKernel.customEnvironment.clear()
            PRootKernel.customEnvironment.putAll(originalEnvironment)
        }
        failure?.let { throw it }
    }

    // ==================== Session mounting ====================

    @Test
    fun executeRegistersSessionShell() = runBlocking {
        requireNativeSandbox()

        val sessionId = session("mount-test")
        val result = ExecutionCoordinator.execute(sessionId, "echo hello")

        // nanoMuse: the coordinator keeps one shell per session; there is no single mounted id.
        assertEquals(0, result.exitCode)
        assertEquals("hello", result.output.trim())
        assertTrue(ExecutionCoordinator.hasShell(sessionId))
    }

    @Test
    fun executeKeepsSessionShellsSideBySide() = runBlocking {
        requireNativeSandbox()

        // nanoMuse: sessions no longer swap one mount; each keeps its own shell side by side.
        val resultA = ExecutionCoordinator.execute(session("A"), "printf '%s' \"\$MINIS_CHAT_SESSION_ID\"")
        assertEquals(0, resultA.exitCode)
        assertEquals(session("A"), resultA.output.trim())
        assertTrue(ExecutionCoordinator.hasShell(session("A")))

        val resultB = ExecutionCoordinator.execute(session("B"), "printf '%s' \"\$MINIS_CHAT_SESSION_ID\"")
        assertEquals(0, resultB.exitCode)
        assertEquals(session("B"), resultB.output.trim())
        assertTrue(ExecutionCoordinator.hasShell(session("B")))
        assertTrue(ExecutionCoordinator.hasShell(session("A")))
    }

    @Test
    fun executeSameSessionDoesNotRemount() = runBlocking {
        requireNativeSandbox()
        val id = session("reuse")
        // nanoMuse: shell-local state catches recreation even when a registry entry exists.
        val first = ExecutionCoordinator.execute(id, "export NM_COORDINATOR_MARKER='$owner'; printf '%s' \"\$\$\"")
        assertEquals(0, first.exitCode)
        val second = ExecutionCoordinator.execute(id, "printf '%s:%s' \"\$NM_COORDINATOR_MARKER\" \"\$\$\"")
        assertEquals(0, second.exitCode)
        assertEquals("$owner:${first.output.trim()}", second.output.trim())
        assertTrue(ExecutionCoordinator.hasShell(id))
    }

    @Test
    fun executeCreatesSessionBindMounts() = runBlocking {
        requireNativeSandbox()
        val id = session("mounts")
        assertEquals(0, ExecutionCoordinator.execute(id, "echo ready").exitCode)
        // nanoMuse: all eight required mounts are checked inside the real shell, regardless
        // of extra user mounts or the global table's last session snapshot.
        val sessionBase = File(context.filesDir, "minis-sessions/$id")
        for (subdir in listOf("attachments", "offloads", "workspace", "browser")) {
            val file = File(sessionBase, "$subdir/$owner.txt")
            assertTrue("Owned marker already exists", file.createNewFile())
            file.writeText("$owner-$subdir")
            assertCommandOutput(id, "cat /var/minis/$subdir/${file.name}", "$owner-$subdir")
        }
        for (subdir in listOf("memory", "skills", "shared", "mcp-servers")) {
            val file = ownedSharedFile(subdir)
            file.writeText("$owner-$subdir")
            assertCommandOutput(id, "cat /var/minis/$subdir/${file.name}", "$owner-$subdir")
        }
    }

    @Test
    fun executeCreatesHostDirectories() = runBlocking {
        requireNativeSandbox()

        val sessionId = session("dirs-test")
        assertEquals(0, ExecutionCoordinator.execute(sessionId, "echo test").exitCode)

        // Verify session host directories exist
        val sessionBase = File(context.filesDir, "minis-sessions/$sessionId")
        for (subdir in listOf("attachments", "offloads", "workspace", "browser")) {
            assertTrue("$subdir should exist", File(sessionBase, subdir).isDirectory)
        }

        // Verify global host directories exist
        val globalBase = File(context.filesDir, "minis-global")
        for (subdir in listOf("memory", "skills", "shared", "mcp-servers")) {
            assertTrue("$subdir should exist", File(globalBase, subdir).isDirectory)
        }
    }

    @Test
    fun sessionBindMountsPointToCorrectHostDirs() = runBlocking {
        requireNativeSandbox()
        val id = session("verify-paths")
        val filename = "$owner.txt"
        // nanoMuse: writes through the shell must reach the independently known host paths.
        assertEquals(0, ExecutionCoordinator.execute(id,
            "printf '%s' '$owner' > /var/minis/workspace/$filename").exitCode)
        assertEquals(owner, File(context.filesDir, "minis-sessions/$id/workspace/$filename").readText())
        val sharedFile = ownedSharedFile("memory")
        assertEquals(0, ExecutionCoordinator.execute(id,
            "printf '%s' '$owner' > /var/minis/memory/${sharedFile.name}").exitCode)
        assertEquals(owner, sharedFile.readText())
    }

    // ==================== Command execution ====================

    @Test
    fun executeReturnsCorrectOutput() = runBlocking {
        requireNativeSandbox()

        val result = ExecutionCoordinator.execute(session("output"), "echo hello world")
        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("hello world"))
    }

    @Test
    fun executeAppendsExitCodeOnFailure() = runBlocking {
        requireNativeSandbox()

        val result = ExecutionCoordinator.execute(session("fail"), "exit 1")
        assertEquals(1, result.exitCode)
        assertTrue("Should append exit code", result.output.contains("(exit code: 1)"))
    }

    @Test
    fun executeDoesNotAppendExitCodeOnSuccess() = runBlocking {
        requireNativeSandbox()

        val result = ExecutionCoordinator.execute(session("ok"), "echo ok")
        assertEquals(0, result.exitCode)
        assertFalse("Should not contain exit code marker", result.output.contains("(exit code:"))
    }

    @Test
    fun executeDoesNotAppendExitCodeOnTimeout() = runBlocking {
        requireNativeSandbox()

        val result = ExecutionCoordinator.execute(session("timeout"), "sleep 60", timeout = 2_000)
        assertEquals(124, result.exitCode)
        assertFalse("Should not double-append exit code", result.output.contains("(exit code: 124)"))
    }

    @Test
    fun executeWithLineCallback() = runBlocking {
        requireNativeSandbox()

        val lines = mutableListOf<String>()
        ExecutionCoordinator.execute(
            session("callback"),
            "echo one; echo two",
            lineCallback = { lines.add(it) }
        )

        assertTrue(lines.size >= 2)
        assertTrue(lines.contains("one"))
        assertTrue(lines.contains("two"))
    }

    // ==================== Serialization ====================

    @Test
    fun executeSerializesConcurrentCalls() = runBlocking {
        requireNativeSandbox()

        // Launch two commands concurrently — they should serialize
        val startTime = System.currentTimeMillis()

        val id = session("serial")
        val deferred1 = async {
            ExecutionCoordinator.execute(id, "sleep 1; export NM_COORDINATOR_SERIAL='$owner'; echo first")
        }
        val deferred2 = async {
            // Small delay to ensure ordering
            delay(50)
            ExecutionCoordinator.execute(id, "printf '%s' \"\$NM_COORDINATOR_SERIAL\"")
        }

        val result1 = deferred1.await()
        val result2 = deferred2.await()

        // Both should succeed
        assertEquals(0, result1.exitCode)
        assertEquals(0, result2.exitCode)
        // nanoMuse: real state is published only after the first command completes.
        assertEquals("first", result1.output.trim())
        assertEquals(owner, result2.output.trim())

        // Total time should be > 1s (serialized, not parallel)
        val elapsed = System.currentTimeMillis() - startTime
        assertTrue("Commands should be serialized (elapsed: ${elapsed}ms)", elapsed >= 900)
    }

    // ==================== sessionDidTerminate ====================

    @Test
    fun sessionDidTerminateDropsOnlyMatchingShell() = runBlocking {
        requireNativeSandbox()
        val terminated = session("terminate")
        val kept = session("keep")
        assertEquals(0, ExecutionCoordinator.execute(terminated, "echo test").exitCode)
        assertEquals(0, ExecutionCoordinator.execute(kept, "export NM_COORDINATOR_MARKER='$owner'").exitCode)
        assertTrue(ExecutionCoordinator.hasShell(terminated))
        assertTrue(ExecutionCoordinator.hasShell(kept))

        // nanoMuse: terminating A drops A's registry entry and keeps B's actual shell state.
        ExecutionCoordinator.sessionDidTerminate(terminated)
        assertFalse(ExecutionCoordinator.hasShell(terminated))
        assertTrue(ExecutionCoordinator.hasShell(kept))
        assertCommandOutput(kept, "printf '%s' \"\$NM_COORDINATOR_MARKER\"", owner)
    }

    @Test
    fun sessionDidTerminateIgnoresMismatchedSession() = runBlocking {
        requireNativeSandbox()

        assertEquals(0, ExecutionCoordinator.execute(session("keep"), "echo test").exitCode)
        val mountsBefore = PRootKernel.bindMounts.size

        ExecutionCoordinator.sessionDidTerminate(session("other"))

        // nanoMuse: terminating another session leaves this session's shell in place.
        assertTrue(ExecutionCoordinator.hasShell(session("keep")))
        assertFalse(ExecutionCoordinator.hasShell(session("other")))
        assertEquals(mountsBefore, PRootKernel.bindMounts.size)
        assertCommandOutput(session("keep"), "printf '%s' \"\$MINIS_CHAT_SESSION_ID\"", session("keep"))
    }

    @Test
    fun sessionDidTerminateIsNoOpWhenNoSession() {
        ExecutionCoordinator.sessionDidTerminate(session("absent"))
        // nanoMuse: nothing was registered for this id, so nothing is registered afterwards.
        assertFalse(ExecutionCoordinator.hasShell(session("absent")))
    }

    // ==================== stopCurrentCommand ====================

    @Test
    fun stopCurrentCommandIsNoOpWhenIdle() {
        // nanoMuse: never stop unrelated live sessions through the global null-id path.
        ExecutionCoordinator.stopCurrentCommand(session("idle")) // Should not throw
    }

    // ==================== Cross-session file isolation ====================

    @Test
    fun differentSessionsHaveIsolatedWorkspaces() = runBlocking {
        requireNativeSandbox()

        // Write file in session A's workspace
        ExecutionCoordinator.execute(
            session("iso-A"),
            "echo 'from A' > /var/minis/workspace/test.txt"
        )

        // Check it exists in session A
        val resultA = ExecutionCoordinator.execute(
            session("iso-A"),
            "cat /var/minis/workspace/test.txt"
        )
        assertTrue(resultA.output.contains("from A"))

        // Switch to session B — workspace should be empty
        val resultB = ExecutionCoordinator.execute(
            session("iso-B"),
            "ls /var/minis/workspace/"
        )
        assertFalse("Session B should not see session A's file", resultB.output.contains("test.txt"))

        // Switch back to A — file should still be there
        val resultA2 = ExecutionCoordinator.execute(
            session("iso-A"),
            "cat /var/minis/workspace/test.txt"
        )
        assertTrue(resultA2.output.contains("from A"))
    }

    @Test
    fun globalDirsAreSharedAcrossSessions() = runBlocking {
        requireNativeSandbox()
        val file = ownedSharedFile("memory")
        // nanoMuse: an exclusively created UUID marker cannot overwrite ordinary shared data.
        assertEquals(0, ExecutionCoordinator.execute(session("global-A"),
            "printf '%s' '$owner' > /var/minis/memory/${file.name}").exitCode)
        assertCommandOutput(session("global-B"), "cat /var/minis/memory/${file.name}", owner)
    }

    // ==================== Helpers ====================

    // nanoMuse: require a provisioned, explicitly isolated native sandbox. Skips are real
    // JUnit assumptions; boot/runtime failures after these prerequisites remain failures.
    private suspend fun requireNativeSandbox() {
        assumeTrue("Run only on an isolated sandbox target with executionCoordinatorSandbox=true",
            InstrumentationRegistry.getArguments().getString("executionCoordinatorSandbox") == "true")
        assumeTrue("The bundled rootfs requires native arm64-v8a, not x86 translation",
            Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a")
        val nativeProot = File(context.applicationInfo.nativeLibraryDir, "libproot.so")
        assumeTrue("Executable libproot.so is required", nativeProot.isFile && nativeProot.canExecute())
        val elfHeader = nativeProot.inputStream().use { input ->
            ByteArray(20).also { header ->
                var read = 0
                while (read < header.size) {
                    val count = input.read(header, read, header.size - read)
                    if (count < 0) break
                    read += count
                }
            }
        }
        assumeTrue("libproot.so must be an ELF64 little-endian AArch64 binary",
            elfHeader[0] == 0x7f.toByte() && elfHeader[1] == 'E'.code.toByte() &&
                elfHeader[2] == 'L'.code.toByte() && elfHeader[3] == 'F'.code.toByte() &&
                elfHeader[4] == 2.toByte() && elfHeader[5] == 1.toByte() &&
                elfHeader[18] == 183.toByte() && elfHeader[19] == 0.toByte())
        val rootfs = File(context.filesDir, "alpine-rootfs")
        val arch = File(rootfs, ".arch")
        assumeTrue("Provisioned aarch64 rootfs is required; tests do not install/reset it",
            arch.isFile && arch.readText().trim() == "aarch64" && Files.exists(File(rootfs, "bin/sh").toPath(), LinkOption.NOFOLLOW_LINKS))
        if (!PRootKernel.isBooted) PRootKernel.boot(context)
        assertTrue("Native boot must succeed", PRootKernel.isBooted)
    }

    // nanoMuse: labels resolve to one owner-only id, preserving A/B/A reuse within a test.
    private fun session(label: String): String = sessions.getOrPut(label) {
        val id = "$owner-$label"
        check(!File(context.filesDir, "minis-sessions/$id").exists()) { "Session collision: $id" }
        id
    }

    private fun ownedSharedFile(subdir: String): File {
        val directory = File(context.filesDir, "minis-global/$subdir")
        check(directory.isDirectory) { "Provision shared directory before running tests: $subdir" }
        val file = File(directory, "$owner-${UUID.randomUUID()}.txt")
        check(file.createNewFile()) { "Shared marker collision: $file" }
        ownedSharedFiles.add(file)
        return file
    }

    private suspend fun assertCommandOutput(id: String, command: String, expected: String) {
        val result = ExecutionCoordinator.execute(id, command)
        assertEquals(0, result.exitCode)
        assertEquals(expected, result.output.trim())
    }
}
