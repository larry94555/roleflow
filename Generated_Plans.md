# Generated_Plans.md

When RoleFlow turns a request into a goal and a plan, it writes **one** Markdown file — the *plan file* —
containing both. This document explains where that file lives, what it contains, how it is generated, and
how it is rendered.

## Where the file lives

Files are written to the **`goals/`** directory (configurable with `roleflow.goals-dir`). A request produces
exactly one file:

```
goals/plan_<prefix>_<timestamp>.md
```

- `<prefix>` — a short, human-readable label derived from the session's first prompt (e.g.
  `search-integers-counterexample`), made unique across sessions by appending a number when needed
  (`legendre`, `legendre-2`, …).
- `<timestamp>` — `yyyyMMdd-HHmmss`, fixed when the request's run begins and reused across clarification
  pauses, so a single request maps to a single file.

The whole session's file and audit-log lines share the prefix, so `grep <prefix>_` finds everything for a
request. (RoleFlow previously wrote a separate `goal_*.md` **and** `plan_*.md`; it now writes only the
combined `plan_*.md`, halving the number of files in the directory.)

## What the file contains

The document is Markdown, with the **goal first** and the **plan second**:

```markdown
# Goal

<the goal: the criteria for when the request is satisfied; whether it is one-time or ongoing>

# Plan

## Phase 1 - Preparation
- Assumption: ...
- Decision: ...
- ...
## Phase 2 - Action
- ...
## Phase 3 - Verification
- ...
## Phase 4 - Next steps
- ...

# Step Details

## Step 1: <step text> — <category>
<detail added by the function for this step>

## Step 2: <step text> — <category>
<detail added by the function for this step>
```

- The **`# Goal`** section is authored by the **GoalBuilder** role.
- The **`# Plan`** section is authored by the **PlanBuilder** role and checked by **PlanReviewer**. It always
  has the four fixed phase headers, each with at least one `- ` step, and Phase 1 calls out the assumptions
  and decision points the request implies. See
  [Plan structure enforcement](CURRENT_PROCESS.md#plan-structure-enforcement).
- The **`# Step Details`** section holds one `## Step N: …` subsection per plan step, each added by the
  **function** that StepReviewer calls for that step's category (SubgoalPlanner / ActionPlanner /
  InformationPlanner / DecisionPlanner). The high-level plan above is left unchanged — these sections only
  *add* detail. See [Functions](CURRENT_PROCESS.md#functions).

## How the file is generated

1. **GoalBuilder** produces the goal. The engine keeps it in the session **but does not write it to its own
   file**.
2. **PlanBuilder** produces the four-phase plan. The engine composes the goal and plan into one document with
   [`PlanDocument.compose`](src/main/java/com/example/roleflow/PlanDocument.java) (which adds the `# Goal` and
   `# Plan` headers and **trims the plan body to start at its first phase header**, so any restated goal or
   stray heading the model put before the phases is dropped and the goal is never stated twice) and writes it
   as the single plan file.
3. **PlanReviewer** may rewrite the plan; if it does, the engine recomposes goal + revised plan and
   overwrites the **same** file (the run id keeps the name stable). A "no change" review writes nothing.
4. **StepReviewer** classifies each step and, for each, calls the mapped **function**
   (SubgoalPlanner / ActionPlanner / InformationPlanner / DecisionPlanner). Each function's output is appended
   as a `## Step N: …` subsection under `# Step Details`, and the engine rewrites the same file. Each function
   call is recorded in the audit trail like a role. See [Functions](CURRENT_PROCESS.md#functions).

Internally the goal and plan stay separate (each is what later roles read and what change-detection
compares); only the on-disk artifact is combined.

## How the file is rendered

The final reply lists the file as a `file:///` link:

```
Files created:
- Plan file: file:///.../goals/plan_legendre_20260627-101500.md
```

- **From a terminal**, that `file:///` link opens the raw Markdown.
- **From the web page**, the link is rewritten to `/goals/<name>`, served by
  [`GoalFileController`](src/main/java/com/example/roleflow/GoalFileController.java), which renders the
  Markdown to an HTML page styled to resemble **GitHub's Markdown preview**
  ([`MarkdownRenderer`](src/main/java/com/example/roleflow/MarkdownRenderer.java) handles headings, lists,
  paragraphs, and inline `**bold**`/`*italic*`/`` `code` ``/links; all content is HTML-escaped). Append
  `?raw=1` (`/goals/<name>?raw=1`) to get the unrendered Markdown source instead.

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `roleflow.goals-dir` | `goals` | Directory the combined goal-and-plan files are written to. |
