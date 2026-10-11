package io.github.nanomuse.dot.gateway

/** Loaded config and every existing account/token count before offering a fresh-install recommendation. */
object GatewayOriginPolicy {
    fun isFresh(configLoaded: Boolean, hasAccount: Boolean, hasProvider: Boolean): Boolean =
        configLoaded && !hasAccount && !hasProvider
}


/** Ownership is a stored UUID binding; an editor cannot move that credential off-origin. */
internal fun isCanonicalGateway(instance: com.openminis.app.data.model.ProviderInstance): Boolean =
    instance.providerType == com.openminis.app.data.model.ProviderType.openAI &&
        instance.credentialType == com.openminis.app.data.model.ProviderCredential.apiKey &&
        instance.customBaseURL == GatewayConnection.ORIGIN && instance.appendV1Suffix &&
        !instance.useResponsesAPI && !instance.azureMode
