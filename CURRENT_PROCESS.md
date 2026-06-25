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
| the **same** role name (self-transition) | the role asked the user a clarifying question — return it and **wait** for the user's next prompt |
| **`done`**, or a target that does not exist | the run is **complete**; the next prompt starts a fresh run |

A safety cap (`roleflow.max-steps`, default 20) stops runaway loops.

## The six roles

```
                         ┌─────────────────────┐
   user prompt ─────────▶│ 1. SignalOrRequest   │  (Classifier)
                         └──────────┬───────────┘
                       signal │            │ request
                              ▼            ▼
                   ┌──────────────────┐   ┌─────────────────────┐
                   │ 2. SignalResponse│   │ 3. HandleRequest     │◀──┐ unclear
                   │    (done)        │   │    (Clarifier)       │───┘ (ask user,
                   └──────────────────┘   └───┬─────────────┬────┘     wait)
                                       clear  │             │ cancelled
                                              ▼             ▼
                                   ┌──────────────────┐    done
                                   │ 4. GoalBuilder    │
                                   │  writes goal file │
                                   └────────┬──────────┘
                                            ▼
                                   ┌──────────────────┐
                                   │ 5. PlanBuilder    │
                                   │  writes plan file │
                                   └────────┬──────────┘
                                            ▼
                                   ┌──────────────────┐
                                   │ 6. ResponseBuilder│
                                   │     (done)        │
                                   └──────────────────┘
```

1. **SignalOrRequest (Classifier)** — decides whether the prompt is a `signal` (a pleasantry or piece of
   information, e.g. "Hello", "Thanks") or a `request` (asks for an action, a state change, or
   information). → `signal` to role 2, `request` to role 3.
2. **SignalResponse (Responder)** — acknowledges information or replies naturally. → `done`. **No files.**
3. **HandleRequest (Clarifier)** — makes the success criteria unambiguous. If clear, restates them
   (`clear` → role 4). If not, asks one clarifying question (`unclear` → itself, pausing for the user). If
   the user cancelled, acknowledges it (`cancelled` → `done`).
4. **GoalBuilder (Goal author)** — constructs the goal (the criteria for when the request is satisfied,
   one-time or ongoing) and **writes a goal file** to `goals/`. → role 5.
5. **PlanBuilder (Plan author)** — using the goal, builds a four-phase plan (Preparation, Action,
   Verification, Next steps) and **writes a plan file** to `goals/`. → role 6.
6. **ResponseBuilder (Final responder)** — tells the user the goal and plan were created and reports the
   file locations. → `done`.

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

- `goal-<runId>.md` — the goal document from GoalBuilder.
- `plan-<runId>.md` — the plan document from PlanBuilder.

The `runId` is created when a request's run begins and is reused across clarification pauses, so a
request's goal and plan files share the same id. These files are human-readable and are also fed back into
later roles as context (for example, PlanBuilder sees the goal, and ResponseBuilder reports the paths).

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
