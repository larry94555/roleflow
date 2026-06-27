package com.example.roleflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The internal skill registry. It collects the skills contributed by every {@link SkillProvider} bean and
 * makes them retrievable by name, so a role's declared skills can be injected into its system prompt.
 *
 * <p>Registration is automatic: Spring injects all {@code SkillProvider} beans, so adding a new skill only
 * requires adding a provider component. Skill names are unique (case-insensitive); a duplicate is ignored
 * with a warning. Mirrors {@link ToolRegistry}. See {@code Skill_Registry.md}.
 */
@Component
public class SkillRegistry {
    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final Map<String, Skill> skills = new LinkedHashMap<>();

    public SkillRegistry(List<SkillProvider> providers) {
        for (SkillProvider provider : providers) {
            for (Skill skill : provider.skills()) {
                register(skill);
            }
        }
        log.info("[skills] registered {} skill(s): {}", skills.size(), skills.keySet());
    }

    private void register(Skill skill) {
        if (skills.putIfAbsent(skill.name().toLowerCase(Locale.ROOT), skill) != null) {
            log.warn("[skills] duplicate skill name ignored: {}", skill.name());
        }
    }

    /** All registered skill names, in registration order. */
    public synchronized List<String> names() {
        return new ArrayList<>(skills.keySet());
    }

    public synchronized boolean contains(String name) {
        return name != null && skills.containsKey(name.toLowerCase(Locale.ROOT));
    }

    /** The skill with this name, or null if none is registered. */
    public synchronized Skill get(String name) {
        return name == null ? null : skills.get(name.toLowerCase(Locale.ROOT));
    }

    public synchronized boolean isEmpty() {
        return skills.isEmpty();
    }
}
