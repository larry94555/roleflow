package com.example.roleflow;

import java.util.List;

/**
 * Supplies one or more {@link Skill}s to the {@link SkillRegistry}. Every Spring bean that implements this
 * interface is registered automatically at startup, so adding a new skill is simply a matter of adding a
 * {@code @Component} that implements {@code SkillProvider}. Mirrors {@link ToolProvider}. See
 * {@code Skill_Registry.md}.
 */
public interface SkillProvider {

    /** The skills this provider contributes. Called once when the registry is built. */
    List<Skill> skills();
}
