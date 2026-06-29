package com.example.roleflow;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the plain text the engine returns into terminal output with clickable hyperlinks, mirroring what the
 * web page does. Modern terminals (Windows Terminal, iTerm2, GNOME Terminal, …) render OSC 8 hyperlink escape
 * sequences; terminals that don't simply show the link text. Like the web page, a {@code file://} goal/plan
 * link is pointed at the HTTP server's rendered view ({@code /goals/<name>}) so the command line and the web
 * open the same page.
 */
final class TerminalHyperlinks {

    /** The ASCII escape character (27); written as a code point so no raw control byte lives in the source. */
    private static final char ESC = (char) 27;

    // ANSI SGR colour codes used to make the visible link text look like a link (blue + underline).
    private static final String BLUE = ESC + "[34m";
    private static final String UNDERLINE = ESC + "[4m";
    private static final String RESET = ESC + "[0m";

    /** OSC 8 hyperlink: ESC ] 8 ; ; &lt;url&gt; ST &lt;text&gt; ESC ] 8 ; ; ST (ST = ESC backslash). */
    static String osc8(String url, String text) {
        return ESC + "]8;;" + url + ESC + "\\" + text + ESC + "]8;;" + ESC + "\\";
    }

    /** Wraps the visible link text in blue + underline so it stands out, then resets the colour. */
    private static String coloured(String text) {
        return BLUE + UNDERLINE + text + RESET;
    }

    // The same file-link shape the web page rewrites: a file:// URL ending in a plain <name>.md.
    private static final Pattern FILE_LINK = Pattern.compile("file://\\S+?/([A-Za-z0-9._-]+\\.md)");

    private final String baseUrl;     // e.g. http://localhost:8080
    private final boolean enabled;

    TerminalHyperlinks(String baseUrl, boolean enabled) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        this.baseUrl = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        this.enabled = enabled;
    }

    /**
     * Replaces each {@code file://…/<name>.md} link in {@code text} with a hyperlink to the server's rendered
     * view of that file. When hyperlinks are disabled the text is returned unchanged.
     */
    String linkifyFiles(String text) {
        if (!enabled || text == null || text.isEmpty()) {
            return text;
        }
        Matcher matcher = FILE_LINK.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String rendered = baseUrl + "/goals/" + matcher.group(1);
            matcher.appendReplacement(out, Matcher.quoteReplacement(osc8(rendered, coloured(matcher.group()))));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /** The audit-trail web page URL for a prompt id. */
    String auditUrl(String auditId) {
        return baseUrl + "/audit.html?prompt=" + URLEncoder.encode(auditId, StandardCharsets.UTF_8);
    }

    /** A one-line clickable pointer to this prompt's audit trail (or the plain URL when disabled). */
    String auditLine(String auditId) {
        String url = auditUrl(auditId);
        // Plain ASCII text (no emoji, which terminals on legacy code pages render as "?"), coloured so it
        // looks like a link. Ctrl+click opens it in supporting terminals (Windows Terminal, iTerm2, …).
        return enabled
                ? osc8(url, coloured("View audit trail (Ctrl+click to open)"))
                : "Audit trail: " + url;
    }
}
