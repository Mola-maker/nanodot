package io.github.nanomuse.dot.gateway

/** Loaded config and every existing account/token count before offering a fresh-install recommendation. */
object GatewayOriginPolicy {
    fun isFresh(configLoaded: Boolean, hasAccount: Boolean, hasProvider: Boolean): Boolean =
        configLoaded && !hasAccount && !hasProvider
}
