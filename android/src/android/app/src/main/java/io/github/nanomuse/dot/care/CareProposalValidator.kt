package io.github.nanomuse.dot.care

import io.github.nanomuse.dot.screen.NodeDto
import io.github.nanomuse.dot.screen.RectPx
import io.github.nanomuse.dot.screen.ScreenSnapshot

/** Only validates data. No node handles, coordinate actions or resource ownership. */
class CareProposalValidator {
    fun validate(proposal: CareProposal, snapshot: ScreenSnapshot): Boolean {
        if (proposal.snapshotId != snapshot.id || proposal.scene != snapshot.scene ||
            !validContent(proposal)) return false
        val matches = snapshot.nodes.filter { it.ref == proposal.targetRef }
        return matches.size == 1 && eligible(matches.single(), snapshot)
    }

    internal fun eligible(node: NodeDto, snapshot: ScreenSnapshot): Boolean =
        identity(node.ref) && node.enabled && !node.password &&
            validRect(snapshot.bounds) && validRect(node.bounds) &&
            node.bounds.left >= snapshot.bounds.left && node.bounds.top >= snapshot.bounds.top &&
            node.bounds.right <= snapshot.bounds.right && node.bounds.bottom <= snapshot.bounds.bottom &&
            snapshot.nodes.count { it.ref == node.ref } == 1

    /** Historical geometry cannot be revalidated against a different current screen.
     * This only checks the shape of caller-declared verified past context, never completion.
     */
    internal fun validContent(proposal: CareProposal): Boolean {
        if (!identity(proposal.snapshotId) || !identity(proposal.targetRef) ||
            !identity(proposal.scene.packageName) || proposal.scene.windowId < 0 ||
            proposal.scene.displayId < 0 || proposal.scene.version < 0 ||
            !proposal.confidence.isFinite() || proposal.confidence !in 0.7f..1f ||
            !safeText(proposal.instruction) ||
            proposal.copyText?.let { !safeText(it, 2048) } == true) return false
        return when (val expectation = proposal.expectation) {
            is CareExpectation.PageContains -> safeText(expectation.text)
            is CareExpectation.Focused -> expectation.ref == proposal.targetRef
            is CareExpectation.TextEquals -> expectation.ref == proposal.targetRef && safeText(expectation.text)
            is CareExpectation.ResultsFor -> safeText(expectation.query)
        }
    }

    internal fun safeText(text: String, limit: Int = 1024): Boolean =
        text.isNotBlank() && text.length <= limit && !executable.containsMatchIn(text) &&
            !executableShape.containsMatchIn(webUrl.replace(text, " ")) && text.none { it == '\u0000' }

    private fun identity(value: String): Boolean = safeText(value, 256)
    private fun validRect(rect: RectPx): Boolean =
        listOf(rect.left, rect.top, rect.right, rect.bottom, rect.width, rect.height).all { it.isFinite() } &&
            rect.width > 0 && rect.height > 0

    private companion object {
        // This is a conservative refusal of executable content, not an execution sandbox.
        // Unknown JSON fields are refused separately; no output is ever evaluated as code.
        // Refuse ordinary unfenced code shapes without enumerating function/member names.
        // HTTP(S) URL tokens are quoted data: query keys and parentheses are not assignments/calls.
        // This intentionally also refuses some code-like labels; it cannot classify every encoded
        // program or language, and must never be used to make output executable.
        val webUrl = Regex("https?://[^\\s<>\"'`]+", RegexOption.IGNORE_CASE)
        const val identifier = "[A-Za-z_$][A-Za-z0-9_$]*"
        val member = "(?:\\s*\\.\\s*$identifier|\\s*\\[[^\\r\\n]+?\\])"
        val executableShape = Regex(
            "\\b$identifier(?:$member)+\\s*(?:=(?!=)|[+*/-]=|\\+\\+|--)|" +
                "\\bfunction\\s*\\*?\\s*(?:$identifier\\s*)?\\([^\\r\\n)]*\\)\\s*\\{|" +
                "\\bdef\\s+$identifier\\s*\\([^\\r\\n)]*\\)\\s*:|" +
                "\\b(?:const|let|var)\\s+$identifier\\s*=|" +
                "\\b$identifier(?:\\s*\\.\\s*$identifier)*\\(|" +
                "\\b$identifier(?:\\s*\\.\\s*$identifier)+\\s*\\(|" +
                "\\b$identifier\\s+\\([^\\r\\n)]*\\)\\s*[;{]|" +
                "\\b$identifier\\s+\\(\\s*(?:['\"]|\\)|(?:[+-]\\s*)?(?:\\d|\\.\\d))|" +
                "(?:\\([^\\r\\n)]*\\)|\\b$identifier)\\s*=>",
        )
        val executable = Regex(
            "```|<\\s*script\\b|javascript\\s*:|\\b(?:Hands\\s*\\.\\s*run|dispatchGesture|setText)\\s*\\(|" +
                "\\b(?:eval|exec|system|Runtime\\s*\\.\\s*getRuntime)\\s*\\(|" +
                "\\b(?:python(?:3)?|bash|sh|node)\\s+-(?:c|e)\\b|\\$\\(",
            RegexOption.IGNORE_CASE,
        )
    }
}
