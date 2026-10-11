package io.github.nanomuse.dot.care

import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.provider.ProviderFactory
import io.github.nanomuse.dot.screen.ScreenSnapshot

/** Resolve existing configuration only when an explicit goal/reobserve requests a plan.
 * No agent loop, config mutation, automatic retries or cross-provider fallback.
 */
class ConfiguredCarePlanner(private val app: MinisApp) {
    suspend fun next(goal: String, snapshot: ScreenSnapshot, history: List<CareProposal>): CarePlanResult {
        val repo = app.providerRepositoryOrNull ?: return missing()
        val selected = repo.lastUsedVisibleEntry()?.takeIf { it.model.isTextOutput }
        val entry = selected ?: repo.group(repo.defaultPrimaryGroupId ?: "")?.let { group ->
            repo.availableMemberEntries(group).firstOrNull { !it.isHidden && it.model.isTextOutput }
        } ?: return missing()
        val instance = repo.instance(entry.providerInstanceId)?.takeIf { it.isEnabled } ?: return missing()
        val key = repo.usableApiKey(instance) ?: return missing()
        val provider = try { ProviderFactory.create(instance,key,entry.model,app) }
            catch (_: Exception) { return missing() }
        return CarePlanner(provider, app.getString(R.string.nm_care_clarify_no_nodes)).next(goal,snapshot,history)
    }
    private fun missing() = CarePlanResult.Unavailable("provider_not_configured")
}
