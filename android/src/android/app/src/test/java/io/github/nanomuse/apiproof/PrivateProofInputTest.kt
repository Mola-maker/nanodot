package io.github.nanomuse.apiproof

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.channels.Channels
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Actual shared ownership algorithm + Linux/JDK POSIX substitute for Android Os.
 * /proc/self/fd metadata follows the SAME live descriptor, including unlinked inode nlink0.
 * This establishes host POSIX behavior, not Android Os adapter/native permission success.
 */
class PrivateProofInputTest {
    private val publicCredential = "synthetic-proof-key"

    private fun withInput(block: (File, HostPosixFilesystem) -> Unit) {
        val cache = Files.createTempDirectory("nanodot-public-input-").toFile()
        val fs = HostPosixFilesystem()
        try {
            val path = File(cache, ProofInputConsumption.INPUT_NAME).toPath()
            Files.write(path, publicCredential.toByteArray(), StandardOpenOption.CREATE_NEW)
            Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE))
            block(cache, fs)
        } finally {
            // This test owns every alias it creates; the actual core never deletes unknown aliases.
            cache.listFiles()?.forEach { Files.deleteIfExists(it.toPath()) }
            Files.deleteIfExists(cache.toPath())
        }
    }

    @Test fun `unaliased input is unlinked while its original descriptor is still open`() = withInput { cache, fs ->
        assertEquals(publicCredential, ProofInputConsumption.consume(cache, fs.uid, fs))
        assertFalse(File(cache, ProofInputConsumption.INPUT_NAME).exists())
        assertEquals(0L, fs.postRemovalLinks)
        assertEquals(1, fs.closedHandles)
        assertTrue(fs.lastReadBuffer!!.all { it == 0.toByte() })
    }

    @Test fun `hard link added after metadata check refuses handoff and preserves unknown alias`() = withInput { cache, fs ->
        val input = File(cache, ProofInputConsumption.INPUT_NAME).toPath()
        val alias = File(cache, "public-hardlink-alias").toPath()
        val failure = runCatching { ProofInputConsumption.consume(cache, fs.uid, fs) { Files.createLink(alias, input) } }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("Proof input rejected", failure!!.message)
        assertNull(failure.cause)
        assertFalse(Files.exists(input))
        assertEquals(publicCredential, String(Files.readAllBytes(alias)))
        assertEquals(1L, fs.postRemovalLinks)
        assertEquals(1, fs.closedHandles)
        assertTrue(fs.lastReadBuffer!!.all { it == 0.toByte() })
    }

    @Test fun `renamed original plus pathname replacement refuses handoff and preserves original alias`() = withInput { cache, fs ->
        val input = File(cache, ProofInputConsumption.INPUT_NAME).toPath()
        val alias = File(cache, "public-renamed-original").toPath()
        val failure = runCatching { ProofInputConsumption.consume(cache, fs.uid, fs) {
            Files.move(input, alias)
            Files.write(input, "public-replacement".toByteArray(), StandardOpenOption.CREATE_NEW)
        } }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("Proof input rejected", failure!!.message)
        assertFalse(Files.exists(input))
        assertEquals(publicCredential, String(Files.readAllBytes(alias)))
        assertEquals(1L, fs.postRemovalLinks)
        assertEquals(1, fs.closedHandles)
        assertTrue(fs.lastReadBuffer!!.all { it == 0.toByte() })
    }

    @Test fun `missing input returns before opening any descriptor`() {
        val cache = Files.createTempDirectory("nanodot-public-missing-").toFile()
        val fs = HostPosixFilesystem()
        try {
            assertNull(ProofInputConsumption.consume(cache, fs.uid, fs))
            assertEquals(0, fs.openedHandles)
            assertEquals(0, fs.closedHandles)
        } finally { Files.delete(cache.toPath()) }
    }

    private class HostPosixFilesystem : ProofInputConsumption.Filesystem {
        val uid = Files.getAttribute(Path.of("/proc/self"), "unix:uid") as Int
        var openedHandles = 0
        var closedHandles = 0
        var postRemovalLinks: Long? = null
        var lastReadBuffer: ByteArray? = null
        private var removed = false
        private fun metadata(path: Path, noFollow: Boolean = false): ProofInputConsumption.Metadata {
            val options = if (noFollow) arrayOf(LinkOption.NOFOLLOW_LINKS) else emptyArray()
            val stat = Files.readAttributes(path, "unix:*", *options)
            return ProofInputConsumption.Metadata(
                (stat["dev"] as Number).toLong(), (stat["ino"] as Number).toLong(),
                (stat["uid"] as Number).toInt(), (stat["mode"] as Number).toInt(),
                (stat["nlink"] as Number).toLong(), (stat["size"] as Number).toLong(),
                stat["isRegularFile"] as Boolean,
            )
        }
        private fun descriptorNames(): Set<String> = Files.list(Path.of("/proc/self/fd")).use { entries ->
            entries.map { it.fileName.toString() }.toList().toSet()
        }
        override fun lstat(path: File): ProofInputConsumption.Metadata? = try { metadata(path.toPath(), true) }
            catch (_: NoSuchFileException) { null }
        override fun openNoFollow(path: File): ProofInputConsumption.OpenedInput {
            val initial = metadata(path.toPath(), true)
            val channel = Files.newByteChannel(path.toPath(), setOf<OpenOption>(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
            val stream = Channels.newInputStream(channel)
            val fdPath = try {
                descriptorNames().map { Path.of("/proc/self/fd", it) }.single { candidate ->
                    val stat = runCatching { metadata(candidate) }.getOrNull()
                    stat?.device == initial.device && stat.inode == initial.inode
                }
            } catch (e: Exception) { stream.close(); throw e }
            openedHandles++
            return object : ProofInputConsumption.OpenedInput {
                override fun stat(): ProofInputConsumption.Metadata = metadata(fdPath).also { if (removed) postRemovalLinks = it.links }
                override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                    lastReadBuffer = bytes
                    return stream.read(bytes, offset, length)
                }
                override fun close() { stream.close(); closedHandles++ }
            }
        }
        override fun remove(path: File) { Files.deleteIfExists(path.toPath()); removed = true }
    }
}
