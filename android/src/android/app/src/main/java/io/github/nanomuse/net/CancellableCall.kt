package io.github.nanomuse.net

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

/** Own the socket until parsing finishes; cancellation also interrupts a delayed response body. */
internal suspend fun <T> Call.awaitParsed(parse: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    if (!continuation.isActive) return@suspendCancellableCoroutine
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWith(Result.failure(e))
        }
        override fun onResponse(call: Call, response: Response) {
            if (!continuation.isActive) { response.close(); return }
            val result = runCatching { response.use(parse) }
            // Parsed values own no response resources. A late response is closed above or by use.
            if (continuation.isActive) continuation.resumeWith(result)
        }
    })
}
