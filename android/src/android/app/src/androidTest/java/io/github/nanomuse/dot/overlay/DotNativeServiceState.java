package io.github.nanomuse.dot.overlay;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure parser for the test harness; no framework calls or settings mutations. */
final class DotNativeServiceState {
    private static final Pattern COMPONENT = Pattern.compile("[A-Za-z0-9_.$]+/[A-Za-z0-9_.$]+");
    private static final String[] LABELS = {"Enabled services:", "Binding services:", "Crashed services:"};
    final boolean valid, enabled, binding, crashed;
    private DotNativeServiceState(boolean valid, boolean enabled, boolean binding, boolean crashed) {
        this.valid = valid; this.enabled = enabled; this.binding = binding; this.crashed = crashed;
    }
    boolean isDisabled() { return valid && !enabled && !binding && !crashed; }
    private static String canonical(String component) {
        if (!COMPONENT.matcher(component).matches()) return null;
        String[] parts = component.split("/", 2);
        return parts[0] + "/" + (parts[1].startsWith(".") ? parts[0] + parts[1] : parts[1]);
    }
    static DotNativeServiceState read(String dump, String own) {
        String expected = canonical(own);
        boolean valid = expected != null;
        boolean[] present = new boolean[3];
        for (int index = 0; index < LABELS.length; index++) {
            String body = null;
            int count = 0;
            for (String line : dump.split("\r?\n")) {
                String trimmed = line.trim();
                if (trimmed.startsWith(LABELS[index])) {
                    count++;
                    body = trimmed.substring(LABELS[index].length()).trim();
                }
            }
            if (count != 1 || body == null || !body.startsWith("{") || !body.endsWith("}")) {
                valid = false;
                continue;
            }
            int depth = 0;
            for (char character : body.toCharArray()) {
                if (character == '{') depth++;
                if (character == '}' && --depth < 0) valid = false;
            }
            if (depth != 0) valid = false;
            Matcher entries = COMPONENT.matcher(body);
            while (entries.find()) {
                if (canonical(entries.group()).equals(expected)) present[index] = true;
            }
            // Unknown formats must not masquerade as an empty framework acknowledgement.
            if (!entries.replaceAll("").matches("[\\s{},]*")) valid = false;
        }
        return new DotNativeServiceState(valid, present[0], present[1], present[2]);
    }
}
