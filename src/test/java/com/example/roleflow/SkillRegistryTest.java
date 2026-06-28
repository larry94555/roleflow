package com.example.roleflow;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillRegistryTest {

    private static SkillProvider provider(Skill... skills) {
        return () -> List.of(skills);
    }

    @Test
    void collectsSkillsFromAllProviders() {
        SkillRegistry registry = new SkillRegistry(List.of(
                provider(new Skill("mathematics", "math", "math guidance")),
                provider(new Skill("physics", "phys", "physics guidance"))));

        assertEquals(2, registry.names().size());
        assertTrue(registry.contains("mathematics"));
        assertTrue(registry.contains("physics"));
        assertFalse(registry.isEmpty());
    }

    @Test
    void looksUpSkillsCaseInsensitively() {
        SkillRegistry registry = new SkillRegistry(List.of(
                provider(new Skill("Mathematics", "math", "guidance"))));

        assertTrue(registry.contains("mathematics"));
        assertNotNull(registry.get("MATHEMATICS"));
        assertEquals("guidance", registry.get("mathematics").instructions());
        assertNull(registry.get("unknown"));
    }

    @Test
    void ignoresADuplicateSkillNameKeepingTheFirst() {
        SkillRegistry registry = new SkillRegistry(List.of(
                provider(new Skill("mathematics", "first", "first guidance")),
                provider(new Skill("mathematics", "second", "second guidance"))));

        assertEquals(1, registry.names().size());
        assertEquals("first guidance", registry.get("mathematics").instructions());
    }

    @Test
    void isEmptyWhenNoProviders() {
        assertTrue(new SkillRegistry(List.of()).isEmpty());
    }

    @Test
    void theMathematicsSkillExplainsThatACleanSearchIsComplete() {
        SkillRegistry registry = new SkillRegistry(List.of(new MathematicsSkillProvider()));

        Skill mathematics = registry.get("mathematics");
        assertNotNull(mathematics);
        String text = mathematics.instructions().toLowerCase();
        assertTrue(text.contains("counterexample"), mathematics.instructions());
        assertTrue(text.contains("complete"), mathematics.instructions());
        // It must push back on treating "no counterexample found" as a failure to retry.
        assertTrue(text.contains("not a failure") || text.contains("not call for"), mathematics.instructions());
    }

    @Test
    void theMathematicsSkillSaysAFoundCounterexampleIsNothingToHandle() {
        Skill mathematics = new SkillRegistry(List.of(new MathematicsSkillProvider())).get("mathematics");

        String text = mathematics.instructions().toLowerCase();
        // A found counterexample is itself the result — there is nothing to "handle".
        assertTrue(text.contains("handle"), mathematics.instructions());
        assertTrue(text.contains("report"), mathematics.instructions());
        assertTrue(text.contains("nothing to"), mathematics.instructions());
    }
}
