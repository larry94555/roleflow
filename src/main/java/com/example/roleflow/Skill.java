package com.example.roleflow;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A unit of reusable domain knowledge that a role can apply when it carries out its action. Where a
 * {@link Tool} lets a role <em>do</em> something, a Skill teaches a role <em>how to think</em> about a
 * subject — its {@link #instructions} are injected into the role's system prompt.
 *
 * <p>A skill is keyed by {@link #name}, which is matched (case-insensitively) against the run's identified
 * topics: a role that lists a skill receives that skill's guidance only when the run actually involves the
 * skill's topic (e.g. the {@code mathematics} skill is applied only when "mathematics" is an identified
 * topic). See {@code Skill_Registry.md}.
 *
 * @param name         the skill's identifier, matched against the run's topics (e.g. "mathematics")
 * @param description  a one-line summary of what the skill teaches, for listings
 * @param instructions the guidance injected into a using role's system prompt
 */
public record Skill(String name, String description, String instructions) {

    /** A {@code {name, description}} summary, for a future {@code skills/list}-style listing. */
    public Map<String, Object> toSummary() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", name);
        summary.put("description", description);
        return summary;
    }
}
