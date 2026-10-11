package io.github.nanomuse.dot.care

import io.github.nanomuse.dot.screen.NodeDto
import io.github.nanomuse.dot.screen.SceneKey
import io.github.nanomuse.dot.screen.ScreenSnapshot

sealed interface VerificationResult {
    data object Confirmed : VerificationResult
    data object NotYet : VerificationResult
    data object NeedsUserConfirmation : VerificationResult
}

/** Image-free evidence for one step. Refs and geometry never identify nodes in a later snapshot.
 * NodeReader currently exposes className, not heading/collection/query semantics. Matching
 * page text cannot prove navigation/results, and screen text cannot prove clipboard contents.
 */
class CareVerifier(before: ScreenSnapshot) {
    private val validator = CareProposalValidator()
    private val eligibleRefs = before.nodes.filter { validator.eligible(it, before) }.map { it.ref }.toSet()
    private val id = before.id
    private val scene = before.scene
    private val nodes = before.nodes.toList()

    fun evaluate(proposal: CareProposal, next: ScreenSnapshot): VerificationResult {
        if (proposal.snapshotId != id || proposal.scene != scene || !validator.validContent(proposal)) {
            return VerificationResult.NeedsUserConfirmation
        }
        if (!sameHost(scene, next.scene) || next.id == id || next.scene.version <= scene.version) {
            return VerificationResult.NotYet
        }
        if (next.nodes.isEmpty() || originalTarget(proposal) == null) return VerificationResult.NeedsUserConfirmation
        if (proposal.action == CareAction.Copy) return VerificationResult.NeedsUserConfirmation
        return when (val expected = proposal.expectation) {
            is CareExpectation.Focused -> {
                if (proposal.action != CareAction.Focus) return VerificationResult.NeedsUserConfirmation
                val old = originalTarget(proposal) ?: return VerificationResult.NeedsUserConfirmation
                val candidates = candidates(old, next.nodes) ?: return VerificationResult.NeedsUserConfirmation
                if (candidates.size > 1) return VerificationResult.NeedsUserConfirmation
                val current = candidates.singleOrNull() ?: return VerificationResult.NotYet
                if (!validator.eligible(current, next) || !old.editable || !current.editable) {
                    return VerificationResult.NeedsUserConfirmation
                }
                if (!old.focused && current.focused) VerificationResult.Confirmed else VerificationResult.NotYet
            }
            is CareExpectation.TextEquals -> {
                if (proposal.action != CareAction.Paste) return VerificationResult.NeedsUserConfirmation
                val old = originalTarget(proposal) ?: return VerificationResult.NeedsUserConfirmation
                val candidates = candidates(old, next.nodes) ?: return VerificationResult.NeedsUserConfirmation
                if (candidates.size > 1) return VerificationResult.NeedsUserConfirmation
                val current = candidates.singleOrNull() ?: return VerificationResult.NotYet
                if (!validator.eligible(current, next) || !old.editable || !current.editable ||
                    old.text == null || current.text == null) return VerificationResult.NeedsUserConfirmation
                if (old.text != expected.text && current.text == expected.text) VerificationResult.Confirmed
                else VerificationResult.NotYet
            }
            is CareExpectation.PageContains -> {
                if (proposal.action != CareAction.Open) return VerificationResult.NeedsUserConfirmation
                if (readableText(nodes, expected.text)) VerificationResult.NotYet
                else if (readableText(next.nodes, expected.text)) VerificationResult.NeedsUserConfirmation
                else VerificationResult.NotYet
            }
            is CareExpectation.ResultsFor -> {
                if (proposal.action != CareAction.Search) return VerificationResult.NeedsUserConfirmation
                val queries = next.nodes.filter { it.editable && it.enabled && !it.password }
                if (queries.isEmpty() || queries.any { it.text == null }) VerificationResult.NeedsUserConfirmation
                else if (queries.any { it.text == expected.query }) {
                    // A matching input is necessary, but a generic text/list class does not identify
                    // results. Unchanged query plus unchanged visible text supplies no new evidence.
                    if (content(nodes) == content(next.nodes)) VerificationResult.NotYet
                    else VerificationResult.NeedsUserConfirmation
                } else VerificationResult.NotYet
            }
        }
    }

    /** Only yields one eligible current target. Used to redraw a waiting step after a fresh sample. */
    fun target(proposal: CareProposal, next: ScreenSnapshot): NodeDto? {
        if (!sameHost(scene, next.scene) || next.scene.version < scene.version) return null
        val old = originalTarget(proposal) ?: return null
        return candidates(old, next.nodes)?.singleOrNull()?.takeIf { validator.eligible(it, next) }
    }

    private fun originalTarget(proposal: CareProposal): NodeDto? =
        nodes.filter { it.ref == proposal.targetRef }.singleOrNull()?.takeIf { it.ref in eligibleRefs }

    private fun candidates(old: NodeDto, current: List<NodeDto>): List<NodeDto>? {
        val id = old.viewId?.takeIf { it.isNotBlank() }
        val label = old.description?.takeIf { it.isNotBlank() }
        if (id == null && label == null) return null
        fun matches(node: NodeDto): Boolean = node.role == old.role &&
            if (id != null) node.viewId == id else node.description == label
        if (nodes.count(::matches) != 1) return null
        return current.filter(::matches)
    }

    private fun readableText(nodes: List<NodeDto>, text: String) = nodes.any {
        it.enabled && !it.password && (it.text == text || it.description == text)
    }
    private fun content(nodes: List<NodeDto>) = nodes.filter { it.enabled && !it.password }
        .map { listOf(it.viewId, it.role, it.text, it.description) }.toSet()

    companion object {
        internal fun sameHost(first: SceneKey, second: SceneKey): Boolean =
            first.packageName == second.packageName && first.windowId == second.windowId &&
                first.displayId == second.displayId
    }
}
