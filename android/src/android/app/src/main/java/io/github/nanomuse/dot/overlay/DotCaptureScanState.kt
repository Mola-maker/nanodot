package io.github.nanomuse.dot.overlay

/** Owns one scan until its outcome; callers supply monotonic capture request tickets. */
internal class CaptureScanTicket {
    var activeGeneration: Long? = null
        private set
    var success: Boolean? = null
        private set
    private var newestGeneration: Long? = null

    fun begin(generation: Long): Long? {
        if (activeGeneration != null || newestGeneration?.let { generation <= it } == true) return null
        newestGeneration = generation
        activeGeneration = generation
        success = null
        return generation
    }

    fun finish(generation: Long, success: Boolean): Boolean {
        if (activeGeneration != generation) return false
        activeGeneration = null
        this.success = success
        return true
    }

    fun cancel() {
        activeGeneration = null
        success = null
    }
}
