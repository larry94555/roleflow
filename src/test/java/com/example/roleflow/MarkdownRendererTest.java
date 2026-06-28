package com.example.roleflow;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownRendererTest {

    @Test
    void rendersHeadingsAtTheRightLevel() {
        String html = MarkdownRenderer.toHtml("# Goal\n## Phase 1 - Preparation\n### Detail");

        assertTrue(html.contains("<h1>Goal</h1>"), html);
        assertTrue(html.contains("<h2>Phase 1 - Preparation</h2>"), html);
        assertTrue(html.contains("<h3>Detail</h3>"), html);
    }

    @Test
    void rendersUnorderedAndOrderedLists() {
        String unordered = MarkdownRenderer.toHtml("- one\n- two");
        assertTrue(unordered.contains("<ul>"), unordered);
        assertTrue(unordered.contains("<li>one</li>"), unordered);
        assertTrue(unordered.contains("<li>two</li>"), unordered);
        assertTrue(unordered.contains("</ul>"), unordered);

        String ordered = MarkdownRenderer.toHtml("1. first\n2. second");
        assertTrue(ordered.contains("<ol>"), ordered);
        assertTrue(ordered.contains("<li>first</li>"), ordered);
        assertTrue(ordered.contains("</ol>"), ordered);
    }

    @Test
    void rendersParagraphsAndInlineFormatting() {
        String html = MarkdownRenderer.toHtml("This is **bold**, *italic*, `code`, and a [link](http://x).");

        assertTrue(html.contains("<p>"), html);
        assertTrue(html.contains("<strong>bold</strong>"), html);
        assertTrue(html.contains("<em>italic</em>"), html);
        assertTrue(html.contains("<code>code</code>"), html);
        assertTrue(html.contains("<a href=\"http://x\">link</a>"), html);
    }

    @Test
    void leavesUnderscoreIdentifiersAlone() {
        String html = MarkdownRenderer.toHtml("- Install the snake_case_helper and run plan_run_1.md");

        assertTrue(html.contains("snake_case_helper"), html);
        assertTrue(html.contains("plan_run_1.md"), html);
        assertFalse(html.contains("<em>"), "intra-word underscores must not become italics");
    }

    @Test
    void escapesHtmlSoContentCannotInjectMarkup() {
        String html = MarkdownRenderer.toHtml("A <script>alert(1)</script> & co.");

        assertFalse(html.contains("<script>"), html);
        assertTrue(html.contains("&lt;script&gt;"), html);
        assertTrue(html.contains("&amp;"), html);
    }

    @Test
    void separatesListsFromFollowingParagraphs() {
        String html = MarkdownRenderer.toHtml("- item\n\nAfter the list.");

        assertTrue(html.contains("</ul>"), html);
        assertTrue(html.contains("<p>After the list.</p>"), html);
        // The paragraph must not be swallowed into a list item.
        assertFalse(html.contains("<li>After the list.</li>"), html);
    }

    @Test
    void rendersACombinedGoalPlanDocumentEndToEnd() {
        String doc = PlanDocument.compose("Deliver a report.",
                "## Phase 1 - Preparation\n- Assumption: x\n- Decision: y\n## Phase 2 - Action\n- act\n"
                        + "## Phase 3 - Verification\n- verify\n## Phase 4 - Next steps\n- next");

        String html = MarkdownRenderer.toHtml(doc);

        assertTrue(html.contains("<h1>Goal</h1>"), html);
        assertTrue(html.contains("<p>Deliver a report.</p>"), html);
        assertTrue(html.contains("<h1>Plan</h1>"), html);
        assertTrue(html.contains("<h2>Phase 1 - Preparation</h2>"), html);
        assertTrue(html.contains("<li>Assumption: x</li>"), html);
    }
}
