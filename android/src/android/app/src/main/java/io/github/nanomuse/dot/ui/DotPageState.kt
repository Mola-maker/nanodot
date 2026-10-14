package io.github.nanomuse.dot.ui

/** Presentation of the owning page's real operation; this class never starts work. */
sealed interface DotPageState {
    data object Loading : DotPageState
    data object Ready : DotPageState
    data class Empty(val message: String) : DotPageState
    data class Error(val message: String, val retryable: Boolean) : DotPageState
}
