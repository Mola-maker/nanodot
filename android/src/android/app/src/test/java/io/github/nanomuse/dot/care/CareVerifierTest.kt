package io.github.nanomuse.dot.care

import io.github.nanomuse.dot.screen.*
import org.junit.Assert.*
import org.junit.Test

/** Losing delta, host, uniqueness or semantic guards must break these public-DTO fixtures. */
class CareVerifierTest {
    private fun check(p: CareProposal, before: ScreenSnapshot, after: ScreenSnapshot) =
        CareVerifier(before).evaluate(p, after)
    private fun focus() = careProposal(CareAction.Focus, CareExpectation.Focused("old:field"))
    @Test fun correctFocusRelinksByIdentityRatherThanReferenceOrBounds() {
        assertEquals(VerificationResult.Confirmed, check(focus(), careScreen(),
            careScreen(id="after",version=4,nodes=listOf(careNode(ref="after:field",focused=true,
                bounds=RectPx(5f,80f,150f,110f))))))
    }
    @Test fun wrongFieldFocusDoesNotAdvance() {
        assertEquals(VerificationResult.NotYet, check(focus(),careScreen(),careScreen(id="after",version=4,
            nodes=listOf(careNode(ref="new:field"),careNode(ref="wrong",viewId="app/ad",focused=true)))))
    }
    @Test fun alreadyFocusedIsNotEvidenceOfUserAction() {
        assertEquals(VerificationResult.NotYet,check(focus(),careScreen(nodes=listOf(careNode(focused=true))),
            careScreen(id="after",version=4,nodes=listOf(careNode(ref="after:field",focused=true)))))
    }
    @Test fun unchangedSnapshotOrOldVersionCannotConfirm() {
        listOf(careScreen(nodes=listOf(careNode(focused=true))),
            careScreen(id="after",version=2,nodes=listOf(careNode(focused=true)))).forEach {
            assertEquals(VerificationResult.NotYet,check(focus(),careScreen(),it))
        }
    }
    @Test fun unrelatedScrollAndAdvertisementRefreshAreNotEvidence() {
        assertEquals(VerificationResult.NotYet,check(focus(),careScreen(),careScreen(id="after",version=4,
            nodes=listOf(careNode(ref="new:field",bounds=RectPx(5f,100f,150f,140f)),
                careNode(ref="ad",viewId="app/ad",text="Today offer")))))
    }
    @Test fun eachHostMismatchCannotAdvance() {
        val after=careScreen(id="after",version=4,nodes=listOf(careNode(focused=true)))
        listOf(after.scene.copy(packageName="other.app"),after.scene.copy(windowId=8),
            after.scene.copy(displayId=1)).forEach {
            assertEquals(VerificationResult.NotYet,check(focus(),careScreen(),careScreen(id="after",scene=it,
                nodes=after.nodes)))
        }
    }
    @Test fun duplicatedIdentityNeedsConfirmationEvenWhenOnlyOneIsFocused() {
        assertEquals(VerificationResult.NeedsUserConfirmation,check(focus(),careScreen(),careScreen(id="after",version=4,
            nodes=listOf(careNode(ref="first",focused=true),careNode(ref="second")))))
    }
    @Test fun noStableIdentityCannotReuseOldRef() {
        val before=careScreen(nodes=listOf(careNode(viewId=null,description=null)))
        assertEquals(VerificationResult.NeedsUserConfirmation,check(focus(),before,careScreen(id="after",version=4,
            nodes=listOf(careNode(viewId=null,description=null,focused=true)))))
    }
    @Test fun stableDescriptionCanRelinkWithoutViewId() {
        val before=careScreen(nodes=listOf(careNode(viewId=null,description="Search products")))
        assertEquals(VerificationResult.Confirmed,check(focus(),before,careScreen(id="after",version=4,
            nodes=listOf(careNode(ref="new",viewId=null,description="Search products",focused=true)))))
    }
    @Test fun changedRoleCannotUseIdAsProof() {
        assertEquals(VerificationResult.NotYet,check(focus(),careScreen(),careScreen(id="after",version=4,
            nodes=listOf(careNode(role="Button",focused=true)))))
    }
    @Test fun pasteRequiresReadableChangedTextInCorrectField() {
        val p=careProposal(CareAction.Paste,CareExpectation.TextEquals("old:field","tea"),copy="tea")
        assertEquals(VerificationResult.Confirmed,check(p,careScreen(),careScreen(id="after",version=4,
            nodes=listOf(careNode(ref="new",text="tea")))))
        assertEquals(VerificationResult.NotYet,check(p,careScreen(nodes=listOf(careNode(text="tea"))),
            careScreen(id="after",version=4,nodes=listOf(careNode(ref="new",text="tea")))))
        assertEquals(VerificationResult.NotYet,check(p,careScreen(),careScreen(id="after",version=4,
            nodes=listOf(careNode(text="coffee"),careNode(ref="ad",viewId="app/ad",text="tea")))))
    }
    @Test fun unreadableOrPasswordTextRequiresConfirmation() {
        val p=careProposal(CareAction.Paste,CareExpectation.TextEquals("old:field","tea"),copy="tea")
        listOf(careNode(text=null),careNode(text="tea",password=true),careNode(text="tea",enabled=false)).forEach {
            assertEquals(VerificationResult.NeedsUserConfirmation,check(p,careScreen(),
                careScreen(id="after",version=4,nodes=listOf(it))))
        }
    }
    @Test fun openCannotInventPageSemanticsFromMatchingText() {
        val p=careProposal(CareAction.Open,CareExpectation.PageContains("Delivery"))
        assertEquals(VerificationResult.NeedsUserConfirmation,check(p,careScreen(),careScreen(id="after",version=4,
            nodes=listOf(careNode(ref="heading",viewId="app/title",role="android.widget.TextView",text="Delivery",editable=false)))))
        assertEquals(VerificationResult.NeedsUserConfirmation,check(p,careScreen(),careScreen(id="after",version=4,
            nodes=listOf(careNode(ref="ad",viewId="app/ad",role="TextView",text="Delivery",editable=false)))))
    }
    @Test fun existingPageAnchorDoesNotBecomeCompletionAfterScroll() {
        val heading=careNode(ref="heading",viewId="app/title",role="android.widget.TextView",text="Delivery",editable=false)
        assertEquals(VerificationResult.NotYet,check(careProposal(CareAction.Open,CareExpectation.PageContains("Delivery")),
            careScreen(nodes=listOf(careNode(),heading)),careScreen(id="after",version=4,nodes=listOf(heading))))
    }
    @Test fun searchCannotInventResultSemanticsFromMatchingQuery() {
        val p=careProposal(CareAction.Search,CareExpectation.ResultsFor("tea"))
        val query=careNode(ref="query",text="tea")
        val result=careNode(ref="results",viewId="app/results",role="android.widget.ListView",description="tea",text=null,editable=false)
        assertEquals(VerificationResult.NeedsUserConfirmation,check(p,careScreen(nodes=listOf(careNode(text="tea"))),
            careScreen(id="after",version=4,nodes=listOf(query,result))))
        assertEquals(VerificationResult.NeedsUserConfirmation,check(p,careScreen(),careScreen(id="after",version=4,
            nodes=listOf(query, result.copy(role="TextView",viewId="app/ad")))))
        assertEquals(VerificationResult.NotYet,check(p,careScreen(),careScreen(id="after",version=4,
            nodes=listOf(query.copy(text="coffee"),result))))
    }
    @Test fun existingResultsDoNotProveNewSearch() {
        val result=careNode(ref="results",viewId="app/results",role="android.widget.ListView",description="tea",editable=false)
        val nodes=listOf(careNode(text="tea"),result)
        assertEquals(VerificationResult.NotYet,check(careProposal(CareAction.Search,CareExpectation.ResultsFor("tea")),
            careScreen(nodes=nodes),careScreen(id="after",version=4,nodes=nodes)))
    }
    @Test fun copyCannotInferClipboardFromScreenText() {
        val p=careProposal(CareAction.Copy,CareExpectation.TextEquals("old:field","tea"),copy="tea")
        assertEquals(VerificationResult.NeedsUserConfirmation,check(p,careScreen(),
            careScreen(id="after",version=4,nodes=listOf(careNode(text="tea")))))
    }
    @Test fun missingNodesNeedConfirmationRatherThanSuccess() {
        assertEquals(VerificationResult.NeedsUserConfirmation,check(focus(),careScreen(),
            careScreen(id="after",version=4,nodes=emptyList())))
    }
    @Test fun invalidBaselineGeometryCannotBecomeFreshCompletion() {
        val before=careScreen(nodes=listOf(careNode(bounds=RectPx(-5f,5f,150f,45f))))
        assertEquals(VerificationResult.NeedsUserConfirmation,check(focus(),before,
            careScreen(id="after",version=4,nodes=listOf(careNode(ref="new",focused=true)))))
    }
    @Test fun mismatchedActionAndConditionCannotConfirm() {
        assertEquals(VerificationResult.NeedsUserConfirmation,check(careProposal(CareAction.Search,
            CareExpectation.Focused("old:field")),careScreen(),careScreen(id="after",version=4,
            nodes=listOf(careNode(focused=true)))))
    }
}

internal fun careNode(ref:String="old:field",viewId:String?="app/query",role:String="EditText",
    text:String?="",description:String?=null,focused:Boolean=false,editable:Boolean=true,
    enabled:Boolean=true,password:Boolean=false,bounds:RectPx=RectPx(5f,5f,150f,45f)) =
    NodeDto(ref,bounds,text,description,viewId,role,focused,true,editable,enabled,password)
internal fun careScreen(id:String="before",version:Long=3,generation:Long=1,
    scene:SceneKey=SceneKey("fixture.app",7,0,version),nodes:List<NodeDto> = listOf(careNode())):ScreenSnapshot {
    val bounds=RectPx(0f,0f,200f,300f)
    return ScreenSnapshot(id,generation,1000,scene,bounds,0,RectPx(0f,0f,0f,0f),null,nodes,null,
        ScreenTransform(bounds,200,300))
}
internal fun careProposal(action:CareAction=CareAction.Focus,
    expected:CareExpectation=CareExpectation.Focused("old:field"),copy:String?=null)=
    CareProposal("before",SceneKey("fixture.app",7,0,3),"old:field",action,"Use search",copy,expected,0.9f)
