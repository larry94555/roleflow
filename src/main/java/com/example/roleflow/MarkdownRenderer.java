package com.example.roleflow;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small, dependency-free Markdown-to-HTML renderer for the constrained Markdown the workflow produces
 * (the goal-and-plan documents): ATX headings, unordered and ordered lists, paragraphs, and the inline spans
 * {@code **bold**}, {@code *italic*}, {@code `code`}, and {@code [text](url)} links. All text is HTML-escaped
 * before inline formatting is applied, so document content cannot inject markup. The output is a fragment
 * styled by {@link GoalFileController} to resemble GitHub's Markdown preview.
 */
final class MarkdownRenderer {

    private MarkdownRenderer() {}

    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.*)$");
    private static final Pattern UNORDERED = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
    private static final Pattern ORDERED = Pattern.compile("^\\s*\\d+[.)]\\s+(.*)$");

    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*");
    // Italics use single asterisks only — underscores are left alone so identifiers like snake_case (common
    // in plans and file paths) are not mangled, matching GitHub's intra-word-underscore behavior.
    private static final Pattern ITALIC = Pattern.compile("(?<!\\*)\\*(?=\\S)(.+?)(?<=\\S)\\*(?!\\*)");
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern LINK = Pattern.compile("\\[(.+?)]\\((.+?)\\)");

    /** Renders {@code markdown} to an HTML fragment (no surrounding {@code <html>}/{@code <body>}). */
    static String toHtml(String markdown) {
        StringBuilder html = new StringBuilder();
        String[] lines = (markdown == null ? "" : markdown).split("\n", -1);

        String openList = null;          // "ul", "ol", or null
        StringBuilder paragraph = new StringBuilder();

        for (String raw : lines) {
            String line = stripTrailing(raw);
            Matcher heading = HEADING.matcher(line);
            Matcher unordered = UNORDERED.matcher(line);
            Matcher ordered = ORDERED.matcher(line);

            if (line.isBlank()) {
                paragraph = flushParagraph(html, paragraph);
                openList = closeList(html, openList);
            } else if (heading.matches()) {
                paragraph = flushParagraph(html, paragraph);
                openList = closeList(html, openList);
                int level = heading.group(1).length();
                html.append("<h").append(level).append('>')
                        .append(inline(heading.group(2).strip()))
                        .append("</h").append(level).append(">\n");
            } else if (unordered.matches()) {
                paragraph = flushParagraph(html, paragraph);
                openList = openList(html, openList, "ul");
                html.append("<li>").append(inline(unordered.group(1).strip())).append("</li>\n");
            } else if (ordered.matches()) {
                paragraph = flushParagraph(html, paragraph);
                openList = openList(html, openList, "ol");
                html.append("<li>").append(inline(ordered.group(1).strip())).append("</li>\n");
            } else {
                openList = closeList(html, openList);
                if (paragraph.length() > 0) paragraph.append(' ');
                paragraph.append(line.strip());
            }
        }
        flushParagraph(html, paragraph);
        closeList(html, openList);
        return html.toString().strip();
    }

    private static String openList(StringBuilder html, String openList, String wanted) {
        if (wanted.equals(openList)) {
            return openList;
        }
        closeList(html, openList);
        html.append('<').append(wanted).append(">\n");
        return wanted;
    }

    private static String closeList(StringBuilder html, String openList) {
        if (openList != null) {
            html.append("</").append(openList).append(">\n");
        }
        return null;
    }

    private static StringBuilder flushParagraph(StringBuilder html, StringBuilder paragraph) {
        if (paragraph.length() > 0) {
            html.append("<p>").append(inline(paragraph.toString().strip())).append("</p>\n");
        }
        return new StringBuilder();
    }

    /** Escapes HTML, then applies inline formatting (code first, so its contents are not re-parsed). */
    private static String inline(String text) {
        String escaped = escape(text);
        escaped = replace(CODE, escaped, m -> "<code>" + m.group(1) + "</code>");
        escaped = replace(LINK, escaped, m -> "<a href=\"" + m.group(2) + "\">" + m.group(1) + "</a>");
        escaped = replace(BOLD, escaped, m -> "<strong>" + m.group(1) + "</strong>");
        escaped = replace(ITALIC, escaped, m -> "<em>" + m.group(1) + "</em>");
        return escaped;
    }

    private static String replace(Pattern pattern, String text, java.util.function.Function<Matcher, String> fn) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(fn.apply(matcher)));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static String escape(String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\r')) end--;
        return line.substring(0, end);
    }
}
