package io.github.nanomuse.dot.care

import io.github.nanomuse.dot.screen.*
import org.junit.Assert.*
import org.junit.Test

/** Removing an identity, geometry, privacy or confidence guard must break these fixtures. */
class CareProposalValidatorTest {
    private val validator = CareProposalValidator()
    @Test fun acceptsCurrentReferencedNodeWithAssociatedBounds() {
        assertTrue(validator.validate(proposal(), snapshot()))
    }
    @Test fun rejectsOldSnapshotAndEachSceneComponent() {
        val s = snapshot()
        val scenes = listOf(s.scene.copy(packageName="other"), s.scene.copy(windowId=8),
            s.scene.copy(displayId=1), s.scene.copy(version=4))
        assertFalse(validator.validate(proposal().copy(snapshotId="old"), s))
        scenes.forEach { assertFalse(validator.validate(proposal().copy(scene=it), s)) }
    }
    @Test fun rejectsMissingDuplicatePasswordAndDisabledTargets() {
        listOf(emptyList(), listOf(node(ref="other")), listOf(node(),node()),
            listOf(node(password=true)), listOf(node().copy(enabled=false))).forEach {
            assertFalse(validator.validate(proposal(), snapshot(it)))
        }
    }
    @Test fun rejectsOutsideEmptyReversedAndNonfiniteNodeBounds() {
        listOf(RectPx(-1f,20f,60f,60f),RectPx(20f,20f,201f,60f),
            RectPx(20f,20f,20f,60f),RectPx(60f,20f,20f,60f),
            RectPx(Float.NaN,20f,60f,60f),RectPx(20f,20f,Float.POSITIVE_INFINITY,60f)).forEach {
            assertFalse(validator.validate(proposal(),snapshot(listOf(node().copy(bounds=it)))))
        }
    }
    @Test fun confidenceMustBeFiniteAndWithinAcceptedRange() {
        listOf(0.69f,-1f,1.01f,Float.NaN,Float.POSITIVE_INFINITY).forEach {
            assertFalse(validator.validate(proposal().copy(confidence=it),snapshot()))
        }
        assertTrue(validator.validate(proposal().copy(confidence=0.7f),snapshot()))
        assertTrue(validator.validate(proposal().copy(confidence=1f),snapshot()))
    }
    @Test fun refusesScriptsInInstructionCopyAndExpectation() {
        listOf("<script>alert(1)</script>","javascript:alert(1)","Hands.run('tap')",
            "dispatchGesture(x)","setText('x')","```sh\nrm file\n```","python -c 'print(1)'").forEach {
            assertFalse(validator.validate(proposal().copy(instruction=it),snapshot()))
            assertFalse(validator.validate(proposal().copy(copyText=it),snapshot()))
            assertFalse(validator.validate(proposal().copy(expectation=CareExpectation.PageContains(it)),snapshot()))
        }
    }
    @Test fun refusesUnfencedPropertyAssignments() {
        // Removing member-assignment refusal must permit a code-containing current proposal.
        listOf("document.body.innerHTML = 'hello';", "settings.profile.name = 'changed'",
            "form['value'] = 3;").forEach(::assertScriptRejected)
    }
    @Test fun refusesUnfencedFunctionCode() {
        listOf("function greet(){ alert('hi'); }", "function add(a, b) { return a + b; }",
            "const sum = (a, b) => a + b;", "def greet():\n    print('hi')").forEach(::assertScriptRejected)
    }
    @Test fun refusesUnfencedInvocations() {
        listOf("alert('hi')", "console.log('hello');", "document.querySelector('#field').focus()",
            "notify () ;", "const answer = 42;").forEach(::assertScriptRejected)
    }
    @Test fun ordinaryQueriesUrlsNumbersAndProseRemainValidCurrentProposals() {
        listOf("啤酒", "搜索无酒精啤酒 330ml", "123.45", "1 + 1 = 2", "Tea (hot)",
            "Copy the label, then open Search.", "https://example.org/search?q=tea&page=2",
            "https://example.org/search?view.name=tea(2)&page=1").forEach { text ->
            assertTrue("Ordinary instruction rejected: $text",validator.validate(proposal().copy(instruction=text),snapshot()))
            assertTrue("Ordinary copy text rejected: $text",validator.validate(proposal().copy(action=CareAction.Paste,
                copyText=text,expectation=CareExpectation.TextEquals("n1",text)),snapshot()))
            assertTrue("Ordinary query rejected: $text",validator.validate(proposal().copy(action=CareAction.Search,
                expectation=CareExpectation.ResultsFor(text)),snapshot()))
        }
    }
    private fun assertScriptRejected(code:String) {
        assertFalse("Executable instruction accepted: $code",validator.validate(proposal().copy(instruction=code),snapshot()))
        assertFalse("Executable copy text accepted: $code",validator.validate(proposal().copy(action=CareAction.Paste,
            copyText=code),snapshot()))
        assertFalse("Executable text condition accepted: $code",validator.validate(proposal().copy(action=CareAction.Paste,
            copyText="hello",expectation=CareExpectation.TextEquals("n1",code)),snapshot()))
        assertFalse("Executable page condition accepted: $code",validator.validate(proposal().copy(expectation=CareExpectation.PageContains(code)),snapshot()))
        assertFalse("Executable query condition accepted: $code",validator.validate(proposal().copy(expectation=CareExpectation.ResultsFor(code)),snapshot()))
    }
    @Test fun focusedAndTextExpectationsMustReferToSameCurrentTarget() {
        assertTrue(validator.validate(proposal().copy(action=CareAction.Focus,
            expectation=CareExpectation.Focused("n1")),snapshot()))
        assertFalse(validator.validate(proposal().copy(expectation=CareExpectation.Focused("old")),snapshot()))
        assertFalse(validator.validate(proposal().copy(expectation=CareExpectation.TextEquals("old","tea")),snapshot()))
        assertTrue(validator.validate(proposal().copy(action=CareAction.Paste,copyText="tea",
            expectation=CareExpectation.TextEquals("n1","tea")),snapshot()))
        assertTrue(validator.validate(proposal().copy(action=CareAction.Search,
            expectation=CareExpectation.ResultsFor("tea")),snapshot()))
        assertTrue(validator.validate(proposal().copy(action=CareAction.Copy,copyText="tea"),snapshot()))
    }
    @Test fun rejectsBlankAndOversizedInstructionAndExpectation() {
        listOf("", " ","x".repeat(1025)).forEach {
            assertFalse(validator.validate(proposal().copy(instruction=it),snapshot()))
            assertFalse(validator.validate(proposal().copy(expectation=CareExpectation.PageContains(it)),snapshot()))
        }
    }
}

internal fun node(ref: String="n1",password:Boolean=false) = NodeDto(ref,RectPx(20f,20f,60f,60f),
    if (password) "private-password" else "Search",null,"fixture/search","EditText",false,true,true,true,password)
internal fun snapshot(nodes:List<NodeDto> = listOf(node()),id:String="s1",version:Long=3):ScreenSnapshot {
    val bounds=RectPx(0f,0f,200f,300f)
    return ScreenSnapshot(id,9,1000,SceneKey("fixture.app",7,0,version),bounds,0,
        RectPx(0f,0f,0f,0f),null,nodes,null,ScreenTransform(bounds,200,300))
}
internal fun proposal()=CareProposal("s1",SceneKey("fixture.app",7,0,3),"n1",CareAction.Open,
    "Open Search",null,CareExpectation.PageContains("Search"),0.9f)
