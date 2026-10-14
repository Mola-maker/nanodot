package io.github.nanomuse.dot.gateway

import android.content.SharedPreferences
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Only a supplied project key connects; the preferences keep a non-secret ownership UUID. */
class GatewayConnection(
    private val repository: ProviderRepository,
    private val preferences: SharedPreferences,
) {
    companion object {
        const val PRESET_ID = "nanodot-workers"
        const val ORIGIN = "https://molamaker.si"
        const val LABEL = "Nanodot · Workers AI"
        private const val INSTANCE_ID = "instance_id"
        private val connectionLock = Mutex()
    }

    suspend fun connect(credential: String) {
        val key = credential.trim()
        require(key.isNotEmpty() && key.none { it.isWhitespace() })
        repository.awaitConfigLoaded()
        check(repository.configLoaded.value) { "Provider configuration is still loading" }
        connectionLock.withLock {
            val ownedId = preferences.getString(INSTANCE_ID, null)
            val instance = repository.instances.firstOrNull {
                it.id == ownedId && it.providerType == ProviderType.openAI &&
                    it.credentialType == ProviderCredential.apiKey &&
                    it.customBaseURL == ORIGIN && it.appendV1Suffix &&
                    !it.useResponsesAPI && !it.azureMode
            } ?: ProviderInstance(
                id = UUID.randomUUID().toString(), label = LABEL,
                providerType = ProviderType.openAI, credentialType = ProviderCredential.apiKey,
                customBaseURL = ORIGIN, appendV1Suffix = true,
                useResponsesAPI = false, azureMode = false,
            ).also { repository.addInstance(it) }
            repository.bindNanodotGatewayCredential(instance.id)
            repository.saveApiKey(instance.id, key)
            check(preferences.edit().putString(INSTANCE_ID, instance.id).commit())
            // Repository retains existing valid entries if the API rejects this refresh.
            repository.refreshModels(instance)
        }
    }
}
