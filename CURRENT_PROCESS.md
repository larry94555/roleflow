# CURRENT_PROCESS.md

This document explains the workflow RoleFlow runs for every prompt, as defined by
[`config/roleflow.active`](config/roleflow.active). It is generated from (but not identical to)
[`config/roleflow.proposed`](config/roleflow.proposed); the wording was adjusted so the steps run
reliably through the engine.

## The idea

A user prompt is not answered by a single model call. Instead it is driven through a sequence of
**roles**. Each role is, for all purposes, a **system prompt** that surrounds the unchanging user prompt
so the model performs one specific job (classify, clarify, build a goal, build a plan, respond). After a
role runs, a **transition** decides which role runs next. Because every role call goes through the shared
[conversation memory](README.md#conversation-memory-and-compaction), each role sees the output of the
earlier ones, and clarifying questions/answers accumulate as context without disrupting the flow.

The same workflow is used whether the prompt arrives from the **terminal** or the **web page**.

## The protocol (how a role talks to the engine)

For each role the engine builds a system prompt from the role's `Action` and sends it with the user
prompt. The model must reply with a single JSON object:

```json
{"message": "<text to show the user>", "decision": "<one allowed decision>", "artifact": "<file content or empty>"}
```

- `message` — what the user sees (only the message from the role where the run ends or pauses is shown).
- `decision` — drives the transition (e.g. `signal`, `request`, `clear`, `unclear`, `cancelled`).
- `artifact` — for steps that produce a file (goal, plan), the full human-readable content.

Parsing is tolerant: if the model wraps the JSON in prose, the engine extracts the object; if there is no
JSON at all, the whole reply is treated as the `message`.

## Transition rules

After a role returns, the engine resolves the model's `decision` against the role's `Transition`:

| Outcome | Meaning |
|---------|---------|
| a **different** role name | continue immediately to that role with the **same** user prompt (no new user input) |
| the **same** role name (self-transition) | re-run that role **automatically**, no user input (e.g. a reviewer re-reviewing its own updated output) |
| **`await`** | return the message to the user and **wait** for their next prompt, then resume at the same role (clarifying questions) |
| **`done`**, or a target that does not exist | the run is **complete**; the next prompt starts a fresh run |

A safety cap (`roleflow.max-steps`, default 20) stops runaway loops (including a reviewer that keeps
recommending changes).

## The eight roles

```
                         ┌─────────────────────┐
   user prompt ─────────▶│ 1. SignalOrRequest   │  (Classifier)
                         └──────────┬───────────┘
                       signal │            │ request
                              ▼            ▼
                   ┌──────────────────┐   ┌─────────────────────┐
                   │ 2. SignalResponse│   │ 3. HandleRequest     │◀──┐ unclear
                   │    (done)        │   │    (Clarifier)       │───┘ -> await
                   └──────────────────┘   └───┬─────────────┬────┘   (ask user, wait)
                                       clear  │             │ cancelled
                                              ▼             ▼
                                   ┌──────────────────┐    done
                                   │ 4. GoalBuilder    │  writes goal file
                                   └────────┬──────────┘
                                            ▼
                                   ┌──────────────────┐
                                   │ 5. PlanBuilder    │  writes plan file
                                   └────────┬──────────┘
                                            ▼
                                   ┌──────────────────┐ change
                                   │ 6. PlanReviewer   │◀──┐ (rewrite plan,
                                   │  may rewrite plan │───┘  re-review)
                                   └────────┬──────────┘
                                        ok  ▼
                                   ┌──────────────────┐
                                   │ 7. StepReviewer   │  classifies each step
                                   └────────┬──────────┘
                                            ▼
                                   ┌──────────────────┐
                                   │ 8. ResponseBuilder│
                                   │     (done)        │
                                   └──────────────────┘
```

1. **SignalOrRequest (Classifier)** — decides whether the prompt is a `signal` (a pleasantry or piece of
   information, e.g. "Hello", "Thanks") or a `request` (asks for an action, a state change, or
   information). → `signal` to role 2, `request` to role 3.
2. **SignalResponse (Responder)** — acknowledges information or replies naturally. → `done`. **No files.**
3. **HandleRequest (Clarifier)** — makes the success criteria unambiguous. If clear, restates them
   (`clear` → role 4). If not, asks one clarifying question (`unclear` → `await`, pausing for the user). If
   the user cancelled, acknowledges it (`cancelled` → `done`).
4. **GoalBuilder (Goal author)** — constructs the goal (the criteria for when the request is satisfied,
   one-time or ongoing) and **writes a goal file** to `goals/`. → role 5.
5. **PlanBuilder (Plan author)** — using the goal, builds a four-phase plan (Preparation, Action,
   Verification, Next steps) and **writes a plan file** to `goals/`. The plan's structure is enforced
   deterministically (see [Plan structure enforcement](#plan-structure-enforcement)). → role 6.
6. **PlanReviewer (Plan reviewer)** — reviews the plan's high-level steps. It is given **web context about
   the topic** (fetched with the `web_search` tool) and uses it to flag steps that contradict how the topic
   actually works — for example, treating the discovery of a mathematical counterexample as a defect to
   fix. If a change is warranted, it rewrites the **updated plan file** and re-reviews (`change` → itself,
   automatically); otherwise it confirms no change is needed (`ok` → role 7). Its verdict is recorded in
   the audit trail.
7. **StepReviewer (Step reviewer)** — classifies every step of the plan as `decision-point`,
   `request-for-information`, `action`, or `subgoal`, and records the classifications in the audit trail.
   This role is **computed deterministically by the engine** (no model call) — see
   [Step classification](#step-classification). → role 8.
8. **ResponseBuilder (Final responder)** — gives a brief confirmation that the goal and plan were created.
   The engine then appends the exact file locations as `file:///` URLs (see [Files written](#files-written)).
   → `done`.

## Plan structure enforcement

The plan format is fixed, so the engine enforces it in code rather than trusting the model. After a
plan-producing role (PlanBuilder, or PlanReviewer when it rewrites the plan) returns its plan,
[`PlanValidator`](src/main/java/com/example/roleflow/PlanValidator.java) checks that it:

- contains, in order, the four headers **Phase 1 - Preparation**, **Phase 2 - Action**,
  **Phase 3 - Verification**, **Phase 4 - Next steps**, each followed by at least one step; and
- **Phase 1 calls out the assumptions and decision points** implied by the request — for example, the
  programming language when an application is requested. These must be made directly (`Assumption: …`,
  `Decision: …`) or deferred as preparation steps to figure them out, so a decision point is never
  silently ignored.

If the check fails, the engine re-prompts the model with the concrete problems as feedback (up to three
attempts) before proceeding. Each check is recorded in the audit trail as a `VALIDATION` event, so a
rejected/retried plan is visible.

## Step classification

The step categories are fixed, so StepReviewer is **computed deterministically by the engine** rather than
by the model (a role declares this with `Compute: classify-steps` in the config). The engine extracts the
plan's steps and classifies each with [`StepClassifier`](src/main/java/com/example/roleflow/StepClassifier.java) —
a transparent keyword heuristic — into `decision-point` (an assumption or a choice that must be made),
`request-for-information`, `action`, or `subgoal`. No model call is made for this role; the per-step
classifications are recorded as the role's `ROLE_RESULT` (e.g.
`Step 1: Decision: use Python -> decision-point`) and a `VALIDATION` event notes how many steps were
classified.

> **Reviewer results in the audit.** Every role's human-readable result is recorded as a `ROLE_RESULT`
> event, so the PlanReviewer's verdict ("no changes needed" / its justification) and the StepReviewer's
> per-step classifications are visible in both the audit log and the audit web page.

## What the user experiences

- **A signal** ("Hello", "Thanks for that"): two model calls (classify → respond), one visible answer,
  **no files created**.
- **A clear request**: classify → clarify (clear) → goal → plan → respond, in one turn. Five model calls,
  one visible answer (from ResponseBuilder), and **a goal file and a plan file** in `goals/`.
- **A request needing clarification**: classify → clarify asks a question and **stops**. The user answers
  in their next prompt, which resumes at the Clarifier and continues to completion. The goal and plan
  files share a run id so they are recognizable as belonging to the same request.
- **A cancelled request**: classify → clarify acknowledges the cancellation → done. **No files.**

## Files written

For a request, files are written to the `goals/` directory (configurable via `roleflow.goals-dir`):

- `goal_<prefix>_<timestamp>.md` — the goal document from GoalBuilder.
- `plan_<prefix>_<timestamp>.md` — the plan document from PlanBuilder.

The `<prefix>` is a short, human-readable label summarizing the session's first prompt (e.g.
`search-integers-counterexample`), kept unique across sessions by appending a number when needed
(`legendre`, `legendre-2`, …). It is the leading part of the run id, so a whole session's files and log
lines can be found with a single `grep <prefix>_`. The `<timestamp>` (`yyyyMMdd-HHmmss`) is created when a
request's run begins and is reused across clarification pauses, so a request's goal and plan files share
the same id. These files are human-readable and are also fed back into later roles as context.

A role is shown the prior artifacts' **content** when it produces output (e.g. PlanBuilder builds on the
goal) or when it declares `Reads:` in the config (e.g. StepReviewer must read the plan to classify its
steps). A **pure reporting** role (ResponseBuilder) is given only the file **locations** and told not to
paste the contents. When the run completes, the engine appends the authoritative file links to the reply
as `file:///` URLs, for example:

```
The goal and plan were created successfully.

Files created:
- Goal file: file:///C:/Users/larry/github/roleflow/goals/goal_legendre_20260625-164426.md
- Plan file: file:///C:/Users/larry/github/roleflow/goals/plan_legendre_20260625-164426.md
```

This way the exact paths come from the engine rather than being transcribed (and possibly garbled) by the
model, and they can be opened directly from a browser.

## Relationship to memory

Every role call is a real turn through the conversation memory, so the workflow's intermediate outputs
become context for later roles and later prompts. If a session runs long enough to approach the token
budget, the memory compacts older turns into a summary exactly as described in the README — the workflow
keeps running on top of that compacted memory.

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `roleflow.config` | `config/roleflow.active` | Path to the workflow file. Empty/missing → plain single-call mode. |
| `roleflow.goals-dir` | `goals` | Where goal and plan files are written. |
| `roleflow.max-steps` | `20` | Safety cap on role steps per prompt. |
