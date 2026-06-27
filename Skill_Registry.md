# Skill_Registry.md

RoleFlow has an **internal skill registry** — a counterpart to the [tool registry](Tool_Registry.md).
Where a **tool** lets a role *do* something (e.g. `web_search`), a **skill** teaches a role *how to think*
about a subject: a skill is a block of domain guidance that is injected into a role's system prompt.

The first skill is **`mathematics`**, which explains how to reason about a mathematical investigation — in
particular that searching a fixed range for a counterexample is a **complete** result whether or not one is
found, and that the goal is to *gain information* about a conjecture rather than to *prove* it.

This document explains how to **create** a new skill, **register** it, and **make a role use it**.

---

## Concepts

| Piece | What it is |
|-------|------------|
| [`Skill`](src/main/java/com/example/roleflow/Skill.java) | One skill: `name`, `description`, and `instructions` (the guidance injected into a role's prompt). |
| [`SkillProvider`](src/main/java/com/example/roleflow/SkillProvider.java) | A bean that contributes one or more `Skill`s (`List<Skill> skills()`). |
| [`SkillRegistry`](src/main/java/com/example/roleflow/SkillRegistry.java) | Collects every `SkillProvider` bean and looks skills up by name (case-insensitive). |
| `Skills:` field in [`roleflow.active`](config/roleflow.active) | Lists the skills a role may apply. |

The design mirrors the tool registry: providers are auto-collected by Spring, names are unique, and a
duplicate name is ignored with a warning.

---

## How a skill reaches the model

A skill is applied to a role only when **both** conditions hold:

1. **The role declares it** — the role lists the skill's name in its `Skills:` field in `roleflow.active`.
2. **The run is about that subject** — the skill's `name` matches one of the **topics** identified by the
   `TopicAnalyzer` role for this run (case-insensitive).

This ties skills to the topic system: the `mathematics` skill is injected into `PlanBuilder` only when
"mathematics" is an identified topic, so a backup-scheduling plan never receives mathematics guidance, while
the Legendre-conjecture plan does. When a skill is applied, the engine records a `VALIDATION` audit event
(`applied skill(s): mathematics`) on the role, so its use is visible in the audit trail.

```
TopicAnalyzer  ──topics──▶  [mathematics, programming]
                                   │
PlanBuilder (Skills: mathematics)  │  engine checks: does a topic match a declared skill?
                                   ▼
        inject the "mathematics" skill instructions into PlanBuilder's system prompt
```

---

## Creating a skill

A skill is any class that produces one or more `Skill` objects. Implement `SkillProvider` and annotate it
with `@Component` — that is the entire installation step (Spring auto-registers it at startup).

```java
package com.example.roleflow;

import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class StatisticsSkillProvider implements SkillProvider {

    @Override
    public List<Skill> skills() {
        return List.of(new Skill(
                "statistics",                               // name — must match the topic it applies to
                "How to reason about sampling and significance.",  // one-line description, for listings
                """
                When the prompt involves statistics:
                - Distinguish a sample from the population it estimates.
                - A result is not 'significant' merely because a program produced a number; state the test,
                  the assumptions, and the uncertainty.
                """.strip()));                              // instructions — injected into the role's prompt
    }
}
```

**Naming:** the `name` is what gets matched against the run's topics, so name a skill after the topic it
serves (`mathematics`, `statistics`, `chemistry`). If `TopicAnalyzer` would call the subject "mathematics",
name the skill `mathematics`.

No other wiring is required — `SkillRegistry` picks the provider up automatically. You can confirm it at
startup from the log line `[skills] registered N skill(s): [...]`.

---

## Making a role use a skill

Add a `Skills:` field to the role in [`config/roleflow.active`](config/roleflow.active), listing one or more
skill names (comma-separated). For example, `PlanBuilder` declares:

```
7. PlanBuilder
Role: Plan author
Action:
  ... build a four-phase plan ...
Output: plan
Skills: mathematics
Transition: PlanReviewer
```

Now, whenever a run's topics include "mathematics", the `mathematics` skill's guidance is appended to
`PlanBuilder`'s system prompt under an `### Skill: mathematics` heading, right after the role's task. To have
a different role (say `PlanReviewer`) also use it, add `Skills: mathematics` to that role too. A role may
list several skills (`Skills: mathematics, statistics`); each is injected only when its topic is present.

---

## Why a skill, not just longer role instructions?

Keeping domain knowledge in a skill rather than baking it into a role's `Action:` text means:

- **It is reused** across roles and runs without copy-paste.
- **It is applied only when relevant** (gated on topic), so unrelated prompts stay lean.
- **Its use is auditable** — the audit trail shows exactly which skill was applied to which role and why
  (the matching topic).
