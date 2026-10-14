package io.github.nanomuse.apiproof

import java.io.Closeable
import java.io.File

/** Shared test-only ownership algorithm; Android and host adapters supply actual POSIX operations. */
internal object ProofInputConsumption {
    const val INPUT_NAME = "nanodot-api-proof.input"

    data class Metadata(val device: Long, val inode: Long, val uid: Int, val mode: Int,
                        val links: Long, val size: Long, val regular: Boolean)
    interface OpenedInput : Closeable {
        fun stat(): Metadata
        fun read(bytes: ByteArray, offset: Int, length: Int): Int
    }
    interface Filesystem {
        fun lstat(path: File): Metadata?
        fun openNoFollow(path: File): OpenedInput
        fun remove(path: File)
    }

    fun consume(cache: File, uid: Int, fs: Filesystem, afterRead: () -> Unit = {}): String? = try {
        consumeChecked(cache, uid, fs, afterRead)
    } catch (_: Exception) { throw IllegalStateException("Proof input rejected") }

    private fun consumeChecked(cache: File, uid: Int, fs: Filesystem, afterRead: () -> Unit): String? {
        val input = File(cache, INPUT_NAME)
        check(input.parentFile == cache)
        val initial = fs.lstat(input) ?: return null
        try {
            check(initial.regular && initial.mode and 4095 == 384 && initial.uid == uid &&
                initial.links == 1L && initial.size in 8L..512L)
            val scratch = ByteArray(513)
            try {
                // Keep this SAME descriptor live through removal and post-removal fstat.
                return fs.openNoFollow(input).use { opened ->
                    val stat = opened.stat()
                    check(stat.device == initial.device && stat.inode == initial.inode &&
                        stat.regular && stat.mode and 4095 == 384 && stat.uid == uid &&
                        stat.links == 1L && stat.size == initial.size)
                    var size = 0
                    while (size < scratch.size) {
                        val n = opened.read(scratch, size, scratch.size - size)
                        if (n == -1) break
                        check(n > 0)
                        size += n
                    }
                    check(size in 8..512 && size.toLong() == initial.size)
                    for (index in 0 until size) {
                        val c = scratch[index].toInt()
                        check(c in 48..57 || c in 65..90 || c in 97..122 || c == 45 || c == 46 || c == 95)
                    }
                    afterRead() // PUBLIC synthetic race hook; the manual case uses its no-op default.
                    fs.remove(input)
                    check(fs.lstat(input) == null)
                    val unlinked = opened.stat()
                    check(unlinked.device == initial.device && unlinked.inode == initial.inode && unlinked.links == 0L)
                    // A surviving hardlink or renamed original never produces a credential handoff.
                    String(scratch, 0, size, Charsets.US_ASCII)
                }
            } finally { scratch.fill(0) }
        } finally { fs.remove(input) }
    }
}
