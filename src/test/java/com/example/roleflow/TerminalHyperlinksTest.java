package com.example.roleflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalHyperlinksTest {

    private static final char ESC = (char) 27;
    private final TerminalHyperlinks links = new TerminalHyperlinks("http://localhost:8080", true);

    @Test
    void buildsAnOsc8HyperlinkSequence() {
        String link = TerminalHyperlinks.osc8("http://x/y", "click me");
        assertEquals(ESC + "]8;;http://x/y" + ESC + "\\click me" + ESC + "]8;;" + ESC + "\\", link);
    }

    @Test
    void rewritesAFileLinkToTheRenderedHttpUrl() {
        String text = "Files created:\n- Plan file: file:///C:/Users/x/goals/plan_abc_123.md";

        String out = links.linkifyFiles(text);

        // The file path stays visible, but it now hyperlinks to the server's rendered view of the file.
        assertTrue(out.contains(ESC + "]8;;http://localhost:8080/goals/plan_abc_123.md" + ESC + "\\"), out);
        assertTrue(out.contains("file:///C:/Users/x/goals/plan_abc_123.md"), "the file path remains visible");
    }

    @Test
    void leavesTextWithoutFileLinksUnchanged() {
        assertEquals("Hello to you too!", links.linkifyFiles("Hello to you too!"));
    }

    @Test
    void buildsAClickableAuditLine() {
        String line = links.auditLine("p-1 2");
        assertTrue(line.contains(ESC + "]8;;http://localhost:8080/audit.html?prompt=p-1+2" + ESC + "\\"), line);
    }

    @Test
    void emitsPlainTextWhenHyperlinksAreDisabled() {
        TerminalHyperlinks off = new TerminalHyperlinks("http://localhost:8080", false);

        String text = "- Plan file: file:///C:/x/goals/plan_a.md";
        assertEquals(text, off.linkifyFiles(text), "no escape sequences when disabled");
        String audit = off.auditLine("pid");
        assertFalse(audit.indexOf(ESC) >= 0, "no escape sequences when disabled");
        assertTrue(audit.contains("http://localhost:8080/audit.html?prompt=pid"), audit);
    }
}
