package io.github.nanomuse.dot.overlay;

import org.junit.Test;
import static org.junit.Assert.*;

/** Runs unchanged with plain javac/JUnit offline; requires no Android service or device. */
public class DotNativeServiceStateTest {
    private static final String OWN = "io.github.nanodot.app/com.openminis.app.accessibility.MinisAccessibilityService";
    private static String dump(String enabled, String binding, String crashed) {
        return "User state[\n Bound services:{Service[label=Nanodot Accessibility, feedbackType[FEEDBACK_GENERIC]]}\n"
            + " Enabled services:{" + enabled + "}\n Binding services:{" + binding + "}\n"
            + " Crashed services:{" + crashed + "}\n]";
    }
    @Test public void crashedButUnboundIsNotAcknowledgedDisabled() {
        assertFalse(DotNativeServiceState.read(dump("{" + OWN + "}", "", "{" + OWN + "}"), OWN).isDisabled());
    }
    @Test public void unrelatedBoundLabelDoesNotReplaceEnabledAcknowledgement() {
        assertFalse(DotNativeServiceState.read(dump(OWN, "", ""), OWN).isDisabled());
        assertTrue(DotNativeServiceState.read(dump("fixture.other/.OtherService", "", ""), OWN).isDisabled());
    }
    @Test public void everyFrameworkListMustBePresent() {
        for (String label : new String[]{"Enabled", "Binding", "Crashed"}) {
            String missing = dump("", "", "").replace(" " + label + " services:{}\n", "");
            assertFalse(label, DotNativeServiceState.read(missing, OWN).isDisabled());
        }
        assertFalse(DotNativeServiceState.read("", OWN).isDisabled());
    }
    @Test public void duplicateFrameworkListsAreAmbiguous() {
        assertFalse(DotNativeServiceState.read(dump("", "", "") + "\n Enabled services:{}", OWN).isDisabled());
    }
    @Test public void malformedFrameworkListsAreNotTreatedAsEmpty() {
        assertFalse(DotNativeServiceState.read(dump("unknown-format", "", ""), OWN).isDisabled());
        assertFalse(DotNativeServiceState.read(dump("", "", "").replace("Crashed services:{}", "Crashed services:"), OWN).isDisabled());
    }
    @Test public void flattenedComponentsAreCanonicalized() {
        String own = "fixture.app/fixture.app.Bridge$Nested";
        assertFalse(DotNativeServiceState.read(dump("fixture.app/.Bridge$Nested", "", ""), own).isDisabled());
        assertFalse(DotNativeServiceState.read(dump("", own, ""), "fixture.app/.Bridge$Nested").isDisabled());
        assertFalse(DotNativeServiceState.read(dump("", "", "fixture.app/.Bridge$Nested"), own).isDisabled());
        assertTrue(DotNativeServiceState.read(dump("fixture.other/.Bridge$Nested", "", ""), own).isDisabled());
    }
    @Test public void missingOrMalformedOwnIdentityCannotAcknowledge() {
        assertFalse(DotNativeServiceState.read(dump("", "", ""), "").isDisabled());
        assertFalse(DotNativeServiceState.read(dump("", "", ""), "not-a-component").isDisabled());
    }
}
