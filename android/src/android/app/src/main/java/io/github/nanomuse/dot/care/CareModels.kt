package io.github.nanomuse.dot.care

import io.github.nanomuse.dot.screen.SceneKey

/** Intent for the user; these values never perform an action. */
enum class CareAction { Open, Focus, Copy, Paste, Search }
sealed interface CareExpectation {
    data class PageContains(val text:String):CareExpectation
    data class Focused(val ref:String):CareExpectation
    data class TextEquals(val ref:String,val text:String):CareExpectation
    data class ResultsFor(val query:String):CareExpectation
}
data class CareProposal(val snapshotId:String,val scene:SceneKey,val targetRef:String,
    val action:CareAction,val instruction:String,val copyText:String?,
    val expectation:CareExpectation,val confidence:Float)
sealed interface CarePlanResult {
    data class Step(val proposal:CareProposal):CarePlanResult
    data class Clarify(val question:String):CarePlanResult
    /** Data-only code for the later controller to map to localized Recovering UI. */
    data class Unavailable(val code:String):CarePlanResult
}
