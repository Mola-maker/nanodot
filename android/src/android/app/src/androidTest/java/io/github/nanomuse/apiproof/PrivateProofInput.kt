package io.github.nanomuse.apiproof

import android.content.Context
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream

/** Only the fixed target-app cache handoff; never accepts instrumentation arguments. */
internal object PrivateProofInput {
    const val INPUT_NAME = "nanodot-api-proof.input"
    const val RESULT_NAME = "nanodot-api-proof.result.json"

    fun consume(context: Context): String? {
        val cache = context.cacheDir
        val input = File(cache, INPUT_NAME)
        check(input.parentFile == cache) { "Invalid proof input location" }
        val initial = try { Os.lstat(input.path) } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT) return null
            throw IllegalStateException("Proof input unavailable")
        }
        try {
            check(OsConstants.S_ISREG(initial.st_mode) && initial.st_mode and 4095 == 384 &&
                initial.st_uid == Process.myUid() && initial.st_nlink == 1L &&
                initial.st_size in 8L..512L) { "Invalid proof input metadata" }
            val fd = Os.open(input.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
            val bytes = FileInputStream(fd).use { stream ->
                val opened = Os.fstat(fd)
                check(opened.st_dev == initial.st_dev && opened.st_ino == initial.st_ino &&
                    opened.st_uid == Process.myUid() && opened.st_mode and 4095 == 384 &&
                    OsConstants.S_ISREG(opened.st_mode) && opened.st_nlink == 1L &&
                    opened.st_size == initial.st_size) { "Proof input changed" }
                val scratch = ByteArray(513)
                try {
                    var size = 0
                    while (size < scratch.size) {
                        val count = stream.read(scratch, size, scratch.size - size)
                        if (count == -1) break
                        size += count
                    }
                    check(size in 8..512 && size.toLong() == initial.st_size) { "Invalid proof input size" }
                    scratch.copyOf(size)
                } finally { scratch.fill(0) }
            }
            try {
                check(bytes.all { byte ->
                    val c = byte.toInt()
                    c in 48..57 || c in 65..90 || c in 97..122 || c == 45 || c == 46 || c == 95
                }) { "Invalid proof input encoding" }
                val credential = bytes.toString(Charsets.US_ASCII)
                // Mandatory BEFORE constructing the provider or any owned HTTP resources.
                Os.remove(input.path)
                val absent = try { Os.lstat(input.path); false } catch (e: ErrnoException) { e.errno == OsConstants.ENOENT }
                check(absent) { "Proof input not removed" }
                return credential
            } finally { bytes.fill(0) }
        } finally {
            // remove unlinks this entry without following a symlink; never deletes outside the fixed cache entry.
            try { Os.remove(input.path) } catch (e: ErrnoException) {
                if (e.errno != OsConstants.ENOENT) throw IllegalStateException("Proof input cleanup failed")
            }
        }
    }
}
