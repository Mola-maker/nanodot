package io.github.nanomuse.apiproof

import android.content.Context
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructStat
import java.io.File
import java.io.FileInputStream

/** Actual Android Os adapter; no instrumentation arguments or credential preferences. */
internal object PrivateProofInput {
    const val INPUT_NAME = ProofInputConsumption.INPUT_NAME
    const val RESULT_NAME = "nanodot-api-proof.result.json"

    fun consume(context: Context, afterRead: () -> Unit = {}): String? =
        ProofInputConsumption.consume(context.cacheDir, Process.myUid(), AndroidFilesystem, afterRead)

    private object AndroidFilesystem : ProofInputConsumption.Filesystem {
        private fun metadata(stat: StructStat) = ProofInputConsumption.Metadata(
            stat.st_dev, stat.st_ino, stat.st_uid, stat.st_mode, stat.st_nlink,
            stat.st_size, OsConstants.S_ISREG(stat.st_mode),
        )
        override fun lstat(path: File): ProofInputConsumption.Metadata? = try {
            metadata(Os.lstat(path.path))
        } catch (e: ErrnoException) {
            if (e.errno == OsConstants.ENOENT) null else throw IllegalStateException("Proof input unavailable")
        }
        override fun openNoFollow(path: File): ProofInputConsumption.OpenedInput {
            val fd = Os.open(path.path, OsConstants.O_RDONLY or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0)
            val stream = try { FileInputStream(fd) } catch (_: Exception) {
                Os.close(fd)
                throw IllegalStateException("Proof input open failed")
            }
            return object : ProofInputConsumption.OpenedInput {
                override fun stat() = metadata(Os.fstat(fd))
                override fun read(bytes: ByteArray, offset: Int, length: Int) = stream.read(bytes, offset, length)
                override fun close() = stream.close()
            }
        }
        override fun remove(path: File) {
            // Only this fixed owned cache entry; remove does not follow its symlink.
            try { Os.remove(path.path) } catch (e: ErrnoException) {
                if (e.errno != OsConstants.ENOENT) throw IllegalStateException("Proof input cleanup failed")
            }
        }
    }
}
